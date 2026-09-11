package dev.omatube.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.omatube.app.model.Channel
import dev.omatube.app.model.Video
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Runs against the real device SQLite engine through Room. This is the test
// that proves the insert-or-ignore plus update strategy works on a minSdk 26
// device, where INSERT ... ON CONFLICT DO UPDATE is not available. The
// verifier runs it on an API 36 emulator now and on older APIs separately.
@RunWith(AndroidJUnit4::class)
class RoomLibraryRepositoryInstrumentedTest {

    private lateinit var repository: RoomLibraryRepository

    @Before
    fun setUp() {
        repository = RoomLibraryRepository(ApplicationProvider.getApplicationContext(), inMemory = true)
    }

    @After
    fun tearDown() {
        repository.close()
    }

    private fun channel(id: String): Channel = Channel(
        id = id,
        title = "Channel $id",
        originalInput = "input-$id",
        handle = "@$id",
        uploadsPlaylistId = "uploads-$id",
    )

    private fun video(
        id: String,
        channelId: String,
        title: String = "Video $id",
        publishedAt: Long = 100,
        durationSeconds: Long = -1,
        isLive: Boolean = false,
        isUpcoming: Boolean = false,
    ): Video = Video(
        id = id,
        channelId = channelId,
        title = title,
        publishedAt = publishedAt,
        durationSeconds = durationSeconds,
        isLive = isLive,
        isUpcoming = isUpcoming,
    )

    @Test
    fun compatibleUpsertsPreserveStatsQueueAndLiveSemantics() = runBlocking {
        repository.upsertChannel(channel("c"))
        repository.upsertVideos(listOf(video("v", "c", title = "First")))
        repository.recordPlayback("v", positionSeconds = 15, watchedDeltaSeconds = 15, newSession = true)
        repository.addWatchNext("v")
        repository.setHistoryCursor("c", "TOKEN", complete = false)

        repository.upsertVideos(listOf(video("v", "c", title = "Second", durationSeconds = 300)))
        repository.upsertVideos(listOf(video("v", "c", title = "Third", durationSeconds = -1)))

        var snapshot = repository.snapshot.first()
        var joined = snapshot.videos.first { it.id == "v" }
        assertEquals("Third", joined.title)
        assertEquals(300L, joined.durationSeconds)
        assertEquals(15L, joined.watchedSeconds)
        assertEquals(1, joined.watchCount)
        assertEquals(listOf("v"), snapshot.watchNext.map { it.id })
        assertEquals(0, snapshot.watchNext.first().queuePosition)
        assertEquals("TOKEN", repository.historyCursor("c"))

        // Positive live information from a metadata page is accepted...
        repository.upsertVideos(listOf(video("v", "c", title = "Third", isLive = true)))
        assertTrue(repository.snapshot.first().videos.first { it.id == "v" }.isLive)

        // ...but an incomplete page reporting false must not clear it.
        repository.upsertVideos(listOf(video("v", "c", title = "Third", isLive = false)))
        assertTrue(repository.snapshot.first().videos.first { it.id == "v" }.isLive)

        // Only a successful replaceLive clears the last known live state.
        repository.replaceLive("c", listOf(video("v", "c", title = "Third", isUpcoming = true)))
        snapshot = repository.snapshot.first()
        joined = snapshot.videos.first { it.id == "v" }
        assertFalse(joined.isLive)
        assertTrue(joined.isUpcoming)

        repository.replaceLive("c", emptyList())
        assertTrue(repository.snapshot.first().videos.none { it.isLive || it.isUpcoming })

        // Watch statistics survived every metadata and live write.
        assertEquals(15L, repository.snapshot.first().videos.first { it.id == "v" }.watchedSeconds)
        assertEquals(1, repository.snapshot.first().videos.first { it.id == "v" }.watchCount)
    }
}
