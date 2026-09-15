package dev.omatube.app.backend

import com.google.gson.JsonParser
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
 * The service-worker bootstrap adapts NewPipeExtractor PR #1520 while v0.26.5 remains pinned;
 * the original request is retained as fallback for servers that do not provide sw.js_data.
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

        val result = if (request.httpMethod() == "GET" && request.url() == SERVICE_WORKER_URL) {
            serviceWorkerVersionResponse(request, headers)
        } else {
            executeOriginal(request, headers)
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

    private fun serviceWorkerVersionResponse(
        request: Request,
        headers: Map<String, List<String>>,
    ): HttpResponse {
        val dataResponse = try {
            transport.execute(
                method = "GET",
                url = SERVICE_WORKER_DATA_URL,
                headers = headers,
                body = null,
                maxResponseBytes = maxResponseBytes,
            )
        } catch (e: InterruptedIOException) {
            throw e
        } catch (_: IOException) {
            return executeOriginal(request, headers)
        }

        val version = if (dataResponse.code in 200..299) {
            parseServiceWorkerVersion(dataResponse.bodyText())
        } else {
            null
        }
        return if (version != null) {
            dataResponse.copy(
                body = "INNERTUBE_CONTEXT_CLIENT_VERSION\":\"$version\"".toByteArray(Charsets.UTF_8),
                latestUrl = request.url(),
            )
        } else {
            executeOriginal(request, headers)
        }
    }

    private fun executeOriginal(request: Request, headers: Map<String, List<String>>): HttpResponse = try {
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

    private fun parseServiceWorkerVersion(body: String): String? = try {
        val json = body.trimStart().removePrefix(XSSI_PREFIX).trimStart()
        val version = JsonParser.parseString(json).asJsonArray[0]
            .asJsonArray[2].asJsonArray[0].asJsonArray[0].asJsonArray[16].asString
        version.takeIf { it.isNotBlank() && VERSION_PATTERN.matches(it) }
    } catch (_: RuntimeException) {
        null
    }

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val SERVICE_WORKER_URL = "https://www.youtube.com/sw.js"
        const val SERVICE_WORKER_DATA_URL = "https://www.youtube.com/sw.js_data"
        const val XSSI_PREFIX = ")]}'"
        val VERSION_PATTERN = Regex("[0-9]+(?:\\.[0-9]+)+")
    }
}
