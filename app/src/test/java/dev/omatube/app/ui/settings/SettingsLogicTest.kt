package dev.omatube.app.ui.settings

import dev.omatube.app.model.Settings
import dev.omatube.app.model.SponsorAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLogicTest {

    @Test
    fun themeIndexFallsBackToDefault() {
        assertEquals(0, SettingsLogic.themeIndex(SettingsThemes.DEFAULT))
        assertEquals(1, SettingsLogic.themeIndex(SettingsThemes.ROSE_PINE))
        assertEquals(2, SettingsLogic.themeIndex(SettingsThemes.NORD))
        assertEquals(0, SettingsLogic.themeIndex("does-not-exist"))
    }

    @Test
    fun themeIdsAreExactlyTheThreeBundledPalettes() {
        assertEquals(listOf("default", "rose-pine", "nord"), SettingsThemes.ids)
    }

    @Test
    fun qualityValuesMatchDesktopOrder() {
        assertEquals(listOf(0, 2160, 1440, 1080, 720, 480, 360), SettingsLogic.qualityValues)
        assertEquals(
            listOf("Auto", "2160p", "1440p", "1080p", "720p", "480p", "360p"),
            SettingsLogic.qualityLabels,
        )
    }

    @Test
    fun qualityIndexFallsBackToAuto() {
        assertEquals(0, SettingsLogic.qualityIndex(0))
        assertEquals(1, SettingsLogic.qualityIndex(2160))
        assertEquals(6, SettingsLogic.qualityIndex(360))
        assertEquals(0, SettingsLogic.qualityIndex(9999))
    }

    @Test
    fun cutoffClampsToZeroThroughSixty() {
        assertEquals(0, SettingsLogic.clampCutoff(-5))
        assertEquals(0, SettingsLogic.clampCutoff(0))
        assertEquals(42, SettingsLogic.clampCutoff(42))
        assertEquals(60, SettingsLogic.clampCutoff(60))
        assertEquals(60, SettingsLogic.clampCutoff(61))
    }

    @Test
    fun sponsorActionRoundTripsThroughValue() {
        assertEquals(0, SettingsLogic.sponsorActionValue(null))
        assertEquals(0, SettingsLogic.sponsorActionValue(SponsorAction.NONE))
        assertEquals(1, SettingsLogic.sponsorActionValue(SponsorAction.MANUAL))
        assertEquals(2, SettingsLogic.sponsorActionValue(SponsorAction.AUTO))

        assertEquals(SponsorAction.NONE, SettingsLogic.sponsorActionFromValue(0))
        assertEquals(SponsorAction.MANUAL, SettingsLogic.sponsorActionFromValue(1))
        assertEquals(SponsorAction.AUTO, SettingsLogic.sponsorActionFromValue(2))
        assertEquals(SponsorAction.NONE, SettingsLogic.sponsorActionFromValue(99))
    }

    @Test
    fun sponsorRowsMatchDesktopLabelsOrderAndColors() {
        assertEquals(
            listOf(
                "sponsor",
                "selfpromo",
                "interaction",
                "intro",
                "outro",
                "preview",
                "music_offtopic",
                "poi_highlight",
            ),
            SettingsLogic.sponsorRows.map { it.key },
        )
        assertEquals(
            listOf(
                "Sponsor",
                "Self promotion",
                "Interaction reminder",
                "Intro",
                "Outro",
                "Preview / recap",
                "Music: non-music",
                "Highlight",
            ),
            SettingsLogic.sponsorRows.map { it.label },
        )
        assertEquals(
            listOf(
                "green",
                "bright_green",
                "orange",
                "cyan",
                "blue",
                "yellow",
                "magenta",
                "red",
            ),
            SettingsLogic.sponsorRows.map { it.colorKey },
        )
    }

    @Test
    fun toggleCategoryAddsAndRemoves() {
        assertEquals(setOf(1L), SettingsLogic.toggleCategory(emptySet(), 1L, true))
        assertEquals(setOf(1L, 2L), SettingsLogic.toggleCategory(setOf(1L), 2L, true))
        assertEquals(setOf(1L), SettingsLogic.toggleCategory(setOf(1L, 2L), 2L, false))
        assertEquals(emptySet<Long>(), SettingsLogic.toggleCategory(setOf(1L), 1L, false))
    }

    @Test
    fun validationRejectsBlankInput() {
        assertFalse(SettingsLogic.isChannelInputValid(""))
        assertFalse(SettingsLogic.isChannelInputValid("   "))
        assertTrue(SettingsLogic.isChannelInputValid(" @channel "))

        assertFalse(SettingsLogic.isCategoryNameValid(""))
        assertFalse(SettingsLogic.isCategoryNameValid("  "))
        assertTrue(SettingsLogic.isCategoryNameValid(" News "))

        assertFalse(SettingsLogic.isApiKeyValid(""))
        assertFalse(SettingsLogic.isApiKeyValid("   "))
        assertTrue(SettingsLogic.isApiKeyValid(" key "))
    }

    @Test
    fun renameRequiresChangeAndNonBlank() {
        assertFalse(SettingsLogic.canRenameCategory("News", "News"))
        assertFalse(SettingsLogic.canRenameCategory(" News ", "News"))
        assertFalse(SettingsLogic.canRenameCategory("   ", "News"))
        assertTrue(SettingsLogic.canRenameCategory("Updates", "News"))
    }

    @Test
    fun withCutoffClampsAndKeepsOtherFields() {
        val settings = Settings(themeId = SettingsThemes.NORD, shortVideoCutoffMinutes = 5)
        val updated = SettingsLogic.withCutoff(settings, 100)
        assertEquals(60, updated.shortVideoCutoffMinutes)
        assertEquals(SettingsThemes.NORD, updated.themeId)
    }

    @Test
    fun withApiKeyTrimsAndSetsRememberFlag() {
        val updated = SettingsLogic.withApiKey(Settings(), "  secret  ", remember = true)
        assertEquals("secret", updated.apiKey)
        assertTrue(updated.rememberApiKey)
    }

    @Test
    fun withSponsorActionDoesNotMutateOriginalMap() {
        val original = Settings()
        val updated = SettingsLogic.withSponsorAction(original, "sponsor", SponsorAction.AUTO)
        assertEquals(SponsorAction.AUTO, updated.sponsorActions["sponsor"])
        assertTrue(original.sponsorActions.isEmpty())
    }

    @Test
    fun withClearedApiKeyOnlyClearsTheKey() {
        val original = Settings(apiKey = "secret", rememberApiKey = true)
        val updated = SettingsLogic.withClearedApiKey(original)
        assertEquals("", updated.apiKey)
        assertTrue(updated.rememberApiKey)
    }
}
