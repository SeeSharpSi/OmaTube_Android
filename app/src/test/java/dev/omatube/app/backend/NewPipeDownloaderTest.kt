package dev.omatube.app.backend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

class NewPipeDownloaderTest {

    @Test
    fun getForwardsHeadersAndReturnsBody() {
        val transport = FakeTransport()
        transport.responder = { transport.response(code = 200, body = "payload") }
        val downloader = NewPipeDownloader(transport, "OmaTubeTest")

        val response = downloader.execute(
            Request.newBuilder()
                .get("https://example.test/resource")
                .setHeader("X-Test", "value")
                .build(),
        )

        assertEquals(200, response.responseCode())
        assertEquals("payload", response.responseBody())
        assertEquals("GET", transport.lastCall().method)
        assertEquals("value", transport.lastCall().headers["X-Test"]?.first())
        assertEquals("OmaTubeTest", transport.lastCall().headers["User-Agent"]?.first())
    }

    @Test
    fun preservesCallerUserAgent() {
        val transport = FakeTransport()
        transport.responder = { transport.response(body = "") }
        NewPipeDownloader(transport, "OmaTubeTest").execute(
            Request.newBuilder()
                .get("https://example.test/")
                .setHeader("User-Agent", "custom-agent")
                .build(),
        )
        assertEquals(listOf("custom-agent"), transport.lastCall().headers["User-Agent"])
        assertEquals(1, transport.lastCall().headers.keys.count { it.equals("User-Agent", true) })
    }

    @Test
    fun postSendsBody() {
        val transport = FakeTransport()
        transport.responder = { transport.response(body = "{}") }
        NewPipeDownloader(transport, "OmaTubeTest").execute(
            Request.newBuilder()
                .post("https://example.test/browse", "{\"a\":1}".toByteArray())
                .build(),
        )
        assertEquals("POST", transport.lastCall().method)
        assertArrayEquals("{\"a\":1}".toByteArray(), transport.lastCall().body)
    }

    @Test
    fun headIsSupported() {
        val transport = FakeTransport()
        transport.responder = { transport.response(code = 200) }
        val response = NewPipeDownloader(transport, "OmaTubeTest").execute(
            Request.newBuilder().head("https://example.test/head").build(),
        )
        assertEquals("HEAD", transport.lastCall().method)
        assertEquals(200, response.responseCode())
    }

    @Test
    fun returnsLatestRedirectUrl() {
        val transport = FakeTransport()
        transport.responder = {
            transport.response(code = 200, latestUrl = "https://example.test/final")
        }
        val response = NewPipeDownloader(transport, "OmaTubeTest").execute(
            Request.newBuilder().get("https://example.test/start").build(),
        )
        assertEquals("https://example.test/final", response.latestUrl())
    }

    @Test
    fun mapsTooManyRequestsToReCaptcha() {
        val transport = FakeTransport()
        transport.responder = { transport.response(code = 429) }
        try {
            NewPipeDownloader(transport, "OmaTubeTest").execute(
                Request.newBuilder().get("https://example.test/limited").build(),
            )
            fail("Expected ReCaptchaException")
        } catch (e: ReCaptchaException) {
            assertEquals("https://example.test/limited", e.url)
        }
    }

    @Test
    fun boundsResponseBody() {
        val transport = FakeTransport()
        transport.responder = { throw HttpTooLargeException("too large") }
        try {
            NewPipeDownloader(transport, "OmaTubeTest", maxResponseBytes = 8).execute(
                Request.newBuilder().get("https://example.test/huge").build(),
            )
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("too large"))
        }
        assertEquals(8, transport.lastCall().maxResponseBytes)
    }
}
