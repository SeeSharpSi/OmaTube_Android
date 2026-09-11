package dev.omatube.app.player

import com.google.common.truth.Truth.assertThat
import dev.omatube.app.model.Settings
import org.junit.Test

class PlaybackQualityTest {
    @Test
    fun optionsMatchDesktopOrderAndLabels() {
        assertThat(PlaybackQuality.OPTIONS.map { it.value })
            .containsExactly(-1, 0, 2160, 1440, 1080, 720, 480, 360)
            .inOrder()
        assertThat(PlaybackQuality.OPTIONS.map { it.label })
            .containsExactly("Default", "Auto", "2160p", "1440p", "1080p", "720p", "480p", "360p")
            .inOrder()
    }

    @Test
    fun unknownGlobalHeightNormalizesToAuto() {
        assertThat(PlaybackQuality.normalizeGlobalHeight(1234)).isEqualTo(PlaybackQuality.AUTO)
        assertThat(PlaybackQuality.normalizeGlobalHeight(-5)).isEqualTo(PlaybackQuality.AUTO)
        assertThat(PlaybackQuality.normalizeGlobalHeight(1080)).isEqualTo(1080)
        assertThat(PlaybackQuality.normalizeGlobalHeight(0)).isEqualTo(0)
    }

    @Test
    fun effectiveHeightUsesPerVideoOverrideThenGlobal() {
        val settings = Settings(maximumVideoHeight = 720)
        assertThat(PlaybackQuality.effectiveHeight(settings, "abc")).isEqualTo(720)

        val overridden = settings.copy(videoQualityOverrides = mapOf("abc" to 1080))
        assertThat(PlaybackQuality.effectiveHeight(overridden, "abc")).isEqualTo(1080)
        assertThat(PlaybackQuality.effectiveHeight(overridden, "other")).isEqualTo(720)
    }

    @Test
    fun applyingDefaultRemovesOverrideAndOtherValuesNormalize() {
        val settings = Settings(videoQualityOverrides = mapOf("abc" to 1080))
        val removed = PlaybackQuality.applyChoice(settings, "abc", PlaybackQuality.DEFAULT)
        assertThat(removed.videoQualityOverrides).doesNotContainKey("abc")

        val set = PlaybackQuality.applyChoice(settings, "abc", 9999)
        assertThat(set.videoQualityOverrides["abc"]).isEqualTo(PlaybackQuality.AUTO)
    }
}
