package dev.omatube.app.backend

import dev.omatube.app.model.Channel
import dev.omatube.app.model.SponsorSegment
import dev.omatube.app.model.Video
import dev.omatube.app.model.VideoPage
import org.schabi.newpipe.extractor.stream.StreamInfo

interface VideoBackend {
    suspend fun resolveChannel(input: String): Channel

    suspend fun recentVideos(channel: Channel): VideoPage

    suspend fun olderVideos(channel: Channel, cursor: String?): VideoPage

    suspend fun liveVideos(channel: Channel): List<Video>

    /**
     * Optional second stage for the Atom fast path. [recentVideos] may return
     * items without durations; this returns richer metadata for the same
     * channel (NewPipe's first VIDEOS page carries durations). The default is
     * empty so backends that already return complete metadata need no work.
     */
    suspend fun enrichRecentVideos(channel: Channel): List<Video> = emptyList()

    suspend fun resolveStream(videoId: String): StreamInfo

    suspend fun sponsorSegments(videoId: String): List<SponsorSegment>
}
