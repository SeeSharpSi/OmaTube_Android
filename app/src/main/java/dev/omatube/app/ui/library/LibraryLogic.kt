package dev.omatube.app.ui.library

import dev.omatube.app.model.LibrarySnapshot
import dev.omatube.app.model.Video
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local unfiltered sentinel. There is no visible "All" category chip. */
const val ALL_CATEGORY_ID: Long = 0L

/**
 * Tapping the active category clears the filter (desktop
 * `AppController::selectCategory` toggles back to unfiltered), while tapping a
 * different category selects it.
 */
fun toggledCategorySelection(currentSelectedId: Long, tappedId: Long): Long =
    if (currentSelectedId == tappedId) ALL_CATEGORY_ID else tappedId

/** Desktop caps the committed queue at 25 entries. */
const val WATCH_NEXT_CAP: Int = 25

object LibraryRoutes {
    const val FEED = "feed"
    const val HISTORY = "history"
    const val WATCH_NEXT = "watchnext"
    const val SETTINGS = "settings"

    fun normalize(route: String): String = when (route) {
        HISTORY -> HISTORY
        WATCH_NEXT -> WATCH_NEXT
        SETTINGS -> SETTINGS
        else -> FEED
    }

    fun label(route: String): String = when (normalize(route)) {
        HISTORY -> "History"
        WATCH_NEXT -> "Watch Next"
        SETTINGS -> "Config"
        else -> "Feed"
    }
}

/**
 * Percentage of the video the viewer reached, matching the desktop query:
 * negative when the duration is unknown or no watch session has been
 * recorded. The desktop joins `video_watch_time`; a missing row yields -1,
 * while a real session that stopped at the start legitimately yields 0.
 */
fun watchProgressPercent(video: Video): Int {
    val duration = video.durationSeconds
    if (duration <= 0L) return -1
    if (video.watchCount <= 0 && video.watchedSeconds <= 0L) return -1
    return ((video.lastPositionSeconds * 100L) / duration).coerceIn(0L, 100L).toInt()
}

private fun cutoffSeconds(cutoffMinutes: Int): Long = cutoffMinutes.coerceAtLeast(0).toLong() * 60L

/**
 * Feed filter that reproduces the desktop SQL page: drop broadcasts/upcoming,
 * keep unknown durations, apply the short-video cutoff, scope to the selected
 * category, and order newest first.
 */
fun feedVideos(
    library: LibrarySnapshot,
    selectedCategoryId: Long,
    cutoffMinutes: Int,
): List<Video> {
    val categoryChannels = if (selectedCategoryId == ALL_CATEGORY_ID) {
        null
    } else {
        library.channels
            .filter { it.categoryIds.contains(selectedCategoryId) }
            .mapTo(HashSet()) { it.id }
    }
    val cutoff = cutoffSeconds(cutoffMinutes)
    return library.videos
        .asSequence()
        .filter { video ->
            !video.isLive &&
                !video.isUpcoming &&
                (video.durationSeconds < 0L || video.durationSeconds > cutoff) &&
                (categoryChannels == null || video.channelId in categoryChannels)
        }
        .sortedWith(compareByDescending<Video> { it.publishedAt }.thenByDescending { it.id })
        .toList()
}

/**
 * One live tile per channel, newest broadcast first. Upcoming streams are not
 * live and never appear in the LIVE NOW rail.
 */
fun liveVideos(library: LibrarySnapshot): List<Video> = library.videos
    .asSequence()
    .filter { it.isLive && !it.isUpcoming }
    .groupBy { it.channelId }
    .mapNotNull { (_, videos) -> videos.maxByOrNull { it.publishedAt } }
    .sortedByDescending { it.publishedAt }
    .toList()

fun videoThumbnailUrl(video: Video): String =
    video.thumbnailUrl.ifEmpty { "https://i.ytimg.com/vi/${video.id}/hqdefault.jpg" }

fun relativeTime(
    millis: Long,
    now: Long = System.currentTimeMillis(),
    locale: Locale = Locale.getDefault(),
): String {
    if (millis <= 0L) return formatDate(millis, locale)
    val seconds = ((now - millis) / 1000L).coerceAtLeast(0L)
    return when {
        seconds < 60L -> "now"
        seconds < 3600L -> "${seconds / 60L} min ago"
        seconds < 86_400L -> "${seconds / 3600L} hr ago"
        seconds < 30L * 86_400L -> "${seconds / 86_400L} days ago"
        else -> formatDate(millis, locale)
    }
}

fun formatDate(millis: Long, locale: Locale = Locale.getDefault()): String =
    SimpleDateFormat("MMM d, yyyy", locale).format(Date(millis))

fun formatWatchDateTime(millis: Long, locale: Locale = Locale.getDefault()): String =
    SimpleDateFormat("MMM d, yyyy h:mm a", locale).format(Date(millis))

/**
 * Index a dragged category should land on, mirroring the desktop drop target:
 * the number of other categories whose center sits left of the dragged center.
 */
fun categoryTargetIndex(otherCenters: List<Float>, draggedCenter: Float): Int =
    otherCenters.count { it < draggedCenter }
