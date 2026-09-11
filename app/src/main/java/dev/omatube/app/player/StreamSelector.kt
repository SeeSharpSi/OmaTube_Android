package dev.omatube.app.player

/**
 * Pure video/audio track selection.
 *
 * Extracted from the resolver so the "pick the best supported video at or below
 * the maximum height, then the best available audio" policy can be unit tested
 * without Android, Media3 or the extractor.
 *
 * Selection returns a stable index into the candidate list. Callers map that
 * index back to the exact original track, so two candidates that share a
 * resolution string but differ in codec, delivery method or muxed/video-only
 * status can never be confused.
 */
object StreamSelector {
    data class Video(
        val resolution: String,
        val height: Int,
        val videoOnly: Boolean,
    )

    data class Audio(
        val trackId: String?,
        val formatRank: Int,
        val bitrate: Int,
        val original: Boolean,
    )

    /**
     * Returns the chosen video index, or -1 when there are no candidates.
     *
     * Streams are ranked best-first by height, with video-only renditions
     * preferred on ties, and de-duplicated by resolution. [maximumHeight]
     * `<= 0` means "Auto": choose the best available. When no stream is at or
     * below the maximum, the lowest available stream is used instead of failing
     * outright; the caller still has to pair it with audio.
     */
    fun selectVideoIndex(candidates: List<Video>, maximumHeight: Int): Int {
        if (candidates.isEmpty()) return -1
        val ranked = candidates.indices
            .sortedWith(
                compareByDescending<Int> { candidates[it].height }
                    .thenByDescending { if (candidates[it].videoOnly) 1 else 0 },
            )
            .distinctBy { candidates[it].resolution }
        if (maximumHeight <= 0) return ranked.first()
        return ranked.firstOrNull { candidates[it].height in 1..maximumHeight } ?: ranked.last()
    }

    /**
     * Returns the chosen audio index, or -1 when there are no candidates.
     *
     * [preferredTrackId] is an optional caller preference (for a future user
     * audio-track selector). The resolver passes `null` because a
     * [org.schabi.newpipe.extractor.stream.VideoStream] carries no audio-track
     * link, so no language matching against the selected video is claimed.
     * Without a preference the highest-ranked codec and bitrate wins.
     */
    fun selectAudioIndex(candidates: List<Audio>, preferredTrackId: String?): Int {
        if (candidates.isEmpty()) return -1
        if (!preferredTrackId.isNullOrEmpty()) {
            val preferred = candidates.indexOfFirst { it.trackId == preferredTrackId }
            if (preferred >= 0) return preferred
        }
        return candidates.indices.sortedWith(
            compareByDescending<Int> { candidates[it].formatRank }
                .thenByDescending { candidates[it].bitrate }
                .thenByDescending { if (candidates[it].original) 1 else 0 },
        ).first()
    }

    /** Codec ranking matching the desktop/NewPipe preference (M4A > WebM > MP3). */
    fun audioFormatRank(mimeType: String?): Int = when (mimeType) {
        "audio/mp4" -> 3
        "audio/webm" -> 2
        "audio/mpeg" -> 1
        else -> 0
    }
}
