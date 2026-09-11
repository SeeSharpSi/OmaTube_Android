package dev.omatube.app.player

import dev.omatube.app.model.SponsorAction
import dev.omatube.app.model.SponsorSegment

/**
 * Pure SponsorBlock decision logic, ported from the desktop `AppController`
 * (`sponsorSkipTarget` / `sponsorManualSegmentAt`) with the same thresholds and
 * color mapping. Keeping it pure makes the auto/manual behavior unit-testable
 * without touching the network or the player.
 */
object SponsorBlockLogic {
    /** A position has to be this far before the end to still trigger a skip. */
    const val END_GUARD_SECONDS = 0.15

    /** Where the player lands after a skip, just past the segment's end. */
    const val SKIP_LANDING_OFFSET_SECONDS = 0.1

    private val COLOR_KEYS: Map<String, String> = mapOf(
        "sponsor" to "green",
        "selfpromo" to "bright_green",
        "intro" to "cyan",
        "outro" to "blue",
        "preview" to "yellow",
        "interaction" to "orange",
        "music_offtopic" to "magenta",
        "poi_highlight" to "red",
    )

    fun colorKey(category: String): String = COLOR_KEYS[category] ?: "green"

    fun label(category: String): String = when (category) {
        "sponsor" -> "Sponsor"
        "selfpromo" -> "Self promotion"
        "interaction" -> "Interaction reminder"
        "intro" -> "Intro"
        "outro" -> "Outro"
        "preview" -> "Preview / recap"
        "music_offtopic" -> "Music: non-music"
        "poi_highlight" -> "Highlight"
        else -> category
    }

    /** Index of the segment covering [positionSeconds], or -1. */
    fun indexAt(
        positionSeconds: Double,
        segments: List<SponsorSegment>,
    ): Int {
        if (!positionSeconds.isFinite() || positionSeconds < 0.0) return -1
        for ((index, segment) in segments.withIndex()) {
            if (positionSeconds >= segment.startSeconds &&
                positionSeconds < segment.endSeconds - END_GUARD_SECONDS
            ) {
                return index
            }
        }
        return -1
    }

    /** Landing position for an automatic skip, or null when no skip applies. */
    fun autoSkipTarget(
        positionSeconds: Double,
        segments: List<SponsorSegment>,
        actions: Map<String, SponsorAction>,
        alreadySkipped: Set<Int>,
    ): Double? {
        val index = indexAt(positionSeconds, segments)
        if (index < 0 || index in alreadySkipped) return null
        val segment = segments[index]
        if (actions[segment.category] != SponsorAction.AUTO) return null
        return segment.endSeconds + SKIP_LANDING_OFFSET_SECONDS
    }

    /** Segment that should show a manual skip button, or null. */
    fun manualSegment(
        positionSeconds: Double,
        segments: List<SponsorSegment>,
        actions: Map<String, SponsorAction>,
    ): SponsorSegment? {
        val index = indexAt(positionSeconds, segments)
        if (index < 0) return null
        val segment = segments[index]
        return if (actions[segment.category] == SponsorAction.MANUAL) segment else null
    }
}
