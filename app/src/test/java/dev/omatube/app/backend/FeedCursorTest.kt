package dev.omatube.app.backend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.Page

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class FeedCursorTest {

    @Test
    fun roundTripsNewPipePage() {
        val page = Page(
            "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
            null,
            listOf("UC1234567890123456789012", "true"),
            null,
            byteArrayOf(1, 2, 3, 4),
        )
        val decoded = FeedCursorCodec.decode(FeedCursorCodec.encodePage(page))
        val newPipe = decoded as FeedCursor.NewPipe
        assertEquals(page.url, newPipe.page?.url)
        assertEquals(page.ids, newPipe.page?.ids)
        assertArrayEquals(page.body, newPipe.page?.body)
    }

    @Test
    fun nullPageEncodesStartMarker() {
        val decoded = FeedCursorCodec.decode(FeedCursorCodec.startNewPipe()) as FeedCursor.NewPipe
        assertNull(decoded.page)
    }

    @Test
    fun roundTripsDataApiToken() {
        val decoded = FeedCursorCodec.decode(FeedCursorCodec.encodeDataApi("CAEQAA")) as FeedCursor.DataApi
        assertEquals("CAEQAA", decoded.pageToken)
    }

    @Test
    fun malformedCursorDecodesToNull() {
        assertNull(FeedCursorCodec.decode(null))
        assertNull(FeedCursorCodec.decode(""))
        assertNull(FeedCursorCodec.decode("not json"))
        assertNull(FeedCursorCodec.decode("{}"))
        assertNull(FeedCursorCodec.decode("""{"v":99,"source":"newpipe"}"""))
        assertNull(FeedCursorCodec.decode("""{"v":1,"source":"other"}"""))
    }

    @Test
    fun idsOnlyCursorHasNoUrl() {
        val cursor = FeedCursorCodec.encodePage(Page(listOf("a", "b")))
        val decoded = FeedCursorCodec.decode(cursor) as FeedCursor.NewPipe
        assertNull(decoded.page?.url)
        assertEquals(listOf("a", "b"), decoded.page?.ids)
    }
}
