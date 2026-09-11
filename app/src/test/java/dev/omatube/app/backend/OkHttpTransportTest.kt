package dev.omatube.app.backend

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the real OkHttp bridge against a local MockWebServer (no external network). */
class OkHttpTransportTest {

    private lateinit var server: MockWebServer
    private lateinit var transport: OkHttpTransport

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        transport = OkHttpTransport()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun performsGet() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("hello"))
        val response = transport.execute("GET", server.url("/resource").toString())
        assertEquals(200, response.code)
        assertEquals("hello", response.bodyText())
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/resource", recorded.path)
    }

    @Test
    fun performsPostWithContentType() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        transport.execute(
            method = "POST",
            url = server.url("/browse").toString(),
            headers = mapOf("Content-Type" to listOf("application/json")),
            body = "{\"a\":1}".toByteArray(),
        )
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("{\"a\":1}", recorded.body.readUtf8())
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test
    fun performsHead() {
        server.enqueue(MockResponse().setResponseCode(204))
        val response = transport.execute("HEAD", server.url("/head").toString())
        assertEquals(204, response.code)
        assertEquals("HEAD", server.takeRequest().method)
    }

    @Test
    fun followsRedirectsAndReportsLatestUrl() {
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", "/final"),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("done"))
        val response = transport.execute("GET", server.url("/start").toString())
        assertEquals(200, response.code)
        assertEquals("done", response.bodyText())
        assertTrue(response.latestUrl.endsWith("/final"))
        server.takeRequest()
        assertEquals("/final", server.takeRequest().path)
    }

    @Test
    fun rejectsOversizedBodies() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(100)))
        try {
            transport.execute("GET", server.url("/big").toString(), maxResponseBytes = 10)
            fail("Expected HttpTooLargeException")
        } catch (e: HttpTooLargeException) {
            assertTrue(e.message!!.contains("exceeded"))
        }
    }

    @Test
    fun carriesSetCookieAcrossRedirect() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/final")
                .addHeader("Set-Cookie", "session=abc; Path=/"),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("done"))

        val response = transport.execute("GET", server.url("/start").toString())

        assertEquals(200, response.code)
        server.takeRequest()
        val redirected = server.takeRequest()
        assertTrue(
            "redirect did not carry the cookie: ${redirected.getHeader("Cookie")}",
            redirected.getHeader("Cookie")?.contains("session=abc") == true,
        )
    }

    @Test
    fun interruptCancelsBlockingCall() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("slow").setBodyDelay(1, TimeUnit.SECONDS),
        )
        val caught = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val worker = Thread {
            try {
                transport.execute("GET", server.url("/slow").toString())
            } catch (t: Throwable) {
                caught.set(t)
            } finally {
                done.countDown()
            }
        }
        worker.start()
        // Wait until the server has received the request, i.e. the call is really in flight.
        server.takeRequest()
        worker.interrupt()
        assertTrue("execute did not finish after interrupt", done.await(5, TimeUnit.SECONDS))
        worker.join(1_000)
        assertNotNull("expected an InterruptedIOException", caught.get())
        assertTrue(
            "expected InterruptedIOException but was ${caught.get()}",
            caught.get() is InterruptedIOException,
        )
    }

    @Test
    fun preInterruptedThreadFailsFast() {
        try {
            Thread.currentThread().interrupt()
            try {
                transport.execute("GET", server.url("/never").toString())
                fail("Expected InterruptedIOException")
            } catch (e: InterruptedIOException) {
                assertTrue(e.message!!.contains("interrupted"))
            }
        } finally {
            Thread.interrupted()
        }
    }
}
