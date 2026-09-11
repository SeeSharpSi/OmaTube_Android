package dev.omatube.app.ui.settings

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Private palette mirroring the desktop theme [colors.toml] resources.
 *
 * The settings module deliberately does not depend on the shared [OmaTheme]
 * composable so it can compile and render before the common UI module lands.
 * Values are copied from the desktop bundles and must stay byte-for-byte
 * compatible with them:
 *
 * - [dev.omatube.app.ui.settings.SettingsThemes.DEFAULT] -> themes/default/colors.toml
 * - [dev.omatube.app.ui.settings.SettingsThemes.ROSE_PINE] -> themes/rose-pine/colors.toml
 * - [dev.omatube.app.ui.settings.SettingsThemes.NORD] -> themes/nord/colors.toml
 */
internal object SettingsThemes {
    const val DEFAULT = "default"
    const val ROSE_PINE = "rose-pine"
    const val NORD = "nord"

    val ids: List<String> = listOf(DEFAULT, ROSE_PINE, NORD)
    val labels: List<String> = listOf("Default", "Rose Pine", "Nord")
}

@Immutable
internal data class SettingsPalette(
    val accent: Color,
    val ink: Color,
    val mutedInk: Color,
    val softInk: Color,
    val paper: Color,
    val popup: Color,
    val panel: Color,
    val rule: Color,
    val softFill: Color,
    val danger: Color,
    val errorFill: Color,
    val green: Color,
    val brightGreen: Color,
    val yellow: Color,
    val orange: Color,
    val cyan: Color,
    val blue: Color,
    val magenta: Color,
    val red: Color,
) {
    companion object {
        fun forTheme(themeId: String, simpleUi: Boolean): SettingsPalette {
            val raw = when (themeId) {
                SettingsThemes.ROSE_PINE -> rosePine
                SettingsThemes.NORD -> nord
                else -> default
            }
            return raw.resolve(simpleUi)
        }
    }
}

private data class RawPalette(
    val accent: Color,
    val selection: Color,
    val muted: Color,
    val background: Color,
    val lighterBackground: Color,
    val foreground: Color,
    val darkForeground: Color,
    val lightForeground: Color,
    val danger: Color,
    val green: Color,
    val brightGreen: Color,
    val yellow: Color,
    val orange: Color,
    val cyan: Color,
    val blue: Color,
    val magenta: Color,
) {
    fun resolve(simpleUi: Boolean): SettingsPalette {
        val paper = if (simpleUi) background else background.copy(alpha = 0.78f)
        val panel = if (simpleUi) lighterBackground else lighterBackground.copy(alpha = 0.92f)
        val rule = if (simpleUi) muted else tint(muted, accent.copy(alpha = 0.24f))
        val softFill = if (simpleUi) selection else tint(selection, accent.copy(alpha = 0.32f))
        return SettingsPalette(
            accent = accent,
            ink = foreground,
            mutedInk = darkForeground,
            softInk = lightForeground,
            paper = paper,
            popup = background,
            panel = panel,
            rule = rule,
            softFill = softFill,
            danger = danger,
            errorFill = tint(paper, danger.copy(alpha = 0.14f)),
            green = green,
            brightGreen = brightGreen,
            yellow = yellow,
            orange = orange,
            cyan = cyan,
            blue = blue,
            magenta = magenta,
            red = danger,
        )
    }
}

private val default = RawPalette(
    accent = Color(0xFF24221E),
    selection = Color(0xFFEEE9DF),
    muted = Color(0xFFDED8CC),
    background = Color(0xFFF7F4ED),
    lighterBackground = Color(0xFFFFFDF8),
    foreground = Color(0xFF24221E),
    darkForeground = Color(0xFF716D65),
    lightForeground = Color(0xFF3E3B35),
    danger = Color(0xFF712222),
    green = Color(0xFF53734A),
    brightGreen = Color(0xFF668C5A),
    yellow = Color(0xFFA66B16),
    orange = Color(0xFFA94F2F),
    cyan = Color(0xFF39736F),
    blue = Color(0xFF466D8F),
    magenta = Color(0xFF76577F),
)

private val rosePine = RawPalette(
    accent = Color(0xFF56949F),
    selection = Color(0xFFDFDAD9),
    muted = Color(0xFFCECACD),
    background = Color(0xFFFAF4ED),
    lighterBackground = Color(0xFFF2E9E1),
    foreground = Color(0xFF575279),
    darkForeground = Color(0xFF9893A5),
    lightForeground = Color(0xFF6E6A86),
    danger = Color(0xFFB4637A),
    green = Color(0xFF286983),
    brightGreen = Color(0xFF286983),
    yellow = Color(0xFFEA9D34),
    orange = Color(0xFFCF8057),
    cyan = Color(0xFFD7827E),
    blue = Color(0xFF56949F),
    magenta = Color(0xFF907AA9),
)

private val nord = RawPalette(
    accent = Color(0xFF81A1C1),
    selection = Color(0xFF434C5E),
    muted = Color(0xFF4C566A),
    background = Color(0xFF2E3440),
    lighterBackground = Color(0xFF3B4252),
    foreground = Color(0xFFD8DEE9),
    darkForeground = Color(0xFF667080),
    lightForeground = Color(0xFFADB5C4),
    danger = Color(0xFFBF616A),
    green = Color(0xFFA3BE8C),
    brightGreen = Color(0xFFA3BE8C),
    yellow = Color(0xFFEBCB8B),
    orange = Color(0xFFD5967A),
    cyan = Color(0xFF88C0D0),
    blue = Color(0xFF81A1C1),
    magenta = Color(0xFFB48EAD),
)

/**
 * Alpha composites [overlay] over [base], approximating Qt's `Qt.tint`.
 * Only used for low-alpha accent washes and is visually equivalent to the
 * desktop output at those values.
 */
internal fun tint(base: Color, overlay: Color): Color {
    val a = overlay.alpha
    return Color(
        red = base.red * (1f - a) + overlay.red * a,
        green = base.green * (1f - a) + overlay.green * a,
        blue = base.blue * (1f - a) + overlay.blue * a,
        alpha = base.alpha,
    )
}

internal fun SettingsPalette.sponsorColor(colorKey: String): Color = when (colorKey) {
    "bright_green" -> brightGreen
    "green" -> green
    "yellow" -> yellow
    "orange" -> orange
    "cyan" -> cyan
    "blue" -> blue
    "magenta" -> magenta
    "red" -> red
    else -> green
}
