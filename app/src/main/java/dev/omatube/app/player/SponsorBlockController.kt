package dev.omatube.app.player

import dev.omatube.app.backend.VideoBackend
import dev.omatube.app.model.SponsorAction
import dev.omatube.app.model.SponsorSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Loads SponsorBlock segments through the backend only (never directly) and
 * exposes them to the player chrome.
 *
 * - Only fetch when SponsorBlock is enabled.
 * - Cancel the in-flight fetch when the video changes or the player closes.
 * - Ignore stale responses so a slow request cannot overwrite a newer video.
 * - Auto-skipping is delegated to [SponsorBlockLogic], which records skipped
 *   segment indices to guard against seek loops.
 */
class SponsorBlockController(
    private val backend: VideoBackend,
    private val scope: CoroutineScope,
) {
    private val _segments = MutableStateFlow<List<SponsorSegment>>(emptyList())
    val segments: StateFlow<List<SponsorSegment>> = _segments.asStateFlow()

    private var loadJob: Job? = null
    private var currentVideoId: String? = null
    private var skippedIndices: MutableSet<Int> = mutableSetOf()

    fun load(videoId: String, enabled: Boolean) {
        loadJob?.cancel()
        loadJob = null
        skippedIndices = mutableSetOf()
        currentVideoId = videoId
        _segments.value = emptyList()
        if (!enabled) return
        loadJob = scope.launch {
            val fetched = try {
                backend.sponsorSegments(videoId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                emptyList()
            }
            if (currentVideoId != videoId) return@launch
            _segments.value = fetched
        }
    }

    /** Auto-skip landing position for the current position, if any. */
    fun autoSkipTarget(
        positionSeconds: Double,
        actions: Map<String, SponsorAction>,
        enabled: Boolean,
    ): Double? {
        if (!enabled) return null
        val segments = _segments.value
        val index = SponsorBlockLogic.indexAt(positionSeconds, segments)
        // Forget segments that are no longer under the playhead (including a
        // position outside every segment) so seeking back can trigger again,
        // while keeping the current segment to stop a repeated seek loop.
        skippedIndices.retainAll { it == index }
        return SponsorBlockLogic.autoSkipTarget(positionSeconds, segments, actions, skippedIndices)
            ?.also { skippedIndices.add(index) }
    }

    /** Manual skip segment for the current position, if any. */
    fun manualSegment(
        positionSeconds: Double,
        actions: Map<String, SponsorAction>,
        enabled: Boolean,
    ): SponsorSegment? {
        if (!enabled) return null
        return SponsorBlockLogic.manualSegment(positionSeconds, _segments.value, actions)
    }

    fun cancel() {
        loadJob?.cancel()
        loadJob = null
        currentVideoId = null
    }
}
