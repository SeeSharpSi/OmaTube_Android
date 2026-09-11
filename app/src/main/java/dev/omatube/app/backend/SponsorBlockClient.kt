package dev.omatube.app.backend

import dev.omatube.app.model.SponsorSegment
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * SponsorBlock `skipSegments` lookups.
 *
 * Mirrors the desktop validation in `src/sponsorblockclient.cpp`: only supported categories are
 * requested and accepted, every segment must be finite with `0 <= start < end < 86400`, a 404 is
 * a successful empty result (not an error), and the response body is capped.
 */
class SponsorBlockClient(
    private val transport: HttpTransport,
    private val userAgent: String = "OmaTube/0.1",
) {

    fun segments(videoId: String, categories: Collection<String>): List<SponsorSegment> {
        if (!VIDEO_ID.matches(videoId)) return emptyList()
        val requested = categories.filter { it in SUPPORTED_CATEGORIES }.distinct()
        if (requested.isEmpty()) return emptyList()

        val url = buildUrl(videoId, requested)
        val response = transport.execute(
            method = "GET",
            url = url,
            headers = mapOf(
                "User-Agent" to listOf(userAgent),
                "Accept" to listOf("application/json"),
            ),
            maxResponseBytes = MAX_RESPONSE_BYTES,
        )
        if (response.code == 404) return emptyList()
        if (response.code !in 200..299) {
            throw IOException("SponsorBlock request failed with HTTP ${response.code}.")
        }
        return parse(response.bodyText())
    }

    private fun buildUrl(videoId: String, categories: List<String>): String {
        val query = buildString {
            append("videoID=").append(encode(videoId))
            for (category in categories) {
                append("&category=").append(encode(category))
            }
            append("&actionType=skip")
        }
        return "$ENDPOINT?$query"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    companion object {
        const val ENDPOINT = "https://sponsor.ajay.app/api/skipSegments"
        const val MAX_RESPONSE_BYTES: Long = 256L * 1024

        val SUPPORTED_CATEGORIES: Set<String> = setOf(
            "sponsor",
            "selfpromo",
            "interaction",
            "intro",
            "outro",
            "preview",
            "music_offtopic",
            "poi_highlight",
        )

        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

        /** Parses and validates a SponsorBlock `skipSegments` response body. */
        fun parse(body: String): List<SponsorSegment> {
            val root = try {
                JSONArray(body)
            } catch (e: JSONException) {
                throw IOException("SponsorBlock response is not valid JSON.", e)
            }
            val segments = ArrayList<SponsorSegment>()
            for (index in 0 until root.length()) {
                val item = root.optJSONObject(index) ?: continue
                val category = item.optString("category")
                if (category !in SUPPORTED_CATEGORIES) continue
                val range = item.optJSONArray("segment") ?: continue
                if (range.length() != 2) continue
                val start = range.optDouble(0, Double.NaN)
                val end = range.optDouble(1, Double.NaN)
                if (!isValidRange(start, end)) continue
                segments.add(SponsorSegment(startSeconds = start, endSeconds = end, category = category))
            }
            segments.sortBy { it.startSeconds }
            return segments
        }

        fun isValidRange(start: Double, end: Double): Boolean {
            if (!start.isFinite() || !end.isFinite()) return false
            return start >= 0.0 && end > start && end < MAX_SECONDS
        }

        private const val MAX_SECONDS = 86400.0
    }
}
