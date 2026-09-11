package dev.omatube.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.omatube.app.model.Settings
import dev.omatube.app.model.SponsorAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File

class DataStoreSettingsStore internal constructor(
    context: Context,
    private val inMemory: Boolean,
    private val fileName: String,
    private val apiKeyStorage: ApiKeyStorage,
    private val apiKeyCipher: ApiKeyCipher,
) : SettingsStore {

    constructor(context: Context, inMemory: Boolean = false) : this(
        context.applicationContext,
        inMemory,
        DEFAULT_FILE_NAME,
        FileApiKeyStorage(context.applicationContext),
        ApiKeyCrypto(),
    )

    // Isolated file name for tests and tooling; production uses the default.
    constructor(context: Context, inMemory: Boolean, fileName: String) : this(
        context.applicationContext,
        inMemory,
        fileName,
        FileApiKeyStorage(context.applicationContext),
        ApiKeyCrypto(),
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val memoryState = MutableStateFlow(Settings())
    private val sessionApiKey = MutableStateFlow("")
    private val persistedKey = MutableStateFlow(PersistedKey())
    private val ready = MutableStateFlow(inMemory)
    private val mutableRecoveryNotice = MutableStateFlow<String?>(null)

    // Non-null once when a remembered key could not be restored and had to be
    // cleared. Integration may surface this; the store never crashes and never
    // leaves "remember" checked with an empty key.
    val recoveryNotice: Flow<String?> = mutableRecoveryNotice.asStateFlow()

    private val dataStore: DataStore<Preferences>? = if (inMemory) {
        null
    } else {
        PreferenceDataStoreFactory.create(scope = scope) {
            File(appContext.filesDir, "datastore/$fileName.preferences_pb").also { file ->
                file.parentFile?.mkdirs()
            }
        }
    }

    init {
        if (!inMemory) {
            scope.launch {
                initializePersistedKey()
                ready.value = true
            }
        }
    }

    override val settings: Flow<Settings> = if (inMemory) {
        memoryState.asStateFlow()
    } else {
        requireNotNull(dataStore).data
            .catch { emit(emptyPreferences()) }
            .combine(persistedKey) { preferences, key -> preferences to key }
            .combine(ready) { (preferences, key), isReady ->
                if (isReady) preferencesToSettings(preferences, key) else null
            }
            .filterNotNull()
            .combine(sessionApiKey) { settings, sessionKey ->
                if (settings.rememberApiKey) settings else settings.copy(apiKey = sessionKey)
            }
    }

    override suspend fun update(transform: (Settings) -> Settings) {
        mutex.withLock {
            if (inMemory) {
                memoryState.value = transform(memoryState.value).validated()
                return
            }

            val current = currentSettings()
            val updated = transform(current).validated()
            val remember = updated.rememberApiKey && updated.apiKey.isNotEmpty()
            val existingCiphertext = persistedKey.value.ciphertext
            val keyUnchanged = updated.apiKey == current.apiKey
            val rememberUnchanged = remember == current.rememberApiKey

            // Re-encrypt only when the key or remember state actually changes;
            // unrelated updates (volume slider, theme) reuse the ciphertext.
            val ciphertext: String? = withContext(Dispatchers.IO) {
                when {
                    !remember -> null
                    keyUnchanged && rememberUnchanged && existingCiphertext != null -> existingCiphertext
                    else -> apiKeyCipher.encrypt(updated.apiKey)
                }
            }

            requireNotNull(dataStore).edit { preferences ->
                preferences[Keys.THEME_ID] = updated.themeId
                preferences[Keys.SIMPLE_UI] = updated.simpleUi
                preferences[Keys.SHORT_CUTOFF] = updated.shortVideoCutoffMinutes
                preferences[Keys.MAX_HEIGHT] = updated.maximumVideoHeight
                preferences[Keys.VOLUME] = updated.playbackVolume
                preferences[Keys.SPONSOR_ENABLED] = updated.sponsorBlockEnabled
                encodeMap(updated.sponsorActions.mapValues { it.value.name })
                    ?.let { preferences[Keys.SPONSOR_ACTIONS] = it }
                    ?: preferences.remove(Keys.SPONSOR_ACTIONS)
                encodeQualityOverrides(updated.videoQualityOverrides)
                    ?.let { preferences[Keys.QUALITY_OVERRIDES] = it }
                    ?: preferences.remove(Keys.QUALITY_OVERRIDES)
                preferences[Keys.REMEMBER_API_KEY] = remember
            }

            val resolvedCiphertext = ciphertext
            withContext(Dispatchers.IO) {
                if (!remember || resolvedCiphertext == null) {
                    apiKeyStorage.clear()
                    persistedKey.value = PersistedKey()
                } else {
                    if (resolvedCiphertext != existingCiphertext) {
                        apiKeyStorage.write(resolvedCiphertext)
                    }
                    persistedKey.value = PersistedKey(resolvedCiphertext, updated.apiKey)
                }
            }
            sessionApiKey.value = if (remember) "" else updated.apiKey
            if (remember) mutableRecoveryNotice.value = null
        }
    }

    // Cancels the DataStore scope. Tests use this before recreating a store
    // over the same file; production may call it at shutdown.
    fun close() {
        scope.cancel()
    }

    // Suspends until the DataStore scope has fully completed, releasing the
    // file for a subsequent store over the same path.
    suspend fun closeAndJoin() {
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    private suspend fun initializePersistedKey() {
        val store = requireNotNull(dataStore)
        val remember = store.data.first()[Keys.REMEMBER_API_KEY] ?: false
        val ciphertext = apiKeyStorage.read()

        if (!remember) {
            if (!ciphertext.isNullOrEmpty()) apiKeyStorage.clear()
            persistedKey.value = PersistedKey()
            return
        }
        if (ciphertext.isNullOrEmpty()) {
            recoverUnusableKey(
                "The saved API key is no longer available. Re-enter it to re-enable remembering.",
            )
            return
        }
        val plaintext = apiKeyCipher.decrypt(ciphertext)
        if (plaintext.isNullOrEmpty()) {
            recoverUnusableKey(
                "The saved API key could not be decrypted. Re-enter it to re-enable remembering.",
            )
            return
        }
        persistedKey.value = PersistedKey(ciphertext, plaintext)
    }

    private suspend fun recoverUnusableKey(message: String) {
        apiKeyStorage.clear()
        requireNotNull(dataStore).edit { it[Keys.REMEMBER_API_KEY] = false }
        persistedKey.value = PersistedKey()
        mutableRecoveryNotice.value = message
    }

    private suspend fun currentSettings(): Settings {
        if (inMemory) return memoryState.value
        ready.first { it }
        val persisted = preferencesToSettings(requireNotNull(dataStore).data.first(), persistedKey.value)
        return if (persisted.rememberApiKey) persisted else persisted.copy(apiKey = sessionApiKey.value)
    }

    private fun preferencesToSettings(preferences: Preferences, key: PersistedKey): Settings {
        val remember = (preferences[Keys.REMEMBER_API_KEY] ?: false) && key.plaintext != null
        return Settings(
            themeId = preferences[Keys.THEME_ID]?.takeIf { it.isNotBlank() } ?: "default",
            simpleUi = preferences[Keys.SIMPLE_UI] ?: false,
            shortVideoCutoffMinutes = (preferences[Keys.SHORT_CUTOFF] ?: 3).coerceIn(0, 60),
            maximumVideoHeight = (preferences[Keys.MAX_HEIGHT] ?: 0).coerceAtLeast(0),
            playbackVolume = (preferences[Keys.VOLUME] ?: 100).coerceIn(0, 100),
            sponsorBlockEnabled = preferences[Keys.SPONSOR_ENABLED] ?: false,
            sponsorActions = decodeSponsorActions(preferences[Keys.SPONSOR_ACTIONS]),
            apiKey = if (remember) key.plaintext.orEmpty() else "",
            rememberApiKey = remember,
            videoQualityOverrides = decodeQualityOverrides(preferences[Keys.QUALITY_OVERRIDES]),
        )
    }

    private fun decodeSponsorActions(raw: String?): Map<String, SponsorAction> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return try {
            val objectValue = JSONObject(raw)
            val result = LinkedHashMap<String, SponsorAction>()
            for (key in objectValue.keys()) {
                val action = try {
                    SponsorAction.valueOf(objectValue.optString(key))
                } catch (exception: IllegalArgumentException) {
                    SponsorAction.NONE
                }
                result[key] = action
            }
            result
        } catch (exception: JSONException) {
            emptyMap()
        }
    }

    private fun decodeQualityOverrides(raw: String?): Map<String, Int> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return try {
            val objectValue = JSONObject(raw)
            val result = LinkedHashMap<String, Int>()
            for (key in objectValue.keys()) {
                if (key.isNotBlank()) {
                    result[key] = objectValue.optInt(key, 0).coerceAtLeast(0)
                }
            }
            result
        } catch (exception: JSONException) {
            emptyMap()
        }
    }

    private fun encodeMap(values: Map<String, String>): String? {
        if (values.isEmpty()) return null
        val objectValue = JSONObject()
        for ((key, value) in values) {
            objectValue.put(key, value)
        }
        return objectValue.toString()
    }

    private fun encodeQualityOverrides(values: Map<String, Int>): String? {
        if (values.isEmpty()) return null
        val objectValue = JSONObject()
        for ((key, value) in values) {
            objectValue.put(key, value)
        }
        return objectValue.toString()
    }

    private fun Settings.validated(): Settings {
        require(themeId.isNotBlank()) { "Theme id cannot be blank." }
        require(shortVideoCutoffMinutes in 0..60) {
            "Short video cutoff must be between 0 and 60 minutes."
        }
        require(maximumVideoHeight >= 0) { "Maximum video height cannot be negative." }
        require(playbackVolume in 0..100) { "Playback volume must be between 0 and 100." }
        for (key in sponsorActions.keys) {
            require(key.isNotBlank()) { "SponsorBlock category cannot be blank." }
        }
        for ((videoId, height) in videoQualityOverrides) {
            require(videoId.isNotBlank()) { "Video quality override id cannot be blank." }
            require(height >= 0) { "Video quality override height cannot be negative." }
        }
        return this
    }

    private data class PersistedKey(
        val ciphertext: String? = null,
        val plaintext: String? = null,
    )

    private object Keys {
        val THEME_ID = stringPreferencesKey("theme_id")
        val SIMPLE_UI = booleanPreferencesKey("simple_ui")
        val SHORT_CUTOFF = intPreferencesKey("short_video_cutoff_minutes")
        val MAX_HEIGHT = intPreferencesKey("maximum_video_height")
        val VOLUME = intPreferencesKey("playback_volume")
        val SPONSOR_ENABLED = booleanPreferencesKey("sponsor_block_enabled")
        val SPONSOR_ACTIONS = stringPreferencesKey("sponsor_actions")
        val REMEMBER_API_KEY = booleanPreferencesKey("remember_api_key")
        val QUALITY_OVERRIDES = stringPreferencesKey("video_quality_overrides")
    }

    companion object {
        const val DEFAULT_FILE_NAME = "omatube_settings"
        const val API_KEY_FILE_NAME = "omatube_api_key"
    }
}
