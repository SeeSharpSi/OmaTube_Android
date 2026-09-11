package dev.omatube.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omatube.app.model.LibrarySnapshot
import dev.omatube.app.model.Settings
import dev.omatube.app.model.Video
import dev.omatube.app.ui.components.OmaErrorBanner
import dev.omatube.app.ui.components.OmaGlyphKind
import dev.omatube.app.ui.components.OmaNavButton
import dev.omatube.app.ui.components.OmaStatusPill
import dev.omatube.app.ui.components.OmaText
import dev.omatube.app.ui.library.FeedContent
import dev.omatube.app.ui.library.HistoryContent
import dev.omatube.app.ui.library.LibraryRoutes
import dev.omatube.app.ui.library.WatchNextContent
import dev.omatube.app.ui.theme.LocalOmaColors
import dev.omatube.app.ui.theme.OmaColors

/**
 * Library entry point shared by the full and simple UIs. Renders the active
 * feed, history, or Watch Next route as the scrolling content, then a header
 * row (route title plus navigation) at the bottom (replacing the desktop
 * keyboard bindings), and error/status overlays. All filtering is local; this
 * composable never performs network or persistence work.
 */
@Composable
fun LibraryScreen(
    library: LibrarySnapshot,
    settings: Settings,
    route: String,
    selectedCategoryId: Long,
    refreshing: Boolean,
    loadingMore: Boolean,
    hasMore: Boolean,
    status: String,
    error: String?,
    automation: Boolean,
    onRoute: (String) -> Unit,
    onCategory: (Long) -> Unit,
    onMoveCategory: (Long, Int) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenVideo: (Video) -> Unit,
    onAddWatchNext: (String) -> Unit,
    onRemoveWatchNext: (String) -> Unit,
    onMoveWatchNext: (String, Int) -> Unit,
    onDeleteHistory: (Long) -> Unit,
    onDismissError: () -> Unit,
) {
    val colors = LocalOmaColors.current
    val simple = settings.simpleUi
    val currentRoute = LibraryRoutes.normalize(route)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .testTag("appWindow"),
    ) {
        val sideMargin = if (simple) 56.dp else 40.dp
        val maxContentWidth = if (simple) 820.dp else 1120.dp
        val topMargin = if (simple) 28.dp else 20.dp
        val bottomMargin = if (simple) 28.dp else 20.dp
        val contentWidth = (maxWidth - sideMargin).coerceAtLeast(0.dp).coerceAtMost(maxContentWidth)
        val bottomOverlayPadding = bottomMargin + 42.dp + 10.dp + 2.dp

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(contentWidth)
                .fillMaxHeight()
                .padding(top = topMargin, bottom = bottomMargin),
            verticalArrangement = Arrangement.Top,
        ) {
            when (currentRoute) {
                LibraryRoutes.HISTORY -> HistoryContent(
                    library = library,
                    settings = settings,
                    automation = automation,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onOpenVideo = onOpenVideo,
                    onDeleteHistory = onDeleteHistory,
                )

                LibraryRoutes.WATCH_NEXT -> WatchNextContent(
                    library = library,
                    automation = automation,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onOpenVideo = onOpenVideo,
                    onRemoveWatchNext = onRemoveWatchNext,
                    onMoveWatchNext = onMoveWatchNext,
                )

                else -> FeedContent(
                    library = library,
                    settings = settings,
                    selectedCategoryId = selectedCategoryId,
                    refreshing = refreshing,
                    loadingMore = loadingMore,
                    hasMore = hasMore,
                    automation = automation,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onCategory = onCategory,
                    onMoveCategory = onMoveCategory,
                    onLoadMore = onLoadMore,
                    onOpenVideo = onOpenVideo,
                    onAddWatchNext = onAddWatchNext,
                )
            }
            LibraryBottomBar(
                simple = simple,
                route = currentRoute,
                refreshing = refreshing,
                colors = colors,
                onRoute = onRoute,
                onRefresh = onRefresh,
            )
        }

        OmaStatusPill(
            status = status,
            active = refreshing || loadingMore,
            panel = colors.lighterBackground,
            rule = colors.muted,
            ink = colors.foreground,
            mutedInk = colors.darkForeground,
            chrome = !simple,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = bottomOverlayPadding),
        )

        if (error != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 18.dp, end = 18.dp, bottom = bottomOverlayPadding),
            ) {
                OmaErrorBanner(
                    message = error,
                    panel = colors.lighterBackground,
                    danger = colors.red,
                    paper = colors.background,
                    onDismiss = onDismissError,
                )
            }
        }
    }
}

@Composable
private fun LibraryBottomBar(
    simple: Boolean,
    route: String,
    refreshing: Boolean,
    colors: OmaColors,
    onRoute: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val buttonSize = if (simple) 44.dp else 38.dp
    val glyphSize = if (simple) 17.dp else 15.dp
    Column(
        modifier = Modifier.fillMaxWidth().testTag("bottomNavigationBar"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(colors.accent),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(42.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OmaText(
                text = LibraryRoutes.label(route),
                color = colors.accent,
                fontSize = 20.sp,
                chrome = true,
                weight = FontWeight.Bold,
                modifier = Modifier.testTag("libraryTitle"),
            )
            Spacer(Modifier.weight(1f))
            OmaNavButton(
                kind = OmaGlyphKind.FEED,
                active = route == LibraryRoutes.FEED,
                contentDescription = "Show feed",
                accent = colors.accent,
                rule = colors.muted,
                softFill = colors.selection,
                panel = colors.lighterBackground,
                mutedInk = colors.darkForeground,
                ink = colors.foreground,
                buttonSize = buttonSize,
                glyphSize = glyphSize,
                testTag = "feedNavigationButton",
                onClick = { onRoute(LibraryRoutes.FEED) },
            )
            Spacer(Modifier.width(8.dp))
            OmaNavButton(
                kind = OmaGlyphKind.WATCH_NEXT,
                active = route == LibraryRoutes.WATCH_NEXT,
                contentDescription = "Show Watch Next",
                accent = colors.accent,
                rule = colors.muted,
                softFill = colors.selection,
                panel = colors.lighterBackground,
                mutedInk = colors.darkForeground,
                ink = colors.foreground,
                buttonSize = buttonSize,
                glyphSize = glyphSize,
                testTag = "watchNextNavigationButton",
                onClick = { onRoute(LibraryRoutes.WATCH_NEXT) },
            )
            Spacer(Modifier.width(8.dp))
            OmaNavButton(
                kind = OmaGlyphKind.HISTORY,
                active = route == LibraryRoutes.HISTORY,
                contentDescription = "Show history",
                accent = colors.accent,
                rule = colors.muted,
                softFill = colors.selection,
                panel = colors.lighterBackground,
                mutedInk = colors.darkForeground,
                ink = colors.foreground,
                buttonSize = buttonSize,
                glyphSize = glyphSize,
                testTag = "historyNavigationButton",
                onClick = { onRoute(LibraryRoutes.HISTORY) },
            )
            Spacer(Modifier.width(8.dp))
            OmaNavButton(
                kind = OmaGlyphKind.CONFIG,
                active = route == LibraryRoutes.SETTINGS,
                contentDescription = "Open settings",
                accent = colors.accent,
                rule = colors.muted,
                softFill = colors.selection,
                panel = colors.lighterBackground,
                mutedInk = colors.darkForeground,
                ink = colors.foreground,
                buttonSize = buttonSize,
                glyphSize = glyphSize,
                testTag = "settingsNavigationButton",
                onClick = { onRoute(LibraryRoutes.SETTINGS) },
            )
            Spacer(Modifier.width(8.dp))
            OmaNavButton(
                kind = OmaGlyphKind.REFRESH,
                active = false,
                contentDescription = "Refresh feed",
                accent = colors.accent,
                rule = colors.muted,
                softFill = colors.selection,
                panel = colors.lighterBackground,
                mutedInk = colors.darkForeground,
                ink = colors.foreground,
                enabled = !refreshing,
                buttonSize = buttonSize,
                glyphSize = glyphSize,
                testTag = "refreshButton",
                onClick = onRefresh,
            )
        }
    }
}
