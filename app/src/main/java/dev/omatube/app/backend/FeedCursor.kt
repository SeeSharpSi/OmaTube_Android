package dev.omatube.app.backend

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.schabi.newpipe.extractor.Page
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Opaque, process-restart-safe page cursors.
 *
 * The NewPipe [Page] returned by a channel tab is serialised explicitly (url, ids and the
 * raw POST body). Decoding is lossless and does not depend on any in-memory extractor state,
 * so a cursor written before a process restart still resumes at the correct continuation.
 *
 * Decoding never throws for malformed input: callers receive `null` and safely re-extract the
 * first page. That is what prevents a corrupted or foreign cursor from causing an endless
 * pagination loop.
 */
sealed class FeedCursor {
    /** A NewPipe continuation page. `page == null` means "start from the first page". */
    data class NewPipe(val page: Page?) : FeedCursor()

    /** A YouTube Data API `nextPageToken`. */
    data class DataApi(val pageToken: String?) : FeedCursor()
}

object FeedCursorCodec {

    private const val VERSION = 1
    private const val SOURCE = "source"
    private const val SOURCE_NEWPIPE = "newpipe"
    private const val SOURCE_DATA_API = "dataapi"

    /** Cursor placed after the Atom fast path so the next call starts NewPipe page 1. */
    fun startNewPipe(): String = encodePage(null)

    fun encodePage(page: Page?): String {
        val json = JSONObject()
        json.put("v", VERSION)
        json.put(SOURCE, SOURCE_NEWPIPE)
        json.put("url", page?.url)
        json.put("ids", page?.ids?.let { JSONArray(it) })
        json.put("body", page?.body?.let { Base64.getEncoder().encodeToString(it) })
        return json.toString()
    }

    fun encodeDataApi(pageToken: String?): String {
        val json = JSONObject()
        json.put("v", VERSION)
        json.put(SOURCE, SOURCE_DATA_API)
        json.put("token", pageToken)
        return json.toString()
    }

    fun decode(raw: String?): FeedCursor? {
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
            if (json.optInt("v", -1) != VERSION) return null
            when (json.optString(SOURCE)) {
                SOURCE_NEWPIPE -> decodePage(json)
                SOURCE_DATA_API -> FeedCursor.DataApi(json.optString("token").ifEmpty { null })
                else -> null
            }
        } catch (e: JSONException) {
            null
        }
    }

    private fun decodePage(json: JSONObject): FeedCursor {
        val url = json.optString("url").ifEmpty { null }
        val idsJson = json.optJSONArray("ids")
        val ids = idsJson?.let { array ->
            List(array.length()) { index -> array.optString(index) }
        }
        val bodyJson = json.optString("body")
        val body = if (bodyJson.isEmpty()) {
            null
        } else {
            try {
                Base64.getDecoder().decode(bodyJson.toByteArray(StandardCharsets.US_ASCII))
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        if (url == null && ids == null) {
            return FeedCursor.NewPipe(null)
        }
        return FeedCursor.NewPipe(Page(url, null, ids, null, body))
    }
}
