package dev.omatube.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import dev.omatube.app.model.LibrarySnapshot
import dev.omatube.app.model.Video
import dev.omatube.app.ui.components.OmaDivider
import dev.omatube.app.ui.components.OmaText
import dev.omatube.app.ui.theme.LocalOmaColors

@Composable
fun WatchNextContent(
    library: LibrarySnapshot,
    automation: Boolean,
    simple: Boolean,
    modifier: Modifier = Modifier,
    onOpenVideo: (Video) -> Unit,
    onRemoveWatchNext: (String) -> Unit,
    onMoveWatchNext: (String, Int) -> Unit,
) {
    val colors = LocalOmaColors.current
    val watchNext = library.watchNext

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (simple) {
            // Simple UI keeps the top header and divider. Full UI drops them
            // and moves the count beside the bottom-bar title.
            OmaText(
                text = "WATCH NEXT (${watchNext.size}/$WATCH_NEXT_CAP)",
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
                .weight(1f)
                .padding(horizontal = if (simple) 0.dp else 20.dp),
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
                    modifier = Modifier.fillMaxSize().testTag("watchNextGrid"),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    gridItemsIndexed(
                        items = watchNext,
                        key = { _, video -> video.id },
                    ) { index, video ->
                        WatchNextCard(
                            video = video,
                            index = index,
                            count = watchNext.size,
                            automation = automation,
                            onOpenVideo = onOpenVideo,
                            onRemoveWatchNext = onRemoveWatchNext,
                            onMoveWatchNext = onMoveWatchNext,
                        )
                    }
                }
            }
            if (watchNext.isEmpty()) {
                EmptyState(
                    title = "Watch Next is empty",
                    body = "Long-press a feed video to add it here. Capped at 25 so it stays worth watching.",
                    chrome = true,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun WatchNextCard(
    video: Video,
    index: Int,
    count: Int,
    automation: Boolean,
    onOpenVideo: (Video) -> Unit,
    onRemoveWatchNext: (String) -> Unit,
    onMoveWatchNext: (String, Int) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        FullVideoCard(
            video = video,
            percent = watchProgressPercent(video),
            automation = automation,
            testTag = "watchNextVideo_${video.id}",
            outlineTestTag = "watchNextVideoOutline_${video.id}",
            contentDescription = "Watch Next video ${video.id} ${video.title}",
            prefixText = "#${index + 1}  \u00b7  ",
            onOpen = { onOpenVideo(video) },
            onLongPress = { menuOpen = true },
            controls = {
                QueueControls(
                    videoId = video.id,
                    position = index,
                    canMoveUp = index > 0,
                    canMoveDown = index < count - 1,
                    chrome = true,
                    onMove = { target -> onMoveWatchNext(video.id, target) },
                    onRemove = { onRemoveWatchNext(video.id) },
                )
            },
        )
        OmaPopupMenu(
            expanded = menuOpen,
            offset = IntOffset(0, 0),
            chrome = true,
            onDismiss = { menuOpen = false },
            items = listOf(
                OmaMenuItem(label = "Remove from Watch Next", danger = true) {
                    onRemoveWatchNext(video.id)
                },
            ),
        )
    }
}
