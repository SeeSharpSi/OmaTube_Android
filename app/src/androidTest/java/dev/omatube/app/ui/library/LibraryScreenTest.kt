package dev.omatube.app.ui.library

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.omatube.app.model.Category
import dev.omatube.app.model.Channel
import dev.omatube.app.model.HistoryEntry
import dev.omatube.app.model.LibrarySnapshot
import dev.omatube.app.model.Settings
import dev.omatube.app.model.Video
import dev.omatube.app.ui.LibraryScreen
import dev.omatube.app.ui.components.OmaSpinnerFrames
import dev.omatube.app.ui.theme.OmaTheme
import kotlin.math.abs
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

    // Adds one live broadcast so the LIVE NOW row and its tile/divider geometry
    // are exercised. The live video is excluded from the normal feed grid.
    private val live = Video(
        id = "live1",
        channelId = "c1",
        title = "Live Now",
        channelTitle = "Channel One",
        publishedAt = 1_800_000_000_000L,
        isLive = true,
    )
    private val libraryWithLive = library.copy(videos = listOf(first, second, live))

    private fun setContent(
        route: String,
        simpleUi: Boolean = false,
        selectedCategoryId: Long = ALL_CATEGORY_ID,
        refreshing: Boolean = false,
        loadingMore: Boolean = false,
        status: String = "",
        error: String? = null,
        snapshot: LibrarySnapshot = library,
        windowWidth: Dp? = null,
        onRoute: (String) -> Unit = {},
        onCategory: (Long) -> Unit = {},
        onRefresh: () -> Unit = {},
        onOpenVideo: (Video) -> Unit = {},
        onAddWatchNext: (String) -> Unit = {},
        onRemoveWatchNext: (String) -> Unit = {},
        onMoveWatchNext: (String, Int) -> Unit = { _, _ -> },
        onDeleteHistory: (Long) -> Unit = {},
        onDismissError: () -> Unit = {},
    ) {
        rule.setContent {
            OmaTheme("default") {
                val screen: @Composable () -> Unit = {
                    LibraryScreen(
                        library = snapshot,
                        settings = Settings(simpleUi = simpleUi, themeId = "default"),
                        route = route,
                        selectedCategoryId = selectedCategoryId,
                        refreshing = refreshing,
                        loadingMore = loadingMore,
                        hasMore = false,
                        status = status,
                        error = error,
                        automation = true,
                        onRoute = onRoute,
                        onCategory = onCategory,
                        onMoveCategory = { _, _ -> },
                        onRefresh = onRefresh,
                        onLoadMore = {},
                        onOpenVideo = onOpenVideo,
                        onAddWatchNext = onAddWatchNext,
                        onRemoveWatchNext = onRemoveWatchNext,
                        onMoveWatchNext = onMoveWatchNext,
                        onDeleteHistory = onDeleteHistory,
                        onDismissError = onDismissError,
                    )
                }
                if (windowWidth != null) {
                    // A horizontally scrollable viewport lets a test request a
                    // window wider than the physical device without the parent
                    // constraints clamping it, so wide-layout geometry can be
                    // measured off-screen.
                    Box(modifier = Modifier.fillMaxHeight().horizontalScroll(rememberScrollState())) {
                        Box(modifier = Modifier.width(windowWidth).fillMaxHeight()) {
                            screen()
                        }
                    }
                } else {
                    screen()
                }
            }
        }
    }

    // ---- Bounds helpers ---------------------------------------------------

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun assertSize(tag: String, widthDp: Float, heightDp: Float, toleranceDp: Float = 1f) {
        val box = bounds(tag)
        assertTrue(
            "$tag width expected ${widthDp}dp but was ${box.right.value - box.left.value}dp",
            abs((box.right.value - box.left.value) - widthDp) <= toleranceDp,
        )
        assertTrue(
            "$tag height expected ${heightDp}dp but was ${box.bottom.value - box.top.value}dp",
            abs((box.bottom.value - box.top.value) - heightDp) <= toleranceDp,
        )
    }

    private fun assertSpansWindowWidth(tag: String, toleranceDp: Float = 1.5f) {
        val window = bounds("appWindow")
        val box = bounds(tag)
        assertTrue(
            "$tag left ${box.left.value}dp should reach window left ${window.left.value}dp",
            abs(box.left.value - window.left.value) <= toleranceDp,
        )
        assertTrue(
            "$tag right ${box.right.value}dp should reach window right ${window.right.value}dp",
            abs(box.right.value - window.right.value) <= toleranceDp,
        )
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
        // Full UI drops the top header and moves the count beside the bottom
        // bar title, so the old combined header text no longer exists.
        rule.onNodeWithText("WATCH NEXT (2/25)").assertDoesNotExist()
        rule.onNodeWithTag("libraryTitle").assertTextEquals("Watch Next")
        rule.onNodeWithTag("watchNextCount").assertExists()
        rule.onNodeWithTag("watchNextCount").assertTextEquals("(2/25)")
        rule.onNodeWithTag("watchNextUp_v1").performClick()
        rule.waitForIdle()
        assertEquals(listOf("v1" to 0), moves)
        rule.onNodeWithTag("watchNextRemove_v1").performClick()
        rule.waitForIdle()
        assertEquals(listOf("v1"), removed)
    }

    @Test
    fun simpleWatchNextKeepsHeaderAndHidesBottomCount() {
        setContent("watchnext", simpleUi = true)
        // Simple UI keeps the combined top header and never renders the bottom
        // count tag that Full UI added.
        rule.onNodeWithText("WATCH NEXT (2/25)").assertExists()
        rule.onNodeWithTag("libraryTitle").assertTextEquals("Watch Next")
        rule.onNodeWithTag("watchNextCount").assertDoesNotExist()
        // Simple Watch Next is title-only rows, never the full-UI card grid.
        rule.onNodeWithTag("watchNextList").assertExists()
        rule.onNodeWithTag("watchNextGrid").assertDoesNotExist()
        rule.onNodeWithText("Second").assertExists()
    }

    @Test
    fun simpleWatchNextRowsKeepQueueMoveAndRemove() {
        val moves = mutableListOf<Pair<String, Int>>()
        val removed = mutableListOf<String>()
        setContent(
            "watchnext",
            simpleUi = true,
            onRemoveWatchNext = { removed.add(it) },
            onMoveWatchNext = { id, index -> moves.add(id to index) },
        )
        // Simple rows keep the same automation tags and queue semantics as the
        // full cards, just as title-only content.
        rule.onNodeWithTag("watchNextVideo_v2").assertExists()
        rule.onNodeWithTag("watchNextVideo_v1").assertExists()
        rule.onNodeWithTag("watchNextUp_v1").performClick()
        rule.waitForIdle()
        assertEquals(listOf("v1" to 0), moves)
        rule.onNodeWithTag("watchNextRemove_v1").performClick()
        rule.waitForIdle()
        assertEquals(listOf("v1"), removed)
    }

    @Test
    fun tappingSimpleWatchNextRowOpensVideo() {
        val opened = mutableListOf<Video>()
        setContent("watchnext", simpleUi = true, onOpenVideo = { opened.add(it) })
        rule.onNodeWithTag("watchNextVideo_v1").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(listOf(first), opened)
    }

    @Test
    fun fullHistoryDropsHeaderAndKeepsGrid() {
        setContent("history")
        rule.onNodeWithText("WATCH HISTORY").assertDoesNotExist()
        rule.onNodeWithTag("historyGrid").assertExists()
        rule.onNodeWithTag("historyVideo_v1").assertExists()
    }

    @Test
    fun simpleHistoryKeepsHeaderAndList() {
        setContent("history", simpleUi = true)
        rule.onNodeWithText("WATCH HISTORY").assertExists()
        rule.onNodeWithTag("historyList").assertExists()
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

    // ---- Transient status notices (Full) vs persisted notices (Simple) -----

    private fun withFrozenClock(block: () -> Unit) {
        val previous = rule.mainClock.autoAdvance
        rule.mainClock.autoAdvance = false
        try {
            block()
        } finally {
            rule.mainClock.autoAdvance = previous
        }
    }

    @Test
    fun fullStatusPopupStartsUpperRightAndDismissesOnClick() {
        withFrozenClock {
            setContent("feed", status = "Downloading")
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("libraryStatus").assertExists()

            val window = bounds("appWindow")
            val status = bounds("libraryStatus")
            assertTrue(
                "status right edge must stay inside the window",
                status.right.value <= window.right.value + 1f,
            )
            assertTrue(
                "status must be anchored to the right edge",
                status.right.value >= window.right.value - 60f,
            )
            assertTrue(
                "status must sit in the upper half of the window",
                status.top.value < (window.top.value + window.bottom.value) / 2f,
            )

            rule.onNodeWithTag("libraryStatus").performClick()
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("libraryStatus").assertDoesNotExist()
        }
    }

    @Test
    fun fullInactiveStatusAutoHidesAfterFourSeconds() {
        withFrozenClock {
            setContent("feed", status = "Idle status")
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("libraryStatus").assertExists()
            rule.mainClock.advanceTimeBy(4_500L)
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("libraryStatus").assertDoesNotExist()
        }
    }

    @Test
    fun fullActiveStatusStaysUntilClickedAndNeverAutoHides() {
        withFrozenClock {
            setContent("feed", refreshing = true, status = "Refreshing")
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("libraryStatus").assertExists()

            // Active work keeps the popup past the four-second window. Advancing
            // the frozen clock drives the infinite spinner loop deterministically
            // without waiting for idle.
            rule.mainClock.advanceTimeBy(5_000L)
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("libraryStatus").assertExists()

            rule.onNodeWithTag("libraryStatus").performClick()
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("libraryStatus").assertDoesNotExist()
        }
    }

    @Test
    fun simpleStatusPersistsPastFourSecondsWithoutFullDismissSemantics() {
        withFrozenClock {
            setContent("feed", simpleUi = true, status = "Persisted status")
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("libraryStatus").assertExists()
            rule.onNodeWithText("Persisted status").assertExists()
            rule.mainClock.advanceTimeBy(4_500L)
            rule.mainClock.advanceTimeByFrame()
            // Simple UI never auto-hides and offers no click-to-dismiss action.
            rule.onNodeWithTag("libraryStatus").assertExists()
            rule.onNodeWithTag("libraryStatus").assertHasNoClickAction()

            val window = bounds("appWindow")
            val status = bounds("libraryStatus")
            assertTrue(
                "simple status stays bottom anchored",
                status.bottom.value > window.bottom.value - 200f,
            )
            assertTrue(
                "simple status stays right aligned",
                status.right.value > window.right.value - 60f,
            )
        }
    }

    // ---- Full refresh spinner ---------------------------------------------

    // Counts braille frame nodes that are descendants of the refresh button,
    // so the simultaneous library status spinner can never satisfy the
    // assertion.
    private fun refreshBrailleFrameCount(): Int = OmaSpinnerFrames.sumOf { frame ->
        rule.onAllNodes(
            hasText(frame) and hasAnyAncestor(hasTestTag("refreshButton")),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size
    }

    @Test
    fun fullRefreshShowsBrailleSpinnerInsteadOfGlyph() {
        withFrozenClock {
            setContent("feed", refreshing = true)
            rule.mainClock.advanceTimeByFrame()

            rule.onNodeWithTag("refreshButton").assertExists()
            rule.onNodeWithTag("refreshButton").assertIsNotEnabled()

            assertEquals(
                "loading refresh must render exactly one braille spinner frame",
                1,
                refreshBrailleFrameCount(),
            )

            // Advance within the spinner interval. Still exactly one braille
            // frame; do not assert which frame so the test stays deterministic.
            rule.mainClock.advanceTimeBy(80L)
            rule.mainClock.advanceTimeByFrame()
            assertEquals(
                "spinner keeps exactly one frame after advancing",
                1,
                refreshBrailleFrameCount(),
            )
        }
    }

    // ---- Edge-to-edge and tile geometry -----------------------------------

    @Test
    fun fullFeedCardAndDividersReachWindowEdges() {
        setContent("feed", snapshot = libraryWithLive)
        assertSpansWindowWidth("feedVideo_v1")
        assertSpansWindowWidth("liveDivider")
        assertSpansWindowWidth("bottomNavigationDivider")
    }

    @Test
    fun fullLiveTileShrinksToCompactSize() {
        setContent("feed", snapshot = libraryWithLive)
        rule.onNodeWithTag("liveVideo_live1").assertExists()
        assertSize("liveVideo_live1", widthDp = 64f, heightDp = 78f)
    }

    @Test
    fun simpleLiveTileKeepsPriorSize() {
        setContent("feed", simpleUi = true, snapshot = libraryWithLive)
        rule.onNodeWithTag("liveVideo_live1").assertExists()
        assertSize("liveVideo_live1", widthDp = 72f, heightDp = 86f)
    }

    @Test
    fun fullWatchNextCountAndNavigationStayInsideWindow() {
        // Pin the layout to a 411 dp phone so the test fails deterministically
        // against the prior compressed bottom bar on wider test devices.
        setContent("watchnext", windowWidth = 411.dp)
        val window = bounds("appWindow")
        val count = bounds("watchNextCount")
        val bar = bounds("bottomNavigationBar")
        val firstNav = bounds("feedNavigationButton")
        val refresh = bounds("refreshButton")
        assertTrue("count starts inside window", count.left.value >= window.left.value - 1f)
        assertTrue("count ends inside window", count.right.value <= window.right.value + 1f)
        assertTrue("bottom bar fits inside window", bar.bottom.value <= window.bottom.value + 1f)
        assertTrue("bottom bar starts inside window", bar.top.value >= window.top.value - 1f)
        assertTrue("count never overlaps nav buttons", count.right.value <= firstNav.left.value + 1f)
        // All five navigation controls keep their exact 38 dp square even on a
        // narrow window; a compressed refresh button fails these assertions.
        assertSize("feedNavigationButton", widthDp = 38f, heightDp = 38f)
        assertSize("watchNextNavigationButton", widthDp = 38f, heightDp = 38f)
        assertSize("historyNavigationButton", widthDp = 38f, heightDp = 38f)
        assertSize("settingsNavigationButton", widthDp = 38f, heightDp = 38f)
        assertSize("refreshButton", widthDp = 38f, heightDp = 38f)
        assertTrue(
            "refresh button right edge must stay inside the window",
            refresh.right.value <= window.right.value + 1f,
        )
    }

    @Test
    fun fullWatchNextCountStaysAdjacentToTitleAwayFromNav() {
        // Wide window makes the old weighted-title bug obvious: the count used
        // to sit at the far right of the weighted group, next to navigation.
        setContent("watchnext", windowWidth = 800.dp)
        val window = bounds("appWindow")
        val title = bounds("libraryTitle")
        val count = bounds("watchNextCount")
        val firstNav = bounds("feedNavigationButton")
        assertTrue(
            "title must stay near the left edge",
            title.left.value - window.left.value <= 25f,
        )
        assertTrue(
            "count must follow the title immediately (6 dp gap)",
            count.left.value - title.right.value <= 10f,
        )
        assertTrue(
            "count must not sit beside the navigation buttons",
            count.right.value < firstNav.left.value - 100f,
        )
    }

    @Test
    fun fullWatchNextCountStaysVisibleAtNarrowWidthAndCap() {
        // 360 dp phone and a full queue: the unweighted count must reserve its
        // width and stay visible/adjacent while the title ellipsizes.
        val capped = library.copy(
            watchNext = (1..WATCH_NEXT_CAP).map { index ->
                first.copy(id = "cap$index", title = "Capped video $index")
            },
        )
        setContent("watchnext", snapshot = capped, windowWidth = 360.dp)
        rule.onNodeWithTag("watchNextCount").assertTextEquals("(25/25)")
        val window = bounds("appWindow")
        val title = bounds("libraryTitle")
        val count = bounds("watchNextCount")
        val firstNav = bounds("feedNavigationButton")
        assertTrue("count starts inside the window", count.left.value >= window.left.value - 1f)
        assertTrue("count ends inside the window", count.right.value <= window.right.value + 1f)
        assertTrue(
            "count must follow the ellipsized title immediately",
            count.left.value - title.right.value <= 10f,
        )
        assertTrue(
            "count must stay clear of the navigation buttons",
            count.right.value <= firstNav.left.value + 1f,
        )
    }

    @Test
    fun fullHistoryCardReachesWindowEdges() {
        setContent("history")
        assertSpansWindowWidth("historyVideo_v1")
    }

    @Test
    fun simpleBottomNavigationMatchesFullGeometry() {
        setContent("feed", simpleUi = true)
        // Same 38 dp squares as the full bar.
        assertSize("feedNavigationButton", widthDp = 38f, heightDp = 38f)
        assertSize("watchNextNavigationButton", widthDp = 38f, heightDp = 38f)
        assertSize("historyNavigationButton", widthDp = 38f, heightDp = 38f)
        assertSize("settingsNavigationButton", widthDp = 38f, heightDp = 38f)
        assertSize("refreshButton", widthDp = 38f, heightDp = 38f)
        // Absolute window geometry: the Simple bar must span the window and the
        // 20 dp internal inset plus 222 dp cluster must match Normal exactly.
        val window = bounds("appWindow")
        val bar = bounds("bottomNavigationBar")
        val first = bounds("feedNavigationButton")
        val last = bounds("refreshButton")
        assertTrue(
            "bar must start flush with the window left",
            abs(bar.left.value - window.left.value) <= 1f,
        )
        assertTrue(
            "bar must end flush with the window right",
            abs(bar.right.value - window.right.value) <= 1f,
        )
        assertTrue(
            "refresh button must keep Normal's absolute 20 dp right inset",
            abs((window.right.value - last.right.value) - 20f) <= 1f,
        )
        assertTrue(
            "navigation cluster must keep Normal's absolute right placement",
            abs((window.right.value - first.left.value) - 242f) <= 2f,
        )
    }

    @Test
    fun simpleRefreshShowsBrailleSpinnerLikeFull() {
        withFrozenClock {
            setContent("feed", simpleUi = true, refreshing = true)
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("refreshButton").assertExists()
            rule.onNodeWithTag("refreshButton").assertIsNotEnabled()
            assertEquals(
                "simple loading refresh must render one braille spinner frame",
                1,
                refreshBrailleFrameCount(),
            )
        }
    }

    // ---- Error banner dismissal -------------------------------------------

    @Test
    fun fullErrorDismissButtonInvokesCallback() {
        var dismissals = 0
        withFrozenClock {
            setContent("feed", error = "Download failed", onDismissError = { dismissals++ })
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("libraryError").assertExists()
            rule.onNodeWithTag("libraryErrorDismiss").performClick()
            rule.mainClock.advanceTimeByFrame()
            assertEquals(1, dismissals)
        }
    }

    @Test
    fun fullErrorBannerRootClickInvokesCallback() {
        var dismissals = 0
        withFrozenClock {
            setContent("feed", error = "Download failed", onDismissError = { dismissals++ })
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("libraryError").performClick()
            rule.mainClock.advanceTimeByFrame()
            assertEquals(1, dismissals)
        }
    }

    @Test
    fun simpleErrorDismissButtonInvokesCallback() {
        var dismissals = 0
        setContent("feed", simpleUi = true, error = "Download failed", onDismissError = { dismissals++ })
        rule.onNodeWithTag("libraryErrorDismiss").performClick()
        rule.waitForIdle()
        assertEquals(1, dismissals)
    }
}
