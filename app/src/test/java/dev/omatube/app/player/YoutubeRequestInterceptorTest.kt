package dev.omatube.app.player

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * Exercises the real [YoutubeRequestInterceptor] through an OkHttp chain against
 * MockWebServer, so the request the server sees is the authoritative assertion.
 *
 * Transport contract: the `Range` header is forwarded verbatim for every media
 * request; only `rn` is added to `videoplayback` URLs.
 */
class YoutubeRequestInterceptorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(rnParameter: Boolean = true): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(YoutubeRequestInterceptor(rnParameter))
            .build()

    private fun execute(
        client: OkHttpClient,
        path: String,
        rangeHeader: String? = null,
    ): RecordedRequest {
        val builder = Request.Builder().url(server.url(path))
        if (rangeHeader != null) builder.header("Range", rangeHeader)
        server.enqueue(MockResponse().setBody("ok"))
        client.newCall(builder.build()).execute().use { it.body?.string() }
        return server.takeRequest()
    }

    @Test
    fun rangeHeaderIsForwardedVerbatim() {
        val recorded = execute(client(), "/videoplayback?itag=22", "bytes=0-9")

        assertThat(recorded.getHeader("Range")).isEqualTo("bytes=0-9")
        assertThat(recorded.requestUrl!!.queryParameter("range")).isNull()
        assertThat(recorded.requestUrl!!.queryParameter("rn")).isEqualTo("1")
    }

    @Test
    fun openEndedRangeHeaderIsForwardedVerbatim() {
        val recorded = execute(client(), "/videoplayback?itag=22", "bytes=100-")

        assertThat(recorded.getHeader("Range")).isEqualTo("bytes=100-")
        assertThat(recorded.requestUrl!!.queryParameter("range")).isNull()
    }

    @Test
    fun suffixRangeHeaderIsForwardedVerbatim() {
        val recorded = execute(client(), "/videoplayback?itag=22", "bytes=-500")

        assertThat(recorded.getHeader("Range")).isEqualTo("bytes=-500")
        assertThat(recorded.requestUrl!!.queryParameter("range")).isNull()
        assertThat(recorded.requestUrl!!.queryParameter("rn")).isEqualTo("1")
    }

    @Test
    fun existingRangeQueryIsNotDuplicatedAndHeaderStillForwarded() {
        val recorded = execute(client(), "/videoplayback?itag=22&range=5-9", "bytes=0-9")

        // The pre-existing query parameter is untouched and no second `range`
        // is added; the Range header is still forwarded for Media3 semantics.
        assertThat(recorded.requestUrl!!.queryParameterValues("range")).containsExactly("5-9")
        assertThat(recorded.getHeader("Range")).isEqualTo("bytes=0-9")
    }

    @Test
    fun requestNumberIncrementsPerRequest() {
        val client = client()
        val first = execute(client, "/videoplayback?itag=22", "bytes=0-9")
        val second = execute(client, "/videoplayback?itag=22", "bytes=10-19")

        assertThat(first.requestUrl!!.queryParameter("rn")).isEqualTo("1")
        assertThat(second.requestUrl!!.queryParameter("rn")).isEqualTo("2")
    }

    @Test
    fun requestNumberIsNotAddedWhenDisabled() {
        val recorded = execute(client(rnParameter = false), "/videoplayback?itag=22", "bytes=0-9")

        assertThat(recorded.requestUrl!!.queryParameter("rn")).isNull()
        assertThat(recorded.getHeader("Range")).isEqualTo("bytes=0-9")
    }

    @Test
    fun webStreamingUrlGetsOriginRefererAndDesktopUserAgent() {
        val recorded = execute(client(), "/videoplayback?itag=22&c=WEB")

        assertThat(recorded.getHeader("Origin")).isEqualTo("https://www.youtube.com")
        assertThat(recorded.getHeader("Referer")).isEqualTo("https://www.youtube.com")
        assertThat(recorded.getHeader("Sec-Fetch-Site")).isEqualTo("cross-site")
        assertThat(recorded.getHeader("User-Agent")).isEqualTo(PlaybackDataSources.DESKTOP_USER_AGENT)
        assertThat(recorded.getHeader("Accept-Encoding")).isEqualTo("identity")
        assertThat(recorded.getHeader("Te")).isEqualTo("trailers")
    }

    @Test
    fun nonYoutubeUrlGetsNoOriginButDesktopUserAgent() {
        val recorded = execute(client(), "/videoplayback?itag=22")

        assertThat(recorded.getHeader("Origin")).isNull()
        assertThat(recorded.getHeader("Referer")).isNull()
        assertThat(recorded.getHeader("User-Agent")).isEqualTo(PlaybackDataSources.DESKTOP_USER_AGENT)
    }

    @Test
    fun androidStreamingUrlGetsAndroidUserAgent() {
        val recorded = execute(client(), "/videoplayback?itag=22&c=ANDROID")

        assertThat(recorded.getHeader("User-Agent"))
            .isEqualTo(YoutubeParsingHelper.getAndroidUserAgent(null))
        assertThat(recorded.getHeader("Origin")).isNull()
    }
}
