package dev.omatube.app.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class AtomFeedParserTest {

    private val channelId = "UC1234567890123456789012"

    @Test
    fun parsesEntriesWithAuthorName() {
        val feed = AtomFeedParser.parse(feed().toByteArray(Charsets.UTF_8))
        assertEquals(channelId, feed.channelId)
        assertEquals("Author Name", feed.channelTitle)
        assertEquals(2, feed.videos.size)
        val first = feed.videos[0]
        assertEquals("AAAAAAAAAAA", first.id)
        assertEquals("First video", first.title)
        assertEquals(channelId, first.channelId)
        assertEquals(-1L, first.durationSeconds)
        assertTrue(first.publishedAt > 0)
    }

    @Test
    fun fallsBackToFeedTitleWhenAuthorMissing() {
        val xml = feed().replace("<author><name>Author Name</name></author>", "")
        val parsed = AtomFeedParser.parse(xml.toByteArray(Charsets.UTF_8))
        assertEquals("Feed Title", parsed.channelTitle)
    }

    @Test
    fun skipsEntriesWithoutRequiredFields() {
        val xml = """
            <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
              <yt:channelId>$channelId</yt:channelId><title>T</title>
              <entry><yt:videoId>AAAAAAAAAAA</yt:videoId><yt:channelId>$channelId</yt:channelId>
              <title>No date</title></entry>
            </feed>
        """.trimIndent()
        assertEquals(0, AtomFeedParser.parse(xml.toByteArray()).videos.size)
    }

    @Test
    fun rejectsMalformedAndWrongRoot() {
        assertThrows("not xml at all")
        assertThrows("<rss><channel/></rss>")
        assertThrows("<feed><title>truncated")
    }

    @Test
    fun rejectsMissingIdentity() {
        val xml = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>Only title</title></feed>"
        assertThrows(xml)
    }

    @Test
    fun clientReturnsMatchingFeed() {
        val transport = FakeTransport()
        transport.responder = { transport.response(body = feed()) }
        val client = AtomFeedClient(transport, "OmaTubeTest")
        val parsed = client.fetch(channelId)
        assertEquals(2, parsed.videos.size)
        assertTrue(transport.lastCall().url.contains("UULF1234567890123456789012"))
    }

    @Test
    fun clientRejectsHttpErrorsAndChannelMismatch() {
        val transport = FakeTransport()
        transport.responder = { transport.response(code = 500) }
        assertFailsWith(IOException::class.java) { AtomFeedClient(transport, "t").fetch(channelId) }

        transport.responder = { transport.response(body = feed(channelId = "UC00000000000000000000AA")) }
        assertFailsWith(IOException::class.java) { AtomFeedClient(transport, "t").fetch(channelId) }
    }

    @Test
    fun clientRejectsInvalidChannelId() {
        val transport = FakeTransport()
        assertFailsWith(IOException::class.java) {
            AtomFeedClient(transport, "t").fetch("not-a-channel")
        }
        assertTrue(transport.calls.isEmpty())
    }

    private fun assertThrows(xml: String) {
        assertFailsWith(AtomFeedParseException::class.java) {
            AtomFeedParser.parse(xml.toByteArray(Charsets.UTF_8))
        }
    }

    private fun <T : Throwable> assertFailsWith(type: Class<T>, block: () -> Unit) {
        try {
            block()
            fail("Expected ${type.simpleName}")
        } catch (e: Throwable) {
            if (!type.isInstance(e)) throw e
        }
    }

    private fun feed(channelId: String = this.channelId): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
          <link rel="self" href="https://www.youtube.com/feeds/videos.xml?playlist_id=UULF1234567890123456789012"/>
          <id>yt:channel:$channelId</id>
          <yt:channelId>$channelId</yt:channelId>
          <title>Feed Title</title>
          <author><name>Author Name</name></author>
          <entry>
            <id>yt:video:AAAAAAAAAAA</id>
            <yt:videoId>AAAAAAAAAAA</yt:videoId>
            <yt:channelId>$channelId</yt:channelId>
            <title>First video</title>
            <published>2026-01-02T03:04:05+00:00</published>
          </entry>
          <entry>
            <id>yt:video:BBBBBBBBBBB</id>
            <yt:videoId>BBBBBBBBBBB</yt:videoId>
            <yt:channelId>$channelId</yt:channelId>
            <title>Second video</title>
            <published>2026-01-01T00:00:00Z</published>
          </entry>
        </feed>
    """.trimIndent()
}
