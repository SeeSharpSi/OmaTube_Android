package dev.omatube.app.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChannelInputTest {

    private val channelId = "UC1234567890123456789012"

    @Test
    fun parsesCanonicalChannelId() {
        assertEquals(ChannelInput.Reference(ChannelInput.Kind.CHANNEL_ID, channelId), ChannelInput.parse(channelId))
    }

    @Test
    fun parsesHandleWithAndWithoutAt() {
        assertEquals(ChannelInput.Reference(ChannelInput.Kind.HANDLE, "@Foo"), ChannelInput.parse("@Foo"))
        assertEquals(ChannelInput.Reference(ChannelInput.Kind.HANDLE, "@Foo"), ChannelInput.parse("Foo"))
    }

    @Test
    fun parsesChannelUrl() {
        assertEquals(ChannelInput.Reference(ChannelInput.Kind.CHANNEL_ID, channelId), ChannelInput.parse("https://www.youtube.com/channel/$channelId"))
        assertEquals(ChannelInput.Reference(ChannelInput.Kind.HANDLE, "@Foo"), ChannelInput.parse("https://www.youtube.com/@Foo/videos"))
        assertEquals(ChannelInput.Reference(ChannelInput.Kind.HANDLE, "@Foo"), ChannelInput.parse("https://m.youtube.com/@Foo"))
    }

    @Test
    fun parsesChannelUrlTabSuffixesWithCanonicalId() {
        for (tab in listOf("videos", "streams", "shorts", "live", "featured", "playlists")) {
            assertEquals(
                "tab=$tab",
                ChannelInput.Reference(ChannelInput.Kind.CHANNEL_ID, channelId),
                ChannelInput.parse("https://www.youtube.com/channel/$channelId/$tab"),
            )
        }
        assertEquals(
            ChannelInput.Reference(ChannelInput.Kind.CHANNEL_ID, channelId),
            ChannelInput.parse("https://www.youtube.com/channel/$channelId/VIDEOS"),
        )
    }

    @Test
    fun rejectsChannelUrlWithUnknownTabSuffix() {
        assertEquals(
            ChannelInput.INVALID_PAGE_MESSAGE,
            assertFails("https://www.youtube.com/channel/$channelId/not-a-tab"),
        )
    }

    @Test
    fun rejectsForeignDomains() {
        val error = assertFails("https://vimeo.com/@Foo")
        assertEquals(ChannelInput.INVALID_INPUT_MESSAGE, error)
        assertEquals(ChannelInput.INVALID_INPUT_MESSAGE, assertFails("https://youtu.be/$channelId"))
    }

    @Test
    fun rejectsNonChannelPages() {
        assertEquals(ChannelInput.INVALID_PAGE_MESSAGE, assertFails("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(ChannelInput.INVALID_PAGE_MESSAGE, assertFails("https://www.youtube.com/playlist?list=PL123"))
    }

    @Test
    fun rejectsMalformedHandle() {
        assertEquals(ChannelInput.INVALID_HANDLE_MESSAGE, assertFails("Qt"))
        assertEquals(ChannelInput.INVALID_HANDLE_MESSAGE, assertFails("this-handle-is-way-too-long-to-fit"))
        assertEquals(ChannelInput.INVALID_INPUT_MESSAGE, assertFails("   "))
    }

    @Test
    fun derivesPlaylistAndFeedIds() {
        assertEquals("UU1234567890123456789012", ChannelInput.uploadsPlaylistId(channelId))
        assertEquals("UULF1234567890123456789012", ChannelInput.longFormPlaylistId(channelId))
        assertEquals(
            "https://www.youtube.com/feeds/videos.xml?playlist_id=UULF1234567890123456789012",
            ChannelInput.atomFeedUrl(channelId),
        )
        assertEquals("", ChannelInput.atomFeedUrl("not-a-channel"))
    }

    @Test
    fun validatesChannelIdShape() {
        assertTrue(ChannelInput.isChannelId(channelId))
        assertFalse(ChannelInput.isChannelId("UU1234567890123456789012"))
    }

    private fun assertFails(input: String): String {
        return try {
            ChannelInput.parse(input)
            fail("Expected IllegalArgumentException for $input")
            ""
        } catch (e: IllegalArgumentException) {
            e.message.orEmpty()
        }
    }
}
