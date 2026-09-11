package dev.omatube.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Cursor-free navigation glyphs, redrawn on a Canvas from the desktop QML
 * geometry. All coordinates are authored against a 15x15 canvas and scaled.
 */
enum class OmaGlyphKind {
    FEED,
    WATCH_NEXT,
    HISTORY,
    CONFIG,
    REFRESH,
}

@Composable
fun OmaGlyph(
    kind: OmaGlyphKind,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp,
    strokeWidth: Float = 1.5f,
) {
    Canvas(modifier.size(size)) {
        drawOmaGlyph(kind, color, strokeWidth)
    }
}

private fun DrawScope.drawOmaGlyph(kind: OmaGlyphKind, color: Color, strokeWidth: Float) {
    val scale = min(size.width, size.height) / 15f
    val stroke = Stroke(width = strokeWidth * scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val w = size.width
    val h = size.height
    when (kind) {
        OmaGlyphKind.FEED -> drawFeed(color, w, h, scale, stroke)
        OmaGlyphKind.WATCH_NEXT -> drawWatchNext(color, w, h, scale, stroke)
        OmaGlyphKind.HISTORY -> drawHistory(color, w, h, scale, stroke)
        OmaGlyphKind.CONFIG -> drawConfig(color, w, h, scale, stroke)
        OmaGlyphKind.REFRESH -> drawRefresh(color, w, h, scale, stroke)
    }
}

private fun DrawScope.drawFeed(
    color: Color,
    w: Float,
    h: Float,
    scale: Float,
    stroke: Stroke,
) {
    val midY = h / 2f + 0.5f * scale
    val roof = Path().apply {
        moveTo(1.5f * scale, midY)
        lineTo(w / 2f, 1.5f * scale)
        lineTo(w - 1.5f * scale, midY)
    }
    drawPath(roof, color, style = stroke)
    drawRect(
        color = color,
        topLeft = Offset(4f * scale, midY),
        size = Size(w - 8f * scale, h - midY - 1.5f * scale),
        style = stroke,
    )
}

private fun DrawScope.drawWatchNext(
    color: Color,
    w: Float,
    h: Float,
    scale: Float,
    stroke: Stroke,
) {
    val triangle = Path().apply {
        moveTo(1.5f * scale, 2.5f * scale)
        lineTo(5.5f * scale, 5f * scale)
        lineTo(1.5f * scale, 7.5f * scale)
        close()
    }
    drawPath(triangle, color)
    drawLine(color, Offset(7.5f * scale, 3.5f * scale), Offset(13.5f * scale, 3.5f * scale), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(7.5f * scale, 7f * scale), Offset(13.5f * scale, 7f * scale), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(1.5f * scale, 10.5f * scale), Offset(13.5f * scale, 10.5f * scale), stroke.width, StrokeCap.Round)
}

private fun DrawScope.drawHistory(
    color: Color,
    w: Float,
    h: Float,
    scale: Float,
    stroke: Stroke,
) {
    val cx = w / 2f
    val cy = h / 2f
    val r = min(w, h) / 2f - 1.5f * scale
    drawCircle(color, radius = r, center = Offset(cx, cy), style = stroke)
    drawLine(color, Offset(cx, cy), Offset(cx, cy - r * 0.55f), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(cx, cy), Offset(cx + r * 0.4f, cy + r * 0.25f), stroke.width, StrokeCap.Round)
}

private fun DrawScope.drawConfig(
    color: Color,
    w: Float,
    h: Float,
    scale: Float,
    stroke: Stroke,
) {
    val cx = w / 2f
    val cy = h / 2f
    val rOuter = min(w, h) / 2f - 1f * scale
    val rRoot = rOuter - 2.5f * scale
    val teeth = 8
    val steps = teeth * 4
    val path = Path()
    for (k in 0..steps) {
        val angle = k * 2.0 * PI / steps
        val radius = if (k % 4 == 1 || k % 4 == 2) rOuter else rRoot
        val px = cx + radius * cos(angle).toFloat()
        val py = cy + radius * sin(angle).toFloat()
        if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path, color, style = stroke)
    drawCircle(color, radius = 2.2f * scale, center = Offset(cx, cy), style = stroke)
}

private fun DrawScope.drawRefresh(
    color: Color,
    w: Float,
    h: Float,
    scale: Float,
    stroke: Stroke,
) {
    val cx = w / 2f
    val cy = h / 2f
    val r = min(w, h) / 2f - 1.5f * scale
    val start = -0.3f * PI.toFloat()
    val end = 1.3f * PI.toFloat()
    drawArc(
        color = color,
        startAngle = Math.toDegrees(start.toDouble()).toFloat(),
        sweepAngle = Math.toDegrees((end - start).toDouble()).toFloat(),
        useCenter = false,
        topLeft = Offset(cx - r, cy - r),
        size = Size(r * 2f, r * 2f),
        style = stroke,
    )
    val px = cx + r * cos(start)
    val py = cy + r * sin(start)
    val tangentX = -sin(start)
    val tangentY = cos(start)
    val normalX = cos(start)
    val normalY = sin(start)
    val tip = r * 0.55f
    val halfWidth = r * 0.38f
    val head = Path().apply {
        moveTo(px + tangentX * tip, py + tangentY * tip)
        lineTo(px + normalX * halfWidth, py + normalY * halfWidth)
        lineTo(px - normalX * halfWidth, py - normalY * halfWidth)
        close()
    }
    drawPath(head, color)
}
