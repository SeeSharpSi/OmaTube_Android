package dev.omatube.app.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The displayed aspect ratio drives the portrait viewport, so the pure helper
 * is pinned against the real [androidx.media3.common.VideoSize] field
 * combinations without starting playback.
 */
class PlaybackDisplayAspectTest {
    @Test
    fun normalLandscapeVideoReportsSixteenByNine() {
        assertThat(displayAspectRatio(width = 1920, height = 1080, pixelWidthHeightRatio = 1f, unappliedRotationDegrees = 0))
            .isWithin(1e-4f)
            .of(16f / 9f)
    }

    @Test
    fun pixelAspectRatioIsApplied() {
        assertThat(displayAspectRatio(width = 720, height = 480, pixelWidthHeightRatio = 1.5f, unappliedRotationDegrees = 0))
            .isWithin(1e-4f)
            .of(2.25f)
    }

    @Test
    fun quarterTurnRotationInvertsTheRatio() {
        assertThat(displayAspectRatio(width = 1920, height = 1080, pixelWidthHeightRatio = 1f, unappliedRotationDegrees = 90))
            .isWithin(1e-4f)
            .of(9f / 16f)
        assertThat(displayAspectRatio(width = 1920, height = 1080, pixelWidthHeightRatio = 1f, unappliedRotationDegrees = 270))
            .isWithin(1e-4f)
            .of(9f / 16f)
        assertThat(displayAspectRatio(width = 1920, height = 1080, pixelWidthHeightRatio = 1f, unappliedRotationDegrees = 180))
            .isWithin(1e-4f)
            .of(16f / 9f)
    }

    @Test
    fun invalidInputReturnsNull() {
        assertThat(displayAspectRatio(width = 0, height = 1080, pixelWidthHeightRatio = 1f, unappliedRotationDegrees = 0)).isNull()
        assertThat(displayAspectRatio(width = 1920, height = 0, pixelWidthHeightRatio = 1f, unappliedRotationDegrees = 0)).isNull()
        assertThat(displayAspectRatio(width = 1920, height = 1080, pixelWidthHeightRatio = 0f, unappliedRotationDegrees = 0)).isNull()
        assertThat(displayAspectRatio(width = 1920, height = 1080, pixelWidthHeightRatio = -1f, unappliedRotationDegrees = 0)).isNull()
        assertThat(displayAspectRatio(width = 1920, height = 1080, pixelWidthHeightRatio = Float.NaN, unappliedRotationDegrees = 0)).isNull()
        assertThat(displayAspectRatio(width = 1920, height = 1080, pixelWidthHeightRatio = Float.POSITIVE_INFINITY, unappliedRotationDegrees = 0)).isNull()
    }
}
