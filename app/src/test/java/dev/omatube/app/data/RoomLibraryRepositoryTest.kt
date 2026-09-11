package dev.omatube.app.data

import androidx.test.core.app.ApplicationProvider
import dev.omatube.app.model.Channel
import dev.omatube.app.model.Video
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class RoomLibraryRepositoryTest {

    private lateinit var repository: RoomLibraryRepository
    private val openRepositories = mutableListOf<RoomLibraryRepository>()

    @Before
    fun setUp() {
        repository = newRepository()
    }

    @After
    fun tearDown() {
        openRepositories.forEach(RoomLibraryRepository::close)
        openRepositories.clear()
    }

    private fun newRepository(): RoomLibraryRepository =
        RoomLibraryRepository(ApplicationProvider.getApplicationContext(), inMemory = true)
            .also { openRepositories.add(it) }

    private fun channel(
        id: String,
        title: String,
        uploadsPlaylistId: String = "uploads-$id",
    ): Channel = Channel(
        id = id,
        title = title,
        originalInput = "input-$id",
        handle = "@$id",
        avatarUrl = "https://example.test/$id.png",
        uploadsPlaylistId = uploadsPlaylistId,
        metadataFetchedAt = 1_700_000_000_000L,
    )

    private fun video(
        id: String,
        channelId: String,
        publishedAt: Long,
        title: String = "video-$id",
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
    fun categoriesAreSortedAndCanBeReordered() = runBlocking {
        repository.addCategory("Alpha")
        repository.addCategory("beta")
        val gamma = repository.addCategory("Gamma")

        assertEquals(
            listOf("Alpha", "beta", "Gamma"),
            repository.snapshot.first().categories.map { it.name },
        )

        repository.moveCategory(gamma, 0)
        assertEquals(
            listOf("Gamma", "Alpha", "beta"),
            repository.snapshot.first().categories.map { it.name },
        )
    }

    @Test
    fun duplicateCategoryNameIsRejected(): Unit = runBlocking {
        repository.addCategory("News")
        expectFailure(IllegalStateException::class.java) { repository.addCategory("News") }
        expectFailure(IllegalArgumentException::class.java) { repository.addCategory("   ") }
    }

    @Test
    fun channelOrderingIsCaseInsensitiveAndUpsertKeepsCategories() = runBlocking {
        val categoryId = repository.addCategory("Tech")
        repository.upsertChannel(channel("b", "banana"))
        repository.upsertChannel(channel("a", "Apple"))
        repository.setChannelCategories("b", setOf(categoryId))

        assertEquals(
            listOf("Apple", "banana"),
            repository.snapshot.first().channels.map { it.title },
        )

        repository.upsertChannel(channel("b", "Banana"))
        val refreshed = repository.snapshot.first().channels.first { it.id == "b" }
        assertEquals("Banana", refreshed.title)
        assertEquals(setOf(categoryId), refreshed.categoryIds)
    }

    @Test
    fun videosArePublishedDescendingAndUnknownDurationIsPreserved() = runBlocking {
        repository.upsertChannel(channel("c", "Channel"))
        repository.upsertVideos(
            listOf(
                video("old", "c", 100, durationSeconds = -1),
                video("new", "c", 200, durationSeconds = 100),
            ),
        )
        assertEquals(
            listOf("new", "old"),
            repository.snapshot.first().videos.map { it.id },
        )

        repository.upsertVideos(listOf(video("old", "c", 100, durationSeconds = 300)))
        assertEquals(300L, repository.snapshot.first().videos.first { it.id == "old" }.durationSeconds)

        repository.upsertVideos(listOf(video("old", "c", 100, durationSeconds = -1)))
        assertEquals(300L, repository.snapshot.first().videos.first { it.id == "old" }.durationSeconds)
    }

    @Test
    fun upsertVideosPreservesWatchStatsAndQueue() = runBlocking {
        repository.upsertChannel(channel("c", "Channel"))
        repository.upsertVideos(listOf(video("v", "c", 100)))
        repository.recordPlayback("v", positionSeconds = 30, watchedDeltaSeconds = 30, newSession = true)
        repository.addWatchNext("v")

        repository.upsertVideos(listOf(video("v", "c", 100, title = "Renamed")))

        val snapshot = repository.snapshot.first()
        val joined = snapshot.videos.first { it.id == "v" }
        assertEquals("Renamed", joined.title)
        assertEquals(30L, joined.watchedSeconds)
        assertEquals(1, joined.watchCount)
        assertEquals(listOf("v"), snapshot.watchNext.map { it.id })
        assertEquals(0, snapshot.watchNext.first().queuePosition)
    }

    @Test
    fun playbackAccumulatesAndKeepsRepeatedSessions() = runBlocking {
        repository.upsertChannel(channel("c", "Channel"))
        repository.upsertVideos(listOf(video("v", "c", 100)))

        repository.recordPlayback("v", positionSeconds = 10, watchedDeltaSeconds = 5, newSession = true)
        repository.recordPlayback("v", positionSeconds = 20, watchedDeltaSeconds = 5, newSession = false)
        repository.recordPlayback("v", positionSeconds = 40, watchedDeltaSeconds = 1, newSession = true)

        val snapshot = repository.snapshot.first()
        val joined = snapshot.videos.first { it.id == "v" }
        assertEquals(11L, joined.watchedSeconds)
        assertEquals(40L, joined.lastPositionSeconds)
        assertEquals(2, joined.watchCount)
        assertEquals(2, snapshot.history.size)
        assertEquals(listOf("v", "v"), snapshot.history.map { it.video.id })
    }

    @Test
    fun replaceLiveSetsAndClearsFlagsOnSuccess() = runBlocking {
        repository.upsertChannel(channel("c", "Channel"))
        repository.upsertVideos(listOf(video("v1", "c", 100), video("v2", "c", 200)))

        repository.replaceLive(
            "c",
            listOf(video("v1", "c", 100).copy(isLive = true)),
        )
        var snapshot = repository.snapshot.first()
        assertTrue(snapshot.videos.first { it.id == "v1" }.isLive)
        assertFalse(snapshot.videos.first { it.id == "v2" }.isLive)

        repository.replaceLive("c", emptyList())
        snapshot = repository.snapshot.first()
        assertTrue(snapshot.videos.none { it.isLive || it.isUpcoming })
    }

    @Test
    fun newVideoRetainsSuppliedLiveFlags() = runBlocking {
        repository.upsertChannel(channel("c", "Channel"))
        repository.upsertVideos(
            listOf(
                video("live", "c", 100, isLive = true),
                video("upcoming", "c", 200, isUpcoming = true),
                video("plain", "c", 300),
            ),
        )

        val snapshot = repository.snapshot.first()
        assertTrue(snapshot.videos.first { it.id == "live" }.isLive)
        assertFalse(snapshot.videos.first { it.id == "live" }.isUpcoming)
        assertTrue(snapshot.videos.first { it.id == "upcoming" }.isUpcoming)
        assertFalse(snapshot.videos.first { it.id == "upcoming" }.isLive)
        assertFalse(snapshot.videos.first { it.id == "plain" }.isLive)
        assertFalse(snapshot.videos.first { it.id == "plain" }.isUpcoming)
    }

    @Test
    fun metadataOnlyAdvancesLiveFlagsAndReplaceLiveClears() = runBlocking {
        repository.upsertChannel(channel("c", "Channel"))
        repository.upsertVideos(listOf(video("v", "c", 100), video("u", "c", 200)))

        // Incomplete Atom metadata reporting false must not invent live state.
        repository.upsertVideos(listOf(video("v", "c", 100), video("u", "c", 200)))
        var snapshot = repository.snapshot.first()
        assertFalse(snapshot.videos.first { it.id == "v" }.isLive)
        assertFalse(snapshot.videos.first { it.id == "u" }.isUpcoming)

        // Positive information from a partial page is still accepted.
        repository.upsertVideos(
            listOf(
                video("v", "c", 100, isLive = true),
                video("u", "c", 200, isUpcoming = true),
            ),
        )
        snapshot = repository.snapshot.first()
        assertTrue(snapshot.videos.first { it.id == "v" }.isLive)
        assertTrue(snapshot.videos.first { it.id == "u" }.isUpcoming)

        // A later incomplete page reporting false must not clear the flag.
        repository.upsertVideos(listOf(video("v", "c", 100), video("u", "c", 200)))
        snapshot = repository.snapshot.first()
        assertTrue(snapshot.videos.first { it.id == "v" }.isLive)
        assertTrue(snapshot.videos.first { it.id == "u" }.isUpcoming)

        // Only a successful replaceLive may clear the last known state.
        repository.replaceLive("c", emptyList())
        snapshot = repository.snapshot.first()
        assertTrue(snapshot.videos.none { it.isLive || it.isUpcoming })
    }

    @Test
    fun watchNextIsCappedDeduplicatedAndReorderable() = runBlocking {
        repository.upsertChannel(channel("c", "Channel"))
        val videos = (1..30).map { video("v$it", "c", it.toLong()) }
        repository.upsertVideos(videos)

        for (index in 1..RoomLibraryRepository.WATCH_NEXT_MAX_ITEMS) {
            repository.addWatchNext("v$index")
        }
        val error = expectFailure(IllegalStateException::class.java) { repository.addWatchNext("v26") }
        assertTrue(error.message!!.contains("full"))

        repository.addWatchNext("v1")
        assertEquals(RoomLibraryRepository.WATCH_NEXT_MAX_ITEMS, repository.snapshot.first().watchNext.size)

        repository.moveWatchNext("v1", 24)
        val ordered = repository.snapshot.first().watchNext.map { it.id }
        assertEquals("v1", ordered.last())
        assertEquals(24, repository.snapshot.first().watchNext.last().queuePosition)

        repository.removeWatchNext("v2")
        val afterRemoval = repository.snapshot.first().watchNext
        assertEquals(24, afterRemoval.size)
        assertEquals(afterRemoval.indices.toList(), afterRemoval.map { it.queuePosition })
    }

    @Test
    fun removingChannelRetainsWatchStats() = runBlocking {
        repository.upsertChannel(channel("c", "Channel"))
        repository.upsertVideos(listOf(video("v", "c", 100)))
        repository.recordPlayback("v", positionSeconds = 50, watchedDeltaSeconds = 50, newSession = true)

        repository.removeChannel("c")
        assertTrue(repository.snapshot.first().videos.isEmpty())

        repository.upsertChannel(channel("c", "Channel"))
        repository.upsertVideos(listOf(video("v", "c", 100)))
        val joined = repository.snapshot.first().videos.first { it.id == "v" }
        assertEquals(50L, joined.watchedSeconds)
        assertEquals(1, joined.watchCount)
    }

    @Test
    fun historyCursorPersistsAndMemoryNeverPrunes() = runBlocking {
        repository.upsertChannel(channel("c", "Channel"))
        assertNull(repository.historyCursor("c"))
        assertFalse(repository.historyComplete("c"))

        repository.setHistoryCursor("c", "TOKEN", complete = false)
        assertEquals("TOKEN", repository.historyCursor("c"))
        assertFalse(repository.historyComplete("c"))

        repository.setHistoryCursor("c", null, complete = true)
        assertNull(repository.historyCursor("c"))
        assertTrue(repository.historyComplete("c"))
        assertTrue(repository.canFetchHistory())
        repository.pruneCache()
    }

    @Test
    fun jsonImportExportRoundTripsChannelsAndCategories() = runBlocking {
        val categoryId = repository.addCategory("Tech")
        repository.upsertChannel(channel("c", "Channel", uploadsPlaylistId = "PL1"))
        repository.setChannelCategories("c", setOf(categoryId))
        repository.upsertVideos(listOf(video("v", "c", 100)))

        val channelsJson = repository.exportChannels()
        val categoriesJson = repository.exportCategories()

        val restored = newRepository()
        assertEquals(1, restored.importJson(channelsJson))
        val restoredChannel = restored.snapshot.first().channels.single()
        assertEquals("c", restoredChannel.id)
        assertEquals("Channel", restoredChannel.title)
        assertEquals("PL1", restoredChannel.uploadsPlaylistId)
        assertEquals(setOf("Tech"), restored.snapshot.first().categories.map { it.name }.toSet())
        assertEquals(1, restored.snapshot.first().channels.single().categoryIds.size)

        assertEquals(1, restored.importJson(categoriesJson))
        assertEquals("Tech", restored.snapshot.first().categories.single().name)

        val parsedCategories = LibraryJson.parse(restored.exportCategories()) as ParsedImport.Categories
        assertEquals(listOf("Tech"), parsedCategories.records.map { it.name })
        assertEquals(listOf("c"), parsedCategories.records.single().channelIds)
    }

    @Test
    fun categoryImportIgnoresUnknownChannels() = runBlocking {
        repository.upsertChannel(channel("known", "Known"))
        val json = """
            {"format":"omatube-categories","version":1,"categories":[
              {"name":"Mixed","channelIds":["known","missing"]}
            ]}
        """.trimIndent()

        assertEquals(1, repository.importJson(json))
        val snapshot = repository.snapshot.first()
        val mixedId = snapshot.categories.single().id
        assertEquals("Mixed", snapshot.categories.single().name)
        assertEquals(setOf(mixedId), snapshot.channels.first { it.id == "known" }.categoryIds)
    }

    @Test
    fun malformedImportsRollBackAtomically() = runBlocking {
        repository.upsertChannel(channel("existing", "Existing"))

        val duplicateIds = """
            {"format":"omatube-channels","version":1,"channels":[
              {"id":"a","title":"A","uploadsPlaylistId":"u"},
              {"id":"a","title":"B","uploadsPlaylistId":"u"}
            ]}
        """.trimIndent()
        expectFailure(LibraryImportException::class.java) { repository.importJson(duplicateIds) }

        val invalidDate = """
            {"format":"omatube-channels","version":1,"channels":[
              {"id":"b","title":"B","uploadsPlaylistId":"u","metadataFetchedAt":"not-a-date"}
            ]}
        """.trimIndent()
        expectFailure(LibraryImportException::class.java) { repository.importJson(invalidDate) }

        val wrongFormat = """{"format":"something-else","version":1,"channels":[]}"""
        expectFailure(LibraryImportException::class.java) { repository.importJson(wrongFormat) }

        val wrongVersion = """{"format":"omatube-channels","version":2,"channels":[]}"""
        expectFailure(LibraryImportException::class.java) { repository.importJson(wrongVersion) }

        val snapshot = repository.snapshot.first()
        assertEquals(listOf("existing"), snapshot.channels.map { it.id })
    }

    @Test
    fun deleteAndClearHistoryAffectOnlyHistoryRows() = runBlocking {
        repository.upsertChannel(channel("c", "Channel"))
        repository.upsertVideos(listOf(video("v", "c", 100)))
        repository.recordPlayback("v", 10, 10, newSession = true)
        repository.recordPlayback("v", 20, 10, newSession = true)

        val history = repository.snapshot.first().history
        assertEquals(2, history.size)
        repository.deleteHistory(history.first().id)
        assertEquals(1, repository.snapshot.first().history.size)

        repository.clearHistory()
        assertTrue(repository.snapshot.first().history.isEmpty())
        assertEquals(1, repository.snapshot.first().videos.size)
        assertEquals(2, repository.snapshot.first().videos.first().watchCount)
    }
}
