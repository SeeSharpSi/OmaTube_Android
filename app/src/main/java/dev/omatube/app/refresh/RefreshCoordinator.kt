package dev.omatube.app.refresh

import dev.omatube.app.backend.VideoBackend
import dev.omatube.app.data.LibraryRepository
import dev.omatube.app.model.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Observable progress for startup, manual refresh and older-video loading. */
data class RefreshProgress(
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val message: String = "",
    val errors: List<String> = emptyList(),
    val liveIncomplete: Boolean = false,
)

/**
 * Staged refresh pipeline shared by startup and the explicit refresh button.
 *
 * Stages: channel metadata (skipped when recently fetched), recent uploads,
 * optional duration enrichment for the Atom fast path, then per-channel live
 * checks. Each stage fans out over the channels with at most
 * [maxConcurrentRequests] in-flight backend calls. Recent uploads are upserted
 * as soon as each channel returns, so the cached feed grows immediately and is
 * visible before the slower enrichment stage finishes. Live checks are
 * independent: a successful empty result clears live state through
 * `replaceLive`, while a failure leaves the last-known live flags untouched and
 * marks the status incomplete instead of silently clearing it.
 *
 * Failure handling: ordinary database or backend errors never escape a
 * request. They are turned into an actionable [RefreshProgress] message (unless
 * routed to [onError]) with every in-flight flag reset, so the UI can retry.
 * Cancellation is always rethrown and never converted into an error message.
 *
 * Older-upload pagination lives in [loadOlder]. It never resets the persisted
 * cursor, stops when the backend reports no next page, when the cursor would
 * repeat, or when the page is empty, and refuses to deepen history past the
 * repository's cache limit.
 */
class RefreshCoordinator(
    private val repository: LibraryRepository,
    private val backend: VideoBackend,
    private val scope: CoroutineScope,
    private val maxConcurrentRequests: Int = 4,
    private val now: () -> Long = System::currentTimeMillis,
    private val onError: (Throwable) -> Unit = {},
) {

    private val _progress = MutableStateFlow(RefreshProgress())
    val progress: StateFlow<RefreshProgress> = _progress.asStateFlow()

    private val gate = Mutex()
    private val limiter = Semaphore(maxConcurrentRequests)

    fun requestRefresh() {
        scope.launch {
            try {
                refresh()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                onError(throwable)
            }
        }
    }

    fun requestLoadOlder(channels: List<Channel>) {
        scope.launch {
            try {
                loadOlder(channels)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                onError(throwable)
            }
        }
    }

    suspend fun refresh() {
        if (!gate.tryLock()) return
        try {
            _progress.value = RefreshProgress(refreshing = true, message = "Refreshing...")

            val channels = try {
                repository.snapshot.first().channels
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                fail("Refresh could not read the local library.", throwable)
                return
            }
            if (channels.isEmpty()) {
                _progress.value = RefreshProgress(message = "No channels to refresh.")
                return
            }

            val errors = mutableListOf<String>()

            _progress.value = _progress.value.copy(message = "Refreshing channel details...")
            val metadataResults = fanOut(channels) { refreshMetadata(it) }
            metadataResults.forEachIndexed { index, result ->
                result.onFailure { error ->
                    errors.add("${channels[index].title}: metadata - ${error.message()}")
                }
            }

            _progress.value = _progress.value.copy(message = "Loading recent uploads...")
            val recentResults = fanOut(channels) { channel ->
                val page = backend.recentVideos(channel)
                if (page.videos.isNotEmpty()) repository.upsertVideos(page.videos)
                page.videos.any { it.durationSeconds < 0L }
            }
            recentResults.forEachIndexed { index, result ->
                result.onFailure { error ->
                    errors.add("${channels[index].title}: recent - ${error.message()}")
                }
            }

            // Atom items may lack durations. Enrich only channels whose recent
            // page had unknown durations, and never let a failure hide the feed.
            val enrichChannels = channels.filterIndexed { index, _ ->
                recentResults[index].getOrNull() == true
            }
            if (enrichChannels.isNotEmpty()) {
                _progress.value = _progress.value.copy(message = "Enriching recent uploads...")
                val enrichResults = fanOut(enrichChannels) { channel ->
                    val enriched = backend.enrichRecentVideos(channel)
                    if (enriched.isNotEmpty()) repository.upsertVideos(enriched)
                }
                enrichResults.forEachIndexed { index, result ->
                    result.onFailure { error ->
                        errors.add("${enrichChannels[index].title}: enrichment - ${error.message()}")
                    }
                }
            }

            _progress.value = _progress.value.copy(message = "Checking live channels...")
            var liveIncomplete = false
            val liveResults = fanOut(channels) { channel ->
                val live = backend.liveVideos(channel)
                repository.replaceLive(channel.id, live)
            }
            liveResults.forEachIndexed { index, result ->
                result.onFailure { error ->
                    liveIncomplete = true
                    errors.add("${channels[index].title}: live - ${error.message()}")
                }
            }

            try {
                repository.pruneCache()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                errors.add("cache prune - ${throwable.message()}")
            }

            _progress.value = RefreshProgress(
                refreshing = false,
                message = when {
                    errors.isEmpty() -> "Refresh complete."
                    liveIncomplete -> "Refresh finished with ${errors.size} issue(s); live status incomplete."
                    else -> "Refresh finished with ${errors.size} issue(s)."
                },
                errors = errors,
                liveIncomplete = liveIncomplete,
            )
        } catch (cancellation: CancellationException) {
            _progress.value = RefreshProgress()
            throw cancellation
        } catch (throwable: Throwable) {
            fail("Refresh failed.", throwable)
        } finally {
            gate.unlock()
        }
    }

    /**
     * Deepens one page per eligible channel exactly once. Exhausted channels
     * and channels whose cursor did not advance are never queried again.
     */
    suspend fun loadOlder(channels: List<Channel>) {
        gate.withLock {
            try {
                if (channels.isEmpty()) return
                if (!repository.canFetchHistory()) {
                    _progress.value = _progress.value.copy(
                        loadingMore = false,
                        message = "Local cache limit reached.",
                        errors = listOf(
                            "Local cache is at its 9 MiB limit; older videos were not fetched.",
                        ),
                    )
                    return
                }

                val candidates = channels.filter { !repository.historyComplete(it.id) }
                if (candidates.isEmpty()) {
                    _progress.value = RefreshProgress(message = "No older videos to load.")
                    return
                }

                _progress.value = RefreshProgress(
                    loadingMore = true,
                    message = "Loading older videos...",
                )
                val errors = mutableListOf<String>()
                val results = fanOut(candidates) { channel ->
                    val cursor = repository.historyCursor(channel.id)
                    val page = backend.olderVideos(channel, cursor)
                    if (page.videos.isNotEmpty()) repository.upsertVideos(page.videos)
                    val next = page.nextPage
                    val exhausted = next == null || next == cursor || page.videos.isEmpty()
                    repository.setHistoryCursor(
                        channelId = channel.id,
                        cursor = if (exhausted) null else next,
                        complete = exhausted,
                    )
                }
                results.forEachIndexed { index, result ->
                    result.onFailure { error ->
                        errors.add("${candidates[index].title}: older - ${error.message()}")
                    }
                }

                try {
                    repository.pruneCache()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    errors.add("cache prune - ${throwable.message()}")
                }

                _progress.value = RefreshProgress(
                    loadingMore = false,
                    message = if (errors.isEmpty()) {
                        "Loaded older videos."
                    } else {
                        "Older videos finished with ${errors.size} issue(s)."
                    },
                    errors = errors,
                )
            } catch (cancellation: CancellationException) {
                _progress.value = RefreshProgress()
                throw cancellation
            } catch (throwable: Throwable) {
                fail("Loading older videos failed.", throwable)
            }
        }
    }

    /**
     * Explicit initial load for one freshly added channel. Does not touch the
     * older-video cursor, so the first attempted load-more starts from the
     * backend's own first page or the persisted cursor, never a fabricated
     * "complete" state.
     */
    suspend fun loadChannel(channel: Channel) {
        gate.withLock {
            try {
                _progress.value = _progress.value.copy(
                    refreshing = true,
                    message = "Loading ${channel.title}...",
                    errors = emptyList(),
                )
                val page = backend.recentVideos(channel)
                if (page.videos.isNotEmpty()) repository.upsertVideos(page.videos)
                val live = backend.liveVideos(channel)
                repository.replaceLive(channel.id, live)
                repository.pruneCache()
                _progress.value = RefreshProgress(
                    message = "Loaded ${channel.title}.",
                )
            } catch (cancellation: CancellationException) {
                _progress.value = RefreshProgress()
                throw cancellation
            } catch (throwable: Throwable) {
                fail("Could not load ${channel.title}.", throwable)
            }
        }
    }

    private suspend fun refreshMetadata(channel: Channel) {
        if (channel.originalInput.isBlank()) return
        if (isMetadataFresh(channel)) return
        val refreshed = backend.resolveChannel(channel.originalInput)
        repository.upsertChannel(
            refreshed.copy(
                originalInput = refreshed.originalInput.ifBlank { channel.originalInput },
                categoryIds = channel.categoryIds,
                metadataFetchedAt = refreshed.metadataFetchedAt.takeIf { it > 0L }
                    ?: now(),
            ),
        )
    }

    private fun isMetadataFresh(channel: Channel): Boolean {
        val fetchedAt = channel.metadataFetchedAt
        if (fetchedAt <= 0L) return false
        return now() - fetchedAt < METADATA_MAX_AGE_MILLIS
    }

    private fun fail(message: String, throwable: Throwable) {
        _progress.value = RefreshProgress(
            message = message,
            errors = listOf(throwable.message() ?: message),
        )
    }

    private suspend fun <T, R> fanOut(
        items: List<T>,
        block: suspend (T) -> R,
    ): List<Result<R>> = coroutineScope {
        items.map { item ->
            async {
                limiter.withPermit {
                    try {
                        Result.success(block(item))
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (throwable: Throwable) {
                        Result.failure(throwable)
                    }
                }
            }
        }.awaitAll()
    }

    private fun Throwable.message(): String = message ?: this::class.java.simpleName

    private companion object {
        /** 29 days. Fresh metadata skips the network channel-resolution stage. */
        const val METADATA_MAX_AGE_MILLIS: Long = 29L * 24L * 60L * 60L * 1000L
    }
}
