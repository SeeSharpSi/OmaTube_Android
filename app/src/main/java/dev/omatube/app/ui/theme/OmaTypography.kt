package dev.omatube.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.omatube.app.R

/**
 * Font faces bundled to match the desktop runtime rather than the Android
 * platform defaults.
 *
 * The desktop declared `monospace`, which resolves to the installed
 * JetBrainsMono Nerd Font (including the braille/box-drawing glyphs used by
 * the refresh spinner), and a default sans face that resolves to Liberation
 * Sans. Both families ship unmodified under the SIL Open Font License 1.1;
 * see `app/src/main/assets/licenses/`.
 *
 * Only basic glyphs are used by the library UI, so Regular and Bold are
 * bundled for each family. Italic is intentionally omitted until a screen
 * actually needs it.
 */
object OmaTypography {
    val mono: FontFamily = FontFamily(
        Font(R.font.jetbrains_mono_nerd_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_nerd_bold, FontWeight.Bold),
    )

    val sans: FontFamily = FontFamily(
        Font(R.font.liberation_sans_regular, FontWeight.Normal),
        Font(R.font.liberation_sans_bold, FontWeight.Bold),
    )

    fun family(chrome: Boolean): FontFamily = if (chrome) mono else sans
}
