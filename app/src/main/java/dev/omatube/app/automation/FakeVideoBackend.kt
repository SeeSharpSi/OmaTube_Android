package dev.omatube.app.automation

import dev.omatube.app.backend.ChannelInput
import dev.omatube.app.backend.VideoBackend
import dev.omatube.app.model.Channel
import dev.omatube.app.model.SponsorSegment
import dev.omatube.app.model.Video
import dev.omatube.app.model.VideoPage
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic in-memory [VideoBackend] for debug automation and offline
 * tests. It never opens a socket, never starts the PO-token WebView and never
 * touches Media3; every call is counted so tests can prove no network work
 * happened.
 */
class FakeVideoBackend(
    private val channelsById: Map<String, Channel> = emptyMap(),
    private val channelsByInput: Map<String, Channel> = emptyMap(),
    var recentProvider: suspend (Channel) -> VideoPage = { VideoPage(emptyList()) },
    var olderProvider: suspend (Channel, String?) -> VideoPage = { _, _ -> VideoPage(emptyList()) },
    var liveProvider: suspend (Channel) -> List<Video> = { emptyList() },
    var enrichProvider: suspend (Channel) -> List<Video> = { emptyList() },
    var sponsorProvider: suspend (String) -> List<SponsorSegment> = { emptyList() },
) : VideoBackend {

    private val resolveCalls = AtomicInteger()
    private val recentCalls = AtomicInteger()
    private val olderCalls = AtomicInteger()
    private val liveCalls = AtomicInteger()
    private val enrichCalls = AtomicInteger()
    private val sponsorCalls = AtomicInteger()

    val resolveCallCount: Int get() = resolveCalls.get()
    val recentCallCount: Int get() = recentCalls.get()
    val olderCallCount: Int get() = olderCalls.get()
    val liveCallCount: Int get() = liveCalls.get()
    val enrichCallCount: Int get() = enrichCalls.get()
    val sponsorCallCount: Int get() = sponsorCalls.get()
    val totalCallCount: Int
        get() = resolveCalls.get() + recentCalls.get() + olderCalls.get() +
            liveCalls.get() + enrichCalls.get() + sponsorCalls.get()

    /** Cursors passed to [olderVideos], in call order. */
    val olderCursors: MutableList<String?> = java.util.Collections.synchronizedList(mutableListOf())

    override suspend fun resolveChannel(input: String): Channel {
        resolveCalls.incrementAndGet()
        val normalized = input.trim()
        channelsByInput[normalized]?.let { return it }
        channelsById[normalized]?.let { return it }
        val byHandle = channelsById.values.filter { it.handle == normalized }
        if (byHandle.size == 1) return byHandle.first()
        throw IllegalArgumentException(ChannelInput.INVALID_HANDLE_MESSAGE)
    }

    override suspend fun recentVideos(channel: Channel): VideoPage {
        recentCalls.incrementAndGet()
        return recentProvider(channel)
    }

    override suspend fun olderVideos(channel: Channel, cursor: String?): VideoPage {
        olderCalls.incrementAndGet()
        olderCursors.add(cursor)
        return olderProvider(channel, cursor)
    }

    override suspend fun liveVideos(channel: Channel): List<Video> {
        liveCalls.incrementAndGet()
        return liveProvider(channel)
    }

    override suspend fun enrichRecentVideos(channel: Channel): List<Video> {
        enrichCalls.incrementAndGet()
        return enrichProvider(channel)
    }

    override suspend fun resolveStream(videoId: String): StreamInfo =
        throw UnsupportedOperationException(
            "Automation and offline tests must never resolve media streams.",
        )

    override suspend fun sponsorSegments(videoId: String): List<SponsorSegment> {
        sponsorCalls.incrementAndGet()
        return sponsorProvider(videoId)
    }

    companion object {
        /** Fake backend wired to the fixed desktop [AutomationFixture]. */
        fun forFixture(): FakeVideoBackend {
            val byId = AutomationFixture.channels.associateBy { it.id }
            val byInput = byId + AutomationFixture.channels.associateBy { it.originalInput }
            return FakeVideoBackend(
                channelsById = byId,
                channelsByInput = byInput,
                recentProvider = { channel ->
                    VideoPage(
                        videos = AutomationFixture.videos.filter { it.channelId == channel.id },
                        nextPage = null,
                    )
                },
            )
        }
    }
}
