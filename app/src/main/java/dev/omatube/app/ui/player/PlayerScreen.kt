package dev.omatube.app.ui.player

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import dev.omatube.app.backend.VideoBackend
import dev.omatube.app.model.Settings
import dev.omatube.app.model.Video
import dev.omatube.app.player.PlayerController
import dev.omatube.app.player.PlayerUiState
import dev.omatube.app.ui.theme.OmaColors
import dev.omatube.app.ui.theme.OmaTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    val controller = remember(video.id, automation) {
        PlayerController(
            context = context.applicationContext,
            video = video,
            initialSettings = settings,
            backend = backend,
            automation = automation,
            onSettingsChange = { currentOnSettingsChange.value(it) },
            onReportPlayback = { id, position, delta, newSession ->
                currentOnReportPlayback.value(id, position, delta, newSession)
            },
            scope = scope,
        )
    }

    LaunchedEffect(settings) { controller.updateSettings(settings) }
    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.release() }
    }

    val state by controller.uiState.collectAsState()
    val colors = remember(settings.themeId) { OmaColors.forTheme(settings.themeId) }
    val fullscreen = remember { mutableStateOf(false) }

    LaunchedEffect(state.playing) { currentOnPlayingChanged.value(state.playing) }
    DisposableEffect(Unit) {
        onDispose { currentOnPlayingChanged.value(false) }
    }

    BackHandler(onBack = onClose)

    PlayerSystemEffects(activity = activity, playing = state.playing)
    PlayerLifecycleEffects(activity = activity, controller = controller)

    var chromeVisible by remember { mutableStateOf(true) }
    LaunchedEffect(state.playing, chromeVisible) {
        if (state.playing && chromeVisible) {
            delay(CHROME_IDLE_MS)
            chromeVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black),
    ) {
        PlayerSurface(
            controller = controller,
            automation = automation,
            videoId = video.id,
            modifier = Modifier.fillMaxSize(),
        )

        // Tap toggles the chrome; holding boosts to 2x and releasing or
        // cancelling the gesture restores the normal speed.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            var boosted = false
                            val boostJob = scope.launch {
                                delay(HOLD_TO_BOOST_MS)
                                boosted = true
                                controller.setSpeed(2f)
                            }
                            val released = tryAwaitRelease()
                            boostJob.cancel()
                            if (boosted) {
                                controller.setSpeed(1f)
                            } else if (released) {
                                chromeVisible = !chromeVisible
                            }
                        },
                    )
                },
        )

        // Controls live in their own container inset for the display cutout, so
        // the top bar and sponsor box stay clear of notches/camera cutouts in
        // immersive landscape. The video surface above is intentionally not
        // inset and keeps filling the whole window. Only displayCutout is
        // applied, never safeDrawing/systemBars, so the hidden status bar
        // cannot double-inset the chrome.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout),
        ) {
            if (chromeVisible) {
                PlayerChromeLayer(
                    state = state,
                    colors = colors,
                    fullscreen = fullscreen.value,
                    onClose = onClose,
                    onTogglePlay = controller::togglePlay,
                    onSeek = controller::seekTo,
                    onQuality = controller::setQuality,
                    onToggleMute = controller::toggleMute,
                    onLive = controller::seekToLiveEdge,
                    onFullscreen = {
                        activity?.let { owner ->
                            val landscape = owner.resources.configuration.orientation ==
                                Configuration.ORIENTATION_LANDSCAPE
                            owner.requestedOrientation = if (landscape) {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            }
                            fullscreen.value = !landscape
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
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

        if (chromeVisible) {
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
        Box(modifier, contentAlignment = Alignment.Center) {
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
                }
            },
            update = { view -> view.player = player },
            modifier = modifier,
        )
    }
}

@Composable
private fun PlayerChromeLayer(
    state: PlayerUiState,
    colors: OmaColors,
    fullscreen: Boolean,
    onClose: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onQuality: (Int) -> Unit,
    onToggleMute: () -> Unit,
    onLive: () -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val compact = maxWidth < COMPACT_WIDTH
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            PlayerTopBar(
                state = state,
                colors = colors,
                onClose = onClose,
                onQuality = onQuality,
            )
            // Portrait floats 16 dp above the phone bottom. Landscape sits
            // flush with the bottom edge but pulls in from both sides so the
            // bar ends clear the side edges and rounded corners, including
            // the side that is the phone's physical bottom.
            Box(
                modifier = if (compact) {
                    Modifier.padding(bottom = 16.dp)
                } else {
                    Modifier.padding(horizontal = 16.dp)
                },
            ) {
                PlayerBottomBar(
                    state = state,
                    colors = colors,
                    compact = compact,
                    fullscreen = fullscreen,
                    onTogglePlay = onTogglePlay,
                    onSeek = onSeek,
                    onToggleMute = onToggleMute,
                    onLive = onLive,
                    onFullscreen = onFullscreen,
                )
            }
        }
    }
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
            .height(52.dp)
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
private fun PlayerBottomBar(
    state: PlayerUiState,
    colors: OmaColors,
    compact: Boolean,
    fullscreen: Boolean,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
    onLive: () -> Unit,
    onFullscreen: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 100.dp else 64.dp)
            .background(ChromeBackground)
            .border(1.dp, chromeBorder(colors), RectangleShape)
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

@Composable
private fun PlayerLifecycleEffects(activity: Activity?, controller: PlayerController) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, activity, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                // PiP keeps the activity resumed; only pause on a real
                // background transition.
                if (activity?.isInPictureInPictureMode != true) {
                    controller.onBackground()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(activity, controller) {
        val owner = activity
        if (owner == null) {
            onDispose { }
        } else {
            val audioManager = owner.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        -> controller.pauseIfPlaying()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                            controller.setDucked(true)
                        AudioManager.AUDIOFOCUS_GAIN -> controller.setDucked(false)
                    }
                }
                .build()
            audioManager.requestAudioFocus(focusRequest)

            val noisyReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    controller.pauseIfPlaying()
                }
            }
            ContextCompat.registerReceiver(
                owner,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )

            onDispose {
                runCatching { owner.unregisterReceiver(noisyReceiver) }
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
        }
    }
}

private const val CHROME_IDLE_MS = 3_000L
private const val HOLD_TO_BOOST_MS = 400L
private val COMPACT_WIDTH = 600.dp
