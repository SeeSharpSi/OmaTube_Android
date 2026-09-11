package dev.omatube.app.backend

/** Records calls and lets each test decide the response, with no network access. */
class FakeTransport : HttpTransport {
    data class Call(
        val method: String,
        val url: String,
        val headers: Map<String, List<String>>,
        val body: ByteArray?,
        val maxResponseBytes: Long,
    )

    val calls = mutableListOf<Call>()

    var responder: (Call) -> HttpResponse = { throw AssertionError("No response configured") }

    override fun execute(
        method: String,
        url: String,
        headers: Map<String, List<String>>,
        body: ByteArray?,
        maxResponseBytes: Long,
    ): HttpResponse {
        val call = Call(method, url, headers, body, maxResponseBytes)
        calls.add(call)
        return responder(call)
    }

    fun lastCall(): Call = calls.last()

    fun response(
        code: Int = 200,
        body: String = "",
        headers: Map<String, List<String>> = emptyMap(),
        latestUrl: String = "",
    ): HttpResponse = HttpResponse(
        code = code,
        message = "OK",
        headers = headers,
        body = body.toByteArray(Charsets.UTF_8),
        latestUrl = latestUrl,
    )
}
