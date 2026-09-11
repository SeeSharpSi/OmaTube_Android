package dev.omatube.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.omatube.app.automation.AutomationLaunch
import dev.omatube.app.model.Channel
import dev.omatube.app.model.LibrarySnapshot
import dev.omatube.app.model.Settings
import dev.omatube.app.model.Video
import dev.omatube.app.refresh.RefreshCoordinator
import dev.omatube.app.refresh.RefreshProgress
import dev.omatube.app.ui.library.ALL_CATEGORY_ID
import dev.omatube.app.ui.library.LibraryRoutes
import dev.omatube.app.ui.library.feedVideos
import dev.omatube.app.ui.library.liveVideos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Everything the root Compose tree renders. */
data class AppUiState(
    val library: LibrarySnapshot = LibrarySnapshot(),
    val settings: Settings = Settings(),
    val route: String = LibraryRoutes.FEED,
    val selectedCategoryId: Long = ALL_CATEGORY_ID,
    val selectedVideo: Video? = null,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val addingChannel: Boolean = false,
    val status: String = "",
    val error: String? = null,
    val automation: Boolean = false,
)

/**
 * Root view model. It owns route/selection state, collects Room and settings
 * into a single [AppUiState], drives the staged [RefreshCoordinator], performs
 * bounded SAF import/export, and serialises playback reports on the graph scope
 * so they survive the player closing.
 *
 * Filtering, category scope and the page window are computed here; the UI never
 * performs network or persistence work. Database observation failures are
 * retried a bounded number of times and surfaced as an actionable error rather
 * than crashing the collector.
 */
class AppViewModel(
    val graph: AppGraph,
    private val appContext: Context,
    private val savedStateHandle: SavedStateHandle? = null,
    private val automationLaunch: AutomationLaunch? = null,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scope ?: viewModelScope
    private val repository = graph.repository
    private val settingsStore = graph.settingsStore

    /** Exposed for the player, which resolves streams and SponsorBlock itself. */
    val backend = graph.backend

    private val coordinator = RefreshCoordinator(
        repository = repository,
        backend = backend,
        scope = graph.scope,
        onError = { throwable -> graph.reportError(throwable) },
    )

    private val libraryFlow = MutableStateFlow(LibrarySnapshot())
    private val settingsFlow = MutableStateFlow(Settings())
    private val categoryFlow = MutableStateFlow(ALL_CATEGORY_ID)

    private var feedPageSize = FEED_PAGE_SIZE
    private var remoteMore = false

    private var route: String = LibraryRoutes.normalize(
        savedStateHandle?.get<String>(KEY_ROUTE) ?: automationLaunch?.route ?: LibraryRoutes.FEED,
    )
    private var selectedCategoryId: Long =
        savedStateHandle?.get<Long>(KEY_CATEGORY) ?: ALL_CATEGORY_ID
    private var selectedVideoId: String? =
        savedStateHandle?.get<String>(KEY_VIDEO) ?: automationLaunch?.playerVideoId

    /** Retained player snapshot; keeps a player open if the cache is pruned. */
    private var manualVideo: Video? =
        savedStateHandle?.get<Bundle>(KEY_MANUAL_VIDEO)?.let(::videoFromBundle)

    private var refreshing = false
    private var loadingMore = false
    private var addingChannel = false
    private var status = ""
    private var error: String? = null

    private val _uiState = MutableStateFlow(
        AppUiState(automation = graph.automation, route = route, selectedCategoryId = selectedCategoryId),
    )
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private val playbackMutex = Mutex()

    init {
        categoryFlow.value = selectedCategoryId
        observeLibrary()
        observeSettings()
        observeRefresh()
        observeRemoteMore()
        observeGraphErrors()
    }

    /** Runs the process-once startup refresh in normal mode. */
    fun start() {
        if (graph.automation) return
        if (!graph.beginStartup()) return
        status = "Refreshing..."
        error = null
        recompute()
        coordinator.requestRefresh()
    }

    // ---- Routes and selection ------------------------------------------

    fun onRoute(newRoute: String) {
        route = LibraryRoutes.normalize(newRoute)
        persistState()
        recompute()
    }

    fun onCategory(categoryId: Long) {
        selectedCategoryId = categoryId
        categoryFlow.value = categoryId
        feedPageSize = FEED_PAGE_SIZE
        persistState()
        recompute()
    }

    fun onMoveCategory(categoryId: Long, toIndex: Int) {
        launchSafely {
            repository.moveCategory(categoryId, toIndex)
        }
    }

    fun openVideo(video: Video) {
        selectedVideoId = video.id
        manualVideo = video
        persistState()
        recompute()
    }

    fun closePlayer() {
        selectedVideoId = null
        manualVideo = null
        persistState()
        recompute()
    }

    fun dismissError() {
        error = null
        graph.clearError()
        recompute()
    }

    // ---- Refresh and paging --------------------------------------------

    fun refresh() {
        if (graph.automation) {
            status = "Automation mode: refresh is disabled."
            error = null
            recompute()
            return
        }
        error = null
        coordinator.requestRefresh()
    }

    fun loadMore() {
        if (loadingMore) return
        val filtered = filteredFeed()
        if (filtered.size > feedPageSize) {
            feedPageSize += FEED_PAGE_SIZE
            recompute()
            return
        }
        if (graph.automation || !remoteMore) return

        val channels = scopeChannels(libraryFlow.value, selectedCategoryId)
        if (channels.isEmpty()) return
        loadingMore = true
        error = null
        recompute()
        scope.launch {
            try {
                coordinator.loadOlder(channels)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                graph.reportError(throwable)
            }
        }
    }

    // ---- Watch Next and history ----------------------------------------

    fun addWatchNext(videoId: String) {
        scope.launch {
            try {
                if (libraryFlow.value.watchNext.any { it.id == videoId }) {
                    status = "Already in Watch Next."
                } else {
                    repository.addWatchNext(videoId)
                    status = "Added to Watch Next."
                }
                error = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                error = actionable(throwable, "Could not add to Watch Next.")
            }
            recompute()
        }
    }

    fun removeWatchNext(videoId: String) {
        scope.launch {
            try {
                repository.removeWatchNext(videoId)
                status = "Removed from Watch Next."
                error = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                error = actionable(throwable, "Could not remove from Watch Next.")
            }
            recompute()
        }
    }

    fun moveWatchNext(videoId: String, toIndex: Int) {
        scope.launch {
            try {
                repository.moveWatchNext(videoId, toIndex)
                error = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                error = actionable(throwable, "Could not reorder Watch Next.")
            }
            recompute()
        }
    }

    fun deleteHistory(entryId: Long) {
        scope.launch {
            try {
                repository.deleteHistory(entryId)
                status = "Removed from history."
                error = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                error = actionable(throwable, "Could not remove history entry.")
            }
            recompute()
        }
    }

    // ---- Settings ------------------------------------------------------

    /**
     * Applies only the fields that actually changed relative to the settings
     * snapshot the UI rendered when the callback was created. This keeps
     * overlapping callbacks (for example a volume change from the player and a
     * quality change from settings, both built from the same snapshot) from
     * clobbering each other. Untouched API key fields are preserved.
     */
    fun onSettingsChange(newSettings: Settings) {
        val base = _uiState.value.settings
        scope.launch {
            try {
                settingsStore.update { current -> mergeSettings(current, base, newSettings) }
                error = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                error = actionable(throwable, "Could not save settings.")
            }
            recompute()
        }
    }

    // ---- Channel and category management -------------------------------

    fun addChannel(input: String, categoryIds: Set<Long>) {
        if (graph.automation) {
            status = ""
            error = "Automation mode: adding channels is disabled."
            recompute()
            return
        }
        scope.launch {
            addingChannel = true
            error = null
            recompute()
            try {
                val channel = backend.resolveChannel(input)
                repository.upsertChannel(channel)
                repository.setChannelCategories(channel.id, categoryIds)
                status = "Added ${channel.title}."
                feedPageSize = FEED_PAGE_SIZE
                graph.scope.launch { coordinator.loadChannel(channel) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                error = actionable(throwable, "Could not add channel.")
            } finally {
                addingChannel = false
                recompute()
            }
        }
    }

    fun removeChannel(channelId: String) {
        launchSafely { repository.removeChannel(channelId) }
    }

    fun setChannelCategories(channelId: String, categoryIds: Set<Long>) {
        launchSafely { repository.setChannelCategories(channelId, categoryIds) }
    }

    fun addCategory(name: String) {
        scope.launch {
            try {
                repository.addCategory(name)
                status = "Added category."
                error = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                error = actionable(throwable, "Could not add category.")
            }
            recompute()
        }
    }

    fun renameCategory(categoryId: Long, name: String) {
        launchSafely { repository.renameCategory(categoryId, name) }
    }

    fun removeCategory(categoryId: Long) {
        scope.launch {
            try {
                repository.removeCategory(categoryId)
                if (selectedCategoryId == categoryId) {
                    onCategory(ALL_CATEGORY_ID)
                }
                status = "Removed category."
                error = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                error = actionable(throwable, "Could not remove category.")
            }
            recompute()
        }
    }

    // ---- SAF import and export -----------------------------------------

    fun importDocument(uri: Uri) {
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) { readBoundedText(uri) }
                val count = repository.importJson(json)
                status = "Imported $count entries."
                error = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                error = actionable(throwable, "Import failed.")
            }
            recompute()
        }
    }

    fun exportDocument(uri: Uri, channels: Boolean) {
        scope.launch {
            try {
                val payload = withContext(Dispatchers.IO) {
                    val json = if (channels) {
                        repository.exportChannels()
                    } else {
                        repository.exportCategories()
                    }
                    writeDocument(uri, json)
                    json
                }
                status = if (channels) {
                    "Exported ${payload.length} characters of channels."
                } else {
                    "Exported ${payload.length} characters of categories."
                }
                error = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                error = actionable(throwable, "Export failed.")
            }
            recompute()
        }
    }

    // ---- Playback recording --------------------------------------------

    /**
     * Serialised on the graph scope so an in-flight report still lands after
     * the player composable is gone. Cancellation only happens when the
     * process scope is cancelled.
     */
    fun reportPlayback(
        videoId: String,
        positionSeconds: Long,
        watchedDeltaSeconds: Long,
        newSession: Boolean,
    ) {
        graph.scope.launch {
            playbackMutex.withLock {
                try {
                    repository.recordPlayback(
                        videoId = videoId,
                        positionSeconds = positionSeconds,
                        watchedDeltaSeconds = watchedDeltaSeconds,
                        newSession = newSession,
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    graph.reportError(throwable)
                }
            }
        }
    }

    // ---- Internals -----------------------------------------------------

    private fun observeLibrary() {
        scope.launch {
            repository.snapshot
                .retryWhen { cause, attempt -> handleObservationFailure("library", cause, attempt) }
                .catch { cause ->
                    if (cause !is CancellationException) reportObservationError("library", cause)
                }
                .collect { snapshot ->
                    libraryFlow.value = snapshot
                    clearObservationError()
                    recompute()
                }
        }
    }

    private fun observeSettings() {
        scope.launch {
            settingsStore.settings
                .retryWhen { cause, attempt -> handleObservationFailure("settings", cause, attempt) }
                .catch { cause ->
                    if (cause !is CancellationException) reportObservationError("settings", cause)
                }
                .collect { settings ->
                    settingsFlow.value = settings
                    clearObservationError()
                    recompute()
                }
        }
    }

    private fun observeRefresh() {
        scope.launch {
            coordinator.progress.collect { progress ->
                applyRefreshProgress(progress)
            }
        }
    }

    private fun observeRemoteMore() {
        scope.launch {
            combine(libraryFlow, categoryFlow) { snapshot, categoryId ->
                scopeChannels(snapshot, categoryId)
            }
                .distinctUntilChanged()
                .collectLatest { channels ->
                    try {
                        remoteMore = channels.any { !repository.historyComplete(it.id) }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (throwable: Throwable) {
                        reportObservationError("remote state", throwable)
                    }
                    recompute()
                }
        }
    }

    private fun observeGraphErrors() {
        scope.launch {
            graph.errors.collect { message ->
                if (message != null) {
                    error = message
                    recompute()
                }
            }
        }
    }

    private suspend fun handleObservationFailure(
        label: String,
        cause: Throwable,
        attempt: Long,
    ): Boolean {
        if (cause is CancellationException) return false
        reportObservationError(label, cause)
        if (attempt >= MAX_OBSERVE_RETRIES) return false
        delay(200L * (attempt + 1L))
        return true
    }

    private fun reportObservationError(label: String, cause: Throwable) {
        error = "$LIVE_DATA_PREFIX ($label): ${cause.message ?: cause::class.java.simpleName}"
        recompute()
    }

    private fun clearObservationError() {
        if (error?.startsWith(LIVE_DATA_PREFIX) == true) {
            error = null
        }
    }

    private fun applyRefreshProgress(progress: RefreshProgress) {
        refreshing = progress.refreshing
        loadingMore = progress.loadingMore
        if (progress.message.isNotEmpty()) status = progress.message
        error = progress.errors.firstOrNull()
        if (progress.liveIncomplete && !status.contains("incomplete", ignoreCase = true)) {
            status = "$status Live status incomplete.".trim()
        }
        recompute()
    }

    private fun filteredFeed(): List<Video> =
        feedVideos(libraryFlow.value, selectedCategoryId, settingsFlow.value.shortVideoCutoffMinutes)

    private fun recompute() {
        val library = libraryFlow.value
        val settings = settingsFlow.value
        val filtered = feedVideos(library, selectedCategoryId, settings.shortVideoCutoffMinutes)
        val live = liveVideos(library)
        val paged = if (feedPageSize <= 0) emptyList() else filtered.take(feedPageSize)
        val shown = (paged + live).distinctBy { it.id }
        // Prefer the live cached snapshot so resume position is current, but
        // retain the opened snapshot as a fallback. Updating the fallback as
        // fresh data arrives lets a pruned/removed video keep playing.
        val cached = selectedVideoId?.let { id -> library.videos.firstOrNull { it.id == id } }
        if (cached != null) manualVideo = cached
        val selected = if (selectedVideoId == null) null else (cached ?: manualVideo)
        _uiState.value = AppUiState(
            library = library.copy(videos = shown),
            settings = settings,
            route = route,
            selectedCategoryId = selectedCategoryId,
            selectedVideo = selected,
            refreshing = refreshing,
            loadingMore = loadingMore,
            hasMore = filtered.size > feedPageSize || remoteMore,
            addingChannel = addingChannel,
            status = status,
            error = error,
            automation = graph.automation,
        )
    }

    private fun scopeChannels(snapshot: LibrarySnapshot, categoryId: Long): List<Channel> =
        if (categoryId == ALL_CATEGORY_ID) {
            snapshot.channels
        } else {
            snapshot.channels.filter { categoryId in it.categoryIds }
        }

    private fun persistState() {
        savedStateHandle?.set(KEY_ROUTE, route)
        savedStateHandle?.set(KEY_CATEGORY, selectedCategoryId)
        savedStateHandle?.set(KEY_VIDEO, selectedVideoId)
        savedStateHandle?.set(KEY_MANUAL_VIDEO, manualVideo?.let(::videoToBundle))
    }

    private fun launchSafely(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                error = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                error = actionable(throwable, "Operation failed.")
            }
            recompute()
        }
    }

    private fun mergeSettings(current: Settings, base: Settings, updated: Settings): Settings {
        var result = current
        if (updated.themeId != base.themeId) result = result.copy(themeId = updated.themeId)
        if (updated.simpleUi != base.simpleUi) result = result.copy(simpleUi = updated.simpleUi)
        if (updated.shortVideoCutoffMinutes != base.shortVideoCutoffMinutes) {
            result = result.copy(shortVideoCutoffMinutes = updated.shortVideoCutoffMinutes)
        }
        if (updated.maximumVideoHeight != base.maximumVideoHeight) {
            result = result.copy(maximumVideoHeight = updated.maximumVideoHeight)
        }
        if (updated.playbackVolume != base.playbackVolume) {
            result = result.copy(playbackVolume = updated.playbackVolume)
        }
        if (updated.sponsorBlockEnabled != base.sponsorBlockEnabled) {
            result = result.copy(sponsorBlockEnabled = updated.sponsorBlockEnabled)
        }
        if (updated.apiKey != base.apiKey) result = result.copy(apiKey = updated.apiKey)
        if (updated.rememberApiKey != base.rememberApiKey) {
            result = result.copy(rememberApiKey = updated.rememberApiKey)
        }

        val actionChanges = changedEntries(base.sponsorActions, updated.sponsorActions)
        if (actionChanges.isNotEmpty()) {
            val merged = result.sponsorActions.toMutableMap()
            for ((key, value) in actionChanges) {
                if (value == null) merged.remove(key) else merged[key] = value
            }
            result = result.copy(sponsorActions = merged)
        }

        val qualityChanges = changedEntries(base.videoQualityOverrides, updated.videoQualityOverrides)
        if (qualityChanges.isNotEmpty()) {
            val merged = result.videoQualityOverrides.toMutableMap()
            for ((key, value) in qualityChanges) {
                if (value == null) merged.remove(key) else merged[key] = value
            }
            result = result.copy(videoQualityOverrides = merged)
        }
        return result
    }

    private fun <V> changedEntries(base: Map<String, V>, updated: Map<String, V>): Map<String, V?> {
        val changes = LinkedHashMap<String, V?>()
        for ((key, value) in updated) {
            if (base[key] != value) changes[key] = value
        }
        for (key in base.keys) {
            if (!updated.containsKey(key)) changes[key] = null
        }
        return changes
    }

    private fun videoToBundle(video: Video): Bundle = Bundle().apply {
        putString(BUNDLE_ID, video.id)
        putString(BUNDLE_CHANNEL_ID, video.channelId)
        putString(BUNDLE_TITLE, video.title)
        putString(BUNDLE_CHANNEL_TITLE, video.channelTitle)
        putLong(BUNDLE_PUBLISHED_AT, video.publishedAt)
        putLong(BUNDLE_DURATION, video.durationSeconds)
        putBoolean(BUNDLE_LIVE, video.isLive)
        putBoolean(BUNDLE_UPCOMING, video.isUpcoming)
        putString(BUNDLE_THUMBNAIL, video.thumbnailUrl)
        putLong(BUNDLE_WATCHED, video.watchedSeconds)
        putLong(BUNDLE_POSITION, video.lastPositionSeconds)
        putInt(BUNDLE_WATCH_COUNT, video.watchCount)
        putLong(BUNDLE_LAST_WATCHED, video.lastWatchedAt)
        putInt(BUNDLE_QUEUE_POSITION, video.queuePosition)
    }

    private fun videoFromBundle(bundle: Bundle): Video? {
        val id = bundle.getString(BUNDLE_ID) ?: return null
        val channelId = bundle.getString(BUNDLE_CHANNEL_ID) ?: return null
        return Video(
            id = id,
            channelId = channelId,
            title = bundle.getString(BUNDLE_TITLE).orEmpty(),
            channelTitle = bundle.getString(BUNDLE_CHANNEL_TITLE).orEmpty(),
            publishedAt = bundle.getLong(BUNDLE_PUBLISHED_AT),
            durationSeconds = bundle.getLong(BUNDLE_DURATION),
            isLive = bundle.getBoolean(BUNDLE_LIVE),
            isUpcoming = bundle.getBoolean(BUNDLE_UPCOMING),
            thumbnailUrl = bundle.getString(BUNDLE_THUMBNAIL).orEmpty(),
            watchedSeconds = bundle.getLong(BUNDLE_WATCHED),
            lastPositionSeconds = bundle.getLong(BUNDLE_POSITION),
            watchCount = bundle.getInt(BUNDLE_WATCH_COUNT),
            lastWatchedAt = bundle.getLong(BUNDLE_LAST_WATCHED),
            queuePosition = bundle.getInt(BUNDLE_QUEUE_POSITION),
        )
    }

    private fun readBoundedText(uri: Uri): String {
        val resolver = appContext.contentResolver
        val stream = resolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open the selected file.")
        stream.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                if (total > MAX_IMPORT_BYTES) {
                    throw IllegalStateException("Import file is too large (limit 5 MiB).")
                }
                buffer.write(chunk, 0, read)
            }
            return buffer.toString(StandardCharsets.UTF_8.name())
        }
    }

    private fun writeDocument(uri: Uri, json: String) {
        val resolver = appContext.contentResolver
        val stream = resolver.openOutputStream(uri, "wt")
            ?: throw IllegalStateException("Could not write the selected document.")
        stream.use { output ->
            output.write(json.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }
    }

    private fun actionable(throwable: Throwable, fallback: String): String {
        val message = throwable.message?.takeIf { it.isNotBlank() }
        return when {
            throwable is IllegalArgumentException && message != null -> message
            message != null -> "$fallback $message"
            else -> fallback
        }
    }

    private companion object {
        const val FEED_PAGE_SIZE = 50
        const val MAX_IMPORT_BYTES = 5 * 1024 * 1024
        const val MAX_OBSERVE_RETRIES = 3L
        const val LIVE_DATA_PREFIX = "Live data error"
        const val KEY_ROUTE = "app_route"
        const val KEY_CATEGORY = "app_selected_category"
        const val KEY_VIDEO = "app_selected_video"
        const val KEY_MANUAL_VIDEO = "app_manual_video"
        const val BUNDLE_ID = "id"
        const val BUNDLE_CHANNEL_ID = "channelId"
        const val BUNDLE_TITLE = "title"
        const val BUNDLE_CHANNEL_TITLE = "channelTitle"
        const val BUNDLE_PUBLISHED_AT = "publishedAt"
        const val BUNDLE_DURATION = "durationSeconds"
        const val BUNDLE_LIVE = "isLive"
        const val BUNDLE_UPCOMING = "isUpcoming"
        const val BUNDLE_THUMBNAIL = "thumbnailUrl"
        const val BUNDLE_WATCHED = "watchedSeconds"
        const val BUNDLE_POSITION = "lastPositionSeconds"
        const val BUNDLE_WATCH_COUNT = "watchCount"
        const val BUNDLE_LAST_WATCHED = "lastWatchedAt"
        const val BUNDLE_QUEUE_POSITION = "queuePosition"
    }
}
