package dev.omatube.app.ui.components

import androidx.compose.ui.graphics.Color

/**
 * Alpha composites [overlay] over [base], matching Qt's `Qt.tint` closely
 * enough for the translucent accent washes the desktop UI uses.
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
