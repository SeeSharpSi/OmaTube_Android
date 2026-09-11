package dev.omatube.app.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SponsorBlockClientTest {

    @Test
    fun notFoundIsAnEmptySuccess() {
        val transport = FakeTransport()
        transport.responder = { transport.response(code = 404) }
        val segments = SponsorBlockClient(transport).segments("dQw4w9WgXcQ", setOf("sponsor"))
        assertTrue(segments.isEmpty())
    }

    @Test
    fun buildsExpectedUrlAndCapsBody() {
        val transport = FakeTransport()
        transport.responder = { transport.response(code = 404) }
        SponsorBlockClient(transport).segments("dQw4w9WgXcQ", setOf("sponsor", "not-real"))
        val call = transport.lastCall()
        assertTrue(call.url.startsWith("https://sponsor.ajay.app/api/skipSegments?"))
        assertTrue(call.url.contains("videoID=dQw4w9WgXcQ"))
        assertTrue(call.url.contains("category=sponsor"))
        assertFalse(call.url.contains("not-real"))
        assertTrue(call.url.contains("actionType=skip"))
        assertEquals(SponsorBlockClient.MAX_RESPONSE_BYTES, call.maxResponseBytes)
    }

    @Test
    fun invalidVideoIdAndNoSupportedCategoriesMakeNoRequest() {
        val transport = FakeTransport()
        val client = SponsorBlockClient(transport)
        assertTrue(client.segments("short", setOf("sponsor")).isEmpty())
        assertTrue(client.segments("dQw4w9WgXcQ", setOf("not-real")).isEmpty())
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun serverErrorsThrow() {
        val transport = FakeTransport()
        transport.responder = { transport.response(code = 503) }
        try {
            SponsorBlockClient(transport).segments("dQw4w9WgXcQ", setOf("sponsor"))
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("503"))
        }
    }

    @Test
    fun parsesAndValidatesSegments() {
        val body = """
            [
              {"category":"sponsor","segment":[10.0,20.0],"UUID":"a"},
              {"category":"intro","segment":[0,5]},
              {"category":"music_offtopic","segment":[30,40]},
              {"category":"not-real","segment":[1,2]},
              {"category":"sponsor","segment":[5,5]},
              {"category":"sponsor","segment":[-1,3]},
              {"category":"sponsor","segment":[1,90000]},
              {"category":"sponsor","segment":["x",3]},
              {"category":"sponsor","segment":[1,2,3]}
            ]
        """.trimIndent()
        val segments = SponsorBlockClient.parse(body)
        assertEquals(3, segments.size)
        assertEquals("intro", segments[0].category)
        assertEquals(0.0, segments[0].startSeconds, 0.0)
        assertEquals("sponsor", segments[1].category)
        assertEquals(10.0, segments[1].startSeconds, 0.0)
        assertEquals("music_offtopic", segments[2].category)
    }

    @Test
    fun rejectsInvalidJson() {
        try {
            SponsorBlockClient.parse("not json")
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("not valid JSON"))
        }
    }

    @Test
    fun rangeValidationRejectsNonFiniteAndOutOfBounds() {
        assertTrue(SponsorBlockClient.isValidRange(0.0, 1.0))
        assertFalse(SponsorBlockClient.isValidRange(Double.NaN, 1.0))
        assertFalse(SponsorBlockClient.isValidRange(1.0, Double.POSITIVE_INFINITY))
        assertFalse(SponsorBlockClient.isValidRange(-0.1, 1.0))
        assertFalse(SponsorBlockClient.isValidRange(5.0, 5.0))
        assertFalse(SponsorBlockClient.isValidRange(1.0, 86400.0))
    }
}
