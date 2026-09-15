package dev.omatube.app.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class DataStoreSettingsStoreRecoveryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fileNames = mutableListOf<String>()

    @After
    fun tearDown() {
        fileNames.forEach { fileName ->
            File(context.filesDir, "datastore/$fileName.preferences_pb").delete()
            File(context.filesDir, "datastore/$fileName.preferences_pb.tmp").delete()
        }
        fileNames.clear()
    }

    private fun newFileName(): String = "settings-recovery-${UUID.randomUUID()}".also { fileNames.add(it) }

    @Test
    fun missingCiphertextClearsRememberButKeepsValidSettings() = runBlocking {
        val fileName = newFileName()
        val storage = FakeApiKeyStorage()
        val working = FakeApiKeyCipher(fail = false)

        val first = DataStoreSettingsStore(context, false, fileName, storage, working)
        first.update {
            it.copy(themeId = "nord", playbackVolume = 42, apiKey = "secret", rememberApiKey = true)
        }
        assertTrue(storage.read()!!.startsWith("enc:"))
        first.closeAndJoin()

        // Simulate a restore that brought back preferences but not the
        // no-backup ciphertext file.
        storage.clear()

        val second = DataStoreSettingsStore(context, false, fileName, storage, working)
        try {
            val recovered = second.settings.first()
            assertFalse(recovered.rememberApiKey)
            assertEquals("", recovered.apiKey)
            assertEquals("nord", recovered.themeId)
            assertEquals(42, recovered.playbackVolume)
            assertNull(storage.read())
            assertNotNull(second.recoveryNotice.first())
        } finally {
            second.closeAndJoin()
        }
    }

    @Test
    fun invalidCiphertextClearsRememberButKeepsValidSettings() = runBlocking {
        val fileName = newFileName()
        val storage = FakeApiKeyStorage()

        val first = DataStoreSettingsStore(context, false, fileName, storage, FakeApiKeyCipher(fail = false))
        first.update {
            it.copy(themeId = "rose-pine", wifiMaximumVideoHeight = 720, apiKey = "secret", rememberApiKey = true)
        }
        first.closeAndJoin()

        val second = DataStoreSettingsStore(context, false, fileName, storage, FakeApiKeyCipher(fail = true))
        try {
            val recovered = second.settings.first()
            assertFalse(recovered.rememberApiKey)
            assertEquals("", recovered.apiKey)
            assertEquals("rose-pine", recovered.themeId)
            assertEquals(720, recovered.wifiMaximumVideoHeight)
            assertNull(storage.read())
            assertNotNull(second.recoveryNotice.first())
        } finally {
            second.closeAndJoin()
        }
    }

    @Test
    fun unrelatedUpdatesReuseCiphertextWithoutReencrypting() = runBlocking {
        val fileName = newFileName()
        val storage = FakeApiKeyStorage()
        val cipher = CountingFakeApiKeyCipher()
        val store = DataStoreSettingsStore(context, false, fileName, storage, cipher)
        try {
            store.update { it.copy(apiKey = "secret", rememberApiKey = true) }
            val ciphertext = storage.read()
            assertEquals(1, cipher.encryptCount)

            store.update { it.copy(playbackVolume = 10) }
            store.update { it.copy(playbackVolume = 20) }
            store.update { it.copy(themeId = "nord") }

            assertEquals(1, cipher.encryptCount)
            assertEquals(ciphertext, storage.read())
            assertEquals("secret", store.settings.first().apiKey)
        } finally {
            store.closeAndJoin()
        }
    }

    @Test
    fun legacyMaximumHeightSeedsBothPreferencesAndLastUsed() = runBlocking {
        val fileName = newFileName()
        seedLegacyMaximumHeight(fileName, 720)

        val store = DataStoreSettingsStore(
            context,
            false,
            fileName,
            FakeApiKeyStorage(),
            FakeApiKeyCipher(fail = false),
        )
        try {
            val migrated = store.settings.first()
            assertEquals(720, migrated.wifiMaximumVideoHeight)
            assertEquals(720, migrated.dataMaximumVideoHeight)
            assertEquals(720, migrated.lastUsedVideoHeight)
        } finally {
            store.closeAndJoin()
        }
    }

    @Test
    fun legacyMaximumHeightNormalizesUnknownValuesToAuto() = runBlocking {
        val fileName = newFileName()
        seedLegacyMaximumHeight(fileName, 1234)

        val store = DataStoreSettingsStore(
            context,
            false,
            fileName,
            FakeApiKeyStorage(),
            FakeApiKeyCipher(fail = false),
        )
        try {
            val migrated = store.settings.first()
            assertEquals(0, migrated.wifiMaximumVideoHeight)
            assertEquals(0, migrated.dataMaximumVideoHeight)
            assertEquals(0, migrated.lastUsedVideoHeight)
        } finally {
            store.closeAndJoin()
        }
    }

    private fun seedLegacyMaximumHeight(fileName: String, value: Int) {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val directory = File(context.filesDir, "datastore").also { it.mkdirs() }
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
                File(directory, "$fileName.preferences_pb")
            }
            dataStore.edit { preferences ->
                preferences[intPreferencesKey("maximum_video_height")] = value
            }
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}

private class FakeApiKeyStorage(private var value: String? = null) : ApiKeyStorage {
    override fun read(): String? = value

    override fun write(ciphertext: String) {
        value = ciphertext
    }

    override fun clear() {
        value = null
    }
}

private class FakeApiKeyCipher(private val fail: Boolean) : ApiKeyCipher {
    override fun encrypt(plaintext: String): String = "enc:$plaintext"

    override fun decrypt(ciphertext: String): String? =
        if (fail || !ciphertext.startsWith("enc:")) null else ciphertext.removePrefix("enc:")
}

private class CountingFakeApiKeyCipher : ApiKeyCipher {
    var encryptCount = 0

    override fun encrypt(plaintext: String): String {
        encryptCount += 1
        return "enc:$plaintext"
    }

    override fun decrypt(ciphertext: String): String =
        ciphertext.removePrefix("enc:")
}
