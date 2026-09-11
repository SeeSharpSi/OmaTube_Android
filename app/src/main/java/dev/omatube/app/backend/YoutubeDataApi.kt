package dev.omatube.app.backend

import dev.omatube.app.model.Channel
import dev.omatube.app.model.Video
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Optional YouTube Data API v3 metadata path.
 *
 * It is only consulted when the user configured an API key. Stream extraction is never delegated
 * here: [NewPipeBackend.resolveStream] always goes through NewPipeExtractor.
 */
class YoutubeDataApi(
    private val transport: HttpTransport,
    private val apiKeyProvider: () -> String,
    private val userAgent: String = "OmaTube/0.1",
) {
    data class UploadPage(
        val videoIds: List<String>,
        val videos: List<Video>,
        val nextPageToken: String?,
    )

    fun hasKey(): Boolean = apiKeyProvider().trim().isNotEmpty()

    fun resolveChannel(reference: ChannelInput.Reference, originalInput: String): Channel? {
        val params = mutableListOf(
            "part" to "id,snippet,contentDetails",
            "fields" to "items(id,snippet(title,customUrl,thumbnails),contentDetails/relatedPlaylists/uploads)",
        )
        when (reference.kind) {
            ChannelInput.Kind.CHANNEL_ID -> params.add("id" to reference.value)
            ChannelInput.Kind.HANDLE -> params.add("forHandle" to reference.value)
        }
        val root = get("channels", params) ?: return null
        val item = root.optJSONArray("items")?.optJSONObject(0) ?: return null
        val snippet = item.optJSONObject("snippet") ?: JSONObject()
        val id = item.optString("id")
        val title = snippet.optString("title")
        val uploadsPlaylistId = item.optJSONObject("contentDetails")
            ?.optJSONObject("relatedPlaylists")
            ?.optString("uploads")
            .orEmpty()
        if (id.isEmpty() || title.isEmpty() || uploadsPlaylistId.isEmpty()) {
            throw IOException("YouTube returned incomplete channel metadata.")
        }
        val customUrl = snippet.optString("customUrl")
        return Channel(
            id = id,
            title = title,
            originalInput = originalInput.trim(),
            handle = if (customUrl.startsWith("@")) customUrl else "",
            avatarUrl = thumbnailUrl(snippet),
            uploadsPlaylistId = uploadsPlaylistId,
            metadataFetchedAt = System.currentTimeMillis(),
        )
    }

    fun uploadPage(playlistId: String, pageToken: String?): UploadPage {
        val params = mutableListOf(
            "part" to "contentDetails",
            "playlistId" to playlistId,
            "maxResults" to "50",
            "fields" to "nextPageToken,items/contentDetails/videoId",
        )
        if (!pageToken.isNullOrEmpty()) params.add("pageToken" to pageToken)
        val root = get("playlistItems", params) ?: return UploadPage(emptyList(), emptyList(), null)
        val ids = ArrayList<String>()
        root.optJSONArray("items")?.let { items ->
            for (index in 0 until items.length()) {
                val id = items.optJSONObject(index)
                    ?.optJSONObject("contentDetails")
                    ?.optString("videoId")
                if (!id.isNullOrEmpty()) ids.add(id)
            }
        }
        val videos = if (ids.isEmpty()) emptyList() else videos(ids)
        return UploadPage(ids, videos, root.optString("nextPageToken").ifEmpty { null })
    }

    fun videos(videoIds: List<String>): List<Video> {
        if (videoIds.isEmpty()) return emptyList()
        val params = listOf(
            "part" to "snippet,contentDetails,liveStreamingDetails",
            "id" to videoIds.joinToString(","),
            "fields" to "items(id,snippet(channelId,channelTitle,title,publishedAt,liveBroadcastContent,thumbnails),contentDetails/duration,liveStreamingDetails)",
        )
        val root = get("videos", params) ?: return emptyList()
        val videos = ArrayList<Video>()
        val items = root.optJSONArray("items") ?: return emptyList()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val snippet = item.optJSONObject("snippet") ?: JSONObject()
            val id = item.optString("id")
            val channelId = snippet.optString("channelId")
            val title = snippet.optString("title")
            if (id.isEmpty() || title.isEmpty()) continue
            val broadcast = snippet.optString("liveBroadcastContent")
            videos.add(
                Video(
                    id = id,
                    channelId = channelId,
                    title = title,
                    channelTitle = snippet.optString("channelTitle"),
                    publishedAt = parsePublished(snippet.optString("publishedAt")) ?: 0L,
                    durationSeconds = durationSeconds(
                        item.optJSONObject("contentDetails")?.optString("duration").orEmpty(),
                    ),
                    isLive = isCurrentlyLive(item, broadcast),
                    isUpcoming = broadcast == "upcoming",
                    thumbnailUrl = thumbnailUrl(snippet),
                )
            )
        }
        return videos
    }

    /**
     * Live video ids for a single channel (`search?eventType=live`), newest first.
     *
     * The Data API `eventType=live` result can lag; callers must confirm with [videos] and
     * [isCurrentlyLive] before treating a result as on air.
     */
    fun searchLiveVideoIds(channelId: String): List<String> {
        val params = listOf(
            "part" to "id",
            "channelId" to channelId,
            "eventType" to "live",
            "type" to "video",
            "maxResults" to "10",
            "fields" to "items/id/videoId",
        )
        val root = get("search", params) ?: return emptyList()
        val items = root.optJSONArray("items") ?: return emptyList()
        val ids = ArrayList<String>(items.length())
        for (index in 0 until items.length()) {
            val id = items.optJSONObject(index)
                ?.optJSONObject("id")
                ?.optString("videoId").orEmpty()
            if (id.isNotEmpty()) ids.add(id)
        }
        return ids
    }

    private fun get(path: String, params: List<Pair<String, String>>): JSONObject? {
        val key = apiKeyProvider().trim()
        if (key.isEmpty()) return null
        val query = buildString {
            for ((name, value) in params) {
                if (isNotEmpty()) append('&')
                append(encode(name)).append('=').append(encode(value))
            }
            append("&key=").append(encode(key))
        }
        val response = transport.execute(
            method = "GET",
            url = "https://www.googleapis.com/youtube/v3/$path?$query",
            headers = mapOf(
                "User-Agent" to listOf(userAgent),
                "Accept" to listOf("application/json"),
            ),
        )
        val body = response.bodyText()
        if (response.code !in 200..299) {
            throw IOException(apiErrorMessage(body) ?: "YouTube API request failed with HTTP ${response.code}.")
        }
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            throw IOException("YouTube returned invalid JSON.", e)
        }
    }

    private fun apiErrorMessage(body: String): String? = try {
        JSONObject(body).optJSONObject("error")?.optString("message")?.ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private companion object {
        fun parsePublished(value: String): Long? {
            if (value.isEmpty()) return null
            return try {
                Instant.parse(value).toEpochMilli()
            } catch (e: Exception) {
                try {
                    OffsetDateTime.parse(value).toInstant().toEpochMilli()
                } catch (e2: Exception) {
                    null
                }
            }
        }

        fun thumbnailUrl(snippet: JSONObject): String {
            val thumbnails = snippet.optJSONObject("thumbnails") ?: return ""
            for (name in listOf("medium", "default", "high")) {
                val url = thumbnails.optJSONObject(name)?.optString("url").orEmpty()
                if (url.isNotEmpty()) return url
            }
            return ""
        }

        /**
         * Confirms a result is on air right now: the broadcast flag must be `live`, an
         * `actualStartTime` must exist and no `actualEndTime` may be present.
         */
        fun isCurrentlyLive(item: JSONObject, broadcast: String): Boolean {
            if (broadcast != "live") return false
            val details = item.optJSONObject("liveStreamingDetails") ?: return false
            return details.optString("actualStartTime").isNotEmpty() &&
                details.optString("actualEndTime").isEmpty()
        }

        fun durationSeconds(value: String): Long {
            val match = ISO_DURATION.matchEntire(value) ?: return -1
            var seconds = 0L
            val multipliers = longArrayOf(86400, 3600, 60, 1)
            var hasComponent = false
            for (index in 1..4) {
                val group = match.groupValues[index]
                if (group.isNotEmpty()) {
                    hasComponent = true
                    seconds += group.toLong() * multipliers[index - 1]
                }
            }
            return if (!hasComponent) -1 else seconds
        }

        val ISO_DURATION = Regex("^P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$")
    }
}
