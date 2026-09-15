package dev.omatube.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import dev.omatube.app.ui.library.CategoryBar
import dev.omatube.app.ui.library.FeedContent
import dev.omatube.app.ui.library.HistoryContent
import dev.omatube.app.ui.library.LibraryRoutes
import dev.omatube.app.ui.library.WATCH_NEXT_CAP
import dev.omatube.app.ui.library.WatchNextContent
import dev.omatube.app.ui.theme.LocalOmaColors
import dev.omatube.app.ui.theme.OmaColors
import kotlinx.coroutines.delay

/**
 * Library entry point shared by the full and simple UIs. Renders the active
 * feed, history, or Watch Next route as the scrolling content, a transparent
 * category overlay hovering above the feed just above the bottom navigation
 * (feed route only; gap taps are consumed so they never reach videos behind),
 * then a header row (route title plus navigation) at the bottom (replacing the
 * desktop keyboard bindings), and error/status overlays. All filtering is
 * local; this composable never performs network or persistence work.
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
        val maxContentWidth = if (simple) 820.dp else 1120.dp
        val topMargin = if (simple) 28.dp else 8.dp
        val bottomMargin = if (simple) 28.dp else 15.dp
        // Full UI lets the main content span the safe-area width on phones so
        // feed card edges and the accent dividers reach the screen edges, while
        // ordinary chrome keeps a 20 dp container inset inside each route.
        // Simple UI keeps its previous 56 dp side margin and content width.
        val contentWidth = if (simple) {
            (maxWidth - 56.dp).coerceAtLeast(0.dp).coerceAtMost(maxContentWidth)
        } else {
            maxWidth.coerceAtMost(maxContentWidth)
        }
        val bottomOverlayPadding = bottomMargin + 42.dp + 10.dp + 2.dp

        // The outer column spans the capped window width so the bottom bar
        // reaches the same absolute horizontal position in both UIs (Simple
        // must not inherit its extra route-content side margin). Route content
        // stays centered at contentWidth inside it.
        val outerWidth = maxWidth.coerceAtMost(maxContentWidth)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(outerWidth)
                .fillMaxHeight()
                .padding(top = topMargin, bottom = bottomMargin),
            verticalArrangement = Arrangement.Top,
        ) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(contentWidth)
                        .fillMaxHeight(),
                ) {
                    when (currentRoute) {
                        LibraryRoutes.HISTORY -> HistoryContent(
                            library = library,
                            settings = settings,
                            automation = automation,
                            modifier = Modifier.fillMaxSize(),
                            onOpenVideo = onOpenVideo,
                            onDeleteHistory = onDeleteHistory,
                        )

                        LibraryRoutes.WATCH_NEXT -> WatchNextContent(
                            library = library,
                            automation = automation,
                            simple = simple,
                            modifier = Modifier.fillMaxSize(),
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
                            modifier = Modifier.fillMaxSize(),
                            onLoadMore = onLoadMore,
                            onOpenVideo = onOpenVideo,
                            onAddWatchNext = onAddWatchNext,
                        )
                    }
                    if (currentRoute == LibraryRoutes.FEED && library.categories.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(
                                    start = if (simple) 0.dp else 20.dp,
                                    end = if (simple) 0.dp else 20.dp,
                                    top = 8.dp,
                                    bottom = 8.dp,
                                )
                                .testTag("categoryOverlay")
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {},
                                ),
                            contentAlignment = Alignment.BottomStart,
                        ) {
                            CategoryBar(
                                categories = library.categories,
                                selectedCategoryId = selectedCategoryId,
                                chrome = !simple,
                                barHeight = if (simple) 42.dp else 36.dp,
                                buttonHeight = if (simple) 40.dp else 34.dp,
                                horizontalPadding = if (simple) 18.dp else 14.dp,
                                fontSize = if (simple) 14.sp else 11.sp,
                                onCategory = onCategory,
                                onMoveCategory = onMoveCategory,
                            )
                        }
                    }
                }
            }
            LibraryBottomBar(
                simple = simple,
                route = currentRoute,
                refreshing = refreshing,
                watchNextCount = library.watchNext.size,
                colors = colors,
                onRoute = onRoute,
                onRefresh = onRefresh,
            )
        }

        val working = refreshing || loadingMore

        if (simple) {
            // Simple UI keeps its existing bottom-anchored status/error
            // placement, persistence and click behavior.
            OmaStatusPill(
                status = status,
                active = working,
                panel = colors.lighterBackground,
                rule = colors.muted,
                ink = colors.foreground,
                mutedInk = colors.darkForeground,
                chrome = false,
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
        } else {
            // Full UI shows transient top-right notices: the status popup and
            // the error banner stack vertically, auto-hide four seconds after
            // work is inactive, and dismiss on tap. While work is active the
            // status popup stays until it is clicked.
            var statusVisible by remember { mutableStateOf(status.isNotEmpty() || working) }
            LaunchedEffect(status, working) {
                statusVisible = status.isNotEmpty() || working
                if (statusVisible && !working) {
                    delay(4_000L)
                    statusVisible = false
                }
            }
            LaunchedEffect(error) {
                if (error != null) {
                    delay(4_000L)
                    onDismissError()
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 20.dp, top = 8.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (statusVisible) {
                    OmaStatusPill(
                        status = status,
                        active = working,
                        panel = colors.lighterBackground,
                        rule = colors.muted,
                        ink = colors.foreground,
                        mutedInk = colors.darkForeground,
                        chrome = true,
                        modifier = Modifier
                            .widthIn(max = 360.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClickLabel = "Dismiss status",
                                role = Role.Button,
                                onClick = { statusVisible = false },
                            ),
                    )
                }
                if (error != null) {
                    OmaErrorBanner(
                        message = error,
                        panel = colors.lighterBackground,
                        danger = colors.red,
                        paper = colors.background,
                        onDismiss = onDismissError,
                        modifier = Modifier
                            .widthIn(max = 360.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClickLabel = "Dismiss error",
                                role = Role.Button,
                                onClick = onDismissError,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryBottomBar(
    simple: Boolean,
    route: String,
    refreshing: Boolean,
    watchNextCount: Int,
    colors: OmaColors,
    onRoute: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    // Simple and Full navigation share one geometry so the Simple bar matches
    // the Full bar in button size, glyph size and horizontal padding.
    val buttonSize = 38.dp
    val glyphSize = 15.dp
    Column(
        modifier = Modifier.fillMaxWidth().testTag("bottomNavigationBar"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(colors.accent)
                .testTag("bottomNavigationDivider"),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!simple && route == LibraryRoutes.WATCH_NEXT) {
                // Weighted leading group: the fixed-size navigation buttons are
                // measured first, then the title and count stay adjacent and
                // left-aligned instead of the count being pushed to the far
                // right beside the navigation controls.
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OmaText(
                        text = LibraryRoutes.label(route),
                        color = colors.accent,
                        fontSize = 17.sp,
                        chrome = true,
                        weight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .testTag("libraryTitle"),
                    )
                    Spacer(Modifier.width(6.dp))
                    OmaText(
                        text = "($watchNextCount/$WATCH_NEXT_CAP)",
                        color = colors.yellow,
                        fontSize = 11.sp,
                        chrome = true,
                        weight = FontWeight.Bold,
                        modifier = Modifier.testTag("watchNextCount"),
                    )
                }
            } else {
                // Simple UI keeps its top Watch Next header and omits the
                // bottom-bar count, but its Watch Next route label still uses
                // the same 17 sp typography as the Full UI; Feed and History
                // keep the 20 sp route label.
                OmaText(
                    text = LibraryRoutes.label(route),
                    color = colors.accent,
                    fontSize = if (route == LibraryRoutes.WATCH_NEXT) 17.sp else 20.sp,
                    chrome = true,
                    weight = FontWeight.Bold,
                    modifier = Modifier.testTag("libraryTitle"),
                )
                Spacer(Modifier.weight(1f))
            }
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
                loading = refreshing,
                buttonSize = buttonSize,
                glyphSize = glyphSize,
                testTag = "refreshButton",
                onClick = onRefresh,
            )
        }
    }
}
