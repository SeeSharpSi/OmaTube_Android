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

    @Test
    fun serviceWorkerDataBootstrapsClientVersion() {
        val transport = FakeTransport()
        transport.responder = { call ->
            assertEquals("https://www.youtube.com/sw.js_data", call.url)
            transport.response(
                body = ")]}'\n[[null,null,[[[null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,\"2.20260914.01.00\"]]]]]",
                headers = mapOf("X-Test" to listOf("response")),
                latestUrl = "https://redirected.test/sw.js_data",
            )
        }
        val downloader = NewPipeDownloader(transport, "OmaTubeTest", maxResponseBytes = 1234)

        val response = downloader.execute(
            Request.newBuilder()
                .get("https://www.youtube.com/sw.js")
                .setHeader("X-Test", "request")
                .build(),
        )

        assertEquals(1, transport.calls.size)
        assertEquals("GET", transport.calls.single().method)
        assertEquals("request", transport.calls.single().headers["X-Test"]?.first())
        assertEquals("OmaTubeTest", transport.calls.single().headers["User-Agent"]?.first())
        assertEquals(null, transport.calls.single().body)
        assertEquals(1234, transport.calls.single().maxResponseBytes)
        assertEquals("INNERTUBE_CONTEXT_CLIENT_VERSION\":\"2.20260914.01.00\"", response.responseBody())
        assertEquals("response", response.responseHeaders()["X-Test"]?.first())
        assertEquals("https://www.youtube.com/sw.js", response.latestUrl())
    }

    @Test
    fun malformedServiceWorkerDataFallsBackToOriginalRequest() {
        val transport = FakeTransport()
        transport.responder = { call ->
            if (call.url.endsWith("sw.js_data")) transport.response(body = "not json")
            else transport.response(body = "original worker")
        }

        val response = NewPipeDownloader(transport, "OmaTubeTest").execute(
            Request.newBuilder().get("https://www.youtube.com/sw.js").build(),
        )

        assertEquals(2, transport.calls.size)
        assertEquals("https://www.youtube.com/sw.js_data", transport.calls[0].url)
        assertEquals("https://www.youtube.com/sw.js", transport.calls[1].url)
        assertEquals("original worker", response.responseBody())
    }
}
