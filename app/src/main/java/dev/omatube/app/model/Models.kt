package dev.omatube.app.model

data class Category(
    val id: Long,
    val name: String,
    val sortOrder: Int = 0,
)

data class Channel(
    val id: String,
    val title: String,
    val originalInput: String = "",
    val handle: String = "",
    val avatarUrl: String = "",
    val uploadsPlaylistId: String = "",
    val metadataFetchedAt: Long = 0,
    val categoryIds: Set<Long> = emptySet(),
)

data class Video(
    val id: String,
    val channelId: String,
    val title: String,
    val channelTitle: String = "",
    val publishedAt: Long = 0,
    val durationSeconds: Long = -1,
    val isLive: Boolean = false,
    val isUpcoming: Boolean = false,
    val thumbnailUrl: String = "",
    val watchedSeconds: Long = 0,
    val lastPositionSeconds: Long = 0,
    val watchCount: Int = 0,
    val lastWatchedAt: Long = 0,
    val queuePosition: Int = -1,
)

data class HistoryEntry(
    val id: Long,
    val video: Video,
    val watchedAt: Long,
)

data class LibrarySnapshot(
    val categories: List<Category> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val videos: List<Video> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val watchNext: List<Video> = emptyList(),
)

enum class SponsorAction {
    NONE,
    MANUAL,
    AUTO,
}

data class SponsorSegment(
    val startSeconds: Double,
    val endSeconds: Double,
    val category: String,
)

data class TranscriptWord(
    val text: String,
    val startMs: Long,
)

data class TranscriptCue(
    val startMs: Long,
    val endMs: Long,
    val words: List<TranscriptWord>,
) {
    val text: String
        get() = words.joinToString(" ") { it.text }
}

data class Settings(
    val themeId: String = "default",
    val simpleUi: Boolean = false,
    val shortVideoCutoffMinutes: Int = 3,
    val wifiMaximumVideoHeight: Int = 0,
    val dataMaximumVideoHeight: Int = 0,
    val lastUsedVideoHeight: Int = 0,
    val playbackVolume: Int = 100,
    val sponsorBlockEnabled: Boolean = false,
    val sponsorActions: Map<String, SponsorAction> = emptyMap(),
    val apiKey: String = "",
    val rememberApiKey: Boolean = false,
    val videoQualityOverrides: Map<String, Int> = emptyMap(),
)

data class VideoPage(
    val videos: List<Video>,
    val nextPage: String? = null,
)
