package dev.omatube.app.backend

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Immutable HTTP response used across the backend so components stay unit-testable. */
data class HttpResponse(
    val code: Int,
    val message: String,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    val latestUrl: String,
) {
    fun bodyText(): String = body.toString(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpResponse) return false
        return code == other.code &&
            message == other.message &&
            headers == other.headers &&
            body.contentEquals(other.body) &&
            latestUrl == other.latestUrl
    }

    override fun hashCode(): Int {
        var result = code
        result = 31 * result + message.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + body.contentHashCode()
        result = 31 * result + latestUrl.hashCode()
        return result
    }
}

/** Blocking HTTP port. Fakes plug in for tests without touching the network. */
interface HttpTransport {
    fun execute(
        method: String,
        url: String,
        headers: Map<String, List<String>> = emptyMap(),
        body: ByteArray? = null,
        maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
    ): HttpResponse

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 2L * 1024 * 1024
    }
}

class HttpTooLargeException(message: String) : IOException(message)

/**
 * OkHttp backed transport with bounded redirects, bounded response bodies and an in-memory
 * cookie jar.
 *
 * Cancellation: [execute] blocks on a latch instead of `Call.execute()` so that a thread
 * interrupt (delivered by `kotlinx.coroutines.runInterruptible`) cancels exactly this call and
 * throws [InterruptedIOException]. It never calls `Dispatcher.cancelAll()`, so cancelling one
 * refresh request can never abort the other in-flight requests.
 */
class OkHttpTransport(
    private val client: OkHttpClient = defaultClient(),
) : HttpTransport {

    override fun execute(
        method: String,
        url: String,
        headers: Map<String, List<String>>,
        body: ByteArray?,
        maxResponseBytes: Long,
    ): HttpResponse {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("HTTP request interrupted before it started")
        }

        val contentType = headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.firstOrNull()
        val methodName = method.uppercase()
        val requestBody = when {
            body != null -> body.toRequestBody(contentType?.toMediaTypeOrNull())
            methodName in METHODS_REQUIRING_BODY ->
                ByteArray(0).toRequestBody(contentType?.toMediaTypeOrNull())
            else -> null
        }
        val builder = Request.Builder().url(url)
        for ((name, values) in headers) {
            if (name.equals("Content-Type", ignoreCase = true)) continue
            for (value in values) {
                builder.addHeader(name, value)
            }
        }
        builder.method(methodName, requestBody)

        val call = client.newCall(builder.build())
        val response = awaitInterruptibly(call)
        response.use { completed ->
            val peeked = completed.peekBody(maxResponseBytes + 1).bytes()
            if (peeked.size.toLong() > maxResponseBytes) {
                throw HttpTooLargeException("HTTP response exceeded $maxResponseBytes bytes")
            }
            return HttpResponse(
                code = completed.code,
                message = completed.message,
                headers = completed.headers.toMultimap(),
                body = peeked,
                latestUrl = completed.request.url.toString(),
            )
        }
    }

    /**
     * Runs the call asynchronously and blocks until it completes. A thread interrupt cancels the
     * specific [call] and surfaces as [InterruptedIOException].
     */
    private fun awaitInterruptibly(call: Call): Response {
        val latch = CountDownLatch(1)
        var completed: Response? = null
        var failure: IOException? = null
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                failure = e
                latch.countDown()
            }

            override fun onResponse(call: Call, response: Response) {
                completed = response
                latch.countDown()
            }
        })
        try {
            latch.await()
        } catch (e: InterruptedException) {
            call.cancel()
            throw InterruptedIOException("HTTP request interrupted").apply { initCause(e) }
        }
        failure?.let { throw it }
        return completed ?: throw IOException("HTTP call produced no response")
    }

    companion object {
        private val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .cookieJar(BoundedCookieJar())
            .build()
    }
}
