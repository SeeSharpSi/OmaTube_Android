package dev.omatube.app.backend

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Normalises user supplied channel references before they reach NewPipeExtractor.
 *
 * Accepted forms:
 *  - a canonical channel id: `UC` followed by 22 URL-safe characters
 *  - a handle with or without the leading `@`
 *  - a youtube.com channel URL (`/@handle`, `/@handle/videos`, `/channel/UC...`,
 *    `/channel/UC.../videos`)
 *
 * Any other host or page shape is rejected with an actionable message. This mirrors
 * the desktop implementation in `src/youtubeclient.cpp::parseChannelReference` and
 * `src/youtubefeed.cpp::longFormYouTubeFeedUrl` so stored channels stay interchangeable.
 */
object ChannelInput {

    enum class Kind { CHANNEL_ID, HANDLE }

    data class Reference(val kind: Kind, val value: String)

    private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")
    private val HANDLE = Regex("^@?([^\\s/@?#]{3,30})$")
    private val YOUTUBE_HOSTS = setOf("youtube.com", "m.youtube.com")
    private val TAB_SUFFIXES = setOf(
        "videos", "streams", "live", "shorts", "playlists", "featured", "community",
    )

    const val INVALID_INPUT_MESSAGE = "Enter a YouTube channel URL, handle, or channel ID."
    const val INVALID_PAGE_MESSAGE = "Enter a channel page, not a video or playlist URL."
    const val INVALID_HANDLE_MESSAGE = "Enter a valid handle or UC channel ID."

    /**
     * @throws IllegalArgumentException with a user-facing message when [input] is not a
     * recognised channel reference.
     */
    fun parse(input: String): Reference {
        var candidate = input.trim()
        require(candidate.isNotEmpty()) { INVALID_INPUT_MESSAGE }

        if (candidate.startsWith("http://", ignoreCase = true)
            || candidate.startsWith("https://", ignoreCase = true)
        ) {
            candidate = parseUrl(candidate)
        }

        if (CHANNEL_ID.matches(candidate)) {
            return Reference(Kind.CHANNEL_ID, candidate)
        }
        val handleMatch = HANDLE.matchEntire(candidate)
        if (handleMatch != null) {
            return Reference(Kind.HANDLE, "@" + handleMatch.groupValues[1])
        }
        throw IllegalArgumentException(INVALID_HANDLE_MESSAGE)
    }

    private fun parseUrl(raw: String): String {
        val uri = try {
            URI(raw)
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException(INVALID_INPUT_MESSAGE, e)
        }
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")
        if (host.isNullOrEmpty() || host !in YOUTUBE_HOSTS) {
            throw IllegalArgumentException(INVALID_INPUT_MESSAGE)
        }
        val segments = uri.path.orEmpty()
            .split('/')
            .filter { it.isNotEmpty() }
        return when {
            segments.size == 1 && segments[0].startsWith("@") -> segments[0]
            segments.size == 2 && segments[0] == "channel" -> segments[1]
            segments.size == 3 && segments[0] == "channel"
                && segments[2].lowercase(Locale.ROOT) in TAB_SUFFIXES -> segments[1]
            segments.size == 2 && segments[0].startsWith("@")
                && segments[1].lowercase(Locale.ROOT) in TAB_SUFFIXES -> segments[0]
            else -> throw IllegalArgumentException(INVALID_PAGE_MESSAGE)
        }
    }

    fun isChannelId(value: String): Boolean = CHANNEL_ID.matches(value)

    fun channelUrl(reference: Reference): String = when (reference.kind) {
        Kind.CHANNEL_ID -> "https://www.youtube.com/channel/${reference.value}"
        Kind.HANDLE -> "https://www.youtube.com/${reference.value}"
    }

    fun channelUrl(channelId: String): String = "https://www.youtube.com/channel/$channelId"

    fun channelTabUrl(channelId: String, tab: String): String =
        "https://www.youtube.com/channel/$channelId/$tab"

    /** `UCxxxxxxxx...` -> `UUxxxxxxxx...` (all uploads, including shorts). */
    fun uploadsPlaylistId(channelId: String): String =
        if (isChannelId(channelId)) "UU" + channelId.substring(2) else ""

    /** `UCxxxxxxxx...` -> `UULFxxxxxxxx...` (long-form uploads, used by the Atom feed). */
    fun longFormPlaylistId(channelId: String): String =
        if (isChannelId(channelId)) "UULF" + channelId.substring(2) else ""

    /** Atom feed used by the recent-videos fast path. Empty when [channelId] is invalid. */
    fun atomFeedUrl(channelId: String): String {
        val playlistId = longFormPlaylistId(channelId)
        return if (playlistId.isEmpty()) "" else
            "https://www.youtube.com/feeds/videos.xml?playlist_id=$playlistId"
    }
}
