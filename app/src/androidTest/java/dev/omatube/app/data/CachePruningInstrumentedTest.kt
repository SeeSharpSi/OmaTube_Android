package dev.omatube.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.omatube.app.model.Channel
import dev.omatube.app.model.Video
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

// Exercises the non-memory cache pruning path against real on-disk SQLite,
// using an isolated database in the app private database directory. Deletes
// its own database files afterward so no test data lingers.
@RunWith(AndroidJUnit4::class)
class CachePruningInstrumentedTest {

    @Test
    fun pruningOnDiskCacheDeletesOldestUnderBoundAndRetainsStats() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val databaseName = "prune-it-${UUID.randomUUID()}"
        val repository = RoomLibraryRepository(context, inMemory = false, databaseName = databaseName)
        try {
            repository.upsertChannel(Channel(id = "c", title = "Channel", uploadsPlaylistId = "u"))
            repository.upsertVideos(
                listOf(Video(id = "oldest", channelId = "c", title = "oldest", publishedAt = 0, durationSeconds = 10)),
            )
            repository.recordPlayback("oldest", positionSeconds = 5, watchedDeltaSeconds = 5, newSession = true)
            repository.addWatchNext("oldest")
            repository.setHistoryCursor("c", "TOKEN", complete = false)

            // ~15 MiB of metadata spreads past the 10 MiB hard cap; repeated
            // titles keep each row large so one prune batch restores headroom.
            val filler = String(CharArray(300_000) { 'x' })
            repository.upsertVideos(
                (1..50).map { index ->
                    Video(
                        id = "v$index",
                        channelId = "c",
                        title = "$filler-$index",
                        publishedAt = index.toLong(),
                    )
                },
            )

            assertFalse(repository.canFetchHistory())

            repository.pruneCache()

            assertTrue(repository.canFetchHistory())
            val afterPrune = repository.snapshot.first()
            assertTrue(afterPrune.videos.none { it.id == "oldest" })
            assertTrue(afterPrune.history.isEmpty())
            assertEquals("TOKEN", repository.historyCursor("c"))

            // Watch statistics and history rows have no foreign key, so they
            // survive the cache delete and rejoin once the video returns.
            repository.upsertVideos(
                listOf(Video(id = "oldest", channelId = "c", title = "oldest", publishedAt = 0, durationSeconds = 10)),
            )
            val restored = repository.snapshot.first()
            val joined = restored.videos.first { it.id == "oldest" }
            assertEquals(5L, joined.watchedSeconds)
            assertEquals(5L, joined.lastPositionSeconds)
            assertEquals(1, joined.watchCount)
            assertEquals(1, restored.history.size)
        } finally {
            repository.close()
            val databasePath = context.getDatabasePath(databaseName)
            listOf(
                databasePath,
                File(databasePath.path + "-wal"),
                File(databasePath.path + "-shm"),
                File(databasePath.path + "-journal"),
            ).forEach { it.delete() }
        }
    }
}
