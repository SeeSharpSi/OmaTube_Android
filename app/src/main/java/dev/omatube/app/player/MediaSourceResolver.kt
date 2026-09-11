package dev.omatube.app.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubePostLiveStreamDvrDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.ByteArrayInputStream

/**
 * Turns a NewPipe `StreamInfo` into a Media3 [MediaSource].
 *
 * This follows the upstream NewPipe `VideoPlaybackResolver` / `PlaybackResolver`
 * strategy (GPL-3.0-or-later, TeamNewPipe), which is the verified behavior:
 *
 * - Live content prefers the DASH manifest, then HLS.
 * - Separate video and audio streams are always merged with
 *   `MergingMediaSource(true, ...)`; a video-only source is never played
 *   silently without audio.
 * - YouTube progressive/OTF/post-live streams with tagged extractor ranges are
 *   converted into single-track DASH manifests for reliable seeking, falling
 *   back to a progressive source when manifest generation fails.
 * - Inline (non-URL) DASH manifests are parsed directly and are never used as a
 *   MediaItem URI.
 */
@OptIn(UnstableApi::class)
class MediaSourceResolver(
    private val dataSources: PlaybackDataSources,
) {
    sealed interface Result {
        data class Success(
            val mediaSource: MediaSource,
            val selectedHeight: Int?,
            val isLive: Boolean,
        ) : Result

        data class Failure(val message: String) : Result
    }

    fun resolve(info: StreamInfo, maximumHeight: Int): Result {
        buildLiveSource(info)?.let { return it }
        return buildVodSource(info, maximumHeight)
    }

    // region Live

    private fun buildLiveSource(info: StreamInfo): Result.Success? {
        if (!isLiveStreamType(info.streamType)) return null
        return try {
            val dashUrl = info.dashMpdUrl
            if (!dashUrl.isNullOrEmpty()) {
                val factory = DashMediaSource.Factory(
                    DefaultDashChunkSource.Factory(dataSources.generic),
                    dataSources.generic,
                ).setManifestParser(YoutubeDashLiveManifestParser())
                Result.Success(
                    mediaSource = factory.createMediaSource(MediaItem.fromUri(dashUrl)),
                    selectedHeight = null,
                    isLive = true,
                )
            } else {
                val hlsUrl = info.hlsUrl
                if (hlsUrl.isNullOrEmpty()) {
                    null
                } else {
                    Result.Success(
                        mediaSource = HlsMediaSource.Factory(dataSources.generic)
                            .createMediaSource(MediaItem.fromUri(hlsUrl)),
                        selectedHeight = null,
                        isLive = true,
                    )
                }
            }
        } catch (_: Throwable) {
            // Fall back to the separated stream path when the live manifest
            // cannot be used.
            null
        }
    }

    // endregion

    // region VOD

    private fun buildVodSource(info: StreamInfo, maximumHeight: Int): Result {
        val youtube = info.service == ServiceList.YouTube
        val muxedVideo = playableVideos(info.videoStreams, youtube)
        val videoOnly = playableVideos(info.videoOnlyStreams, youtube)
        val audioStreams = filterAudio(info.audioStreams)

        val allVideos = muxedVideo + videoOnly
        if (allVideos.isEmpty() && audioStreams.isEmpty()) {
            return Result.Failure("No playable streams were found for this video.")
        }

        var video = selectVideo(allVideos, maximumHeight)
        // No per-video audio-language link exists on VideoStream, so the
        // optional preferred track id is left null (best available audio).
        val audio = selectAudio(audioStreams, preferredTrackId = null)

        if (video != null && video.isVideoOnly && audio == null) {
            // Never silently drop audio. Fall back to a muxed rendition if the
            // video-only selection has no companion audio track.
            val muxed = selectVideo(muxedVideo, maximumHeight)
            if (muxed != null && !muxed.isVideoOnly) {
                video = muxed
            } else {
                return Result.Failure("No audio stream is available for this video.")
            }
        }

        val sources = mutableListOf<MediaSource>()
        val selectedVideo = video
        if (selectedVideo != null) {
            sources += runCatching { buildMediaSource(info, selectedVideo) }
                .getOrElse { return Result.Failure(it.message ?: "Could not build the video source.") }
        }
        if (audio != null && (selectedVideo == null || selectedVideo.isVideoOnly)) {
            sources += runCatching { buildMediaSource(info, audio) }
                .getOrElse { return Result.Failure(it.message ?: "Could not build the audio source.") }
        }
        if (sources.isEmpty()) {
            return Result.Failure("No playable streams were found for this video.")
        }
        sources += buildSubtitleSources(info)

        val merged = if (sources.size == 1) {
            sources.first()
        } else {
            MergingMediaSource(true, *sources.toTypedArray())
        }
        return Result.Success(
            mediaSource = merged,
            selectedHeight = selectedVideo?.height,
            isLive = false,
        )
    }

    // endregion

    // region Source building

    private fun buildMediaSource(info: StreamInfo, stream: Stream): MediaSource {
        if (info.service == ServiceList.YouTube) {
            return buildYoutubeMediaSource(info, stream)
        }
        return when (stream.deliveryMethod) {
            DeliveryMethod.PROGRESSIVE_HTTP -> progressiveSource(stream, dataSources.generic)
            DeliveryMethod.DASH ->
                if (stream.isUrl) {
                    dashUrlSource(stream, dataSources.generic)
                } else {
                    inlineDashSource(stream, dataSources.generic)
                }
            DeliveryMethod.HLS -> hlsSource(stream, dataSources.generic)
            else -> throw UnsupportedOperationException(
                "Unsupported delivery method: ${stream.deliveryMethod}",
            )
        }
    }

    private fun buildYoutubeMediaSource(info: StreamInfo, stream: Stream): MediaSource {
        return when (info.streamType) {
            StreamType.VIDEO_STREAM, StreamType.AUDIO_STREAM -> when (stream.deliveryMethod) {
                DeliveryMethod.PROGRESSIVE_HTTP ->
                    if ((stream as? VideoStream)?.isVideoOnly == true || stream is AudioStream) {
                        runCatching { progressiveDashManifestSource(stream, info.duration) }
                            .getOrElse { progressiveSource(stream, dataSources.youtubeProgressive) }
                    } else {
                        progressiveSource(stream, dataSources.youtubeProgressive)
                    }
                DeliveryMethod.DASH ->
                    runCatching { otfDashManifestSource(stream, info.duration) }
                        .getOrElse {
                            if (stream.isUrl) {
                                dashUrlSource(stream, dataSources.youtubeDash)
                            } else {
                                throw it
                            }
                        }
                DeliveryMethod.HLS -> hlsSource(stream, dataSources.youtubeHls)
                else -> throw UnsupportedOperationException(
                    "Unsupported YouTube delivery method: ${stream.deliveryMethod}",
                )
            }
            StreamType.POST_LIVE_STREAM, StreamType.POST_LIVE_AUDIO_STREAM ->
                runCatching { postLiveDashManifestSource(stream, info.duration) }
                    .getOrElse {
                        if (stream.isUrl) {
                            progressiveSource(stream, dataSources.youtubeProgressive)
                        } else {
                            throw it
                        }
                    }
            else -> throw UnsupportedOperationException(
                "Unsupported YouTube stream type: ${info.streamType}",
            )
        }
    }

    private fun progressiveDashManifestSource(stream: Stream, durationSeconds: Long): MediaSource {
        val itag = requireNotNull(stream.itagItem) { "YouTube stream has no itag metadata" }
        val manifest = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(
            stream.content,
            itag,
            durationSeconds,
        )
        return dashManifestSource(manifest, stream, dataSources.youtubeDash)
    }

    private fun otfDashManifestSource(stream: Stream, durationSeconds: Long): MediaSource {
        val itag = requireNotNull(stream.itagItem) { "YouTube stream has no itag metadata" }
        val manifest = YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(
            stream.content,
            itag,
            durationSeconds,
        )
        return dashManifestSource(manifest, stream, dataSources.youtubeDash)
    }

    private fun postLiveDashManifestSource(stream: Stream, durationSeconds: Long): MediaSource {
        val itag = requireNotNull(stream.itagItem) { "YouTube stream has no itag metadata" }
        val manifest = YoutubePostLiveStreamDvrDashManifestCreator
            .fromPostLiveStreamDvrStreamingUrl(
                stream.content,
                itag,
                itag.targetDurationSec,
                durationSeconds,
            )
        return dashManifestSource(manifest, stream, dataSources.youtubeDash)
    }

    private fun dashManifestSource(
        manifestContent: String,
        stream: Stream,
        factory: androidx.media3.datasource.DataSource.Factory,
    ): MediaSource {
        val manifest: DashManifest = DashManifestParser().parse(
            baseUri(stream),
            ByteArrayInputStream(manifestContent.toByteArray(Charsets.UTF_8)),
        )
        return DashMediaSource.Factory(
            DefaultDashChunkSource.Factory(factory),
            factory,
        ).createMediaSource(manifest, mediaItemFor(stream))
    }

    private fun progressiveSource(
        stream: Stream,
        factory: androidx.media3.datasource.DataSource.Factory,
    ): MediaSource {
        return ProgressiveMediaSource.Factory(factory)
            .createMediaSource(mediaItemFor(stream))
    }

    private fun dashUrlSource(
        stream: Stream,
        factory: androidx.media3.datasource.DataSource.Factory,
    ): MediaSource {
        return DashMediaSource.Factory(
            DefaultDashChunkSource.Factory(factory),
            factory,
        ).createMediaSource(mediaItemFor(stream))
    }

    private fun inlineDashSource(
        stream: Stream,
        factory: androidx.media3.datasource.DataSource.Factory,
    ): MediaSource {
        // The stream content is already a DASH manifest, so parse it. The
        // manifest text is never used as a URI; only manifestUrl is.
        val manifest: DashManifest = DashManifestParser().parse(
            baseUri(stream),
            ByteArrayInputStream(stream.content.toByteArray(Charsets.UTF_8)),
        )
        return DashMediaSource.Factory(
            DefaultDashChunkSource.Factory(factory),
            factory,
        ).createMediaSource(manifest, mediaItemFor(stream))
    }

    private fun hlsSource(
        stream: Stream,
        factory: androidx.media3.datasource.DataSource.Factory,
    ): MediaSource {
        if (!stream.isUrl) {
            throw UnsupportedOperationException("Inline HLS manifests are not supported")
        }
        return HlsMediaSource.Factory(factory).createMediaSource(mediaItemFor(stream))
    }

    private fun buildSubtitleSources(info: StreamInfo): List<MediaSource> {
        val subtitles = info.subtitles ?: return emptyList()
        return subtitles.mapNotNull { subtitle -> buildSubtitleSource(subtitle) }
    }

    private fun buildSubtitleSource(subtitle: SubtitlesStream): MediaSource? {
        if (!subtitle.isUrl || subtitle.deliveryMethod == DeliveryMethod.TORRENT) return null
        val format = subtitle.format ?: return null
        val configuration = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.content))
            .setMimeType(format.mimeType)
            .setLanguage(subtitle.locale?.toLanguageTag())
            .setRoleFlags(
                if (subtitle.isAutoGenerated) {
                    C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND
                } else {
                    C.ROLE_FLAG_CAPTION
                },
            )
            .build()
        return SingleSampleMediaSource.Factory(dataSources.youtubeProgressive)
            .createMediaSource(configuration, C.TIME_UNSET)
    }

    // endregion

    // region Selection helpers

    private fun selectVideo(streams: List<VideoStream>, maximumHeight: Int): VideoStream? {
        val candidates = streams.map { stream ->
            StreamSelector.Video(
                resolution = stream.resolution,
                height = stream.height,
                videoOnly = stream.isVideoOnly,
            )
        }
        val index = StreamSelector.selectVideoIndex(candidates, maximumHeight)
        return streams.getOrNull(index)
    }

    private fun selectAudio(streams: List<AudioStream>, preferredTrackId: String?): AudioStream? {
        val candidates = streams.map(::audioCandidate)
        val index = StreamSelector.selectAudioIndex(candidates, preferredTrackId)
        return streams.getOrNull(index)
    }

    private fun audioCandidate(stream: AudioStream): StreamSelector.Audio = StreamSelector.Audio(
        trackId = stream.audioTrackId,
        formatRank = StreamSelector.audioFormatRank(stream.format?.mimeType),
        bitrate = stream.averageBitrate,
        original = stream.audioTrackType?.name == "ORIGINAL",
    )

    private fun playableVideos(streams: List<VideoStream>?, youtube: Boolean): List<VideoStream> {
        if (streams == null) return emptyList()
        return streams.filter { stream ->
            stream.deliveryMethod != DeliveryMethod.TORRENT &&
                (stream.deliveryMethod != DeliveryMethod.HLS ||
                    stream.format != org.schabi.newpipe.extractor.MediaFormat.OPUS) &&
                (!youtube || stream.itagItem == null || stream.itagItem!!.id in SUPPORTED_ITAG_IDS)
        }
    }

    private fun filterAudio(streams: List<AudioStream>?): List<AudioStream> {
        if (streams == null) return emptyList()
        return streams.filter { stream ->
            stream.deliveryMethod != DeliveryMethod.TORRENT &&
                !(stream.deliveryMethod == DeliveryMethod.HLS &&
                    stream.format == org.schabi.newpipe.extractor.MediaFormat.OPUS)
        }
    }

    private fun mediaItemFor(stream: Stream): MediaItem {
        return MediaItem.Builder().setUri(baseUri(stream)).build()
    }

    /** A real URI for the stream. Never the inline manifest text. */
    private fun baseUri(stream: Stream): Uri {
        val manifestUrl = stream.manifestUrl
        return when {
            !manifestUrl.isNullOrEmpty() -> Uri.parse(manifestUrl)
            stream.isUrl -> Uri.parse(stream.content)
            else -> Uri.EMPTY
        }
    }

    private fun isLiveStreamType(type: StreamType): Boolean {
        return type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM
    }

    // endregion

    private companion object {
        /**
         * YouTube itags whose codecs Media3 can decode. Copied from the upstream
         * NewPipe `ListHelper.SUPPORTED_ITAG_IDS`.
         */
        val SUPPORTED_ITAG_IDS: Set<Int> = setOf(
            17, 36,
            18, 34, 35, 59, 78, 22, 37, 38,
            43, 44, 45, 46,
            171, 172, 139, 140, 141, 249, 250, 251,
            160, 133, 134, 135, 212, 136, 298, 137, 299, 266,
            278, 242, 243, 244, 245, 246, 247, 248, 271, 272, 302, 303, 308, 313, 315,
        )
    }
}
