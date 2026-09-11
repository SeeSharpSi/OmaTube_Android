package dev.omatube.app.ui.library

import com.google.common.truth.Truth.assertThat
import dev.omatube.app.model.Category
import dev.omatube.app.model.Channel
import dev.omatube.app.model.LibrarySnapshot
import dev.omatube.app.model.Video
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

class LibraryLogicTest {

    @Before
    fun pinTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun video(
        id: String,
        channelId: String = "channel",
        title: String = id,
        publishedAt: Long = 0,
        durationSeconds: Long = -1,
        isLive: Boolean = false,
        isUpcoming: Boolean = false,
        watchedSeconds: Long = 0,
        lastPositionSeconds: Long = 0,
        watchCount: Int = 0,
    ) = Video(
        id = id,
        channelId = channelId,
        title = title,
        channelTitle = channelId,
        publishedAt = publishedAt,
        durationSeconds = durationSeconds,
        isLive = isLive,
        isUpcoming = isUpcoming,
        watchedSeconds = watchedSeconds,
        lastPositionSeconds = lastPositionSeconds,
        watchCount = watchCount,
    )

    @Test
    fun watchProgressPercent_matchesDesktopQuery() {
        assertThat(watchProgressPercent(video("a", durationSeconds = -1, lastPositionSeconds = 10)))
            .isEqualTo(-1)
        assertThat(watchProgressPercent(video("a", durationSeconds = 0, lastPositionSeconds = 10)))
            .isEqualTo(-1)
        assertThat(watchProgressPercent(video("a", durationSeconds = 200, lastPositionSeconds = 50)))
            .isEqualTo(-1)
        assertThat(
            watchProgressPercent(
                video("a", durationSeconds = 200, lastPositionSeconds = 50, watchCount = 1),
            ),
        ).isEqualTo(25)
        assertThat(
            watchProgressPercent(
                video("a", durationSeconds = 200, lastPositionSeconds = 999, watchCount = 1),
            ),
        ).isEqualTo(100)
    }

    @Test
    fun watchProgressPercent_hidesUnwatchedButShowsRealZeroSession() {
        // No watch row at all: the desktop card hides the watched label.
        assertThat(watchProgressPercent(video("a", durationSeconds = 200))).isEqualTo(-1)

        // A real session that stopped at the start is 0%, not hidden.
        assertThat(
            watchProgressPercent(
                video("a", durationSeconds = 200, lastPositionSeconds = 0, watchCount = 1),
            ),
        ).isEqualTo(0)
        assertThat(
            watchProgressPercent(
                video("a", durationSeconds = 200, watchedSeconds = 10, watchCount = 0),
            ),
        ).isEqualTo(0)
    }

    @Test
    fun feedVideos_excludesLiveUpcomingAndShortVideos() {
        val library = LibrarySnapshot(
            videos = listOf(
                video("live", durationSeconds = 600, isLive = true, publishedAt = 500),
                video("upcoming", durationSeconds = 600, isUpcoming = true, publishedAt = 400),
                video("short", durationSeconds = 60, publishedAt = 300),
                video("unknownDuration", durationSeconds = -1, publishedAt = 200),
                video("long", durationSeconds = 600, publishedAt = 100),
            ),
        )
        val result = feedVideos(library, ALL_CATEGORY_ID, cutoffMinutes = 3)
        assertThat(result.map { it.id }).containsExactly("unknownDuration", "long").inOrder()
    }

    @Test
    fun feedVideos_scopesToSelectedCategoryAndOrdersNewestFirst() {
        val library = LibrarySnapshot(
            categories = listOf(Category(id = 1, name = "A"), Category(id = 2, name = "B")),
            channels = listOf(
                Channel(id = "channel", title = "Channel", categoryIds = setOf(1L)),
                Channel(id = "other", title = "Other", categoryIds = setOf(2L)),
            ),
            videos = listOf(
                video("old", channelId = "channel", durationSeconds = 600, publishedAt = 100),
                video("new", channelId = "channel", durationSeconds = 600, publishedAt = 900),
                video("otherChannel", channelId = "other", durationSeconds = 600, publishedAt = 950),
            ),
        )
        val result = feedVideos(library, selectedCategoryId = 1, cutoffMinutes = 3)
        assertThat(result.map { it.id }).containsExactly("new", "old").inOrder()
    }

    @Test
    fun liveVideos_keepsNewestPerChannelAndDropsUpcoming() {
        val library = LibrarySnapshot(
            videos = listOf(
                video("aOld", channelId = "a", isLive = true, publishedAt = 10),
                video("aNew", channelId = "a", isLive = true, publishedAt = 20),
                video("bLive", channelId = "b", isLive = true, publishedAt = 5),
                video("bUpcoming", channelId = "b", isLive = true, isUpcoming = true, publishedAt = 30),
                video("notLive", channelId = "c", publishedAt = 40),
            ),
        )
        assertThat(liveVideos(library).map { it.id }).containsExactly("aNew", "bLive").inOrder()
    }

    @Test
    fun relativeTime_matchesDesktopBuckets() {
        val now = 2_000_000_000_000L
        assertThat(relativeTime(now - 10_000L, now, Locale.US)).isEqualTo("now")
        assertThat(relativeTime(now - 5L * 60_000L, now, Locale.US)).isEqualTo("5 min ago")
        assertThat(relativeTime(now - 3L * 3_600_000L, now, Locale.US)).isEqualTo("3 hr ago")
        assertThat(relativeTime(now - 4L * 86_400_000L, now, Locale.US)).isEqualTo("4 days ago")
        val dateMillis = Instant.parse("2026-09-05T12:00:00Z").toEpochMilli()
        assertThat(relativeTime(dateMillis, now, Locale.US)).isEqualTo("Sep 5, 2026")
    }

    @Test
    fun formatWatchDateTime_usesDesktopPattern() {
        val millis = Instant.parse("2026-09-05T15:04:00Z").toEpochMilli()
        val formatted = formatWatchDateTime(millis, Locale.US)
        assertThat(formatted).isEqualTo("Sep 5, 2026 3:04 PM")
    }

    @Test
    fun tappingActiveCategoryDeselectsToUnfilteredSentinel() {
        assertThat(toggledCategorySelection(currentSelectedId = 1L, tappedId = 1L))
            .isEqualTo(ALL_CATEGORY_ID)
        assertThat(toggledCategorySelection(currentSelectedId = 2L, tappedId = 1L))
            .isEqualTo(1L)
        assertThat(toggledCategorySelection(currentSelectedId = ALL_CATEGORY_ID, tappedId = 3L))
            .isEqualTo(3L)
    }

    @Test
    fun categoryTargetIndex_countsOnlyCentersLeftOfDrag() {
        assertThat(categoryTargetIndex(listOf(100f, 300f, 500f), 0f)).isEqualTo(0)
        assertThat(categoryTargetIndex(listOf(100f, 300f, 500f), 250f)).isEqualTo(1)
        assertThat(categoryTargetIndex(listOf(100f, 300f, 500f), 999f)).isEqualTo(3)
    }
}
