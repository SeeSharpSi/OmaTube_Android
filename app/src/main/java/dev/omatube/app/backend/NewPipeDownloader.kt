package dev.omatube.app.backend

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.io.InterruptedIOException

/**
 * NewPipeExtractor [Downloader] backed by [HttpTransport].
 *
 * Supports GET, POST and HEAD, forwards the extractor supplied headers verbatim, keeps OkHttp's
 * redirect handling and timeouts, bounds the response body, and surfaces HTTP 429 as
 * [ReCaptchaException] exactly as the extractor expects.
 *
 * This method is intentionally blocking: NewPipeExtractor calls it synchronously from whatever
 * thread drives extraction. The scheduling and cancellation policy lives in [NewPipeBackend],
 * which runs every extraction on a bounded IO dispatcher and rethrows [kotlinx.coroutines.CancellationException].
 */
class NewPipeDownloader(
    private val transport: HttpTransport,
    private val userAgent: String,
    private val maxResponseBytes: Long = HttpTransport.DEFAULT_MAX_RESPONSE_BYTES,
) : Downloader() {

    override fun execute(request: Request): Response {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("Request interrupted before it started")
        }
        val headers = LinkedHashMap<String, List<String>>()
        for ((name, value) in request.headers()) {
            headers[name] = value
        }
        val existingUserAgent = headers.keys.any { it.equals("User-Agent", ignoreCase = true) }
        if (!existingUserAgent) {
            headers["User-Agent"] = listOf(userAgent)
        }

        val result = try {
            transport.execute(
                method = request.httpMethod(),
                url = request.url(),
                headers = headers,
                body = request.dataToSend(),
                maxResponseBytes = maxResponseBytes,
            )
        } catch (e: HttpTooLargeException) {
            throw IOException(e.message, e)
        }

        if (result.code == HTTP_TOO_MANY_REQUESTS) {
            throw ReCaptchaException("reCaptcha challenge requested", request.url())
        }

        return Response(
            result.code,
            result.message,
            result.headers,
            result.bodyText(),
            result.latestUrl,
        )
    }

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
