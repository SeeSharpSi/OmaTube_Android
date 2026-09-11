package dev.omatube.app.data

import dev.omatube.app.model.Channel
import dev.omatube.app.model.LibrarySnapshot
import dev.omatube.app.model.Settings
import dev.omatube.app.model.Video
import kotlinx.coroutines.flow.Flow

interface LibraryRepository {
    val snapshot: Flow<LibrarySnapshot>

    suspend fun addCategory(name: String): Long

    suspend fun renameCategory(id: Long, name: String)

    suspend fun removeCategory(id: Long)

    suspend fun moveCategory(id: Long, toIndex: Int)

    suspend fun upsertChannel(channel: Channel)

    suspend fun removeChannel(id: String)

    suspend fun setChannelCategories(channelId: String, categoryIds: Set<Long>)

    suspend fun upsertVideos(videos: List<Video>)

    suspend fun replaceLive(channelId: String, videos: List<Video>)

    suspend fun recordPlayback(
        videoId: String,
        positionSeconds: Long,
        watchedDeltaSeconds: Long,
        newSession: Boolean,
    )

    suspend fun deleteHistory(id: Long)

    suspend fun clearHistory()

    suspend fun addWatchNext(videoId: String)

    suspend fun removeWatchNext(videoId: String)

    suspend fun moveWatchNext(videoId: String, toIndex: Int)

    suspend fun historyCursor(channelId: String): String?

    suspend fun setHistoryCursor(channelId: String, cursor: String?, complete: Boolean)

    suspend fun historyComplete(channelId: String): Boolean

    suspend fun canFetchHistory(): Boolean

    suspend fun pruneCache()

    suspend fun exportChannels(): String

    suspend fun exportCategories(): String

    suspend fun importJson(json: String): Int
}

interface SettingsStore {
    val settings: Flow<Settings>

    suspend fun update(transform: (Settings) -> Settings)
}
