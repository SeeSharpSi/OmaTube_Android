package dev.omatube.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.omatube.app.ui.settings.SettingsPalette
import dev.omatube.app.ui.settings.SettingsThemes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the bundled palette values that the Android port intentionally changes
 * from the desktop bundles, and confirms every other theme still uses its
 * bundled accent.
 */
@RunWith(AndroidJUnit4::class)
class OmaThemeTest {

    @Test
    fun defaultAccentUsesPaletteBlueAndKeepsForeground() {
        val colors = OmaColors.forTheme(OmaThemeIds.DEFAULT)
        assertEquals(Color(0xFF466D8F), colors.accent)
        assertEquals(Color(0xFF466D8F), colors.blue)
        assertEquals(Color(0xFF24221E), colors.foreground)
    }

    @Test
    fun settingsDefaultAccentMatchesOmaThemeDefault() {
        assertEquals(
            Color(0xFF466D8F),
            SettingsPalette.forTheme(SettingsThemes.DEFAULT, simpleUi = true).accent,
        )
    }

    @Test
    fun otherThemesKeepTheirBundledAccents() {
        assertEquals(Color(0xFF56949F), OmaColors.forTheme(OmaThemeIds.ROSE_PINE).accent)
        assertEquals(Color(0xFF81A1C1), OmaColors.forTheme(OmaThemeIds.NORD).accent)
    }
}
