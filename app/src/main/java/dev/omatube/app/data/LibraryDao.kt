package dev.omatube.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface LibraryDao {

    // ---- Snapshot reads -------------------------------------------------

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    suspend fun categories(): List<CategoryEntity>

    @Query("SELECT * FROM channels ORDER BY title COLLATE NOCASE ASC")
    suspend fun channels(): List<ChannelEntity>

    @Query("SELECT * FROM category_channels")
    suspend fun channelCategories(): List<ChannelCategoryCrossRef>

    @Query("SELECT * FROM videos ORDER BY publishedAt DESC, id DESC")
    suspend fun videos(): List<VideoEntity>

    @Query("SELECT * FROM video_watch_time")
    suspend fun watchTimes(): List<WatchTimeEntity>

    @Query("SELECT * FROM watch_next ORDER BY position ASC, addedAt ASC, videoId ASC")
    suspend fun watchNext(): List<WatchNextEntity>

    @Query("SELECT * FROM history ORDER BY datetime DESC, id DESC")
    suspend fun history(): List<HistoryEntity>

    // ---- Categories -----------------------------------------------------

    @Query("SELECT COALESCE(MAX(sortOrder) + 1, 0) FROM categories")
    suspend fun nextCategorySortOrder(): Int

    @Query("SELECT id FROM categories WHERE name = :name LIMIT 1")
    suspend fun categoryIdByName(name: String): Long?

    @Query("UPDATE categories SET name = :name WHERE id = :id")
    suspend fun renameCategory(id: Long, name: String): Int

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: Long): Int

    @Query("SELECT id FROM categories ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    suspend fun categoryIds(): List<Long>

    @Query("UPDATE categories SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateCategoryOrder(id: Long, sortOrder: Int)

    @Insert
    suspend fun insertCategory(entity: CategoryEntity): Long

    // ---- Channels -------------------------------------------------------

    // INSERT ... ON CONFLICT ... DO UPDATE needs SQLite 3.24+, which is not
    // available on all minSdk 26 devices. Insert-or-ignore plus an explicit
    // update is portable and is always called inside a repository transaction.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChannel(entity: ChannelEntity): Long

    @Query(
        "UPDATE channels SET title = :title, originalInput = :originalInput, handle = :handle, " +
            "avatarUrl = :avatarUrl, uploadsPlaylistId = :uploadsPlaylistId, " +
            "metadataFetchedAt = :metadataFetchedAt WHERE id = :id",
    )
    suspend fun updateChannel(
        id: String,
        title: String,
        originalInput: String,
        handle: String,
        avatarUrl: String,
        uploadsPlaylistId: String,
        metadataFetchedAt: Long,
    ): Int

    @Query("DELETE FROM channels WHERE id = :channelId")
    suspend fun deleteChannel(channelId: String): Int

    @Query("SELECT COUNT(*) FROM channels WHERE id = :channelId")
    suspend fun channelExists(channelId: String): Int

    @Query("DELETE FROM category_channels WHERE channelId = :channelId")
    suspend fun deleteMembershipsForChannel(channelId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMembership(ref: ChannelCategoryCrossRef)

    // ---- Videos ---------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVideo(entity: VideoEntity): Long

    // Metadata refresh must not erase live state learned from a more complete
    // source. A true flag is accepted; a false flag from an incomplete Atom
    // feed is ignored. A confirmed live flag also clears a stale upcoming
    // flag. Duration updates only when known.
    @Query(
        "UPDATE videos SET channelId = :channelId, title = :title, publishedAt = :publishedAt, " +
            "thumbnailUrl = :thumbnailUrl, " +
            "durationSeconds = CASE WHEN :durationSeconds < 0 THEN durationSeconds ELSE :durationSeconds END, " +
            "isLive = CASE WHEN :isLive = 1 THEN 1 ELSE isLive END, " +
            "isUpcoming = CASE WHEN :isUpcoming = 1 THEN 1 WHEN :isLive = 1 THEN 0 ELSE isUpcoming END " +
            "WHERE id = :id",
    )
    suspend fun updateVideoMetadata(
        id: String,
        channelId: String,
        title: String,
        publishedAt: Long,
        durationSeconds: Long,
        isLive: Boolean,
        isUpcoming: Boolean,
        thumbnailUrl: String,
    ): Int

    // Successful replaceLive owns the live flags: it may set or clear them.
    @Query(
        "UPDATE videos SET channelId = :channelId, title = :title, publishedAt = :publishedAt, " +
            "thumbnailUrl = :thumbnailUrl, " +
            "durationSeconds = CASE WHEN :durationSeconds < 0 THEN durationSeconds ELSE :durationSeconds END, " +
            "isLive = :isLive, isUpcoming = :isUpcoming WHERE id = :id",
    )
    suspend fun updateVideoWithLiveFlags(
        id: String,
        channelId: String,
        title: String,
        publishedAt: Long,
        durationSeconds: Long,
        isLive: Boolean,
        isUpcoming: Boolean,
        thumbnailUrl: String,
    ): Int

    @Query("UPDATE videos SET isLive = 0, isUpcoming = 0 WHERE channelId = :channelId")
    suspend fun clearLiveFlags(channelId: String)

    @Query("SELECT channelId FROM videos WHERE id = :videoId")
    suspend fun channelIdForVideo(videoId: String): String?

    @Query("SELECT 1 FROM videos WHERE id = :videoId")
    suspend fun videoExists(videoId: String): Int?

    @Query(
        "DELETE FROM videos WHERE id IN (" +
            "SELECT id FROM videos ORDER BY publishedAt ASC, id ASC LIMIT :limit)",
    )
    suspend fun deleteOldestVideos(limit: Int): Int

    // ---- Watch statistics and history -----------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWatchTimeRow(entity: WatchTimeEntity): Long

    @Query(
        "UPDATE video_watch_time SET watchedSeconds = watchedSeconds + :watchedDelta, " +
            "lastPositionSeconds = :position, lastWatchedAt = :timestamp, " +
            "watchCount = watchCount + :sessionIncrement WHERE videoId = :videoId",
    )
    suspend fun updateWatchTime(
        videoId: String,
        watchedDelta: Long,
        position: Long,
        timestamp: Long,
        sessionIncrement: Int,
    )

    @Insert
    suspend fun insertHistory(entity: HistoryEntity): Long

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteHistory(id: Long): Int

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    // ---- Channel history cursor -----------------------------------------

    @Query("SELECT nextPageToken FROM channel_history WHERE channelId = :channelId")
    suspend fun historyCursor(channelId: String): String?

    @Query("SELECT historyComplete FROM channel_history WHERE channelId = :channelId")
    suspend fun historyComplete(channelId: String): Boolean?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHistoryState(entity: ChannelHistoryEntity): Long

    @Query(
        "UPDATE channel_history SET nextPageToken = :cursor, historyComplete = :complete " +
            "WHERE channelId = :channelId",
    )
    suspend fun updateHistoryCursor(channelId: String, cursor: String, complete: Boolean): Int

    // ---- Watch Next -----------------------------------------------------

    @Query("SELECT videoId FROM watch_next WHERE videoId = :videoId")
    suspend fun watchNextEntry(videoId: String): String?

    @Query("SELECT COUNT(*) FROM watch_next")
    suspend fun watchNextCount(): Int

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM watch_next")
    suspend fun nextWatchNextPosition(): Int

    @Query("SELECT videoId FROM watch_next ORDER BY position ASC, addedAt ASC, videoId ASC")
    suspend fun watchNextIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWatchNext(entity: WatchNextEntity)

    @Query("DELETE FROM watch_next WHERE videoId = :videoId")
    suspend fun deleteWatchNext(videoId: String): Int

    @Query("UPDATE watch_next SET position = :position WHERE videoId = :videoId")
    suspend fun updateWatchNextPosition(videoId: String, position: Int)
}
