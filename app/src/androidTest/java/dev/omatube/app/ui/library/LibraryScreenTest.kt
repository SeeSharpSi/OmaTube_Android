package dev.omatube.app.ui.library

import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import dev.omatube.app.model.Category
import dev.omatube.app.model.Channel
import dev.omatube.app.model.HistoryEntry
import dev.omatube.app.model.LibrarySnapshot
import dev.omatube.app.model.Settings
import dev.omatube.app.model.Video
import dev.omatube.app.ui.LibraryScreen
import dev.omatube.app.ui.theme.OmaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val first = Video(
        id = "v1",
        channelId = "c1",
        title = "First",
        channelTitle = "Channel One",
        publishedAt = 1_700_000_000_000L,
        durationSeconds = 600,
        watchedSeconds = 120,
        lastPositionSeconds = 120,
        watchCount = 1,
    )
    private val second = Video(
        id = "v2",
        channelId = "c1",
        title = "Second",
        channelTitle = "Channel One",
        // A distinct, deterministic publish date: both fixtures must format to
        // different labels or the onNodeWithText date assertion is ambiguous.
        publishedAt = 1_500_000_000_000L,
        durationSeconds = 600,
    )
    private val library = LibrarySnapshot(
        categories = listOf(Category(id = 1, name = "Alpha"), Category(id = 2, name = "Beta")),
        channels = listOf(Channel(id = "c1", title = "Channel One", categoryIds = setOf(1L))),
        videos = listOf(first, second),
        history = listOf(HistoryEntry(id = 7L, video = first, watchedAt = 1_234L)),
        watchNext = listOf(second, first),
    )

    private fun setContent(
        route: String,
        simpleUi: Boolean = false,
        selectedCategoryId: Long = ALL_CATEGORY_ID,
        onRoute: (String) -> Unit = {},
        onCategory: (Long) -> Unit = {},
        onOpenVideo: (Video) -> Unit = {},
        onAddWatchNext: (String) -> Unit = {},
        onRemoveWatchNext: (String) -> Unit = {},
        onMoveWatchNext: (String, Int) -> Unit = { _, _ -> },
        onDeleteHistory: (Long) -> Unit = {},
    ) {
        rule.setContent {
            OmaTheme("default") {
                LibraryScreen(
                    library = library,
                    settings = Settings(simpleUi = simpleUi, themeId = "default"),
                    route = route,
                    selectedCategoryId = selectedCategoryId,
                    refreshing = false,
                    loadingMore = false,
                    hasMore = false,
                    status = "",
                    error = null,
                    automation = true,
                    onRoute = onRoute,
                    onCategory = onCategory,
                    onMoveCategory = { _, _ -> },
                    onRefresh = {},
                    onLoadMore = {},
                    onOpenVideo = onOpenVideo,
                    onAddWatchNext = onAddWatchNext,
                    onRemoveWatchNext = onRemoveWatchNext,
                    onMoveWatchNext = onMoveWatchNext,
                    onDeleteHistory = onDeleteHistory,
                    onDismissError = {},
                )
            }
        }
    }

    @Test
    fun feedRouteShowsGridAndUserCategoriesOnly() {
        setContent("feed")
        rule.onNodeWithTag("feedGrid").assertExists()
        rule.onNodeWithTag("categoryButton_1").assertExists()
        rule.onNodeWithTag("categoryButton_2").assertExists()
        // There is no visible "All" chip; unfiltered is the internal sentinel.
        rule.onNodeWithTag("categoryButton_0").assertDoesNotExist()
        rule.onNodeWithTag("feedVideo_v1").assertExists()
    }

    @Test
    fun tappingActiveCategoryClearsFilterToSentinel() {
        val selected = mutableListOf<Long>()
        setContent("feed", selectedCategoryId = 1L, onCategory = { selected.add(it) })
        rule.onNodeWithTag("categoryButton_1").performClick()
        rule.waitForIdle()
        assertEquals(listOf(ALL_CATEGORY_ID), selected)
    }

    @Test
    fun tappingOtherCategorySelectsIt() {
        val selected = mutableListOf<Long>()
        setContent("feed", selectedCategoryId = 1L, onCategory = { selected.add(it) })
        rule.onNodeWithTag("categoryButton_2").performClick()
        rule.waitForIdle()
        assertEquals(listOf(2L), selected)
    }

    @Test
    fun feedCardsShowPublishedDateAndHideUnwatchedProgress() {
        setContent("feed")
        // Desktop full feed prints the published date beside the channel.
        rule.onNodeWithText(relativeTime(first.publishedAt)).assertExists()
        // Watched card shows its percentage...
        rule.onNodeWithText("20% watched").assertExists()
        // ...but an unwatched card (second) must not render a 0% label.
        rule.onNodeWithText("0% watched").assertDoesNotExist()
    }

    @Test
    fun navigationButtonsInvokeRouteCallback() {
        val routes = mutableListOf<String>()
        setContent("feed", onRoute = { routes.add(it) })
        rule.onNodeWithTag("historyNavigationButton").performClick()
        rule.onNodeWithTag("watchNextNavigationButton").performClick()
        rule.onNodeWithTag("settingsNavigationButton").performClick()
        rule.waitForIdle()
        assertEquals(listOf("history", "watchnext", "settings"), routes)
    }

    @Test
    fun simpleFeedUsesTextRows() {
        setContent("feed", simpleUi = true)
        rule.onNodeWithTag("feedVideo_v1").assertExists()
        rule.onNodeWithTag("feedList").assertExists()
    }

    @Test
    fun longPressFeedVideoAddsToWatchNext() {
        val added = mutableListOf<String>()
        setContent("feed", onAddWatchNext = { added.add(it) })
        rule.onNodeWithTag("feedVideo_v1").performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals(listOf("v1"), added)
    }

    @Test
    fun longPressHistoryVideoDeletesByEntryId() {
        val deleted = mutableListOf<Long>()
        setContent("history", onDeleteHistory = { deleted.add(it) })
        rule.onNodeWithTag("historyVideo_v1").performTouchInput { longClick() }
        rule.onNodeWithText("Delete from history").performClick()
        rule.waitForIdle()
        assertEquals(listOf(7L), deleted)
    }

    @Test
    fun watchNextControlsMoveAndRemove() {
        val moves = mutableListOf<Pair<String, Int>>()
        val removed = mutableListOf<String>()
        setContent(
            "watchnext",
            onRemoveWatchNext = { removed.add(it) },
            onMoveWatchNext = { id, index -> moves.add(id to index) },
        )
        rule.onNodeWithText("WATCH NEXT (2/25)").assertExists()
        rule.onNodeWithTag("watchNextUp_v1").performClick()
        rule.waitForIdle()
        assertEquals(listOf("v1" to 0), moves)
        rule.onNodeWithTag("watchNextRemove_v1").performClick()
        rule.waitForIdle()
        assertEquals(listOf("v1"), removed)
    }

    // ---- Tap-to-open regression coverage ---------------------------------
    //
    // These use real pointer injection (performTouchInput click), not the
    // semantic performClick action. The original bug chained clickable with a
    // later pointerInput long-press detector that consumed the down/up events,
    // so the semantic click action still worked while a real tap did nothing.

    @Test
    fun tappingFullFeedCardOpensVideo() {
        val opened = mutableListOf<Video>()
        setContent("feed", onOpenVideo = { opened.add(it) })
        rule.onNodeWithTag("feedVideo_v1").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(listOf(first), opened)
    }

    @Test
    fun tappingSimpleFeedRowOpensVideo() {
        val opened = mutableListOf<Video>()
        setContent("feed", simpleUi = true, onOpenVideo = { opened.add(it) })
        rule.onNodeWithTag("feedVideo_v1").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(listOf(first), opened)
    }

    @Test
    fun tappingFullHistoryCardOpensVideo() {
        val opened = mutableListOf<Video>()
        setContent("history", onOpenVideo = { opened.add(it) })
        rule.onNodeWithTag("historyVideo_v1").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(listOf(first), opened)
    }

    @Test
    fun tappingSimpleHistoryRowOpensVideo() {
        val opened = mutableListOf<Video>()
        setContent("history", simpleUi = true, onOpenVideo = { opened.add(it) })
        rule.onNodeWithTag("historyVideo_v1").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(listOf(first), opened)
    }

    @Test
    fun tappingWatchNextCardOpensVideo() {
        val opened = mutableListOf<Video>()
        setContent("watchnext", onOpenVideo = { opened.add(it) })
        rule.onNodeWithTag("watchNextVideo_v1").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(listOf(first), opened)
    }

    @Test
    fun watchNextQueueControlsDoNotOpenVideo() {
        val opened = mutableListOf<Video>()
        setContent("watchnext", onOpenVideo = { opened.add(it) })
        rule.onNodeWithTag("watchNextUp_v1").performTouchInput { click() }
        rule.onNodeWithTag("watchNextRemove_v1").performTouchInput { click() }
        rule.waitForIdle()
        assertTrue("queue controls must not launch the player", opened.isEmpty())
    }

    @Test
    fun longPressStillAddsWithoutOpening() {
        val opened = mutableListOf<Video>()
        val added = mutableListOf<String>()
        setContent(
            "feed",
            onOpenVideo = { opened.add(it) },
            onAddWatchNext = { added.add(it) },
        )
        rule.onNodeWithTag("feedVideo_v1").performTouchInput { longClick() }
        rule.waitForIdle()
        assertTrue("long-press must not open the player", opened.isEmpty())
        assertEquals(listOf("v1"), added)
    }

    @Test
    fun longPressSimpleFeedStillAddsWithoutOpening() {
        val opened = mutableListOf<Video>()
        val added = mutableListOf<String>()
        setContent(
            "feed",
            simpleUi = true,
            onOpenVideo = { opened.add(it) },
            onAddWatchNext = { added.add(it) },
        )
        rule.onNodeWithTag("feedVideo_v1").performTouchInput { longClick() }
        rule.waitForIdle()
        assertTrue("long-press must not open the player", opened.isEmpty())
        assertEquals(listOf("v1"), added)
    }

    @Test
    fun dragCancelsFeedClickThenNextTapOpens() {
        val opened = mutableListOf<Video>()
        setContent("feed", onOpenVideo = { opened.add(it) })
        rule.onNodeWithTag("feedVideo_v1").performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertTrue("a drag must not open the player", opened.isEmpty())
        rule.onNodeWithTag("feedVideo_v1").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(listOf(first), opened)
    }

    @Test
    fun feedCardKeepsAccessibleDescription() {
        setContent("feed")
        rule.onNodeWithContentDescription("Video v1 First").assertExists()
    }
}
