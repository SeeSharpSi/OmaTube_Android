package dev.omatube.app.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class YoutubeDataApiTest {

    private fun api(transport: FakeTransport): YoutubeDataApi =
        YoutubeDataApi(transport, { "test-key" }, "OmaTubeTest")

    @Test
    fun searchesPerChannelLiveVideos() {
        val transport = FakeTransport()
        transport.responder = {
            transport.response(
                body = """{"items":[{"id":{"videoId":"LIVE0000001"}},{"id":{"videoId":"LIVE0000002"}}]}""",
            )
        }
        val ids = api(transport).searchLiveVideoIds("UC1234567890123456789012")
        assertEquals(listOf("LIVE0000001", "LIVE0000002"), ids)
        val url = transport.lastCall().url
        assertTrue(url.contains("/search?"))
        assertTrue(url.contains("eventType=live"))
        assertTrue(url.contains("channelId=UC1234567890123456789012"))
        assertTrue(url.contains("type=video"))
    }

    @Test
    fun validatesCurrentLivenessWithLiveStreamingDetails() {
        val transport = FakeTransport()
        transport.responder = {
            transport.response(
                body = """
                    {"items":[
                      {"id":"LIVE0000001","snippet":{"channelId":"UC1","channelTitle":"C","title":"Live","publishedAt":"2026-01-01T00:00:00Z","liveBroadcastContent":"live"},
                       "contentDetails":{"duration":"PT0S"},
                       "liveStreamingDetails":{"actualStartTime":"2026-01-01T00:01:00Z"}},
                      {"id":"ENDED000001","snippet":{"channelId":"UC1","channelTitle":"C","title":"Ended","publishedAt":"2026-01-01T00:00:00Z","liveBroadcastContent":"live"},
                       "liveStreamingDetails":{"actualStartTime":"2026-01-01T00:01:00Z","actualEndTime":"2026-01-01T02:00:00Z"}},
                      {"id":"UPCOM0000001","snippet":{"channelId":"UC1","channelTitle":"C","title":"Soon","publishedAt":"2026-01-01T00:00:00Z","liveBroadcastContent":"upcoming"},
                       "contentDetails":{"duration":"PT10M"}},
                      {"id":"VOD000000001","snippet":{"channelId":"UC1","channelTitle":"C","title":"Vod","publishedAt":"2026-01-01T00:00:00Z","liveBroadcastContent":"none"},
                       "contentDetails":{"duration":"PT1H2M3S"}}
                    ]}
                """.trimIndent(),
            )
        }
        val videos = api(transport).videos(listOf("LIVE0000001", "ENDED000001", "UPCOM0000001", "VOD000000001"))
        assertEquals(4, videos.size)
        val byId = videos.associateBy { it.id }
        assertTrue(byId.getValue("LIVE0000001").isLive)
        assertFalse(byId.getValue("ENDED000001").isLive)
        assertTrue(byId.getValue("UPCOM0000001").isUpcoming)
        assertEquals(3723L, byId.getValue("VOD000000001").durationSeconds)
    }

    @Test
    fun quotaFailuresThrowSoCacheCanBePreserved() {
        val transport = FakeTransport()
        transport.responder = {
            transport.response(code = 403, body = """{"error":{"message":"quotaExceeded"}}""")
        }
        try {
            api(transport).searchLiveVideoIds("UC1234567890123456789012")
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("quotaExceeded"))
        }
    }
}
