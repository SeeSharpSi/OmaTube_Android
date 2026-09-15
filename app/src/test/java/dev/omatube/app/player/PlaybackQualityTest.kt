package dev.omatube.app.player

import com.google.common.truth.Truth.assertThat
import dev.omatube.app.connectivity.ConnectionType
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
    fun lastUsedSentinelDoesNotConflictWithDefaultOrAuto() {
        assertThat(PlaybackQuality.LAST_USED).isEqualTo(-2)
        assertThat(PlaybackQuality.LAST_USED).isNotEqualTo(PlaybackQuality.DEFAULT)
        assertThat(PlaybackQuality.LAST_USED).isNotEqualTo(PlaybackQuality.AUTO)
        assertThat(PlaybackQuality.isPreference(PlaybackQuality.LAST_USED)).isTrue()
        assertThat(PlaybackQuality.isConcrete(PlaybackQuality.LAST_USED)).isFalse()
    }

    @Test
    fun unknownGlobalHeightNormalizesToAuto() {
        assertThat(PlaybackQuality.normalizeGlobalHeight(1234)).isEqualTo(PlaybackQuality.AUTO)
        assertThat(PlaybackQuality.normalizeGlobalHeight(-5)).isEqualTo(PlaybackQuality.AUTO)
        assertThat(PlaybackQuality.normalizeGlobalHeight(1080)).isEqualTo(1080)
        assertThat(PlaybackQuality.normalizeGlobalHeight(0)).isEqualTo(0)
    }

    @Test
    fun effectiveHeightUsesPerVideoOverrideThenConnectionPreference() {
        val settings = Settings(wifiMaximumVideoHeight = 720, dataMaximumVideoHeight = 480)
        assertThat(PlaybackQuality.effectiveHeight(settings, "abc", ConnectionType.WIFI)).isEqualTo(720)
        assertThat(PlaybackQuality.effectiveHeight(settings, "abc", ConnectionType.DATA)).isEqualTo(480)

        val overridden = settings.copy(videoQualityOverrides = mapOf("abc" to 1080))
        assertThat(PlaybackQuality.effectiveHeight(overridden, "abc", ConnectionType.WIFI)).isEqualTo(1080)
        assertThat(PlaybackQuality.effectiveHeight(overridden, "abc", ConnectionType.DATA)).isEqualTo(1080)
    }

    @Test
    fun lastUsedPreferenceResolvesThroughSharedConcreteHeight() {
        val settings = Settings(
            wifiMaximumVideoHeight = PlaybackQuality.LAST_USED,
            dataMaximumVideoHeight = PlaybackQuality.LAST_USED,
            lastUsedVideoHeight = 1440,
        )
        assertThat(PlaybackQuality.effectiveHeight(settings, "abc", ConnectionType.WIFI)).isEqualTo(1440)
        assertThat(PlaybackQuality.effectiveHeight(settings, "abc", ConnectionType.DATA)).isEqualTo(1440)

        val fixedLastUsed = settings.copy(lastUsedVideoHeight = PlaybackQuality.AUTO)
        assertThat(PlaybackQuality.effectiveHeight(fixedLastUsed, "abc", ConnectionType.WIFI))
            .isEqualTo(PlaybackQuality.AUTO)
    }

    @Test
    fun applyingDefaultRemovesOverrideButKeepsLastUsed() {
        val settings = Settings(
            videoQualityOverrides = mapOf("abc" to 1080),
            lastUsedVideoHeight = 1080,
        )
        val removed = PlaybackQuality.applyChoice(settings, "abc", PlaybackQuality.DEFAULT)
        assertThat(removed.videoQualityOverrides).doesNotContainKey("abc")
        assertThat(removed.lastUsedVideoHeight).isEqualTo(1080)
    }

    @Test
    fun applyingConcreteChoiceUpdatesOverrideAndLastUsed() {
        val settings = Settings(videoQualityOverrides = mapOf("abc" to 1080), lastUsedVideoHeight = 1080)
        val set = PlaybackQuality.applyChoice(settings, "abc", 720)
        assertThat(set.videoQualityOverrides["abc"]).isEqualTo(720)
        assertThat(set.lastUsedVideoHeight).isEqualTo(720)

        val auto = PlaybackQuality.applyChoice(set, "abc", PlaybackQuality.AUTO)
        assertThat(auto.videoQualityOverrides["abc"]).isEqualTo(PlaybackQuality.AUTO)
        assertThat(auto.lastUsedVideoHeight).isEqualTo(PlaybackQuality.AUTO)

        val unknown = PlaybackQuality.applyChoice(settings, "abc", 9999)
        assertThat(unknown.videoQualityOverrides["abc"]).isEqualTo(PlaybackQuality.AUTO)
        assertThat(unknown.lastUsedVideoHeight).isEqualTo(PlaybackQuality.AUTO)
    }
}
