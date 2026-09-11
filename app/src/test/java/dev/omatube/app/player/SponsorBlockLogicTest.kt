package dev.omatube.app.player

import com.google.common.truth.Truth.assertThat
import dev.omatube.app.model.SponsorAction
import dev.omatube.app.model.SponsorSegment
import org.junit.Test

class SponsorBlockLogicTest {
    private val sponsor = SponsorSegment(10.0, 20.0, "sponsor")
    private val intro = SponsorSegment(0.0, 5.0, "intro")
    private val segments = listOf(intro, sponsor)

    @Test
    fun autoSkipReturnsLandingJustPastTheEnd() {
        val target = SponsorBlockLogic.autoSkipTarget(
            positionSeconds = 12.0,
            segments = segments,
            actions = mapOf("sponsor" to SponsorAction.AUTO),
            alreadySkipped = emptySet(),
        )
        assertThat(target).isEqualTo(20.1)
    }

    @Test
    fun autoSkipIgnoresManualAndNoneActions() {
        assertThat(
            SponsorBlockLogic.autoSkipTarget(
                12.0,
                segments,
                mapOf("sponsor" to SponsorAction.MANUAL),
                emptySet(),
            ),
        ).isNull()
        assertThat(
            SponsorBlockLogic.autoSkipTarget(
                12.0,
                segments,
                mapOf("sponsor" to SponsorAction.NONE),
                emptySet(),
            ),
        ).isNull()
    }

    @Test
    fun autoSkipLoopGuardSkipsASegmentOnlyOnce() {
        assertThat(
            SponsorBlockLogic.autoSkipTarget(
                12.0, segments, mapOf("sponsor" to SponsorAction.AUTO), setOf(1),
            ),
        ).isNull()
    }

    @Test
    fun endGuardPreventsSkippingAtTheVeryEnd() {
        assertThat(
            SponsorBlockLogic.autoSkipTarget(
                19.95, segments, mapOf("sponsor" to SponsorAction.AUTO), emptySet(),
            ),
        ).isNull()
    }

    @Test
    fun manualSegmentOnlyMatchesManualAction() {
        assertThat(
            SponsorBlockLogic.manualSegment(12.0, segments, mapOf("sponsor" to SponsorAction.MANUAL)),
        ).isEqualTo(sponsor)
        assertThat(
            SponsorBlockLogic.manualSegment(12.0, segments, mapOf("sponsor" to SponsorAction.AUTO)),
        ).isNull()
    }

    @Test
    fun colorKeysMatchDesktopMapping() {
        assertThat(SponsorBlockLogic.colorKey("sponsor")).isEqualTo("green")
        assertThat(SponsorBlockLogic.colorKey("selfpromo")).isEqualTo("bright_green")
        assertThat(SponsorBlockLogic.colorKey("poi_highlight")).isEqualTo("red")
        assertThat(SponsorBlockLogic.colorKey("unknown")).isEqualTo("green")
    }

    @Test
    fun labelsMatchDesktopStrings() {
        assertThat(SponsorBlockLogic.label("sponsor")).isEqualTo("Sponsor")
        assertThat(SponsorBlockLogic.label("music_offtopic")).isEqualTo("Music: non-music")
        assertThat(SponsorBlockLogic.label("custom")).isEqualTo("custom")
    }
}
