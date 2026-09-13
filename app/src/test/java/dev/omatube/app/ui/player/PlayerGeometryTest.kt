package dev.omatube.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic JVM tests for the pure portrait stack solver that drives the
 * reserved bars and the aspect-fitted video viewport.
 */
class PlayerGeometryTest {
    private val sixteenByNine = 16f / 9f

    @Test
    fun wideVideoFillsTheSafeWidthAndCentersTheStack() {
        val geometry = computePortraitStackGeometry(
            containerWidth = 1080f,
            containerHeight = 2160f,
            videoAspect = sixteenByNine,
            topSlotHeight = 156f,
            bottomSlotHeight = 300f,
        )

        assertEquals(1080f, geometry.videoWidth, 0.01f)
        assertEquals(607.5f, geometry.videoHeight, 0.01f)
        assertEquals(0f, geometry.videoLeft, 0.01f)
        // stack = 156 + 607.5 + 300 = 1063.5; centered in 2160.
        assertEquals(548.25f, geometry.topSlotTop, 0.01f)
        assertEquals(704.25f, geometry.videoTop, 0.01f)
        assertEquals(1311.75f, geometry.bottomSlotTop, 0.01f)
    }

    @Test
    fun tallVideoFitsTheRemainingHeightAndNarrows() {
        val geometry = computePortraitStackGeometry(
            containerWidth = 1080f,
            containerHeight = 2160f,
            videoAspect = 9f / 16f,
            topSlotHeight = 156f,
            bottomSlotHeight = 300f,
        )

        // Available video height is 2160 - 156 - 300 = 1704.
        assertEquals(1704f, geometry.videoHeight, 0.01f)
        assertEquals(958.5f, geometry.videoWidth, 0.01f)
        assertEquals(60.75f, geometry.videoLeft, 0.01f)
        assertEquals(0f, geometry.topSlotTop, 0.01f)
        assertEquals(156f, geometry.videoTop, 0.01f)
        assertEquals(1860f, geometry.bottomSlotTop, 0.01f)
    }

    @Test
    fun invalidAspectFallsBackToSixteenByNine() {
        val invalid = listOf(Float.NaN, 0f, -2f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        invalid.forEach { aspect ->
            val geometry = computePortraitStackGeometry(
                containerWidth = 1080f,
                containerHeight = 2160f,
                videoAspect = aspect,
                topSlotHeight = 156f,
                bottomSlotHeight = 300f,
            )
            assertEquals("aspect=$aspect width", 1080f, geometry.videoWidth, 0.01f)
            assertEquals("aspect=$aspect height", 607.5f, geometry.videoHeight, 0.01f)
        }
    }

    @Test
    fun invalidContainerAndSlotInputsClampToNonNegative() {
        val geometry = computePortraitStackGeometry(
            containerWidth = Float.NaN,
            containerHeight = -100f,
            videoAspect = sixteenByNine,
            topSlotHeight = -50f,
            bottomSlotHeight = 20f,
        )

        assertTrue(geometry.videoWidth >= 0f)
        assertTrue(geometry.videoHeight >= 0f)
        assertTrue(geometry.videoLeft >= 0f)
        assertTrue(geometry.videoTop >= 0f)
        assertTrue(geometry.topSlotTop >= 0f)
        assertTrue(geometry.bottomSlotTop >= 0f)
        assertEquals(0f, geometry.videoWidth, 0.001f)
        assertEquals(0f, geometry.videoHeight, 0.001f)
    }

    @Test
    fun squareVideoStaysInsideTheAvailableHeight() {
        val geometry = computePortraitStackGeometry(
            containerWidth = 1000f,
            containerHeight = 2000f,
            videoAspect = 1f,
            topSlotHeight = 100f,
            bottomSlotHeight = 100f,
        )

        assertEquals(1000f, geometry.videoWidth, 0.01f)
        assertEquals(1000f, geometry.videoHeight, 0.01f)
        assertEquals(400f, geometry.topSlotTop, 0.01f)
        assertEquals(500f, geometry.videoTop, 0.01f)
        assertEquals(1500f, geometry.bottomSlotTop, 0.01f)
    }
}
