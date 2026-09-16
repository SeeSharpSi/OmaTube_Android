package dev.omatube.app.ui.player

/**
 * Pure motion helpers for the transcript scrub gutter.
 *
 * The scrub line is physical: in-bounds finger movement moves the line by
 * gain * finger delta. Once clamped at an edge, the list scrolls at the
 * amplified line speed while the finger is held. Pointer samples only update
 * marker position and velocity; the frame loop performs all list movement so
 * the same delta is never applied twice.
 */

internal const val TRANSCRIPT_SCRUB_GAIN = 1.75f

internal data class ScrubMarkerStep(
    val markerY: Float,
    val residualPx: Float,
    val edge: Int,
)

private fun scrubBounds(
    innerHeightPx: Float,
    markerHeightPx: Float,
    edgeMarginPx: Float,
): Pair<Float, Float> {
    val maxPossible = (innerHeightPx - markerHeightPx).coerceAtLeast(0f)
    if (!maxPossible.isFinite() || maxPossible <= 0f) return 0f to 0f
    val minY = edgeMarginPx.coerceIn(0f, maxPossible)
    val maxY = (innerHeightPx - edgeMarginPx - markerHeightPx).coerceIn(minY, maxPossible)
    return minY to maxY
}

internal fun clampScrubMarkerY(
    yPx: Float,
    innerHeightPx: Float,
    markerHeightPx: Float,
    edgeMarginPx: Float,
): Float {
    if (!innerHeightPx.isFinite() || !yPx.isFinite()) return 0f
    val (minY, maxY) = scrubBounds(innerHeightPx, markerHeightPx, edgeMarginPx)
    return yPx.coerceIn(minY, maxY)
}

internal fun amplifiedDragDelta(rawDyPx: Float, gain: Float = TRANSCRIPT_SCRUB_GAIN): Float {
    if (!rawDyPx.isFinite()) return 0f
    return rawDyPx * gain
}

/**
 * Amplified line speed in px/sec from one pointer sample. Returns null when
 * the sample carries no usable time delta or no movement, so the caller
 * retains the last valid velocity instead of stopping or dividing by zero.
 */
internal fun amplifiedVelocityPxPerSec(
    rawDyPx: Float,
    dtMillis: Long,
    gain: Float = TRANSCRIPT_SCRUB_GAIN,
): Float? {
    if (!rawDyPx.isFinite() || rawDyPx == 0f) return null
    if (dtMillis <= 0L) return null
    val dtSec = dtMillis / 1000f
    if (!dtSec.isFinite() || dtSec <= 0f) return null
    return (rawDyPx * gain) / dtSec
}

/**
 * Advance the physical marker by one amplified delta. The marker clamps at
 * the edges; any signed overshoot is returned as [ScrubMarkerStep.residualPx].
 * The caller queues that residual only on an edge transition; incremental
 * deltas mean a reversal moves the line inward immediately even while the
 * finger itself is still outside.
 */
internal fun stepScrubMarker(
    currentY: Float,
    amplifiedDyPx: Float,
    innerHeightPx: Float,
    markerHeightPx: Float,
    edgeMarginPx: Float,
): ScrubMarkerStep {
    val (minY, maxY) = scrubBounds(innerHeightPx, markerHeightPx, edgeMarginPx)
    if (!amplifiedDyPx.isFinite()) {
        val clamped = currentY.coerceIn(minY, maxY)
        return ScrubMarkerStep(clamped, 0f, edgeOf(clamped, minY, maxY))
    }
    val unclamped = currentY + amplifiedDyPx
    val clamped = unclamped.coerceIn(minY, maxY)
    val residual = unclamped - clamped
    return ScrubMarkerStep(clamped, residual, edgeOf(clamped, minY, maxY))
}

private fun edgeOf(markerY: Float, minY: Float, maxY: Float): Int = when {
    markerY <= minY + 0.5f -> -1
    markerY >= maxY - 0.5f -> 1
    else -> 0
}

/** Frame-loop distance for a held edge at constant velocity. dt is clamped for safety. */
internal fun edgeScrollStepPx(velocityPxPerSec: Float, dtSec: Float): Float {
    if (!velocityPxPerSec.isFinite()) return 0f
    if (!dtSec.isFinite()) return 0f
    val safeDt = dtSec.coerceIn(0f, 0.1f)
    if (safeDt <= 0f) return 0f
    return velocityPxPerSec * safeDt
}
