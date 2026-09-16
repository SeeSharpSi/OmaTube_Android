package dev.omatube.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM regression tests for the transcript scrub gutter physics.
 * Gain is 1.75x; edge behavior is symmetric and one-shot; invalid
 * inputs are safe; degenerate viewports clamp inside bounds.
 */
class TranscriptScrubMotionTest {
    private val innerHeight = 1000f
    private val markerHeight = 3f // 1dp at 3x
    private val edgeMargin = 6f // 2dp at 3x
    private val minY = edgeMargin
    private val maxY = innerHeight - edgeMargin - markerHeight

    @Test
    fun gainIsOnePointSevenFive() {
        assertEquals(1.75f, TRANSCRIPT_SCRUB_GAIN, 0.0001f)
        assertEquals(17.5f, amplifiedDragDelta(10f), 0.001f)
        assertEquals(-14f, amplifiedDragDelta(-8f), 0.001f)
        assertEquals(0f, amplifiedDragDelta(0f), 0.0f)
    }

    @Test
    fun velocityIsAmplifiedAndSymmetric() {
        val down = amplifiedVelocityPxPerSec(20f, 100L)!!
        val up = amplifiedVelocityPxPerSec(-20f, 100L)!!
        assertEquals(350f, down, 0.01f)
        assertEquals(-350f, up, 0.01f)
        assertEquals(-up, down, 0.01f)
        // Same sample yields same speed whether at edge or in bounds:
        // velocity helper takes only dy/dt, no position.
        val before = amplifiedVelocityPxPerSec(12f, 60L)!!
        val after = amplifiedVelocityPxPerSec(12f, 60L)!!
        assertEquals(before, after, 0.0f)
        // Top and bottom edges are symmetric via step helper.
        val topHit = stepScrubMarker(minY, -100f, innerHeight, markerHeight, edgeMargin)
        val bottomHit = stepScrubMarker(maxY, 100f, innerHeight, markerHeight, edgeMargin)
        assertEquals(minY, topHit.markerY, 0.001f)
        assertEquals(maxY, bottomHit.markerY, 0.001f)
        assertEquals(-1, topHit.edge)
        assertEquals(1, bottomHit.edge)
        assertEquals(-topHit.residualPx, bottomHit.residualPx, 0.001f)
    }

    @Test
    fun overshootIsOneTimeResidualAndBoundsAreTwoDp() {
        // Start just inside bottom edge, push past it.
        val hit = stepScrubMarker(maxY - 10f, 50f, innerHeight, markerHeight, edgeMargin)
        assertEquals(maxY, hit.markerY, 0.001f)
        assertEquals(1, hit.edge)
        assertEquals(40f, hit.residualPx, 0.001f)
        // Marker never leaves the 2dp inset bounds.
        assertEquals(edgeMargin, minY, 0.0f)
        assertEquals(innerHeight - edgeMargin - markerHeight, maxY, 0.0f)
        assertEquals(minY, clampScrubMarkerY(-5000f, innerHeight, markerHeight, edgeMargin), 0.0f)
        assertEquals(maxY, clampScrubMarkerY(5000f, innerHeight, markerHeight, edgeMargin), 0.0f)
        // In-bounds step consumes the full amplified delta with no residual.
        val mid = stepScrubMarker(500f, 35f, innerHeight, markerHeight, edgeMargin)
        assertEquals(535f, mid.markerY, 0.001f)
        assertEquals(0f, mid.residualPx, 0.0f)
        assertEquals(0, mid.edge)
    }

    @Test
    fun reversalLeavesEdgeImmediately() {
        val atBottom = stepScrubMarker(maxY, 80f, innerHeight, markerHeight, edgeMargin)
        assertEquals(1, atBottom.edge)
        val reversed = stepScrubMarker(atBottom.markerY, -10f, innerHeight, markerHeight, edgeMargin)
        assertEquals(maxY - 10f, reversed.markerY, 0.001f)
        assertEquals(0, reversed.edge)
        assertEquals(0f, reversed.residualPx, 0.0f)

        val atTop = stepScrubMarker(minY, -80f, innerHeight, markerHeight, edgeMargin)
        assertEquals(-1, atTop.edge)
        val reversedUp = stepScrubMarker(atTop.markerY, 10f, innerHeight, markerHeight, edgeMargin)
        assertEquals(minY + 10f, reversedUp.markerY, 0.001f)
        assertEquals(0, reversedUp.edge)
        assertEquals(0f, reversedUp.residualPx, 0.0f)
    }

    @Test
    fun zeroAndInvalidInputsAreSafe() {
        assertNull(amplifiedVelocityPxPerSec(0f, 100L))
        assertNull(amplifiedVelocityPxPerSec(10f, 0L))
        assertNull(amplifiedVelocityPxPerSec(10f, -5L))
        assertNull(amplifiedVelocityPxPerSec(Float.NaN, 100L))
        assertNull(amplifiedVelocityPxPerSec(Float.POSITIVE_INFINITY, 100L))
        assertEquals(0f, amplifiedDragDelta(Float.NaN), 0.0f)
        assertEquals(0f, amplifiedDragDelta(Float.POSITIVE_INFINITY), 0.0f)
        assertEquals(0f, amplifiedDragDelta(Float.NEGATIVE_INFINITY), 0.0f)

        assertEquals(0f, edgeScrollStepPx(Float.NaN, 0.05f), 0.0f)
        assertEquals(0f, edgeScrollStepPx(200f, Float.NaN), 0.0f)
        assertEquals(0f, edgeScrollStepPx(200f, 0f), 0.0f)
        assertEquals(0f, edgeScrollStepPx(200f, -0.05f), 0.0f)
        assertEquals(0f, edgeScrollStepPx(Float.POSITIVE_INFINITY, 0.05f), 0.0f)
        // dt is clamped to 0.1s for safety.
        assertEquals(20f, edgeScrollStepPx(200f, 1.0f), 0.001f)
        assertEquals(10f, edgeScrollStepPx(200f, 0.05f), 0.001f)
        assertEquals(-10f, edgeScrollStepPx(-200f, 0.05f), 0.001f)

        assertEquals(0f, clampScrubMarkerY(Float.NaN, innerHeight, markerHeight, edgeMargin), 0.0f)
        assertEquals(0f, clampScrubMarkerY(500f, Float.NaN, markerHeight, edgeMargin), 0.0f)
        val nanStep = stepScrubMarker(500f, Float.NaN, innerHeight, markerHeight, edgeMargin)
        assertEquals(500f, nanStep.markerY, 0.001f)
        assertEquals(0f, nanStep.residualPx, 0.0f)
    }

    @Test
    fun degenerateViewportClampsInsideBounds() {
        // Zero or smaller-than-marker viewports collapse to 0.
        assertEquals(0f, clampScrubMarkerY(50f, 0f, markerHeight, edgeMargin), 0.0f)
        assertEquals(0f, clampScrubMarkerY(50f, 2f, markerHeight, edgeMargin), 0.0f)
        assertEquals(0f, clampScrubMarkerY(-10f, -100f, markerHeight, edgeMargin), 0.0f)
        val collapsed = stepScrubMarker(50f, 20f, 0f, markerHeight, edgeMargin)
        assertEquals(0f, collapsed.markerY, 0.0f)
        assertEquals(70f, collapsed.residualPx, 0.001f)
        // Margin larger than viewport still clamps inside, never negative.
        val tight = clampScrubMarkerY(500f, 10f, 8f, 20f)
        assertEquals(2f, tight, 0.001f)
    }
}
