package dev.omatube.app.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * Transport regression for the real, configured [PlaybackDataSources.youtubeDash]
 * factory (not a hand-built interceptor instance).
 *
 * It drives the factory through Media3's `DataSource` with the same `DataSpec`
 * offsets Media3 uses for a generated single-track DASH manifest (init, index,
 * media), against a MockWebServer that answers `206 Partial Content`. The
 * assertions prove:
 *
 * - Media3's `Range` header reaches the server verbatim;
 * - the response body is exactly the requested slice (no header stripping, no
 *   double skip, no `range` query rewrite);
 * - `rn` is appended once per request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
@OptIn(UnstableApi::class)
class YoutubeDashDataSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var dataSources: PlaybackDataSources

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dataSources = PlaybackDataSources(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun initRangeUsesStandardRangeHeaderAndReadsExactBytes() {
        val body = ByteArray(740) { index -> (index and 0xFF).toByte() }
        enqueuePartial(start = 0L, body = body)

        val (remaining, data) = readAll(dataSpec(position = 0L, length = 740L))

        val recorded = server.takeRequest()
        assertThat(remaining).isEqualTo(740L)
        assertThat(data).isEqualTo(body)
        assertThat(recorded.getHeader("Range")).isEqualTo("bytes=0-739")
        assertThat(recorded.requestUrl!!.queryParameter("range")).isNull()
        assertThat(recorded.requestUrl!!.queryParameter("rn")).isEqualTo("1")
    }

    @Test
    fun indexRangeUsesStandardRangeHeaderAndReadsExactBytes() {
        val body = ByteArray(488) { index -> ((index + 7) and 0xFF).toByte() }
        enqueuePartial(start = 740L, body = body)

        val (remaining, data) = readAll(dataSpec(position = 740L, length = 488L))

        val recorded = server.takeRequest()
        assertThat(remaining).isEqualTo(488L)
        assertThat(data).isEqualTo(body)
        assertThat(recorded.getHeader("Range")).isEqualTo("bytes=740-1227")
        assertThat(recorded.requestUrl!!.queryParameter("range")).isNull()
    }

    @Test
    fun openEndedMediaRangeUsesStandardRangeHeaderAndReadsExactBytes() {
        val body = ByteArray(2048) { index -> ((index * 3) and 0xFF).toByte() }
        enqueuePartial(start = 1228L, body = body)

        val (_, data) = readAll(dataSpec(position = 1228L, length = C.LENGTH_UNSET.toLong()))

        val recorded = server.takeRequest()
        assertThat(data).isEqualTo(body)
        assertThat(recorded.getHeader("Range")).isEqualTo("bytes=1228-")
        assertThat(recorded.requestUrl!!.queryParameter("range")).isNull()
    }

    @Test
    fun requestNumberIncrementsAcrossRanges() {
        enqueuePartial(start = 0L, body = ByteArray(10) { 1 })
        readAll(dataSpec(position = 0L, length = 10L))
        enqueuePartial(start = 10L, body = ByteArray(10) { 2 })
        readAll(dataSpec(position = 10L, length = 10L))

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertThat(first.getHeader("Range")).isEqualTo("bytes=0-9")
        assertThat(second.getHeader("Range")).isEqualTo("bytes=10-19")
        assertThat(first.requestUrl!!.queryParameter("rn")).isEqualTo("1")
        assertThat(second.requestUrl!!.queryParameter("rn")).isEqualTo("2")
    }

    private fun dataSpec(position: Long, length: Long): DataSpec =
        DataSpec.Builder()
            .setUri(Uri.parse(server.url("/videoplayback?itag=136&c=ANDROID").toString()))
            .setPosition(position)
            .setLength(length)
            .build()

    private fun enqueuePartial(start: Long, body: ByteArray) {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader(
                    "Content-Range",
                    "bytes $start-${start + body.size - 1}/$TOTAL_BYTES",
                )
                .setHeader("Content-Type", "video/mp4")
                .setBody(Buffer().write(body)),
        )
    }

    private fun readAll(dataSpec: DataSpec): Pair<Long, ByteArray> {
        val source: DataSource = dataSources.youtubeDash.createDataSource()
        try {
            val remaining = source.open(dataSpec)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = source.read(buffer, 0, buffer.size)
                if (read == -1) break
                output.write(buffer, 0, read)
            }
            return remaining to output.toByteArray()
        } finally {
            source.close()
        }
    }

    private companion object {
        const val TOTAL_BYTES = 26_455_880L
    }
}
