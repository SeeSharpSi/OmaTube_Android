package dev.omatube.app.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.time.Instant

class StreamMapperTest {

    private val videoUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    @Test
    fun extractsVideoIdFromSupportedUrls() {
        assertEquals("dQw4w9WgXcQ", StreamMapper.videoIdFromUrl(videoUrl))
        assertEquals("dQw4w9WgXcQ", StreamMapper.videoIdFromUrl("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", StreamMapper.videoIdFromUrl("https://www.youtube.com/live/dQw4w9WgXcQ"))
        assertNull(StreamMapper.videoIdFromUrl(null))
        assertNull(StreamMapper.videoIdFromUrl("https://www.youtube.com/@handle"))
    }

    @Test
    fun mapsRegularVideoFields() {
        val item = item(videoUrl, StreamType.VIDEO_STREAM)
        item.duration = 125L
        item.uploadDate = DateWrapper(Instant.ofEpochSecond(1_700_000_000))
        val video = StreamMapper.toVideo(item, "UC1234567890123456789012", "Fallback")
        requireNotNull(video)
        assertEquals("dQw4w9WgXcQ", video.id)
        assertEquals(125L, video.durationSeconds)
        assertEquals(1_700_000_000_000L, video.publishedAt)
        assertEquals("Uploader", video.channelTitle)
        assertEquals("https://image.test/large.jpg", video.thumbnailUrl)
        assertFalse(video.isLive)
        assertFalse(video.isUpcoming)
    }

    @Test
    fun mapsLiveAndUpcomingSignals() {
        val live = StreamMapper.toVideo(item(videoUrl, StreamType.LIVE_STREAM), "uc", "title")
        assertTrue(live!!.isLive)
        assertTrue(StreamMapper.isCurrentlyLive(item(videoUrl, StreamType.LIVE_STREAM)))

        val upcoming = item(videoUrl, StreamType.VIDEO_STREAM)
        upcoming.contentAvailability = ContentAvailability.UPCOMING
        assertTrue(StreamMapper.toVideo(upcoming, "uc", "title")!!.isUpcoming)

        val postLive = StreamMapper.toVideo(item(videoUrl, StreamType.POST_LIVE_STREAM), "uc", "title")
        assertFalse(postLive!!.isLive)
    }

    @Test
    fun unknownDurationAndMissingDateAreSafe() {
        val item = item(videoUrl, StreamType.VIDEO_STREAM)
        val video = StreamMapper.toVideo(item, "uc", "title")!!
        assertEquals(-1L, video.durationSeconds)
        assertEquals(0L, video.publishedAt)
    }

    @Test
    fun rejectsBlankTitleAndMissingThumbnails() {
        assertNull(StreamMapper.toVideo(item(videoUrl, StreamType.VIDEO_STREAM, name = ""), "uc", "title"))
        val noThumbs = item(videoUrl, StreamType.VIDEO_STREAM)
        noThumbs.setThumbnails(emptyList())
        assertEquals("", StreamMapper.bestThumbnail(noThumbs))
    }

    private fun item(
        url: String,
        type: StreamType,
        name: String = "Title",
    ): StreamInfoItem {
        val item = StreamInfoItem(0, url, name, type)
        item.uploaderName = "Uploader"
        item.setThumbnails(
            listOf(
                Image("https://image.test/small.jpg", 120, 160, Image.ResolutionLevel.UNKNOWN),
                Image("https://image.test/large.jpg", 720, 1280, Image.ResolutionLevel.UNKNOWN),
            ),
        )
        return item
    }
}
