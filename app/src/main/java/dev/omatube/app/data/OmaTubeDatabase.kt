package dev.omatube.app.data

import androidx.room.Database
import androidx.room.RoomDatabase

// Version 1 is the first Android schema. Desktop SQLite schema versions are
// intentionally unrelated; interoperability happens through the JSON formats.
// There is no destructive migration fallback.
@Database(
    entities = [
        CategoryEntity::class,
        ChannelEntity::class,
        ChannelCategoryCrossRef::class,
        VideoEntity::class,
        WatchTimeEntity::class,
        HistoryEntity::class,
        ChannelHistoryEntity::class,
        WatchNextEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class OmaTubeDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        const val DATABASE_NAME = DEFAULT_LIBRARY_DATABASE_NAME
    }
}
