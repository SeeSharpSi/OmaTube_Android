package dev.omatube.app

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.omatube.app.automation.AutomationConfig
import dev.omatube.app.automation.AutomationLaunch
import dev.omatube.app.ui.LibraryScreen
import dev.omatube.app.ui.library.LibraryRoutes
import dev.omatube.app.ui.player.PlayerScreen
import dev.omatube.app.ui.settings.SettingsScreen
import dev.omatube.app.ui.theme.LocalOmaColors
import dev.omatube.app.ui.theme.OmaTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Single Activity that hosts the full/simple library, settings and player.
 *
 * It owns no persistence. The [AppViewModel] survives configuration changes
 * (the manifest handles orientation), startup refresh is process-once, and
 * system Back closes the player, then settings, then returns to the feed.
 * Picture-in-picture is requested on home when a real video is playing.
 */
class MainActivity : ComponentActivity() {

    private val playerPlaying = MutableStateFlow(false)

    private var currentLaunch: AutomationLaunch? = null

    private val viewModel: AppViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = application as OmaTubeApplication
                AppViewModel(
                    graph = app.appGraph,
                    appContext = this@MainActivity.applicationContext,
                    savedStateHandle = createSavedStateHandle(),
                    automationLaunch = currentLaunch,
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launch = AutomationConfig.from(intent)
        currentLaunch = launch
        // Identity-aware: Activity recreation keeps the existing graph and its
        // seeded fixture; only a new debug launch configuration replaces it.
        (application as OmaTubeApplication).installGraph(launch)
        setContent {
            AppRoot(
                viewModel = viewModel,
                onPlayerPlayingChanged = { playing -> playerPlaying.value = playing },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val launch = AutomationConfig.from(intent) ?: return
        if (launch == currentLaunch) return
        currentLaunch = launch
        // A fresh Activity installs the new graph exactly once in onCreate and
        // gets a new ViewModelStore. Installing here as well would close the
        // graph still referenced by this retained ViewModel.
        finish()
        startActivity(Intent(intent))
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (currentLaunch != null) return
        if (!playerPlaying.value) return
        if (isInPictureInPictureMode) return
        try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build(),
            )
        } catch (illegalState: IllegalStateException) {
            // The player had not produced a valid running state; skip PiP.
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) {
            playerPlaying.value = false
        }
    }
}

@Composable
private fun AppRoot(
    viewModel: AppViewModel,
    onPlayerPlayingChanged: (Boolean) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnPlayerPlayingChanged = rememberUpdatedState(onPlayerPlayingChanged)
    LaunchedEffect(Unit) { viewModel.start() }

    OmaTheme(state.settings.themeId) {
        val simple = state.settings.simpleUi
        val colors = LocalOmaColors.current
        val video = state.selectedVideo

        // Non-player Full UI themes the resource-level system bars for older
        // devices; Simple UI and the player restore the window theme colors.
        // The player still owns its own fullscreen window behavior.
        OmaSystemBars(fullUi = !simple && video == null)

        BackHandler(
            enabled = video != null || state.route != LibraryRoutes.FEED,
        ) {
            if (video != null) {
                viewModel.closePlayer()
            } else {
                viewModel.onRoute(LibraryRoutes.FEED)
            }
        }

        if (video != null) {
            PlayerScreen(
                video = video,
                settings = state.settings,
                backend = viewModel.backend,
                automation = state.automation,
                onClose = viewModel::closePlayer,
                onSettingsChange = viewModel::onSettingsChange,
                onReportPlayback = viewModel::reportPlayback,
                onPlayingChanged = { playing ->
                    currentOnPlayerPlayingChanged.value(playing)
                },
            )
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                // Full UI paints its palette background behind the status and
                // gesture navigation bars; Simple UI keeps its prior inset
                // rendering untouched. The player branch above is unchanged.
                if (!simple) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.background),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                ) {
                    when (state.route) {
                        LibraryRoutes.SETTINGS -> SettingsScreen(
                            library = state.library,
                            settings = state.settings,
                            addingChannel = state.addingChannel,
                            error = state.error,
                            onClose = { viewModel.onRoute(LibraryRoutes.FEED) },
                            onSettingsChange = viewModel::onSettingsChange,
                            onAddChannel = viewModel::addChannel,
                            onRemoveChannel = viewModel::removeChannel,
                            onSetChannelCategories = viewModel::setChannelCategories,
                            onAddCategory = viewModel::addCategory,
                            onRenameCategory = viewModel::renameCategory,
                            onRemoveCategory = viewModel::removeCategory,
                            onImport = viewModel::importDocument,
                            onExport = viewModel::exportDocument,
                            onDismissError = viewModel::dismissError,
                        )

                        else -> LibraryScreen(
                            library = state.library,
                            settings = state.settings,
                            route = state.route,
                            selectedCategoryId = state.selectedCategoryId,
                            refreshing = state.refreshing,
                            loadingMore = state.loadingMore,
                            hasMore = state.hasMore,
                            status = state.status,
                            error = state.error,
                            automation = state.automation,
                            onRoute = viewModel::onRoute,
                            onCategory = viewModel::onCategory,
                            onMoveCategory = viewModel::onMoveCategory,
                            onRefresh = viewModel::refresh,
                            onLoadMore = viewModel::loadMore,
                            onOpenVideo = viewModel::openVideo,
                            onAddWatchNext = viewModel::addWatchNext,
                            onRemoveWatchNext = viewModel::removeWatchNext,
                            onMoveWatchNext = viewModel::moveWatchNext,
                            onDeleteHistory = viewModel::deleteHistory,
                            onDismissError = viewModel::dismissError,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Keeps the system bar icons readable and, on older supported releases that
 * still honor opaque resource bars, themes the bar colors for the non-player
 * Full UI. Icon appearance follows the background luminance: light Default/Rose
 * Pine backgrounds get dark icons, the dark Nord background gets light icons.
 * Simple UI and the player restore the window theme colors so their rendering
 * is unchanged.
 */
@Composable
private fun OmaSystemBars(fullUi: Boolean) {
    val background = LocalOmaColors.current.background
    val darkIcons = background.luminance() > 0.5f
    val view = LocalView.current
    val activity = view.context as? Activity
    SideEffect {
        val window = activity?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = darkIcons
        controller.isAppearanceLightNavigationBars = darkIcons
        applySystemBarColors(
            window = window,
            context = view.context,
            fullUi = fullUi,
            backgroundArgb = background.toArgb(),
        )
    }
}

/**
 * Sets the deprecated window system-bar colors. Modern targets enforce
 * edge-to-edge over these, but older supported devices render opaque resource
 * bars, so the palette has to be applied explicitly there.
 */
@Suppress("DEPRECATION")
private fun applySystemBarColors(
    window: Window,
    context: Context,
    fullUi: Boolean,
    backgroundArgb: Int,
) {
    val barColor = if (fullUi) {
        backgroundArgb
    } else {
        ContextCompat.getColor(context, R.color.omatube_window_background)
    }
    window.statusBarColor = barColor
    window.navigationBarColor = barColor
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        window.navigationBarDividerColor = barColor
    }
}
