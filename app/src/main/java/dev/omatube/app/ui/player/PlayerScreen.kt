package dev.omatube.app.ui.player

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.omatube.app.backend.VideoBackend
import dev.omatube.app.model.Settings
import dev.omatube.app.model.TranscriptCue
import dev.omatube.app.model.TranscriptWord
import dev.omatube.app.model.Video
import dev.omatube.app.player.PlaybackService
import dev.omatube.app.player.PlayerController
import dev.omatube.app.player.PlayerUiState
import dev.omatube.app.ui.theme.OmaColors
import dev.omatube.app.ui.theme.OmaTypography
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Full-screen player with Media3 rendering behind custom Compose Foundation
 * chrome. Mirrors the desktop `PlayerControls.qml` layout and colors.
 *
 * The player never touches persistence directly: position/watch reports and
 * settings changes are handed to the root through the callbacks.
 */
@Composable
fun PlayerScreen(
    video: Video,
    settings: Settings,
    backend: VideoBackend,
    automation: Boolean,
    onClose: () -> Unit,
    onSettingsChange: (Settings) -> Unit,
    onReportPlayback: (String, Long, Long, Boolean) -> Unit,
    onPlayingChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val currentOnSettingsChange = rememberUpdatedState(onSettingsChange)
    val currentOnReportPlayback = rememberUpdatedState(onReportPlayback)
    val currentOnPlayingChanged = rememberUpdatedState(onPlayingChanged)

    // Automation keeps a local controller that never touches the service,
    // network or real media. Real playback is owned by PlaybackService so it
    // survives Activity stop, screen lock and Home.
    val localController = if (automation) {
        remember(video.id, automation) {
            PlayerController(
                context = context.applicationContext,
                video = video,
                initialSettings = settings,
                backend = backend,
                automation = true,
                onSettingsChange = { currentOnSettingsChange.value(it) },
                onReportPlayback = { id, position, delta, newSession ->
                    currentOnReportPlayback.value(id, position, delta, newSession)
                },
                scope = scope,
            )
        }
    } else {
        null
    }

    DisposableEffect(localController) {
        if (localController == null) {
            onDispose { }
        } else {
            localController.start()
            onDispose { localController.release() }
        }
    }

    LaunchedEffect(localController, settings) {
        localController?.updateSettings(settings)
    }

    val serviceController by PlaybackService.controller.collectAsState()

    LaunchedEffect(video.id, automation) {
        if (!automation) {
            ContextCompat.startForegroundService(
                context,
                PlaybackService.startIntent(context, video, settings),
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(video.id, automation) {
        if (!automation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    BackHandler(onBack = onClose)

    // Only adopt the service controller once it owns the requested video; a
    // stale controller from a previous selection must not render or accept
    // input while the new start is in flight.
    val controller = localController ?: serviceController?.takeIf { it.videoId == video.id }
    if (controller == null) {
        PlayerStartingSurface(settings = settings)
    } else {
        PlayerContent(
            video = video,
            settings = settings,
            automation = automation,
            controller = controller,
            activity = activity,
            onClose = onClose,
            onPlayingChanged = { playing -> currentOnPlayingChanged.value(playing) },
        )
    }
}

/**
 * Black surface with the themed loading frame shown while the service-owned
 * controller is being constructed. Once [PlaybackService.controller] emits,
 * the full player chrome renders with the same UI as automation.
 */
@Composable
private fun PlayerStartingSurface(settings: Settings) {
    val colors = remember(settings.themeId) { OmaColors.forTheme(settings.themeId) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black),
    ) {
        PlayerCenterOverlay(
            state = PlayerUiState(loading = true),
            colors = colors,
            chromeVisible = true,
            onTogglePlay = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun PlayerContent(
    video: Video,
    settings: Settings,
    automation: Boolean,
    controller: PlayerController,
    activity: Activity?,
    onClose: () -> Unit,
    onPlayingChanged: (Boolean) -> Unit,
) {
    val currentOnPlayingChanged = rememberUpdatedState(onPlayingChanged)
    val scope = rememberCoroutineScope()
    val state by controller.uiState.collectAsState()
    val colors = remember(settings.themeId) { OmaColors.forTheme(settings.themeId) }
    val configuration = LocalConfiguration.current
    // Portrait behaviour is keyed on the physical orientation, never on compact
    // width; compact still chooses the bottom-bar contents and height.
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(state.playing) { currentOnPlayingChanged.value(state.playing) }
    DisposableEffect(Unit) {
        onDispose { currentOnPlayingChanged.value(false) }
    }

    PlayerSystemEffects(activity = activity, playing = state.playing)

    var chromeVisible by remember { mutableStateOf(true) }
    var portraitCenterVisible by remember { mutableStateOf(true) }
    var scrubbing by remember { mutableStateOf(false) }
    // Bumped on every scrub contact change so releasing the seek bar restarts
    // the full idle delay instead of resuming an old countdown.
    var seekInteraction by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.playing, chromeVisible, scrubbing, seekInteraction, isPortrait) {
        if (shouldAutoHideChrome(isPortrait, state.playing, chromeVisible, scrubbing)) {
            delay(CHROME_IDLE_MS)
            chromeVisible = false
        }
    }
    LaunchedEffect(state.playing, portraitCenterVisible, isPortrait) {
        if (shouldAutoHidePortraitCenter(isPortrait, state.playing, portraitCenterVisible)) {
            delay(CHROME_IDLE_MS)
            portraitCenterVisible = false
        }
    }
    // Portrait keeps top/bottom bars visible, so the center flag is independent.
    // Mirror landscape: any play/pause transition re-shows the center control;
    // the auto-hide above then clears it after a few seconds when playing,
    // while paused it stays visible.
    LaunchedEffect(state.playing, isPortrait) {
        if (isPortrait) {
            portraitCenterVisible = true
        }
    }

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val displayCutout = WindowInsets.displayCutout
    // One-sided cutout insets are asymmetric; mirror the larger physical side
    // onto both ends so the landscape chrome stays symmetric and the right
    // controls cannot sit under a corner cutout.
    val cutoutHorizontal = with(density) {
        maxOf(
            displayCutout.getLeft(this, layoutDirection),
            displayCutout.getRight(this, layoutDirection),
        ).toDp()
    }

    val handleScrubbingChange: (Boolean) -> Unit = { active ->
        scrubbing = active
        seekInteraction += 1
        if (active) {
            chromeVisible = true
        }
    }
    // Fullscreen is derived from the physical/config orientation, so a rotation
    // immediately flips the icon; the button only requests the opposite
    // orientation and never stores its own fullscreen flag.
    val onFullscreen: () -> Unit = {
        activity?.requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black),
    ) {
        // Landscape keeps the full-bleed surface. Portrait instead lays the
        // surface out inside the safe container so controls and transcript can
        // form a top-pinned stack.
        if (isLandscape) {
            PlayerSurface(
                controller = controller,
                automation = automation,
                videoId = video.id,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Tap toggles the chrome; holding boosts to 2x and releasing or
        // cancelling the gesture restores the normal speed. Landscape uses a
        // full-window layer; portrait adds its own layer above the inset video
        // so taps on the letterboxed surface still reach it.
        if (!isPortrait) {
            PlayerGestureLayer(
                controller = controller,
                scope = scope,
                onToggleChrome = { chromeVisible = !chromeVisible },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Controls live in their own container inset for the display cutout, so
        // the top bar and sponsor box stay clear of notches/camera cutouts in
        // immersive landscape. Portrait reuses the same container for the
        // surface, chrome, and transcript.
        // Only displayCutout is applied, never safeDrawing/systemBars, so the
        // hidden status bar cannot double-inset the chrome. The cutout
        // contributes only its vertical sides here; the larger of the left/right
        // insets is mirrored as horizontal padding so both chrome edges clear a
        // corner cutout.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Vertical))
                .padding(horizontal = cutoutHorizontal),
        ) {
            val compact = maxWidth < COMPACT_WIDTH
            val portraitGeometry = if (isPortrait) {
                computePortraitStackGeometry(
                    containerWidth = constraints.maxWidth.toFloat(),
                    containerHeight = constraints.maxHeight.toFloat(),
                    videoAspect = state.videoAspectRatio ?: DEFAULT_VIDEO_ASPECT,
                    topSlotHeight = with(density) { TOP_BAR_HEIGHT.toPx() },
                    bottomSlotHeight = with(density) {
                        (if (compact) COMPACT_BOTTOM_HEIGHT else WIDE_BOTTOM_HEIGHT).toPx()
                    },
                    minTranscriptHeight = with(density) { MIN_TRANSCRIPT_HEIGHT.toPx() },
                    bottomPadding = with(density) { PORTRAIT_BOTTOM_PADDING.toPx() },
                )
            } else {
                null
            }

            if (portraitGeometry != null) {
                val videoModifier = Modifier
                    .offset {
                        IntOffset(
                            portraitGeometry.videoLeft.roundToInt(),
                            portraitGeometry.videoTop.roundToInt(),
                        )
                    }
                    .size(
                        width = with(density) { portraitGeometry.videoWidth.toDp() },
                        height = with(density) { portraitGeometry.videoHeight.toDp() },
                    )
                PlayerSurface(
                    controller = controller,
                    automation = automation,
                    videoId = video.id,
                    modifier = videoModifier,
                )

                // Portrait gesture input covers video only. Transcript remains scrollable.
                PlayerGestureLayer(
                    controller = controller,
                    scope = scope,
                    onToggleChrome = { portraitCenterVisible = !portraitCenterVisible },
                    modifier = videoModifier,
                )

                Box(
                    modifier = Modifier.offset {
                        IntOffset(0, portraitGeometry.topSlotTop.roundToInt())
                    },
                ) {
                    PlayerTopBar(
                        state = state,
                        colors = colors,
                        onClose = onClose,
                        onQuality = controller::setQuality,
                    )
                }
                Box(
                    modifier = Modifier.offset {
                        IntOffset(0, portraitGeometry.bottomSlotTop.roundToInt())
                    },
                ) {
                    PlayerBottomBar(
                        state = state,
                        colors = colors,
                        compact = compact,
                        fullscreen = isLandscape,
                        onTogglePlay = controller::togglePlay,
                        onSeek = controller::seekTo,
                        onScrubbingChange = handleScrubbingChange,
                        onToggleMute = controller::toggleMute,
                        onLive = controller::seekToLiveEdge,
                        onFullscreen = onFullscreen,
                        topBorderOnly = true,
                    )
                }
                TranscriptPanel(
                    state = state,
                    colors = colors,
                    onSeek = controller::seekTo,
                    modifier = Modifier
                        .offset { IntOffset(0, portraitGeometry.transcriptTop.roundToInt()) }
                        .height(with(density) { portraitGeometry.transcriptHeight.toDp() }),
                )

                PlayerCenterOverlay(
                    state = state,
                    colors = colors,
                    chromeVisible = portraitCenterVisible,
                    onTogglePlay = controller::togglePlay,
                    modifier = videoModifier,
                )
            } else {
                if (chromeVisible) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        PlayerTopBar(
                            state = state,
                            colors = colors,
                            onClose = onClose,
                            onQuality = controller::setQuality,
                        )
                        // Landscape pulls the bottom bar in from both sides so
                        // its ends clear the side edges and rounded corners.
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            PlayerBottomBar(
                                state = state,
                                colors = colors,
                                compact = compact,
                                fullscreen = isLandscape,
                                onTogglePlay = controller::togglePlay,
                                onSeek = controller::seekTo,
                                onScrubbingChange = handleScrubbingChange,
                                onToggleMute = controller::toggleMute,
                                onLive = controller::seekToLiveEdge,
                                onFullscreen = onFullscreen,
                            )
                        }
                    }
                }
            }

            state.manualSegment?.let { segment ->
                SponsorSkipButton(
                    segment = segment,
                    colors = colors,
                    onSkip = {
                        controller.seekTo(((segment.endSeconds + 0.1) * 1000.0).toLong())
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 110.dp),
                )
            }
        }

        // In landscape the center transport and loading frame stay anchored to
        // the whole window; portrait anchors them to the video viewport above.
        if (!isPortrait) {
            PlayerCenterOverlay(
                state = state,
                colors = colors,
                chromeVisible = chromeVisible,
                onTogglePlay = controller::togglePlay,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (chromeVisible || isPortrait) {
            PlayerOverlay(
                state = state,
                colors = colors,
                onRetry = controller::retry,
                onReplay = controller::replay,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(
    controller: PlayerController,
    automation: Boolean,
    videoId: String,
    modifier: Modifier = Modifier,
) {
    val player = controller.player
    if (automation || player == null) {
        Box(modifier.testTag("playerVideoSurface"), contentAlignment = Alignment.Center) {
            BasicText(
                text = "Automation player $videoId",
                style = TextStyle(
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.5f),
                    fontFamily = OmaTypography.mono,
                    fontSize = 14.sp,
                ),
            )
        }
    } else {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    // The portrait stack assumes a letterboxed FIT surface.
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = { view -> view.player = player },
            modifier = modifier.testTag("playerVideoSurface"),
        )
    }
}

/**
 * Tap surface for the player. A tap toggles the chrome, a hold boosts to 2x
 * and releasing or cancelling restores the normal speed.
 */
@Composable
private fun PlayerGestureLayer(
    controller: PlayerController,
    scope: CoroutineScope,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.pointerInput(Unit) {
            var boosted = false
            detectTapGestures(
                onTap = {
                    if (!boosted) {
                        onToggleChrome()
                    }
                },
                onPress = {
                    boosted = false
                    val boostJob = scope.launch {
                        delay(HOLD_TO_BOOST_MS)
                        boosted = true
                        controller.setSpeed(2f)
                    }
                    tryAwaitRelease()
                    boostJob.cancel()
                    if (boosted) {
                        controller.setSpeed(1f)
                    }
                },
            )
        },
    )
}

@Composable
private fun PlayerTopBar(
    state: PlayerUiState,
    colors: OmaColors,
    onClose: () -> Unit,
    onQuality: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TOP_BAR_HEIGHT)
            .testTag("playerTopBar")
            .background(ChromeBackground)
            .border(1.dp, chromeBorder(colors), RectangleShape)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChromeButton(
            text = "< BACK",
            colors = colors,
            onClick = onClose,
            width = 76.dp,
            height = 36.dp,
            fontSize = 14,
            weight = FontWeight.SemiBold,
        )
        BasicText(
            text = state.title,
            style = titleStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        QualitySelector(
            qualityValue = state.qualityValue,
            colors = colors,
            onQuality = onQuality,
        )
    }
}

@Composable
internal fun PlayerBottomBar(
    state: PlayerUiState,
    colors: OmaColors,
    compact: Boolean,
    fullscreen: Boolean,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    onToggleMute: () -> Unit,
    onLive: () -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    topBorderOnly: Boolean = false,
) {
    // Owned here and drawn as a sibling above the bar, centered over the whole
    // control rectangle, so it never follows the seek handle inside the row.
    var scrubPreviewMs by remember { mutableStateOf<Long?>(null) }
    val topBorderColor = chromeBorder(colors)
    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) COMPACT_BOTTOM_HEIGHT else WIDE_BOTTOM_HEIGHT)
                .testTag("playerBottomBar")
                .background(ChromeBackground)
                .then(
                    if (topBorderOnly) {
                        Modifier.drawBehind {
                            val stroke = 1.dp.toPx()
                            drawLine(
                                topBorderColor,
                                Offset(0f, stroke / 2f),
                                Offset(size.width, stroke / 2f),
                                strokeWidth = stroke,
                            )
                        }
                    } else {
                        Modifier.border(1.dp, topBorderColor, RectangleShape)
                    },
                )
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(8.dp),
        ) {
            if (compact) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PlayerSeekBar(
                        positionMs = state.positionMs,
                        durationMs = state.durationMs,
                        live = state.isLive,
                        segments = state.sponsorSegments,
                        colors = colors,
                        onSeek = onSeek,
                        modifier = Modifier.fillMaxWidth(),
                        onScrubbingChange = onScrubbingChange,
                        onScrubPreviewChange = { scrubPreviewMs = it },
                    )
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PlayPauseButton(playing = state.playing, colors = colors, onClick = onTogglePlay)
                        if (state.isLive) {
                            ChromeButton(text = "LIVE", colors = colors, onClick = onLive, width = 60.dp)
                        }
                        BasicText(
                            text = formatTime(state.positionMs / 1000f),
                            style = timeStyle,
                            modifier = Modifier.testTag("playerTimeCurrent"),
                        )
                        BasicText(text = "/", style = timeStyle)
                        BasicText(
                            text = formatTime(state.durationMs / 1000f),
                            style = timeStyle,
                            modifier = Modifier.testTag("playerTimeTotal"),
                        )
                        Spacer(Modifier.weight(1f))
                        MuteButton(muted = state.muted, colors = colors, onClick = onToggleMute)
                        FullscreenButton(fullscreen = fullscreen, colors = colors, onClick = onFullscreen)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PlayPauseButton(playing = state.playing, colors = colors, onClick = onTogglePlay)
                    if (state.isLive) {
                        ChromeButton(text = "LIVE", colors = colors, onClick = onLive, width = 60.dp)
                    }
                    BasicText(
                        text = formatTime(state.positionMs / 1000f),
                        style = timeStyle,
                        modifier = Modifier.testTag("playerTimeCurrent"),
                    )
                    PlayerSeekBar(
                        positionMs = state.positionMs,
                        durationMs = state.durationMs,
                        live = state.isLive,
                        segments = state.sponsorSegments,
                        colors = colors,
                        onSeek = onSeek,
                        modifier = Modifier.weight(1f),
                        onScrubbingChange = onScrubbingChange,
                        onScrubPreviewChange = { scrubPreviewMs = it },
                    )
                    BasicText(
                        text = formatTime(state.durationMs / 1000f),
                        style = timeStyle,
                        modifier = Modifier.testTag("playerTimeTotal"),
                    )
                    MuteButton(muted = state.muted, colors = colors, onClick = onToggleMute)
                    FullscreenButton(fullscreen = fullscreen, colors = colors, onClick = onFullscreen)
                }
            }
        }

        scrubPreviewMs?.let { previewMs ->
            ScrubPreviewLabel(
                text = formatTime(previewMs / 1000f),
                colors = colors,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/**
 * Timestamp bubble shown while a scrub drag is active. It is a sibling of the
 * bar so it can overflow upward, with its bottom edge 8 dp above the bar top.
 */
@Composable
private fun ScrubPreviewLabel(
    text: String,
    colors: OmaColors,
    modifier: Modifier = Modifier,
) {
    val labelDensity = LocalDensity.current
    val gapPx = with(labelDensity) { 8.dp.toPx() }
    var labelHeightPx by remember { mutableIntStateOf(0) }
    BasicText(
        text = text,
        style = timeStyle,
        modifier = modifier
            .offset { IntOffset(0, -labelHeightPx - gapPx.roundToInt()) }
            .onSizeChanged { labelHeightPx = it.height }
            .background(ChromeBackground)
            .border(1.dp, colors.accent, RectangleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("playerSeekScrubLabel"),
    )
}

@Composable
internal fun TranscriptPanel(
    state: PlayerUiState,
    colors: OmaColors,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val markerHeightPx = with(LocalDensity.current) { 1.dp.toPx() }
    val activeIndex = activeTranscriptCueIndex(state.transcriptCues, state.positionMs)
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) listState.animateScrollToItem((activeIndex - 1).coerceAtLeast(0))
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(TranscriptBackground)
            .testTag("playerTranscript"),
    ) {
        if (state.transcriptCues.isEmpty()) {
            BasicText(
                text = if (state.transcriptLoading) "Loading transcript..." else "Transcript unavailable",
                style = TextStyle(
                    color = ChromeInk.copy(alpha = 0.64f),
                    fontFamily = OmaTypography.sans,
                    fontSize = 14.sp,
                ),
                modifier = Modifier
                    .padding(12.dp)
                    .testTag("playerTranscriptEmpty"),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(
                    items = state.transcriptCues,
                    key = { index, cue -> "${cue.startMs}_$index" },
                ) { index, cue ->
                    val active = index == activeIndex
                    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
                    val currentTextLayout = rememberUpdatedState(textLayout)
                    val currentOnSeek = rememberUpdatedState(onSeek)
                    var gutterHeightPx by remember { mutableIntStateOf(0) }
                    // Smooth progress: fractional position through the cue's words,
                    // mapped onto the measured text height so the gutter tick glides
                    // continuously instead of snapping per line.
                    val markerTop = textLayout?.let { layout ->
                        if (!active) {
                            null
                        } else {
                            val fraction = transcriptCueProgressFraction(cue, state.positionMs)
                            val total = layout.size.height.toFloat()
                            if (total <= 0f) {
                                0f
                            } else {
                                (fraction * total - markerHeightPx).coerceIn(
                                    0f,
                                    (total - markerHeightPx).coerceAtLeast(0f),
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("playerTranscriptCue_$index")
                            .padding(horizontal = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                        ) {
                            // Timestamp gutter doubles as a scrub handle: tap jumps to
                            // the cue start, vertical drag seeks smoothly through the
                            // cue. Kept narrow so text drags still scroll the list.
                            Box(
                                modifier = Modifier
                                    .width(58.dp)
                                    .fillMaxHeight()
                                    .onSizeChanged { gutterHeightPx = it.height }
                                    .testTag("playerTranscriptScrub_$index")
                                    .pointerInput(cue) {
                                        detectTapGestures {
                                            currentOnSeek.value(cue.startMs)
                                        }
                                    }
                                    .pointerInput(cue) {
                                        detectVerticalDragGestures(
                                            onDragStart = { offset ->
                                                val height = gutterHeightPx.coerceAtLeast(1)
                                                val fraction = (offset.y / height.toFloat()).coerceIn(0f, 1f)
                                                currentOnSeek.value(transcriptSeekForFraction(cue, fraction))
                                            },
                                            onVerticalDrag = { change, _ ->
                                                val height = gutterHeightPx.coerceAtLeast(1)
                                                val fraction =
                                                    (change.position.y / height.toFloat()).coerceIn(0f, 1f)
                                                currentOnSeek.value(transcriptSeekForFraction(cue, fraction))
                                                change.consume()
                                            },
                                        )
                                    },
                                contentAlignment = Alignment.TopStart,
                            ) {
                                BasicText(
                                    text = formatTime(cue.startMs / 1000f),
                                    style = TextStyle(
                                        color = ChromeInk.copy(alpha = 0.58f),
                                        fontFamily = OmaTypography.mono,
                                        fontSize = 12.sp,
                                    ),
                                )
                            }
                            BasicText(
                                text = transcriptText(
                                    cue = cue,
                                    positionMs = state.positionMs,
                                    active = active,
                                    highlight = colors.accent.copy(alpha = 0.52f),
                                ),
                                style = TextStyle(
                                    color = ChromeInk,
                                    fontFamily = OmaTypography.sans,
                                    fontSize = 16.sp,
                                    lineHeight = 23.sp,
                                ),
                                onTextLayout = { textLayout = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("playerTranscriptText_$index")
                                    .pointerInput(cue) {
                                        detectTapGestures { position ->
                                            val layout = currentTextLayout.value
                                                ?: return@detectTapGestures
                                            val offset = layout.getOffsetForPosition(position)
                                            transcriptWordAtCharacterOffset(cue, offset)?.let {
                                                currentOnSeek.value(it.startMs)
                                            }
                                        }
                                    },
                            )
                        }
                        if (markerTop != null) {
                            Box(
                                Modifier
                                    .offset { IntOffset(0, markerTop.roundToInt()) }
                                    .width(28.dp)
                                    .height(1.dp)
                                    .background(colors.brightRed)
                                    .testTag("playerTranscriptActive_$index"),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun activeTranscriptCueIndex(cues: List<TranscriptCue>, positionMs: Long): Int =
    cues.indexOfLast { positionMs >= it.startMs && positionMs < it.endMs }

internal fun transcriptText(
    cue: TranscriptCue,
    positionMs: Long,
    active: Boolean,
    highlight: Color,
): AnnotatedString = buildAnnotatedString {
    cue.words.forEachIndexed { index, word ->
        val text = if (index == 0) word.text else " ${word.text}"
        if (active && positionMs >= word.startMs) {
            withStyle(SpanStyle(background = highlight, color = ChromeInk)) {
                append(text)
            }
        } else {
            append(text)
        }
    }
}

@Composable
private fun PlayerSystemEffects(activity: Activity?, playing: Boolean) {
    DisposableEffect(activity) {
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insets?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            insets?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(activity, playing) {
        val window = activity?.window
        if (playing) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private const val CHROME_IDLE_MS = 3_000L
private const val HOLD_TO_BOOST_MS = 400L
private val COMPACT_WIDTH = 600.dp
private val TOP_BAR_HEIGHT = 52.dp
private val COMPACT_BOTTOM_HEIGHT = 100.dp
private val WIDE_BOTTOM_HEIGHT = 64.dp
private val MIN_TRANSCRIPT_HEIGHT = 180.dp
private val DEFAULT_VIDEO_ASPECT = 16f / 9f
private val TranscriptBackground = Color.Black
private val PORTRAIT_BOTTOM_PADDING = 24.dp

internal fun shouldAutoHideChrome(
    isPortrait: Boolean,
    playing: Boolean,
    chromeVisible: Boolean,
    scrubbing: Boolean,
): Boolean = !isPortrait && playing && chromeVisible && !scrubbing

internal fun shouldAutoHidePortraitCenter(
    isPortrait: Boolean,
    playing: Boolean,
    centerVisible: Boolean,
): Boolean = isPortrait && playing && centerVisible

/**
 * Absolute stack geometry for the portrait player, in pixels relative to the
 * display-cutout-safe container. Top slot and aspect-fitted video are pinned to
 * top; bottom controls sit above a fixed inset. Transcript fills space between.
 */
internal data class PortraitStackGeometry(
    val topSlotTop: Float,
    val bottomSlotTop: Float,
    val videoLeft: Float,
    val videoTop: Float,
    val videoWidth: Float,
    val videoHeight: Float,
    val transcriptTop: Float,
    val transcriptHeight: Float,
)

/**
 * Pure portrait layout solver. Invalid or non-positive input is neutralized:
 * a bad aspect falls back to 16:9 and every returned dimension is non-negative.
 */
internal fun computePortraitStackGeometry(
    containerWidth: Float,
    containerHeight: Float,
    videoAspect: Float,
    topSlotHeight: Float,
    bottomSlotHeight: Float,
    minTranscriptHeight: Float,
    bottomPadding: Float,
): PortraitStackGeometry {
    val safeWidth = sanitizeDimension(containerWidth)
    val safeHeight = sanitizeDimension(containerHeight)
    val aspect = if (videoAspect.isFinite() && videoAspect > 0f) videoAspect else DEFAULT_VIDEO_ASPECT
    val topSlot = sanitizeDimension(topSlotHeight)
    val bottomSlot = sanitizeDimension(bottomSlotHeight)
    val padding = sanitizeDimension(bottomPadding)

    val videoTop = topSlot
    val bottomTop = (safeHeight - padding - bottomSlot).coerceAtLeast(0f)
    val transcriptMinimum = sanitizeDimension(minTranscriptHeight)
        .coerceAtMost((bottomTop - videoTop).coerceAtLeast(0f))
    val videoAreaHeight = (bottomTop - videoTop - transcriptMinimum).coerceAtLeast(0f)
    var videoWidth = safeWidth
    var videoHeight = videoWidth / aspect
    if (videoHeight > videoAreaHeight) {
        videoHeight = videoAreaHeight
        videoWidth = videoHeight * aspect
    }
    videoWidth = sanitizeDimension(videoWidth).coerceAtMost(safeWidth)
    videoHeight = sanitizeDimension(videoHeight)

    val videoLeft = ((safeWidth - videoWidth) / 2f).coerceAtLeast(0f)
    val transcriptTop = videoTop + videoHeight
    return PortraitStackGeometry(
        topSlotTop = 0f,
        bottomSlotTop = bottomTop,
        videoLeft = videoLeft,
        videoTop = videoTop,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        transcriptTop = transcriptTop,
        transcriptHeight = (bottomTop - transcriptTop).coerceAtLeast(0f),
    )
}

/** Inserted spaces belong to following word; other offsets use displayed text. */
internal fun transcriptWordAtCharacterOffset(cue: TranscriptCue, offset: Int): TranscriptWord? {
    if (offset < 0) return null
    var cursor = 0
    cue.words.forEachIndexed { index, word ->
        if (index > 0) {
            if (offset == cursor) return word
            cursor++
        }
        if (offset in cursor until cursor + word.text.length) return word
        cursor += word.text.length
    }
    return null
}

internal fun activeTranscriptWordCharacterOffset(cue: TranscriptCue, positionMs: Long): Int? {
    val index = cue.words.indexOfLast { it.startMs <= positionMs }
    if (index < 0) return null
    return cue.words.take(index).sumOf { it.text.length + 1 }
}

/**
 * Smooth 0..1 progress through a cue based on word timings, interpolating by
 * time between consecutive word starts (and towards [TranscriptCue.endMs] after
 * the last word). Empty cues fall back to wall-clock fraction. Used to glide
 * the gutter tick continuously instead of snapping per line.
 */
internal fun transcriptCueProgressFraction(cue: TranscriptCue, positionMs: Long): Float {
    if (cue.words.isEmpty()) {
        if (cue.endMs <= cue.startMs) return 0f
        return ((positionMs - cue.startMs).toFloat() / (cue.endMs - cue.startMs).toFloat()).coerceIn(0f, 1f)
    }
    val index = cue.words.indexOfLast { it.startMs <= positionMs }
    if (index < 0) return 0f
    if (index >= cue.words.size - 1) {
        val lastStart = cue.words.last().startMs
        if (cue.endMs <= lastStart) return 1f
        val between =
            ((positionMs - lastStart).toFloat() / (cue.endMs - lastStart).toFloat()).coerceIn(0f, 1f)
        return ((index.toFloat() + between) / cue.words.size.toFloat()).coerceIn(0f, 1f)
    }
    val current = cue.words[index].startMs
    val next = cue.words[index + 1].startMs
    val between = if (next > current) {
        ((positionMs - current).toFloat() / (next - current).toFloat()).coerceIn(0f, 1f)
    } else {
        1f
    }
    return ((index.toFloat() + between) / cue.words.size.toFloat()).coerceIn(0f, 1f)
}

/**
 * Inverse of [transcriptCueProgressFraction]: map a 0..1 vertical fraction
 * through the cue gutter back to a seek position. Word boundaries anchor the
 * mapping so scrubbing lands on spoken words, interpolating by time within.
 */
internal fun transcriptSeekForFraction(cue: TranscriptCue, fraction: Float): Long {
    val safe = fraction.coerceIn(0f, 1f)
    if (cue.words.isEmpty()) {
        if (cue.endMs <= cue.startMs) return cue.startMs
        return (cue.startMs + safe * (cue.endMs - cue.startMs).toFloat()).toLong()
            .coerceIn(cue.startMs, cue.endMs)
    }
    val scaled = safe * cue.words.size.toFloat()
    val index = scaled.toInt().coerceIn(0, cue.words.size - 1)
    val between = (scaled - index.toFloat()).coerceIn(0f, 1f)
    val currentStart = cue.words[index].startMs
    val nextStart = if (index + 1 < cue.words.size) cue.words[index + 1].startMs else cue.endMs
    if (nextStart <= currentStart) return currentStart.coerceIn(cue.startMs, cue.endMs)
    return (currentStart + between * (nextStart - currentStart).toFloat()).toLong()
        .coerceIn(cue.startMs, cue.endMs)
}

private fun sanitizeDimension(value: Float): Float =
    if (value.isFinite()) value.coerceAtLeast(0f) else 0f
