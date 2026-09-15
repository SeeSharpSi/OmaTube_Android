package dev.omatube.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omatube.app.model.HistoryEntry
import dev.omatube.app.model.LibrarySnapshot
import dev.omatube.app.model.Settings
import dev.omatube.app.model.Video
import dev.omatube.app.ui.components.OmaDivider
import dev.omatube.app.ui.components.OmaText
import dev.omatube.app.ui.theme.LocalOmaColors

@Composable
fun HistoryContent(
    library: LibrarySnapshot,
    settings: Settings,
    automation: Boolean,
    modifier: Modifier = Modifier,
    onOpenVideo: (Video) -> Unit,
    onDeleteHistory: (Long) -> Unit,
) {
    val colors = LocalOmaColors.current
    val simple = settings.simpleUi
    val chrome = !simple
    val spacing = if (simple) 18.dp else 14.dp
    val history = library.history

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        if (simple) {
            // Simple UI keeps the top header and divider; Full UI drops them.
            OmaText(
                text = "WATCH HISTORY",
                color = colors.brightYellow,
                fontSize = 11.sp,
                chrome = false,
                weight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            OmaDivider(color = colors.muted)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (simple) {
                SimpleHistoryList(
                    history = history,
                    onOpenVideo = onOpenVideo,
                    onDeleteHistory = onDeleteHistory,
                )
            } else {
                FullHistoryGrid(
                    history = history,
                    automation = automation,
                    onOpenVideo = onOpenVideo,
                    onDeleteHistory = onDeleteHistory,
                )
            }
            if (history.isEmpty()) {
                EmptyState(
                    title = "No watch history yet",
                    body = "Videos you watch appear here.",
                    chrome = chrome,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun FullHistoryGrid(
    history: List<HistoryEntry>,
    automation: Boolean,
    onOpenVideo: (Video) -> Unit,
    onDeleteHistory: (Long) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = when {
            maxWidth >= 1040.dp -> 4
            maxWidth >= 780.dp -> 3
            maxWidth >= 520.dp -> 2
            else -> 1
        }
        val state = rememberLazyGridState()
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            modifier = Modifier.fillMaxSize().testTag("historyGrid"),
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            gridItems(items = history, key = { it.id }) { entry ->
                HistoryCard(
                    entry = entry,
                    automation = automation,
                    onOpenVideo = onOpenVideo,
                    onDeleteHistory = onDeleteHistory,
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(
    entry: HistoryEntry,
    automation: Boolean,
    onOpenVideo: (Video) -> Unit,
    onDeleteHistory: (Long) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        FullVideoCard(
            video = entry.video,
            percent = watchProgressPercent(entry.video),
            automation = automation,
            testTag = "historyVideo_${entry.video.id}",
            outlineTestTag = "historyVideoOutline_${entry.video.id}",
            contentDescription = "History video ${entry.video.id} ${entry.video.title}",
            metaText = "  \u00b7  last viewed ${formatWatchDateTime(entry.watchedAt)}",
            onOpen = { onOpenVideo(entry.video) },
            onLongPress = { menuOpen = true },
        )
        OmaPopupMenu(
            expanded = menuOpen,
            offset = IntOffset(0, 0),
            chrome = true,
            onDismiss = { menuOpen = false },
            items = listOf(
                OmaMenuItem(label = "Delete from history", danger = true) {
                    onDeleteHistory(entry.id)
                },
            ),
        )
    }
}

@Composable
private fun SimpleHistoryList(
    history: List<HistoryEntry>,
    onOpenVideo: (Video) -> Unit,
    onDeleteHistory: (Long) -> Unit,
) {
    val state = rememberLazyListState()
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().testTag("historyList"),
    ) {
        items(items = history, key = { it.id }) { entry ->
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                SimpleVideoRow(
                    video = entry.video,
                    percent = watchProgressPercent(entry.video),
                    testTag = "historyVideo_${entry.video.id}",
                    contentDescription = "History video ${entry.video.id} ${entry.video.title}",
                    metaText = "last viewed ${formatWatchDateTime(entry.watchedAt)}",
                    onOpen = { onOpenVideo(entry.video) },
                    onLongPress = { menuOpen = true },
                )
                OmaPopupMenu(
                    expanded = menuOpen,
                    offset = IntOffset(0, 0),
                    chrome = false,
                    onDismiss = { menuOpen = false },
                    items = listOf(
                        OmaMenuItem(label = "Delete from history", danger = true) {
                            onDeleteHistory(entry.id)
                        },
                    ),
                )
            }
        }
    }
}
