package dev.omatube.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Bundled desktop palettes, taken from the desktop theme bundles under
 * themes/<id>/colors.toml. The Default palette intentionally uses its own blue
 * for [OmaColors.accent] instead of the desktop near-black accent, so seek
 * progress and accent chrome stay visible on the light background. No Material
 * defaults, no dynamic colors: the library UI uses only these values.
 */
object OmaThemeIds {
    const val DEFAULT = "default"
    const val ROSE_PINE = "rose-pine"
    const val NORD = "nord"

    val all: List<String> = listOf(DEFAULT, ROSE_PINE, NORD)

    fun normalize(themeId: String): String = when (themeId) {
        ROSE_PINE -> ROSE_PINE
        NORD -> NORD
        else -> DEFAULT
    }
}

@Immutable
data class OmaColors(
    val mode: String,
    val accent: Color,
    val selection: Color,
    val muted: Color,
    val background: Color,
    val darkBackground: Color,
    val darkerBackground: Color,
    val lighterBackground: Color,
    val foreground: Color,
    val darkForeground: Color,
    val lightForeground: Color,
    val brightForeground: Color,
    val red: Color,
    val yellow: Color,
    val orange: Color,
    val green: Color,
    val cyan: Color,
    val blue: Color,
    val magenta: Color,
    val brown: Color,
    val brightRed: Color,
    val brightYellow: Color,
    val brightGreen: Color,
    val brightCyan: Color,
    val brightBlue: Color,
    val brightMagenta: Color,
) {
    /** Desktop `glassPanel`: lighter background, translucent on both modes. */
    fun glassPanel(): Color = if (mode == "dark") {
        lighterBackground.copy(alpha = 0.78f)
    } else {
        lighterBackground.copy(alpha = 0.88f)
    }

    companion object {
        fun forTheme(themeId: String): OmaColors = when (OmaThemeIds.normalize(themeId)) {
            OmaThemeIds.ROSE_PINE -> rosePine
            OmaThemeIds.NORD -> nord
            else -> default
        }
    }
}

private val default = OmaColors(
    mode = "light",
    accent = Color(0xFF466D8F),
    selection = Color(0xFFEEE9DF),
    muted = Color(0xFFDED8CC),
    background = Color(0xFFF7F4ED),
    darkBackground = Color(0xFFEEE9DF),
    darkerBackground = Color(0xFFDED8CC),
    lighterBackground = Color(0xFFFFFDF8),
    foreground = Color(0xFF24221E),
    darkForeground = Color(0xFF716D65),
    lightForeground = Color(0xFF3E3B35),
    brightForeground = Color(0xFF24221E),
    red = Color(0xFF712222),
    yellow = Color(0xFFA66B16),
    orange = Color(0xFFA94F2F),
    green = Color(0xFF53734A),
    cyan = Color(0xFF39736F),
    blue = Color(0xFF466D8F),
    magenta = Color(0xFF76577F),
    brown = Color(0xFF76533B),
    brightRed = Color(0xFFBD3535),
    brightYellow = Color(0xFFC98624),
    brightGreen = Color(0xFF668C5A),
    brightCyan = Color(0xFF4B918B),
    brightBlue = Color(0xFF5888B0),
    brightMagenta = Color(0xFF916B9C),
)

private val rosePine = OmaColors(
    mode = "light",
    accent = Color(0xFF56949F),
    selection = Color(0xFFDFDAD9),
    muted = Color(0xFFCECACD),
    background = Color(0xFFFAF4ED),
    darkBackground = Color(0xFFEDE7E1),
    darkerBackground = Color(0xFFE1DBD5),
    lighterBackground = Color(0xFFF2E9E1),
    foreground = Color(0xFF575279),
    darkForeground = Color(0xFF9893A5),
    lightForeground = Color(0xFF6E6A86),
    brightForeground = Color(0xFF575279),
    red = Color(0xFFB4637A),
    yellow = Color(0xFFEA9D34),
    orange = Color(0xFFCF8057),
    green = Color(0xFF286983),
    cyan = Color(0xFFD7827E),
    blue = Color(0xFF56949F),
    magenta = Color(0xFF907AA9),
    brown = Color(0xFF67402B),
    brightRed = Color(0xFFB4637A),
    brightYellow = Color(0xFFEA9D34),
    brightGreen = Color(0xFF286983),
    brightCyan = Color(0xFFD7827E),
    brightBlue = Color(0xFF56949F),
    brightMagenta = Color(0xFF907AA9),
)

private val nord = OmaColors(
    mode = "dark",
    accent = Color(0xFF81A1C1),
    selection = Color(0xFF434C5E),
    muted = Color(0xFF4C566A),
    background = Color(0xFF2E3440),
    darkBackground = Color(0xFF222730),
    darkerBackground = Color(0xFF191C23),
    lighterBackground = Color(0xFF3B4252),
    foreground = Color(0xFFD8DEE9),
    darkForeground = Color(0xFF667080),
    lightForeground = Color(0xFFADB5C4),
    brightForeground = Color(0xFFD8DEE9),
    red = Color(0xFFBF616A),
    yellow = Color(0xFFEBCB8B),
    orange = Color(0xFFD5967A),
    green = Color(0xFFA3BE8C),
    cyan = Color(0xFF88C0D0),
    blue = Color(0xFF81A1C1),
    magenta = Color(0xFFB48EAD),
    brown = Color(0xFF6A4B3D),
    brightRed = Color(0xFFBF616A),
    brightYellow = Color(0xFFEBCB8B),
    brightGreen = Color(0xFFA3BE8C),
    brightCyan = Color(0xFF8FBCBB),
    brightBlue = Color(0xFF81A1C1),
    brightMagenta = Color(0xFFB48EAD),
)

val LocalOmaColors = staticCompositionLocalOf { default }

/**
 * Provides the desktop theme palette selected by [themeId] to the library UI.
 * Unknown ids fall back to the bundled default theme. Deliberately does not
 * install Material theming so no Material defaults leak into the screens.
 */
@Composable
fun OmaTheme(themeId: String, content: @Composable () -> Unit) {
    val colors = remember(themeId) { OmaColors.forTheme(themeId) }
    CompositionLocalProvider(LocalOmaColors provides colors, content = content)
}
