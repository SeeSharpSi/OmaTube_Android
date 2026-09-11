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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.omatube.app.model.LibrarySnapshot
import dev.omatube.app.model.Settings
import dev.omatube.app.model.Video
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun FeedContent(
    library: LibrarySnapshot,
    settings: Settings,
    selectedCategoryId: Long,
    refreshing: Boolean,
    loadingMore: Boolean,
    hasMore: Boolean,
    automation: Boolean,
    modifier: Modifier = Modifier,
    onLoadMore: () -> Unit,
    onOpenVideo: (Video) -> Unit,
    onAddWatchNext: (String) -> Unit,
) {
    val simple = settings.simpleUi
    val spacing = if (simple) 18.dp else 14.dp
    val chrome = !simple
    val overlayReserve = (if (simple) 42.dp else 36.dp) + 16.dp
    val bottomContentPadding = overlayReserve + 12.dp

    val videos = remember(
        library.videos,
        library.channels,
        selectedCategoryId,
        settings.shortVideoCutoffMinutes,
    ) {
        feedVideos(library, selectedCategoryId, settings.shortVideoCutoffMinutes)
    }
    val live = remember(library.videos) { liveVideos(library) }
    val avatarUrls = remember(library.channels) {
        library.channels.associate { it.id to it.avatarUrl }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        LiveRow(
            live = live,
            avatarUrls = avatarUrls,
            automation = automation,
            onOpen = onOpenVideo,
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (simple) {
                SimpleFeedList(
                    videos = videos,
                    loadingMore = loadingMore,
                    hasMore = hasMore,
                    bottomContentPadding = bottomContentPadding,
                    onLoadMore = onLoadMore,
                    onOpenVideo = onOpenVideo,
                    onAddWatchNext = onAddWatchNext,
                )
            } else {
                FullFeedGrid(
                    videos = videos,
                    loadingMore = loadingMore,
                    hasMore = hasMore,
                    automation = automation,
                    bottomContentPadding = bottomContentPadding,
                    onLoadMore = onLoadMore,
                    onOpenVideo = onOpenVideo,
                    onAddWatchNext = onAddWatchNext,
                )
            }
            if (videos.isEmpty() && !refreshing) {
                EmptyState(
                    title = "No videos yet",
                    body = "Open Config to add a channel, then refresh.",
                    chrome = chrome,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun FullFeedGrid(
    videos: List<Video>,
    loadingMore: Boolean,
    hasMore: Boolean,
    automation: Boolean,
    bottomContentPadding: Dp = 0.dp,
    onLoadMore: () -> Unit,
    onOpenVideo: (Video) -> Unit,
    onAddWatchNext: (String) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = when {
            maxWidth >= 1040.dp -> 4
            maxWidth >= 780.dp -> 3
            maxWidth >= 520.dp -> 2
            else -> 1
        }
        val state = rememberLazyGridState()
        var lastTriggered by remember { mutableIntStateOf(-1) }
        LaunchedEffect(state, hasMore, loadingMore, videos.size) {
            snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                .distinctUntilChanged()
                .collect { last ->
                    if (
                        hasMore &&
                        !loadingMore &&
                        videos.isNotEmpty() &&
                        last >= videos.size - 4 &&
                        last > lastTriggered
                    ) {
                        lastTriggered = last
                        onLoadMore()
                    }
                }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            modifier = Modifier.fillMaxSize().testTag("feedGrid"),
            contentPadding = PaddingValues(
                start = 6.dp,
                end = 6.dp,
                top = 12.dp,
                bottom = bottomContentPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            gridItems(items = videos, key = { it.id }) { video ->
                FullVideoCard(
                    video = video,
                    percent = watchProgressPercent(video),
                    automation = automation,
                    testTag = "feedVideo_${video.id}",
                    outlineTestTag = "feedVideoOutline_${video.id}",
                    contentDescription = "Video ${video.id} ${video.title}",
                    metaText = relativeTime(video.publishedAt),
                    onOpen = { onOpenVideo(video) },
                    onLongPress = { onAddWatchNext(video.id) },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LoadMoreFooter(
                    loadingMore = loadingMore,
                    hasMore = hasMore,
                    chrome = true,
                    onLoadMore = onLoadMore,
                )
            }
        }
    }
}

@Composable
private fun SimpleFeedList(
    videos: List<Video>,
    loadingMore: Boolean,
    hasMore: Boolean,
    bottomContentPadding: Dp = 0.dp,
    onLoadMore: () -> Unit,
    onOpenVideo: (Video) -> Unit,
    onAddWatchNext: (String) -> Unit,
) {
    val state = rememberLazyListState()
    var lastTriggered by remember { mutableIntStateOf(-1) }
    LaunchedEffect(state, hasMore, loadingMore, videos.size) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { last ->
                if (
                    hasMore &&
                    !loadingMore &&
                    videos.isNotEmpty() &&
                    last >= videos.size - 4 &&
                    last > lastTriggered
                ) {
                    lastTriggered = last
                    onLoadMore()
                }
            }
    }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().testTag("feedList"),
        contentPadding = PaddingValues(bottom = bottomContentPadding),
    ) {
        items(items = videos, key = { it.id }) { video ->
            SimpleVideoRow(
                video = video,
                percent = watchProgressPercent(video),
                testTag = "feedVideo_${video.id}",
                contentDescription = "Video ${video.id} ${video.title}",
                metaText = relativeTime(video.publishedAt),
                onOpen = { onOpenVideo(video) },
                onLongPress = { onAddWatchNext(video.id) },
            )
        }
        item {
            LoadMoreFooter(
                loadingMore = loadingMore,
                hasMore = hasMore,
                chrome = false,
                onLoadMore = onLoadMore,
            )
        }
    }
}
