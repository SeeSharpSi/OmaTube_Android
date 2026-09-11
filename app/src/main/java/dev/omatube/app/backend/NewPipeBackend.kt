package dev.omatube.app.backend

import android.content.Context
import dev.omatube.app.model.Channel
import dev.omatube.app.model.Settings
import dev.omatube.app.model.SponsorAction
import dev.omatube.app.model.SponsorSegment
import dev.omatube.app.model.Video
import dev.omatube.app.model.VideoPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.channel.ChannelExtractor
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Locale

/**
 * The app's NewPipeExtractor-backed [VideoBackend].
 *
 * - Channel resolution, uploads/live pagination and stream extraction always use NewPipeExtractor
 *   (pinned `com.github.teamnewpipe:NewPipeExtractor:v0.26.5`).
 * - The [AtomFeedClient] UULF feed is an optional fast path for recent videos; the YouTube Data API
 *   is an optional metadata path when an API key is configured. Neither is ever used to extract a
 *   playable stream, and the Atom fast path is never blocked on enrichment.
 * - There is no PO-token provider and no hidden WebView. NewPipeExtractor v0.26.5 only asks a
 *   provider for Android/iOS client tokens and never for WEB tokens, and WebView BotGuard can only
 *   mint WEB tokens, so a provider could not supply usable playback tokens. Public VOD playback
 *   uses the extractor's supported Android-Reel path, which requires no token. This backend
 *   therefore does not claim full token coverage; it deliberately stays on the NewPipe-native
 *   route.
 *
 * Cancellation: every entry point runs the blocking extractor and OkHttp work through
 * [runInterruptible] under an overall [withTimeout]. A cancelled refresh request interrupts only
 * its own thread and OkHttp call; no global request cancellation is used.
 */
class NewPipeBackend(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val settings: () -> Settings,
) : VideoBackend {

    private val transport: HttpTransport = OkHttpTransport()
    private val downloader = NewPipeDownloader(transport, USER_AGENT)
    private val atomFeed = AtomFeedClient(transport, USER_AGENT)
    private val sponsorBlock = SponsorBlockClient(transport, USER_AGENT)
    private val dataApi = YoutubeDataApi(transport, { settings().apiKey }, USER_AGENT)
    private val ioDispatcher = Dispatchers.IO

    @Volatile
    private var initialized = false

    init {
        initializeExtractor()
    }

    private fun initializeExtractor() {
        if (initialized) return
        synchronized(INIT_LOCK) {
            if (initialized) return
            val locale = Locale.getDefault()
            val country = locale.country
            val localization = Localization(
                locale.language.ifEmpty { "en" },
                if (country.isEmpty()) null else country,
            )
            NewPipe.init(downloader, localization)
            initialized = true
        }
    }

    override suspend fun resolveChannel(input: String): Channel = extract(CHANNEL_TIMEOUT_MS) {
        val reference = ChannelInput.parse(input)
        if (dataApi.hasKey()) {
            dataApi.resolveChannel(reference, input)?.let { return@extract it }
        }
        resolveChannelWithNewPipe(reference, input)
    }

    override suspend fun recentVideos(channel: Channel): VideoPage = extract(PAGE_TIMEOUT_MS) {
        if (dataApi.hasKey()) {
            val page = dataApi.uploadPage(channel.uploadsPlaylistId, null)
            return@extract VideoPage(
                videos = page.videos,
                nextPage = page.nextPageToken?.let { FeedCursorCodec.encodeDataApi(it) },
            )
        }

        // Atom fast path: return immediately, never block on duration enrichment.
        val feed = try {
            atomFeed.fetch(channel.id)
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            return@extract newPipePage(channel, null)
        } catch (e: RuntimeException) {
            return@extract newPipePage(channel, null)
        }
        VideoPage(videos = feed.videos, nextPage = FeedCursorCodec.startNewPipe())
    }

    override suspend fun olderVideos(channel: Channel, cursor: String?): VideoPage =
        extract(PAGE_TIMEOUT_MS) {
            val decoded = FeedCursorCodec.decode(cursor)
            if (decoded is FeedCursor.DataApi && dataApi.hasKey()) {
                val page = dataApi.uploadPage(channel.uploadsPlaylistId, decoded.pageToken)
                return@extract VideoPage(
                    videos = page.videos,
                    nextPage = page.nextPageToken?.let { FeedCursorCodec.encodeDataApi(it) },
                )
            }
            newPipePage(channel, (decoded as? FeedCursor.NewPipe)?.page)
        }

    override suspend fun liveVideos(channel: Channel): List<Video> = extract(PAGE_TIMEOUT_MS) {
        if (dataApi.hasKey()) {
            // Optional Data API path: per-channel live search plus current-live validation.
            // A quota/HTTP failure propagates, so callers keep the last-known live state.
            val ids = dataApi.searchLiveVideoIds(channel.id)
            if (ids.isEmpty()) return@extract emptyList()
            return@extract dataApi.videos(ids).filter { it.isLive }
        }

        val extractor = service().getChannelTabExtractorFromId(channel.id, ChannelTabs.LIVESTREAMS)
        extractor.fetchPage()
        // v0.26.5 behavior (YoutubeChannelTabExtractor.getInitialPage + getTabData): when the
        // channel has no streams tab, `getTabData()` is empty, `items` stays empty and the result
        // is a successful empty page with no continuation. Only genuine network/parse failures
        // throw. So a channel without a streams tab yields an empty list here, while a failed live
        // request propagates and callers keep the last-known live state. We never convert a
        // parse/extraction error into an empty list.
        extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .filter { StreamMapper.isCurrentlyLive(it) }
            .mapNotNull { StreamMapper.toVideo(it, channel.id, channel.title) }
    }

    override suspend fun resolveStream(videoId: String): StreamInfo = extract(STREAM_TIMEOUT_MS) {
        require(VIDEO_ID.matches(videoId)) { "Invalid YouTube video id: $videoId" }
        StreamInfo.getInfo(
            service().getStreamExtractor("https://www.youtube.com/watch?v=$videoId"),
        )
    }

    override suspend fun sponsorSegments(videoId: String): List<SponsorSegment> =
        extract(SPONSOR_TIMEOUT_MS) {
            val current = settings()
            if (!current.sponsorBlockEnabled) return@extract emptyList()
            val categories = current.sponsorActions
                .filterValues { it != SponsorAction.NONE }
                .keys
            if (categories.isEmpty()) return@extract emptyList()
            sponsorBlock.segments(videoId, categories)
        }

    /**
     * Lightweight duration/live enrichment for the Atom fast path.
     *
     * `recentVideos` intentionally returns Atom items without durations. The RefreshCoordinator
     * secondary stage can call this to upsert richer metadata without delaying the Atom response.
     *
     * In the Data API path `recentVideos` already returns durations, so the [VideoBackend] default
     * (`emptyList()`) is kept. Without an API key this fetches NewPipe's VIDEOS first page, which
     * carries durations, and returns those videos for upsert.
     */
    override suspend fun enrichRecentVideos(channel: Channel): List<Video> =
        extract(PAGE_TIMEOUT_MS) {
            if (dataApi.hasKey()) return@extract emptyList()
            newPipePage(channel, null).videos
        }

    /**
     * Releases backend resources. Safe to call more than once.
     *
     * There is no PO-token helper and no hidden WebView to release. It deliberately does not call
     * `Dispatcher.cancelAll()`: request cancellation is per-call via `runInterruptible`/thread
     * interrupt in [OkHttpTransport], so cancelling one refresh request can never abort the other
     * concurrent requests.
     */
    fun close() {
        // Nothing to release. Kept as the lifecycle hook used by AppGraph.
    }

    private suspend fun <T> extract(timeoutMillis: Long, block: () -> T): T =
        withTimeout(timeoutMillis) {
            runInterruptible(ioDispatcher) { block() }
        }

    private fun resolveChannelWithNewPipe(
        reference: ChannelInput.Reference,
        originalInput: String,
    ): Channel {
        val extractor: ChannelExtractor =
            service().getChannelExtractor(ChannelInput.channelUrl(reference))
        extractor.fetchPage()
        val id = extractor.id
        if (!ChannelInput.isChannelId(id)) {
            throw IOException("NewPipe returned a non-canonical channel ID: $id")
        }
        val handle = if (reference.kind == ChannelInput.Kind.HANDLE) reference.value else ""
        return Channel(
            id = id,
            title = extractor.name,
            originalInput = originalInput.trim(),
            handle = handle,
            avatarUrl = extractor.avatars.maxByOrNull { it.height }?.url.orEmpty(),
            uploadsPlaylistId = ChannelInput.uploadsPlaylistId(id),
            metadataFetchedAt = System.currentTimeMillis(),
        )
    }

    private fun newPipePage(channel: Channel, page: Page?): VideoPage {
        val extractor = service().getChannelTabExtractorFromId(channel.id, ChannelTabs.VIDEOS)
        // v0.26.5 `YoutubeChannelTabExtractor.getPage(Page)` posts the opaque continuation body
        // directly and never reads the initial `jsonResponse` (no `assertPageFetched`), so a
        // continuation does not need the extra initial tab request. Only the first page calls
        // `fetchPage()`.
        val info = if (page == null) {
            extractor.fetchPage()
            extractor.initialPage
        } else {
            extractor.getPage(page)
        }
        val videos = info.items
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { StreamMapper.toVideo(it, channel.id, channel.title) }
        if (videos.isEmpty()) {
            // Never emit a cursor for an empty page: that is what would otherwise let a
            // misbehaving continuation loop forever.
            return VideoPage(videos = emptyList(), nextPage = null)
        }
        val next = if (info.hasNextPage()) info.nextPage else null
        return VideoPage(
            videos = videos,
            nextPage = next?.let { FeedCursorCodec.encodePage(it) },
        )
    }

    private fun service() = NewPipe.getService(SERVICE_ID)

    companion object {
        const val SERVICE_ID = 0
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "OmaTube/0.1"
        private const val CHANNEL_TIMEOUT_MS = 60_000L
        private const val PAGE_TIMEOUT_MS = 60_000L
        private const val STREAM_TIMEOUT_MS = 90_000L
        private const val SPONSOR_TIMEOUT_MS = 20_000L
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
        private val INIT_LOCK = Any()
    }
}
