package dev.omatube.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)],
)
internal data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "channels",
    indices = [Index(value = ["title"])],
)
internal data class ChannelEntity(
    @PrimaryKey val id: String,
    val title: String,
    val originalInput: String = "",
    val handle: String = "",
    val avatarUrl: String = "",
    val uploadsPlaylistId: String = "",
    val metadataFetchedAt: Long = 0,
)

@Entity(
    tableName = "category_channels",
    primaryKeys = ["categoryId", "channelId"],
    indices = [Index(value = ["channelId"])],
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ChannelCategoryCrossRef(
    val categoryId: Long,
    val channelId: String,
)

@Entity(
    tableName = "videos",
    indices = [Index(value = ["channelId"]), Index(value = ["publishedAt"])],
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class VideoEntity(
    @PrimaryKey val id: String,
    val channelId: String,
    val title: String,
    val publishedAt: Long = 0,
    val durationSeconds: Long = -1,
    val isLive: Boolean = false,
    val isUpcoming: Boolean = false,
    val thumbnailUrl: String = "",
)

// Deliberately has no foreign key to videos: watch statistics survive both a
// metadata cache prune and removal of the channel that produced the video.
@Entity(tableName = "video_watch_time")
internal data class WatchTimeEntity(
    @PrimaryKey val videoId: String,
    val watchedSeconds: Long = 0,
    val lastPositionSeconds: Long = 0,
    val watchCount: Int = 0,
    val lastWatchedAt: Long = 0,
)

// No foreign key: repeated sessions are kept even when the video or channel
// metadata is later removed. Snapshot joins omit rows whose joins are gone.
@Entity(
    tableName = "history",
    indices = [Index(value = ["datetime"]), Index(value = ["videoId"])],
)
internal data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val datetime: Long,
    val videoId: String,
    val channelId: String,
)

@Entity(
    tableName = "channel_history",
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ChannelHistoryEntity(
    @PrimaryKey val channelId: String,
    val nextPageToken: String = "",
    val historyComplete: Boolean = false,
)

@Entity(
    tableName = "watch_next",
    indices = [Index(value = ["position"])],
    foreignKeys = [
        ForeignKey(
            entity = VideoEntity::class,
            parentColumns = ["id"],
            childColumns = ["videoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class WatchNextEntity(
    @PrimaryKey val videoId: String,
    val position: Int = 0,
    val addedAt: Long = 0,
)
