package dev.omatube.app.backend

import dev.omatube.app.model.Video
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import javax.xml.parsers.DocumentBuilderFactory

class AtomFeedParseException(message: String) : IOException(message)

/** Channel-scoped subset of a YouTube long-form uploads Atom feed. */
data class AtomFeed(
    val channelId: String,
    val channelTitle: String,
    val videos: List<Video>,
)

/**
 * Parses the YouTube `feeds/videos.xml?playlist_id=UULF...` Atom feed used by the
 * recent-videos fast path. The desktop parser is `src/youtubefeed.cpp::parseYouTubeFeed`;
 * this keeps the same validation rules (identity, title and per-entry fields required).
 */
object AtomFeedParser {

    fun parse(xml: ByteArray): AtomFeed {
        val document = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { isXIncludeAware = false }
                runCatching { isExpandEntityReferences = false }
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        } catch (e: Exception) {
            throw AtomFeedParseException("Invalid Atom feed: ${e.message}")
        }

        val root = document.documentElement
            ?: throw AtomFeedParseException("Response is not an Atom feed document.")
        if (root.localNameOrName() != "feed") {
            throw AtomFeedParseException("Response is not an Atom feed document.")
        }

        val feedChannelId = root.descendantText("channelId")
        val feedTitle = root.descendantText("title")
        val authorName = root.descendantElements("author")
            .firstNotNullOfOrNull { it.descendantText("name") }
            ?.takeIf { it.isNotBlank() }
        val channelTitle = authorName ?: feedTitle
        if (feedChannelId.isNullOrBlank() || channelTitle.isNullOrBlank()) {
            throw AtomFeedParseException("Atom feed is missing channel identity or title.")
        }

        val videos = ArrayList<Video>()
        for (entry in root.descendantElements("entry")) {
            val videoId = entry.childText("videoId")
            val entryChannelId = entry.childText("channelId")
            val title = entry.childText("title")
            val published = entry.childText("published")
            val publishedAt = parsePublished(published)
            if (videoId.isNullOrBlank() || entryChannelId.isNullOrBlank()
                || title.isNullOrBlank() || publishedAt == null
            ) {
                continue
            }
            videos.add(
                Video(
                    id = videoId,
                    channelId = entryChannelId,
                    title = title,
                    channelTitle = channelTitle,
                    publishedAt = publishedAt,
                    durationSeconds = -1,
                    isLive = false,
                    isUpcoming = false,
                )
            )
        }
        return AtomFeed(feedChannelId, channelTitle, videos)
    }

    private fun parsePublished(value: String?): Long? {
        if (value.isNullOrBlank()) return null
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
}

/** Thin blocking Atom client used by [NewPipeBackend]. */
class AtomFeedClient(
    private val transport: HttpTransport,
    private val userAgent: String,
) {
    fun fetch(channelId: String): AtomFeed {
        val url = ChannelInput.atomFeedUrl(channelId)
        if (url.isEmpty()) {
            throw IOException("Stored YouTube channel ID is invalid.")
        }
        val response = transport.execute(
            method = "GET",
            url = url,
            headers = mapOf(
                "User-Agent" to listOf(userAgent),
                "Accept" to listOf("application/atom+xml,application/xml;q=0.9"),
            ),
        )
        if (response.code !in 200..299) {
            throw IOException("YouTube feed request failed with HTTP ${response.code}.")
        }
        val feed = AtomFeedParser.parse(response.body)
        if (feed.channelId != channelId) {
            throw IOException("YouTube feed returned a different channel.")
        }
        return feed
    }
}

private fun Node.localNameOrName(): String = localName ?: nodeName.substringAfter(':')

private fun Element.childText(localName: String): String? {
    val children = childNodes
    for (index in 0 until children.length) {
        val node = children.item(index)
        if (node.nodeType == Node.ELEMENT_NODE && node.localNameOrName() == localName) {
            return node.textContent?.trim()
        }
    }
    return null
}

private fun Element.descendantText(localName: String): String? {
    val nodes = getElementsByTagNameNS("*", localName)
    for (index in 0 until nodes.length) {
        val text = nodes.item(index).textContent?.trim()
        if (!text.isNullOrEmpty()) return text
    }
    return null
}

private fun Element.descendantElements(localName: String): List<Element> {
    val nodes = getElementsByTagNameNS("*", localName)
    return buildList(nodes.length) {
        for (index in 0 until nodes.length) {
            (nodes.item(index) as? Element)?.let { add(it) }
        }
    }
}
