package dev.omatube.app.backend

import dev.omatube.app.model.Video
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Maps NewPipe [StreamInfoItem]s onto the app [Video] model.
 *
 * Live/upcoming detection is based on the extractor's own signals:
 *  - [StreamType.LIVE_STREAM] / [StreamType.AUDIO_LIVE_STREAM] mean the stream is running now.
 *  - [ContentAvailability.UPCOMING] means the stream is scheduled but not started.
 * `POST_LIVE_*` streams have already ended and are treated as ordinary videos with a duration.
 */
object StreamMapper {

    private val LIVE_NOW = setOf(
        StreamType.LIVE_STREAM,
        StreamType.AUDIO_LIVE_STREAM,
    )

    fun toVideo(
        item: StreamInfoItem,
        fallbackChannelId: String,
        fallbackChannelTitle: String,
    ): Video? {
        val id = videoIdFromUrl(item.url) ?: return null
        val title = item.name
        if (title.isNullOrBlank()) return null

        val publishedAt = item.uploadDate?.instant?.toEpochMilli() ?: 0L
        val duration = item.duration
        val live = item.streamType in LIVE_NOW
        val upcoming = item.contentAvailability == ContentAvailability.UPCOMING

        return Video(
            id = id,
            channelId = fallbackChannelId,
            title = title,
            channelTitle = item.uploaderName?.takeIf { it.isNotBlank() } ?: fallbackChannelTitle,
            publishedAt = publishedAt,
            durationSeconds = if (duration >= 0) duration else -1,
            isLive = live,
            isUpcoming = upcoming,
            thumbnailUrl = bestThumbnail(item),
        )
    }

    fun isCurrentlyLive(item: StreamInfoItem): Boolean = item.streamType in LIVE_NOW

    fun bestThumbnail(item: StreamInfoItem): String {
        val thumbnails = item.thumbnails
        if (thumbnails.isEmpty()) return ""
        return thumbnails.maxByOrNull { it.height }?.url ?: thumbnails.first().url
    }

    fun videoIdFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val watchPrefix = "https://www.youtube.com/watch"
        if (url.startsWith(watchPrefix) || url.contains("watch?")) {
            val query = url.substringAfter('?', "")
            for (pair in query.split('&')) {
                val key = pair.substringBefore('=', "")
                if (key == "v") {
                    val value = pair.substringAfter('=', "").substringBefore('&')
                    if (value.matches(Regex("^[A-Za-z0-9_-]{11}$"))) return value
                }
            }
        }
        val path = url.substringBefore('?').trimEnd('/')
        val lastSegment = path.substringAfterLast('/')
        return if (url.contains("/shorts/") || url.contains("/live/") || url.contains("/embed/")) {
            lastSegment.takeIf { it.matches(Regex("^[A-Za-z0-9_-]{11}$")) }
        } else {
            null
        }
    }
}
