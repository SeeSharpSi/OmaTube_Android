package dev.omatube.app.backend

import dev.omatube.app.model.TranscriptCue
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Locale

class YoutubeTranscriptLoaderTest {
    @Test
    fun emptyCueTextIsSafe() {
        assertEquals("", TranscriptCue(0, 1, emptyList()).text)
    }

    @Test
    fun selectsExactLocaleThenHumanCaption() {
        val tracks = listOf(
            track("en", "https://example.test/auto", auto = true),
            track("fr-CA", "https://example.test/fr-ca"),
            track("fr-FR", "https://example.test/auto-fr", auto = true),
            track("fr-FR", "https://example.test/fr"),
        )
        val selected = YoutubeTranscriptLoader(FakeTransport()) { Locale.FRANCE }
            .selectTrack(tracks)
        assertEquals("https://example.test/fr", selected?.content)
    }

    @Test
    fun fallsBackToEnglishThenFirstAvailableAndIgnoresUnsupportedTracks() {
        val tracks = listOf(
            track("de", "magnet:?xt=1"),
            track("es", "https://example.test/es"),
            track("en", "https://example.test/en"),
        )
        val selected = YoutubeTranscriptLoader(FakeTransport()) { Locale.JAPAN }.selectTrack(tracks)
        assertEquals("https://example.test/en", selected?.content)
        assertTrue(
            YoutubeTranscriptLoader(FakeTransport()).selectTrack(
                listOf(track("de", "magnet:?xt=1")),
            ) == null,
        )
    }

    @Test
    fun rewritesOnlyFormatQueryParameter() {
        val url = "https://example.test/caption?fmt=srv3&x=1&fmt=old".toHttpUrlOrNull()!!
        val rewritten = YoutubeTranscriptLoader.rewriteFormat(url)
        assertEquals("json3", rewritten.queryParameter("fmt"))
        assertEquals("1", rewritten.queryParameter("x"))
        assertEquals(1, rewritten.queryParameterNames.count { it == "fmt" })
    }

    @Test
    fun requestsJson3AndCapsResponse() {
        val transport = FakeTransport()
        transport.responder = { transport.response(body = "{\"events\":[]}") }
        val loader = YoutubeTranscriptLoader(transport, "test-agent")
        loader.loadTrack(track("en", "https://example.test/caption?fmt=srv3"))
        assertEquals("GET", transport.lastCall().method)
        assertEquals("test-agent", transport.lastCall().headers["User-Agent"]?.single())
        assertEquals(YoutubeTranscriptLoader.MAX_RESPONSE_BYTES, transport.lastCall().maxResponseBytes)
        assertEquals("json3", transport.lastCall().url.toHttpUrlOrNull()?.queryParameter("fmt"))
    }

    @Test
    fun httpFailureAndMalformedJsonAreExplicitFailures() {
        val transport = FakeTransport()
        transport.responder = { transport.response(code = 503) }
        try {
            YoutubeTranscriptLoader(transport).loadTrack(track("en", "https://example.test/caption"))
            throw AssertionError("Expected IOException")
        } catch (error: IOException) {
            assertTrue(error.message!!.contains("503"))
        }
        try {
            YoutubeTranscriptLoader.parse("not json")
            throw AssertionError("Expected IOException")
        } catch (error: IOException) {
            assertTrue(error.message!!.contains("not valid JSON"))
        }
    }

    @Test
    fun parsesWhitespaceAndProgressiveWordTiming() {
        val cues = YoutubeTranscriptLoader.parse(
            """
            {"events":[
              {"tStartMs":1000,"dDurationMs":4000,"segs":[
                {"utf8":"  hello\n  world  ","tOffsetMs":0},
                {"utf8":"next","tOffsetMs":3000}
              ]},
              {"tStartMs":6000,"dDurationMs":0,"segs":[{"utf8":"   "}]},
              {"tStartMs":7000,"dDurationMs":-2,"segs":[{"utf8":"last"}]}
            ]}
            """.trimIndent(),
        )
        assertEquals(2, cues.size)
        assertEquals("hello world next", cues[0].text)
        assertEquals(listOf(1000L, 2500L, 4000L), cues[0].words.map { it.startMs })
        assertEquals(5000L, cues[0].endMs)
        assertEquals("last", cues[1].text)
        assertTrue(cues.all { it.endMs > it.startMs })
    }

    @Test
    fun missingDurationEndsAtNextCue() {
        val cues = YoutubeTranscriptLoader.parse(
            """
            {"events":[
              {"tStartMs":1000,"segs":[{"utf8":"first cue"}]},
              {"tStartMs":4000,"dDurationMs":2000,"segs":[{"utf8":"second cue"}]}
            ]}
            """.trimIndent(),
        )

        assertEquals(4_000L, cues.first().endMs)
        assertEquals(listOf(1_000L, 2_500L), cues.first().words.map { it.startMs })
    }

    private fun track(language: String, content: String, auto: Boolean = false): SubtitlesStream =
        SubtitlesStream.Builder()
            .setContent(content, true)
            .setLanguageCode(language)
            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
            .setAutoGenerated(auto)
            .build()
}
