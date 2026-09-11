package dev.omatube.app.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Builds the data source factories used to fetch YouTube media.
 *
 * Transport policy (verified against real YouTube `videoplayback` responses):
 *
 * - The standard HTTP `Range` header is used for every media request, including
 *   the single-track DASH manifests generated for YouTube video-only/audio
 *   streams. A normal OkHttp **GET** plus `Range` returns a correct `206 Partial
 *   Content` response.
 * - Only the `rn` (request number) query parameter is appended to
 *   `videoplayback` URLs, once per request. `rn` is needed by the Android client
 *   streams this app extracts.
 * - The `Range` header is never rewritten or removed, so Media3's byte offsets
 *   for init/index/media are preserved exactly.
 * - HLS playlists do not get `rn`, because their URLs use `/` as a parameter
 *   delimiter and cannot carry the extra query parameter safely.
 *
 * The OkHttp interceptor also adds the headers YouTube expects for web/embedded
 * streaming URLs (`Origin`, `Referer`, `Sec-Fetch-*`), `TE: trailers` and a
 * matching User-Agent. `Accept-Encoding: identity` is forced so byte ranges are
 * never transparently gzip-decoded and corrupted.
 *
 * Note: upstream NewPipe sends `videoplayback` requests as a POST with a
 * `{0x78, 0x00}` body and a `range` query parameter. That is a different client
 * profile; for our OkHttp GET transport the standard `Range` header is the
 * behavior proven to stream correctly.
 */
@OptIn(UnstableApi::class)
class PlaybackDataSources(context: Context) {
    /** Plain factory for live manifests and non-YouTube streams. */
    val generic: DataSource.Factory =
        DefaultDataSource.Factory(context, createHttpFactory(rnParameter = false))

    /** YouTube generated DASH: standard `Range` header plus `rn`. */
    val youtubeDash: DataSource.Factory =
        createHttpFactory(rnParameter = true)

    /** YouTube progressive: standard `Range` header plus `rn`. */
    val youtubeProgressive: DataSource.Factory =
        createHttpFactory(rnParameter = true)

    /** YouTube HLS: standard `Range` header, no `rn`. */
    val youtubeHls: DataSource.Factory =
        createHttpFactory(rnParameter = false)

    private fun createHttpFactory(rnParameter: Boolean): DataSource.Factory {
        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(YoutubeRequestInterceptor(rnParameter))
            .build()
        return OkHttpDataSource.Factory(client)
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS = 30L

        /** Fallback for non-YouTube hosts where a desktop UA is expected. */
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

private const val YOUTUBE_BASE_URL = "https://www.youtube.com"

/**
 * Adds the YouTube request number and request headers to media requests.
 *
 * It deliberately leaves the `Range` header untouched: Media3 sets it from the
 * `DataSpec` offsets and the server must see it verbatim.
 */
@OptIn(UnstableApi::class)
internal class YoutubeRequestInterceptor(
    private val rnParameterEnabled: Boolean,
) : Interceptor {
    private val requestNumber = AtomicLong(0L)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val originalUrl = request.url
        val isVideoPlayback = originalUrl.encodedPath.startsWith("/videoplayback")
        val url = if (isVideoPlayback &&
            rnParameterEnabled &&
            originalUrl.queryParameter("rn") == null
        ) {
            originalUrl.newBuilder()
                .addQueryParameter("rn", requestNumber.incrementAndGet().toString())
                .build()
        } else {
            originalUrl
        }

        val urlString = url.toString()
        // newBuilder preserves the original headers, including `Range`.
        val builder = request.newBuilder().url(url)
        if (YoutubeParsingHelper.isWebStreamingUrl(urlString) ||
            YoutubeParsingHelper.isWebEmbeddedPlayerStreamingUrl(urlString)
        ) {
            builder.header("Origin", YOUTUBE_BASE_URL)
            builder.header("Referer", YOUTUBE_BASE_URL)
            builder.header("Sec-Fetch-Dest", "empty")
            builder.header("Sec-Fetch-Mode", "cors")
            builder.header("Sec-Fetch-Site", "cross-site")
        }
        builder.header("Te", "trailers")
        builder.header("Accept-Encoding", "identity")
        builder.header("User-Agent", userAgentFor(urlString))
        return chain.proceed(builder.build())
    }

    private fun userAgentFor(url: String): String = when {
        YoutubeParsingHelper.isAndroidStreamingUrl(url) ->
            YoutubeParsingHelper.getAndroidUserAgent(null)
        YoutubeParsingHelper.isIosStreamingUrl(url) ->
            YoutubeParsingHelper.getIosUserAgent(null)
        YoutubeParsingHelper.isVisionOsStreamingUrl(url) ->
            YoutubeParsingHelper.getVisionOsUserAgent(null)
        else -> PlaybackDataSources.DESKTOP_USER_AGENT
    }
}
