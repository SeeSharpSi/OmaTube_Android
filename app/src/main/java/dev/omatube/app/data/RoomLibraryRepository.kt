package dev.omatube.app.data

import android.content.Context
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.omatube.app.model.Category
import dev.omatube.app.model.Channel
import dev.omatube.app.model.HistoryEntry
import dev.omatube.app.model.LibrarySnapshot
import dev.omatube.app.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

const val DEFAULT_LIBRARY_DATABASE_NAME = "omatube.sqlite3"

class RoomLibraryRepository(
    context: Context,
    private val inMemory: Boolean = false,
    private val databaseName: String = DEFAULT_LIBRARY_DATABASE_NAME,
) : LibraryRepository {

    private val database: OmaTubeDatabase = buildDatabase(context.applicationContext)

    private val dao: LibraryDao
        get() = database.libraryDao()

    @Volatile
    private var closed = false

    // Releases the Room database, including any in-memory or WAL temporary
    // files. Safe to call more than once; useful for tests and shutdown.
    fun close() {
        if (closed) return
        closed = true
        database.close()
    }

    // Room only reports a table as invalidated when it changes through Room,
    // which is every write this repository performs.
    override val snapshot: Flow<LibrarySnapshot> = callbackFlow {
        val observer = object : InvalidationTracker.Observer(OBSERVED_TABLES) {
            override fun onInvalidated(tables: Set<String>) {
                trySend(Unit)
            }
        }
        database.invalidationTracker.addObserver(observer)
        trySend(Unit)
        awaitClose { database.invalidationTracker.removeObserver(observer) }
    }
        .conflate()
        .map { database.withTransaction { loadSnapshot() } }
        .flowOn(Dispatchers.IO)

    // ---- Categories -----------------------------------------------------

    override suspend fun addCategory(name: String): Long {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Category name cannot be empty." }
        return database.withTransaction {
            if (dao.categoryIdByName(trimmed) != null) {
                throw IllegalStateException("Category '$trimmed' already exists.")
            }
            dao.insertCategory(CategoryEntity(name = trimmed, sortOrder = dao.nextCategorySortOrder()))
        }
    }

    override suspend fun renameCategory(id: Long, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Category name cannot be empty." }
        database.withTransaction {
            val existing = dao.categoryIdByName(trimmed)
            if (existing != null && existing != id) {
                throw IllegalStateException("Category '$trimmed' already exists.")
            }
            if (dao.renameCategory(id, trimmed) != 1) {
                throw NoSuchElementException("Category does not exist.")
            }
        }
    }

    override suspend fun removeCategory(id: Long) {
        dao.deleteCategory(id)
    }

    override suspend fun moveCategory(id: Long, toIndex: Int) {
        database.withTransaction {
            val ids = dao.categoryIds()
            val from = ids.indexOf(id)
            if (from < 0) throw NoSuchElementException("Category does not exist.")
            if (toIndex !in ids.indices) throw IndexOutOfBoundsException("Target index is out of range.")
            if (from == toIndex) return@withTransaction
            val reordered = ids.toMutableList().apply { add(toIndex, removeAt(from)) }
            reordered.forEachIndexed { index, categoryId ->
                dao.updateCategoryOrder(categoryId, index)
            }
        }
    }

    // ---- Channels -------------------------------------------------------

    override suspend fun upsertChannel(channel: Channel) {
        require(channel.id.isNotBlank()) { "Channel id is required." }
        require(channel.title.isNotBlank()) { "Channel title is required." }
        database.withTransaction {
            upsertChannelInternal(
                id = channel.id,
                title = channel.title,
                originalInput = channel.originalInput,
                handle = channel.handle,
                avatarUrl = channel.avatarUrl,
                uploadsPlaylistId = channel.uploadsPlaylistId,
                metadataFetchedAt = channel.metadataFetchedAt,
            )
        }
    }

    override suspend fun removeChannel(id: String) {
        require(id.isNotBlank()) { "Channel id is required." }
        dao.deleteChannel(id)
    }

    override suspend fun setChannelCategories(channelId: String, categoryIds: Set<Long>) {
        require(channelId.isNotBlank()) { "Channel id is required." }
        database.withTransaction {
            if (dao.channelExists(channelId) == 0) {
                throw NoSuchElementException("Channel does not exist.")
            }
            dao.deleteMembershipsForChannel(channelId)
            for (categoryId in categoryIds) {
                dao.insertMembership(ChannelCategoryCrossRef(categoryId = categoryId, channelId = channelId))
            }
        }
    }

    // ---- Videos ---------------------------------------------------------

    override suspend fun upsertVideos(videos: List<Video>) {
        if (videos.isEmpty()) return
        for (video in videos) {
            require(video.id.isNotBlank()) { "Video id is required." }
            require(video.channelId.isNotBlank()) { "Video channel id is required." }
        }
        database.withTransaction {
            for (video in videos) {
                insertVideoIfAbsent(video, video.channelId)
                dao.updateVideoMetadata(
                    id = video.id,
                    channelId = video.channelId,
                    title = video.title,
                    publishedAt = video.publishedAt,
                    durationSeconds = video.durationSeconds,
                    isLive = video.isLive,
                    isUpcoming = video.isUpcoming,
                    thumbnailUrl = video.thumbnailUrl,
                )
            }
        }
    }

    override suspend fun replaceLive(channelId: String, videos: List<Video>) {
        require(channelId.isNotBlank()) { "Channel id is required." }
        database.withTransaction {
            dao.clearLiveFlags(channelId)
            for (video in videos) {
                require(video.id.isNotBlank()) { "Video id is required." }
                insertVideoIfAbsent(video, channelId)
                dao.updateVideoWithLiveFlags(
                    id = video.id,
                    channelId = channelId,
                    title = video.title,
                    publishedAt = video.publishedAt,
                    durationSeconds = video.durationSeconds,
                    isLive = video.isLive,
                    isUpcoming = video.isUpcoming,
                    thumbnailUrl = video.thumbnailUrl,
                )
            }
        }
    }

    // ---- Playback recording --------------------------------------------

    override suspend fun recordPlayback(
        videoId: String,
        positionSeconds: Long,
        watchedDeltaSeconds: Long,
        newSession: Boolean,
    ) {
        require(videoId.isNotBlank()) { "Video id is required to record playback." }
        val timestamp = System.currentTimeMillis()
        database.withTransaction {
            dao.insertWatchTimeRow(WatchTimeEntity(videoId = videoId))
            dao.updateWatchTime(
                videoId = videoId,
                watchedDelta = max(0L, watchedDeltaSeconds),
                position = max(0L, positionSeconds),
                timestamp = timestamp,
                sessionIncrement = if (newSession) 1 else 0,
            )
            if (newSession) {
                val channelId = dao.channelIdForVideo(videoId)
                if (!channelId.isNullOrBlank()) {
                    dao.insertHistory(
                        HistoryEntity(datetime = timestamp, videoId = videoId, channelId = channelId),
                    )
                }
            }
        }
    }

    override suspend fun deleteHistory(id: Long) {
        dao.deleteHistory(id)
    }

    override suspend fun clearHistory() {
        dao.clearHistory()
    }

    // ---- Watch Next -----------------------------------------------------

    override suspend fun addWatchNext(videoId: String) {
        require(videoId.isNotBlank()) { "Video id is required for Watch Next." }
        database.withTransaction {
            if (dao.watchNextEntry(videoId) != null) return@withTransaction
            if (dao.videoExists(videoId) == null) {
                throw NoSuchElementException("Video is not in the local library.")
            }
            if (dao.watchNextCount() >= WATCH_NEXT_MAX_ITEMS) {
                throw IllegalStateException(
                    "Watch Next is full ($WATCH_NEXT_MAX_ITEMS). Remove something first.",
                )
            }
            dao.insertWatchNext(
                WatchNextEntity(
                    videoId = videoId,
                    position = dao.nextWatchNextPosition(),
                    addedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    override suspend fun removeWatchNext(videoId: String) {
        require(videoId.isNotBlank()) { "Video id is required for Watch Next." }
        database.withTransaction {
            dao.deleteWatchNext(videoId)
            val ids = dao.watchNextIds()
            ids.forEachIndexed { index, id -> dao.updateWatchNextPosition(id, index) }
        }
    }

    override suspend fun moveWatchNext(videoId: String, toIndex: Int) {
        require(videoId.isNotBlank()) { "Video id is required for Watch Next." }
        database.withTransaction {
            val ids = dao.watchNextIds()
            val from = ids.indexOf(videoId)
            if (from < 0) throw NoSuchElementException("Video is not in Watch Next.")
            if (toIndex !in ids.indices) throw IndexOutOfBoundsException("Target index is out of range.")
            if (from == toIndex) return@withTransaction
            val reordered = ids.toMutableList().apply { add(toIndex, removeAt(from)) }
            reordered.forEachIndexed { index, id -> dao.updateWatchNextPosition(id, index) }
        }
    }

    // ---- Channel history cursor ----------------------------------------

    override suspend fun historyCursor(channelId: String): String? {
        require(channelId.isNotBlank()) { "Channel id is required." }
        return dao.historyCursor(channelId)?.takeIf { it.isNotEmpty() }
    }

    override suspend fun setHistoryCursor(channelId: String, cursor: String?, complete: Boolean) {
        require(channelId.isNotBlank()) { "Channel id is required." }
        database.withTransaction {
            dao.insertHistoryState(ChannelHistoryEntity(channelId = channelId))
            dao.updateHistoryCursor(channelId, cursor.orEmpty(), complete)
        }
    }

    override suspend fun historyComplete(channelId: String): Boolean {
        require(channelId.isNotBlank()) { "Channel id is required." }
        return dao.historyComplete(channelId) ?: false
    }

    override suspend fun canFetchHistory(): Boolean {
        if (inMemory) return true
        return withContext(Dispatchers.IO) {
            databaseSizeBytes() < HISTORY_FETCH_DATABASE_BYTES
        }
    }

    // ---- Cache size management -----------------------------------------

    override suspend fun pruneCache() {
        if (inMemory) return
        withContext(Dispatchers.IO) {
            var batch = 16
            while (databaseSizeBytes() > MAX_DATABASE_BYTES) {
                val deleted = dao.deleteOldestVideos(batch)
                if (deleted == 0) break
                checkpointWal()
                vacuum()
                batch *= 2
            }
        }
    }

    // ---- Import/export --------------------------------------------------

    override suspend fun exportChannels(): String = database.withTransaction {
        val nameById = dao.categories().associate { it.id to it.name }
        val channels = dao.channels()
        val membershipsByChannel = dao.channelCategories().groupBy { it.channelId }
        val exported = channels.map { channel ->
            val categoryNames = membershipsByChannel[channel.id].orEmpty()
                .sortedBy { it.categoryId }
                .mapNotNull { nameById[it.categoryId] }
            ExportedChannel(
                id = channel.id,
                originalInput = channel.originalInput,
                handle = channel.handle,
                title = channel.title,
                avatarUrl = channel.avatarUrl,
                uploadsPlaylistId = channel.uploadsPlaylistId,
                metadataFetchedAt = channel.metadataFetchedAt,
                categoryNames = categoryNames,
            )
        }
        LibraryJson.encodeChannels(exported)
    }

    override suspend fun exportCategories(): String = database.withTransaction {
        val channels = dao.channels()
        val titleById = channels.associate { it.id to it.title }
        val membershipsByCategory = dao.channelCategories().groupBy { it.categoryId }
        val exported = dao.categories().map { category ->
            val channelIds = membershipsByCategory[category.id].orEmpty()
                .map { it.channelId }
                .filter { titleById.containsKey(it) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { titleById[it].orEmpty() })
            ExportedCategory(name = category.name, channelIds = channelIds)
        }
        LibraryJson.encodeCategories(exported)
    }

    override suspend fun importJson(json: String): Int {
        val parsed = LibraryJson.parse(json)
        return when (parsed) {
            is ParsedImport.Channels -> database.withTransaction {
                importChannels(parsed.records)
                parsed.records.size
            }
            is ParsedImport.Categories -> database.withTransaction {
                importCategories(parsed.records)
                parsed.records.size
            }
        }
    }

    private suspend fun importChannels(records: List<ImportChannel>) {
        for (record in records) {
            upsertChannelInternal(
                id = record.id,
                title = record.title,
                originalInput = record.originalInput,
                handle = record.handle,
                avatarUrl = record.avatarUrl,
                uploadsPlaylistId = record.uploadsPlaylistId,
                metadataFetchedAt = record.metadataFetchedAt,
            )
            for (name in record.categories) {
                val categoryId = dao.categoryIdByName(name)
                    ?: dao.insertCategory(CategoryEntity(name = name, sortOrder = dao.nextCategorySortOrder()))
                dao.insertMembership(ChannelCategoryCrossRef(categoryId = categoryId, channelId = record.id))
            }
        }
    }

    private suspend fun importCategories(records: List<ImportCategory>) {
        for (record in records) {
            val categoryId = dao.categoryIdByName(record.name)
                ?: dao.insertCategory(CategoryEntity(name = record.name, sortOrder = dao.nextCategorySortOrder()))
            for (channelId in record.channelIds) {
                if (dao.channelExists(channelId) > 0) {
                    dao.insertMembership(ChannelCategoryCrossRef(categoryId = categoryId, channelId = channelId))
                }
            }
        }
    }

    // ---- Compatible upsert building blocks ------------------------------

    // API 26-28 devices ship SQLite below 3.24, where INSERT ... ON CONFLICT
    // DO UPDATE is unavailable. Insert-or-ignore plus an explicit update is
    // portable; every caller wraps the pair in database.withTransaction.
    private suspend fun upsertChannelInternal(
        id: String,
        title: String,
        originalInput: String,
        handle: String,
        avatarUrl: String,
        uploadsPlaylistId: String,
        metadataFetchedAt: Long,
    ) {
        dao.insertChannel(
            ChannelEntity(
                id = id,
                title = title,
                originalInput = originalInput,
                handle = handle,
                avatarUrl = avatarUrl,
                uploadsPlaylistId = uploadsPlaylistId,
                metadataFetchedAt = metadataFetchedAt,
            ),
        )
        dao.updateChannel(
            id = id,
            title = title,
            originalInput = originalInput,
            handle = handle,
            avatarUrl = avatarUrl,
            uploadsPlaylistId = uploadsPlaylistId,
            metadataFetchedAt = metadataFetchedAt,
        )
    }

    private suspend fun insertVideoIfAbsent(video: Video, channelId: String) {
        dao.insertVideo(
            VideoEntity(
                id = video.id,
                channelId = channelId,
                title = video.title,
                publishedAt = video.publishedAt,
                durationSeconds = video.durationSeconds,
                isLive = video.isLive,
                isUpcoming = video.isUpcoming,
                thumbnailUrl = video.thumbnailUrl,
            ),
        )
    }

    // ---- Snapshot -------------------------------------------------------

    private suspend fun loadSnapshot(): LibrarySnapshot {
        val categories = dao.categories()
        val channels = dao.channels()
        val memberships = dao.channelCategories()
        val videoRows = dao.videos()
        val watchTimes = dao.watchTimes().associateBy { it.videoId }
        val queueRows = dao.watchNext()
        val historyRows = dao.history()

        val membershipsByChannel = memberships.groupBy({ it.channelId }, { it.categoryId })
        val channelTitles = channels.associate { it.id to it.title }
        val knownChannelIds = channels.mapTo(HashSet()) { it.id }
        val queuePositionByVideo = queueRows.associate { it.videoId to it.position }

        val videos = videoRows.map { row ->
            row.toDomain(
                channelTitle = channelTitles[row.channelId].orEmpty(),
                stats = watchTimes[row.id],
                queuePosition = queuePositionByVideo[row.id] ?: -1,
            )
        }
        val videosById = videos.associateBy { it.id }

        val history = historyRows.mapNotNull { row ->
            if (row.channelId !in knownChannelIds) return@mapNotNull null
            val video = videosById[row.videoId] ?: return@mapNotNull null
            HistoryEntry(id = row.id, video = video, watchedAt = row.datetime)
        }

        val watchNext = queueRows.mapNotNull { row ->
            videosById[row.videoId]?.copy(queuePosition = row.position)
        }

        return LibrarySnapshot(
            categories = categories.map { Category(id = it.id, name = it.name, sortOrder = it.sortOrder) },
            channels = channels.map { channel ->
                Channel(
                    id = channel.id,
                    title = channel.title,
                    originalInput = channel.originalInput,
                    handle = channel.handle,
                    avatarUrl = channel.avatarUrl,
                    uploadsPlaylistId = channel.uploadsPlaylistId,
                    metadataFetchedAt = channel.metadataFetchedAt,
                    categoryIds = membershipsByChannel[channel.id]?.toSet() ?: emptySet(),
                )
            },
            videos = videos,
            history = history,
            watchNext = watchNext,
        )
    }

    private fun VideoEntity.toDomain(
        channelTitle: String,
        stats: WatchTimeEntity?,
        queuePosition: Int,
    ): Video = Video(
        id = id,
        channelId = channelId,
        title = title,
        channelTitle = channelTitle,
        publishedAt = publishedAt,
        durationSeconds = durationSeconds,
        isLive = isLive,
        isUpcoming = isUpcoming,
        thumbnailUrl = thumbnailUrl,
        watchedSeconds = stats?.watchedSeconds ?: 0L,
        lastPositionSeconds = stats?.lastPositionSeconds ?: 0L,
        watchCount = stats?.watchCount ?: 0,
        lastWatchedAt = stats?.lastWatchedAt ?: 0L,
        queuePosition = queuePosition,
    )

    // ---- Storage helpers ------------------------------------------------

    private fun buildDatabase(context: Context): OmaTubeDatabase {
        val callback = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }
        return if (inMemory) {
            Room.inMemoryDatabaseBuilder(context, OmaTubeDatabase::class.java)
                .addCallback(callback)
                .build()
        } else {
            Room.databaseBuilder(context, OmaTubeDatabase::class.java, databaseName)
                .addCallback(callback)
                .build()
        }
    }

    private fun databaseSizeBytes(): Long {
        if (inMemory) return 0
        val path = try {
            database.openHelper.writableDatabase.path
        } catch (exception: RuntimeException) {
            return 0
        } ?: return 0

        val pageCount = readPragmaLong("PRAGMA page_count")
        val pageSize = readPragmaLong("PRAGMA page_size")
        val logicalSize = if (pageCount > 0 && pageSize > 0) pageCount * pageSize else 0
        val mainSize = File(path).length()
        val walSize = File("$path-wal").length()
        return maxOf(logicalSize, mainSize) + walSize
    }

    private fun readPragmaLong(statement: String): Long = try {
        database.openHelper.writableDatabase.query(statement).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    } catch (exception: RuntimeException) {
        0L
    }

    private fun checkpointWal() {
        if (inMemory) return
        try {
            database.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
        } catch (exception: RuntimeException) {
            // A failed checkpoint must not abort the prune loop.
        }
    }

    private fun vacuum() {
        if (inMemory) return
        try {
            database.openHelper.writableDatabase.execSQL("VACUUM")
        } catch (exception: RuntimeException) {
            // VACUUM is best-effort; deletion still reclaims space on next open.
        }
    }

    companion object {
        // Cached history grows without age limits until the database reaches
        // the maximum; then the oldest videos are pruned first in, first out.
        const val MAX_DATABASE_BYTES: Long = 10L * 1024 * 1024
        // Deeper history fetching is only allowed below this soft limit so a
        // prune pass is not immediately undone by the next page load.
        const val HISTORY_FETCH_DATABASE_BYTES: Long = 9L * 1024 * 1024
        const val WATCH_NEXT_MAX_ITEMS: Int = 25

        private val OBSERVED_TABLES = arrayOf(
            "categories",
            "channels",
            "category_channels",
            "videos",
            "video_watch_time",
            "history",
            "channel_history",
            "watch_next",
        )
    }
}
