package dev.omatube.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import dev.omatube.app.model.SponsorSegment
import dev.omatube.app.player.PlaybackQuality
import dev.omatube.app.player.PlayerUiState
import dev.omatube.app.player.SponsorBlockLogic
import dev.omatube.app.ui.theme.OmaColors
import dev.omatube.app.ui.theme.OmaTypography
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal val ChromeBackground = Color(0.02f, 0.02f, 0.02f, 0.82f)
internal val OverlayBackground = Color(0.02f, 0.02f, 0.02f, 0.88f)
internal val ChromeInk = Color.White

/** Desktop handle border `Qt.rgba(0, 0, 0, 0.82)`. */
private val HandleBorder = Color(0f, 0f, 0f, 0.82f)

// Chrome text uses the bundled JetBrainsMono Nerd face; the Sponsor skip box
// uses the bundled Liberation Sans face, matching the desktop QML where the
// chrome declares `monospace` and the skip box leaves the family default.
private val Mono = OmaTypography.mono
private val Sans = OmaTypography.sans

internal fun chromeBorder(colors: OmaColors): Color = colors.accent.copy(alpha = 0.72f)

internal fun sponsorColor(colors: OmaColors, category: String): Color =
    when (SponsorBlockLogic.colorKey(category)) {
        "bright_green" -> colors.brightGreen
        "green" -> colors.green
        "yellow" -> colors.yellow
        "orange" -> colors.orange
        "cyan" -> colors.cyan
        "blue" -> colors.blue
        "magenta" -> colors.magenta
        "red" -> colors.red
        else -> colors.green
    }

/**
 * A flat, bordered chrome button matching the desktop `.qml` controls. No
 * Material ripple or elevation is used.
 *
 * [weight] defaults to normal; the desktop only uses DemiBold for Back, the
 * quality selector and Replay.
 */
@Composable
internal fun ChromeButton(
    text: String,
    colors: OmaColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    height: Dp = 36.dp,
    fontSize: Int = 12,
    weight: FontWeight = FontWeight.Normal,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RectangleShape
    Box(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .height(height)
            .background(if (pressed) colors.accent.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, if (pressed) colors.accent else colors.muted, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = ChromeInk,
                fontFamily = Mono,
                fontSize = fontSize.sp,
                fontWeight = weight,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/** Desktop play/pause glyph drawn with a canvas, not an icon font. */
@Composable
internal fun PlayPauseButton(
    playing: Boolean,
    colors: OmaColors,
    onClick: () -> Unit,
    buttonSize: Dp = 40.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(buttonSize)
            .testTag("playerPlayPauseButton")
            .semantics { contentDescription = if (playing) "Pause" else "Play" }
            .background(if (pressed) colors.accent.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, if (pressed) colors.accent else colors.muted, RectangleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(buttonSize)) {
            if (playing) {
                drawRect(ChromeInk, topLeft = Offset(size.width * 0.32f, size.height * 0.24f), size = Size(size.width * 0.13f, size.height * 0.52f))
                drawRect(ChromeInk, topLeft = Offset(size.width * 0.57f, size.height * 0.24f), size = Size(size.width * 0.13f, size.height * 0.52f))
            } else {
                drawPath(
                    path = Path().apply {
                        moveTo(size.width * 0.30f, size.height * 0.22f)
                        lineTo(size.width * 0.30f, size.height * 0.78f)
                        lineTo(size.width * 0.76f, size.height * 0.50f)
                        close()
                    },
                    color = ChromeInk,
                )
            }
        }
    }
}

/**
 * Square speaker icon replacing the MUTE/UNMUTE text button. Height matches
 * the old text button (36 dp); only the width shrinks to a square.
 */
@Composable
internal fun MuteButton(
    muted: Boolean,
    colors: OmaColors,
    onClick: () -> Unit,
    buttonSize: Dp = 36.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(buttonSize)
            .testTag("playerMuteButton")
            .semantics { contentDescription = if (muted) "Unmute" else "Mute" }
            .background(if (pressed) colors.accent.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, if (pressed) colors.accent else colors.muted, RectangleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(buttonSize)) {
            val w = size.width
            val h = size.height
            drawRect(ChromeInk, topLeft = Offset(w * 0.20f, h * 0.40f), size = Size(w * 0.12f, h * 0.20f))
            drawPath(
                path = Path().apply {
                    moveTo(w * 0.32f, h * 0.40f)
                    lineTo(w * 0.52f, h * 0.22f)
                    lineTo(w * 0.52f, h * 0.78f)
                    lineTo(w * 0.32f, h * 0.60f)
                    close()
                },
                color = ChromeInk,
            )
            if (muted) {
                drawLine(
                    ChromeInk,
                    start = Offset(w * 0.62f, h * 0.40f),
                    end = Offset(w * 0.82f, h * 0.60f),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    ChromeInk,
                    start = Offset(w * 0.82f, h * 0.40f),
                    end = Offset(w * 0.62f, h * 0.60f),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            } else {
                drawArc(
                    ChromeInk,
                    startAngle = -50f,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = Offset(w * 0.52f, h * 0.28f),
                    size = Size(w * 0.20f, h * 0.44f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    ChromeInk,
                    startAngle = -50f,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = Offset(w * 0.52f, h * 0.18f),
                    size = Size(w * 0.32f, h * 0.64f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }
}

/**
 * YouTube-style fullscreen icon: four detached corners forming a square
 * outline. Entering fullscreen shows outward corners; while fullscreen the
 * corners invert to point inward.
 */
@Composable
internal fun FullscreenButton(
    fullscreen: Boolean,
    colors: OmaColors,
    onClick: () -> Unit,
    buttonSize: Dp = 36.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(buttonSize)
            .testTag("playerFullscreenButton")
            .semantics { contentDescription = if (fullscreen) "Exit fullscreen" else "Fullscreen" }
            .background(if (pressed) colors.accent.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, if (pressed) colors.accent else colors.muted, RectangleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(buttonSize)) {
            val w = size.width
            val h = size.height
            drawPath(
                path = Path().apply {
                    if (fullscreen) {
                        // Exit: vertices form a small inner square, arms reach
                        // outward, so the corners point inward.
                        val pad = 0.16f
                        val v = 0.38f
                        moveTo(w * v, h * pad)
                        lineTo(w * v, h * v)
                        lineTo(w * pad, h * v)
                        moveTo(w * (1f - v), h * pad)
                        lineTo(w * (1f - v), h * v)
                        lineTo(w * (1f - pad), h * v)
                        moveTo(w * v, h * (1f - pad))
                        lineTo(w * v, h * (1f - v))
                        lineTo(w * pad, h * (1f - v))
                        moveTo(w * (1f - v), h * (1f - pad))
                        lineTo(w * (1f - v), h * (1f - v))
                        lineTo(w * (1f - pad), h * (1f - v))
                    } else {
                        // Enter: vertices sit at the outer corners, arms reach
                        // inward along the edges.
                        val pad = 0.26f
                        val arm = 0.20f
                        moveTo(w * pad, h * (pad + arm))
                        lineTo(w * pad, h * pad)
                        lineTo(w * (pad + arm), h * pad)
                        moveTo(w * (1f - pad - arm), h * pad)
                        lineTo(w * (1f - pad), h * pad)
                        lineTo(w * (1f - pad), h * (pad + arm))
                        moveTo(w * pad, h * (1f - pad - arm))
                        lineTo(w * pad, h * (1f - pad))
                        lineTo(w * (pad + arm), h * (1f - pad))
                        moveTo(w * (1f - pad - arm), h * (1f - pad))
                        lineTo(w * (1f - pad), h * (1f - pad))
                        lineTo(w * (1f - pad), h * (1f - pad - arm))
                    }
                },
                color = ChromeInk,
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

/**
 * Shared desktop slider drawing: a four-pixel `rule` track with a one-pixel
 * `darkForeground` border, an `accent` progress fill, optional SponsorBlock
 * segments on top of the fill, and a ten-pixel square handle (foreground
 * default, accent pressed) with a one-pixel black border.
 *
 * Render order matches `PlayerControls.qml`: track, progress, segments, handle.
 * The handle is clamped inside the track edges so it never overhangs.
 *
 * The whole forty-pixel-high canvas is the invisible touch target, which is
 * larger than the ten-pixel handle.
 */
private fun DrawScope.drawDesktopSlider(
    colors: OmaColors,
    fraction: Float,
    pressed: Boolean,
    segments: List<SponsorSegment> = emptyList(),
    durationMs: Long = 0L,
) {
    val trackHeight = 4.dp.toPx()
    val centerY = size.height / 2f
    val top = centerY - trackHeight / 2f

    drawRect(colors.muted, topLeft = Offset(0f, top), size = Size(size.width, trackHeight))
    drawRect(
        colors.darkForeground,
        topLeft = Offset(0f, top),
        size = Size(size.width, trackHeight),
        style = Stroke(1.dp.toPx()),
    )
    drawRect(colors.accent, topLeft = Offset(0f, top), size = Size(size.width * fraction, trackHeight))

    if (durationMs > 0L) {
        segments.forEach { segment ->
            val start = (segment.startSeconds / (durationMs / 1000.0)).toFloat().coerceIn(0f, 1f)
            val end = (segment.endSeconds / (durationMs / 1000.0)).toFloat().coerceIn(0f, 1f)
            if (end > start) {
                val x = start * size.width
                val w = ((end - start) * size.width).coerceAtLeast(2f)
                drawRect(
                    sponsorColor(colors, segment.category),
                    topLeft = Offset(x, top),
                    size = Size(w, trackHeight),
                )
            }
        }
    }

    val handleSize = 10.dp.toPx()
    val half = handleSize / 2f
    val handleLeft = (size.width * fraction - half)
        .coerceIn(0f, (size.width - handleSize).coerceAtLeast(0f))
    val handleTop = centerY - half
    drawRect(
        if (pressed) colors.accent else colors.foreground,
        topLeft = Offset(handleLeft, handleTop),
        size = Size(handleSize, handleSize),
    )
    drawRect(
        HandleBorder,
        topLeft = Offset(handleLeft, handleTop),
        size = Size(handleSize, handleSize),
        style = Stroke(1.dp.toPx()),
    )
}

/**
 * Seek track with desktop geometry and SponsorBlock overlays. Live streams with
 * a known duration stay seekable (DVR); "Go Live" is a separate action.
 */
@Composable
internal fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    live: Boolean,
    segments: List<SponsorSegment>,
    colors: OmaColors,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onScrubbingChange: (Boolean) -> Unit = {},
) {
    var dragging by remember { mutableStateOf(false) }
    var previewFraction by remember { mutableFloatStateOf(0f) }
    val currentOnScrubbingChange = rememberUpdatedState(onScrubbingChange)
    val enabled = durationMs > 0L
    val playbackFraction = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val fraction = if (dragging) previewFraction else playbackFraction
    val shownMs = if (dragging) (previewFraction * durationMs).toLong() else positionMs

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp),
    ) {
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val widthPx = constraints.maxWidth.toFloat()

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("playerSeekBar")
                .semantics {
                    if (durationMs > 0L) {
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            current = shownMs.toFloat(),
                            range = 0f..durationMs.toFloat(),
                        )
                    }
                    setProgress { target ->
                        onSeek(target.toLong().coerceIn(0L, durationMs.coerceAtLeast(0L)))
                        true
                    }
                }
                .pointerInput(enabled, durationMs) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            previewFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            dragging = false
                            onSeek((previewFraction * durationMs).toLong())
                        },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { change, _ ->
                            previewFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            change.consume()
                        },
                    )
                }
                .pointerInput(enabled, durationMs) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        onSeek(((offset.x / size.width).coerceIn(0f, 1f) * durationMs).toLong())
                    }
                }
                // Non-consuming monitor: reports scrub contact for any pointer
                // down, including a stationary press and the pre-slop phase. It
                // observes final-pass events, so it cannot race the tap and drag
                // detectors into reporting release while a drag is still active.
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    var active = false
                    try {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            active = true
                            currentOnScrubbingChange.value(true)
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                if (event.changes.none { it.pressed }) break
                            }
                            active = false
                            currentOnScrubbingChange.value(false)
                        }
                    } finally {
                        if (active) {
                            active = false
                            currentOnScrubbingChange.value(false)
                        }
                    }
                },
        ) {
            drawDesktopSlider(
                colors = colors,
                fraction = fraction,
                pressed = dragging,
                segments = segments,
                durationMs = durationMs,
            )
        }

        if (dragging) {
            val labelText = formatTime(shownMs / 1000f)
            val textWidthPx = textMeasurer.measure(AnnotatedString(labelText), timeStyle).size.width.toFloat()
            val padPx = with(density) { 4.dp.toPx() }
            val labelWidthPx = textWidthPx + padPx * 2f
            val left = (fraction * widthPx - labelWidthPx / 2f)
                .coerceIn(0f, (widthPx - labelWidthPx).coerceAtLeast(0f))
            BasicText(
                text = labelText,
                style = timeStyle,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(left.roundToInt(), 0) }
                    .background(ChromeBackground)
                    .padding(horizontal = 4.dp)
                    .testTag("playerSeekScrubLabel"),
            )
        }
    }
}

/** The desktop `ComboBox` chevron, eight by five with a 1.2px round stroke. */
@Composable
private fun QualityChevron(modifier: Modifier = Modifier, color: Color = ChromeInk) {
    Canvas(modifier = modifier.size(width = 8.dp, height = 5.dp)) {
        drawPath(
            path = Path().apply {
                moveTo(0f, 0.5f)
                lineTo(size.width / 2f, size.height - 0.5f)
                lineTo(size.width, 0.5f)
            },
            color = color,
            style = Stroke(
                width = 1.2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

/**
 * Quality dropdown matching `PlayerControls.qml`: natural label casing, a width
 * that hugs the widest label (label + 8 + 24 + 6), an eight-by-five chevron at
 * the right, and a popup the same width as the selector with a six-pixel
 * selected marker.
 */
@Composable
internal fun QualitySelector(
    qualityValue: Int,
    colors: OmaColors,
    onQuality: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        color = ChromeInk,
        fontFamily = Mono,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val widestLabel = remember { PlaybackQuality.OPTIONS.maxByOrNull { it.label.length }?.label ?: "Default" }
    val labelWidthPx = remember(widestLabel, textMeasurer) {
        textMeasurer.measure(AnnotatedString(widestLabel), labelStyle).size.width
    }
    val selectorWidth = with(density) { labelWidthPx.toDp() } + 8.dp + 24.dp + 6.dp
    val accentBackground = expanded || pressed

    Box {
        Row(
            modifier = Modifier
                .width(selectorWidth)
                .height(34.dp)
                .background(
                    if (accentBackground) {
                        colors.accent.copy(alpha = 0.24f)
                    } else {
                        colors.background.copy(alpha = 0.18f)
                    },
                )
                .border(1.dp, if (accentBackground) colors.accent else colors.muted, RectangleShape)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { expanded = true },
                )
                .testTag("playerQualitySelector")
                .padding(start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = PlaybackQuality.labelFor(qualityValue),
                style = labelStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            QualityChevron()
        }
        if (expanded) {
            Popup(
                alignment = Alignment.TopStart,
                offset = with(density) { IntOffset(0, 34.dp.roundToPx() + 2) },
                onDismissRequest = { expanded = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
            ) {
                Column(
                    modifier = Modifier
                        .width(selectorWidth)
                        .heightIn(max = 320.dp)
                        .background(colors.background)
                        .border(1.dp, colors.accent, RectangleShape)
                        .padding(1.dp),
                ) {
                    PlaybackQuality.OPTIONS.forEach { option ->
                        val selected = option.value == qualityValue
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(34.dp)
                                .background(if (selected) colors.selection else Color.Transparent)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    onQuality(option.value)
                                    expanded = false
                                }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .background(if (selected) colors.accent else Color.Transparent),
                            )
                            BasicText(
                                text = option.label,
                                style = TextStyle(color = colors.foreground, fontFamily = Mono, fontSize = 12.sp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Manual SponsorBlock skip box, positioned by the caller. */
@Composable
internal fun SponsorSkipButton(
    segment: SponsorSegment,
    colors: OmaColors,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .background(colors.background)
            .border(2.dp, sponsorColor(colors, segment.category), RectangleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSkip)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = "Skip ${SponsorBlockLogic.label(segment.category)}",
            style = TextStyle(color = colors.foreground, fontFamily = Sans, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

/** Loading / error / ended overlay matching `PlayerControls.qml`. */
@Composable
internal fun PlayerOverlay(
    state: PlayerUiState,
    colors: OmaColors,
    onRetry: () -> Unit,
    onReplay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.overlay == PlayerUiState.Overlay.NONE) return
    val isError = state.overlay == PlayerUiState.Overlay.ERROR
    val isLoading = state.overlay == PlayerUiState.Overlay.LOADING

    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.overlay) {
        while (state.overlay == PlayerUiState.Overlay.LOADING) {
            delay(120L)
            frame = (frame + 1) % SPINNER_FRAMES.size
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .then(if (isLoading) Modifier.size(80.dp) else Modifier.width(320.dp))
                .testTag("playerOverlay")
                .background(OverlayBackground)
                .border(1.dp, if (isError) colors.red else colors.accent, RectangleShape)
                .padding(if (isLoading) 12.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (isLoading) Arrangement.Center else Arrangement.spacedBy(12.dp),
        ) {
            when (state.overlay) {
                PlayerUiState.Overlay.LOADING -> {
                    BasicText(
                        text = SPINNER_FRAMES[frame],
                        style = TextStyle(color = ChromeInk, fontFamily = Mono, fontSize = 34.sp, textAlign = TextAlign.Center),
                    )
                }
                PlayerUiState.Overlay.ERROR -> {
                    BasicText(
                        text = state.error ?: "Playback error",
                        style = TextStyle(color = colors.red, fontFamily = Mono, fontSize = 15.sp, textAlign = TextAlign.Center),
                    )
                    ChromeButton(
                        text = "RETRY",
                        colors = colors,
                        onClick = onRetry,
                        width = 120.dp,
                    )
                }
                PlayerUiState.Overlay.ENDED -> {
                    BasicText(
                        text = "Playback ended",
                        style = TextStyle(color = ChromeInk, fontFamily = Mono, fontSize = 15.sp, textAlign = TextAlign.Center),
                    )
                    ChromeButton(
                        text = "REPLAY",
                        colors = colors,
                        onClick = onReplay,
                        width = 120.dp,
                        weight = FontWeight.SemiBold,
                    )
                }
                PlayerUiState.Overlay.NONE -> Unit
            }
        }
    }
}

/** Text style helper for the time labels. */
internal val timeStyle = TextStyle(
    color = ChromeInk,
    fontFamily = Mono,
    fontSize = 12.sp,
)

internal val titleStyle = TextStyle(
    color = ChromeInk,
    fontFamily = Mono,
    fontSize = 13.sp,
    fontWeight = FontWeight.Medium,
)

internal val SPINNER_FRAMES = listOf("|", "/", "-", "\\")

internal fun formatTime(seconds: Float): String {
    val safe = if (!seconds.isFinite() || seconds < 0f) 0f else seconds
    val whole = safe.toInt()
    val hours = whole / 3600
    val minutes = (whole % 3600) / 60
    val secs = whole % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}
