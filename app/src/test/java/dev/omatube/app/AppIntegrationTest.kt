package dev.omatube.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.omatube.app.automation.AutomationConfig
import dev.omatube.app.automation.AutomationFixture
import dev.omatube.app.automation.FakeVideoBackend
import dev.omatube.app.data.ApiKeyCipher
import dev.omatube.app.data.ApiKeyStorage
import dev.omatube.app.data.DataStoreSettingsStore
import dev.omatube.app.data.LibraryRepository
import dev.omatube.app.data.RoomLibraryRepository
import dev.omatube.app.data.SettingsStore
import dev.omatube.app.model.Channel
import dev.omatube.app.model.LibrarySnapshot
import dev.omatube.app.model.Settings
import dev.omatube.app.model.Video
import dev.omatube.app.model.VideoPage
import dev.omatube.app.refresh.RefreshCoordinator
import dev.omatube.app.ui.library.ALL_CATEGORY_ID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Offline integration coverage for the root graph, refresh coordinator and
 * view model. Every test uses in-memory Room, isolated settings and a
 * [FakeVideoBackend]; nothing here touches the network, Media3, yt-dlp or the
 * PO-token WebView.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AppIntegrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var testScope: CoroutineScope
    private val graphs = mutableListOf<AppGraph>()
    private val settingsFileNames = mutableListOf<String>()

    @Before
    fun setUp() {
        testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @After
    fun tearDown() {
        testScope.cancel()
        graphs.forEach { it.close() }
        graphs.clear()
        settingsFileNames.forEach { fileName ->
            File(context.filesDir, "datastore/$fileName.preferences_pb").delete()
            File(context.filesDir, "datastore/$fileName.preferences_pb.tmp").delete()
        }
        settingsFileNames.clear()
    }

    // ---- Startup exactly once ------------------------------------------

    @Test
    fun startupRefreshRunsExactlyOncePerProcess() {
        val fake = FakeVideoBackend()
        val repository = newRepository()
        runBlocking { repository.upsertChannel(channel("c1")) }
        val graph = newGraph(fake, repository)
        val viewModel = newViewModel(graph)

        viewModel.start()
        viewModel.start()

        awaitTrue { fake.recentCallCount == 1 }
        Thread.sleep(150)
        assertEquals(1, fake.recentCallCount)
        assertFalse(graph.beginStartup())
    }

    // ---- Live failure preserves last-known state -----------------------

    @Test
    fun liveFailureKeepsCachedLiveAndMarksStatusIncomplete() {
        val repository = newRepository()
        val channelOne = channel("c1")
        val channelTwo = channel("c2")
        runBlocking {
            repository.upsertChannel(channelOne)
            repository.upsertChannel(channelTwo)
            repository.upsertVideos(listOf(video("v1", "c1"), video("v2", "c2")))
            repository.replaceLive("c2", listOf(video("v2", "c2").copy(isLive = true)))
        }
        val fake = FakeVideoBackend(
            liveProvider = { channel ->
                if (channel.id == "c1") {
                    listOf(video("v1", "c1").copy(isLive = true))
                } else {
                    throw IOException("live extraction unavailable")
                }
            },
        )
        val coordinator = RefreshCoordinator(repository, fake, testScope)

        runBlocking { coordinator.refresh() }

        val snapshot = runBlocking { repository.snapshot.first() }
        assertTrue(snapshot.videos.first { it.id == "v1" }.isLive)
        assertTrue(snapshot.videos.first { it.id == "v2" }.isLive)
        assertTrue(coordinator.progress.value.liveIncomplete)
        assertTrue(coordinator.progress.value.errors.isNotEmpty())
    }

    // ---- Local category filter before the page window ------------------

    @Test
    fun feedFiltersCachedVideosByCategoryBeforePaging() {
        val repository = newRepository()
        val categoryA = runBlocking { repository.addCategory("Alpha") }
        val categoryB = runBlocking { repository.addCategory("Beta") }
        runBlocking {
            repository.upsertChannel(channel("ca"))
            repository.upsertChannel(channel("cb"))
            repository.setChannelCategories("ca", setOf(categoryA))
            repository.setChannelCategories("cb", setOf(categoryB))
            // The newest 60 cached videos belong to the other category, so a
            // naive "first 50 then filter" would render an empty feed.
            val hidden = (0 until 60).map {
                video("h$it", "cb", publishedAt = 2_000_000L + it)
            }
            val matching = (0 until 60).map {
                video("m$it", "ca", publishedAt = 1_000_000L + it)
            }
            repository.upsertVideos(hidden + matching)
            repository.setHistoryCursor("ca", null, complete = true)
            repository.setHistoryCursor("cb", null, complete = true)
        }
        val fake = FakeVideoBackend()
        val graph = newGraph(fake, repository)
        val viewModel = newViewModel(graph)

        viewModel.onCategory(categoryA)

        awaitTrue { viewModel.uiState.value.library.videos.count { it.channelId == "ca" } == 50 }
        val page = viewModel.uiState.value
        assertEquals(50, page.library.videos.count { it.channelId == "ca" })
        assertEquals(0, page.library.videos.count { it.channelId == "cb" })
        assertTrue(page.hasMore)

        viewModel.loadMore()
        awaitTrue { viewModel.uiState.value.library.videos.count { it.channelId == "ca" } == 60 }
        assertEquals(0, fake.olderCallCount)
    }

    // ---- Remote paging only after the cache is exhausted ---------------

    @Test
    fun remoteOlderLoadsOnceWhenCacheExhaustedAndCursorProgresses() {
        val base = newRepository()
        runBlocking {
            base.upsertChannel(channel("c1"))
            base.upsertVideos((1..5).map { video("v$it", "c1", publishedAt = 1_000L + it) })
        }
        val repository = RecordingRepository(base)
        val remote = video("remote-1", "c1", publishedAt = 500L)
        val fake = FakeVideoBackend(
            olderProvider = { _, cursor ->
                when (cursor) {
                    null -> VideoPage(listOf(remote), nextPage = "cursor-1")
                    "cursor-1" -> VideoPage(emptyList(), nextPage = null)
                    else -> error("Unexpected cursor: $cursor")
                }
            },
        )
        val graph = newGraph(fake, repository)
        val viewModel = newViewModel(graph)

        awaitTrue { viewModel.uiState.value.hasMore }
        viewModel.loadMore()
        awaitTrue { fake.olderCallCount == 1 && !viewModel.uiState.value.loadingMore }

        assertEquals(1, fake.olderCursors.size)
        assertEquals(null, fake.olderCursors[0])
        assertEquals(1, repository.historyCursorWrites.size)
        assertEquals("c1" to "cursor-1", repository.historyCursorWrites[0])
        awaitTrue { viewModel.uiState.value.library.videos.any { it.id == "remote-1" } }

        viewModel.loadMore()
        awaitTrue { fake.olderCallCount == 2 && !viewModel.uiState.value.loadingMore }
        assertEquals("cursor-1", fake.olderCursors[1])

        awaitTrue { runBlocking { base.historyComplete("c1") } }
        viewModel.loadMore()
        Thread.sleep(150)
        assertEquals(2, fake.olderCallCount)
        assertTrue(repository.pruneCount >= 1)
        assertEquals(ALL_CATEGORY_ID, viewModel.uiState.value.selectedCategoryId)
    }

    // ---- Automation stays offline and renders the fixture ---------------

    @Test
    fun automationUsesFakeBackendDisabledRefreshAndSeededFixture() {
        val graph = AppGraph.automation(context, simpleUi = true, themeId = "nord")
        graphs.add(graph)
        assertTrue(graph.backend is FakeVideoBackend)
        val fake = graph.backend as FakeVideoBackend
        val viewModel = newViewModel(graph)

        awaitTrue { viewModel.uiState.value.library.videos.size >= 5 }
        viewModel.refresh()

        assertTrue(viewModel.uiState.value.status.contains("Automation mode", ignoreCase = true))
        assertTrue(viewModel.uiState.value.settings.simpleUi)
        assertEquals("nord", viewModel.uiState.value.settings.themeId)
        assertEquals(0, fake.totalCallCount)
        assertEquals(2, viewModel.uiState.value.library.categories.size)
    }

    // ---- Importing the wrong document surfaces an error ----------------

    @Test
    fun importWrongDocumentSurfacesActionableError() {
        val repository = newRepository()
        val graph = newGraph(FakeVideoBackend(), repository)
        val viewModel = newViewModel(graph)
        val uri = Uri.parse("content://dev.omatube.test/not-omatube.json")
        shadowOf(context.contentResolver).registerInputStream(
            uri,
            ByteArrayInputStream("{ definitely not valid json".toByteArray()),
        )

        viewModel.importDocument(uri)

        awaitTrue { viewModel.uiState.value.error != null }
        val error = viewModel.uiState.value.error
        assertNotNull(error)
        assertTrue(error!!.contains("JSON", ignoreCase = true))
        assertTrue(runBlocking { repository.snapshot.first().channels.isEmpty() })
    }

    // ---- Playback reports update watch state ---------------------------

    @Test
    fun playbackReportsRecordPositionWatchedTimeAndSingleSession() {
        val repository = newRepository()
        runBlocking {
            repository.upsertChannel(channel("c1"))
            repository.upsertVideos(listOf(video("v1", "c1", publishedAt = 1_000L, durationSeconds = 600L)))
        }
        val graph = newGraph(FakeVideoBackend(), repository)
        val viewModel = newViewModel(graph)

        viewModel.reportPlayback("v1", 120L, 30L, newSession = true)
        awaitTrue {
            runBlocking {
                val stored = repository.snapshot.first().videos.first { it.id == "v1" }
                stored.watchCount == 1 &&
                    stored.lastPositionSeconds == 120L &&
                    stored.watchedSeconds == 30L
            }
        }
        assertEquals(1, runBlocking { repository.snapshot.first().history.size })

        viewModel.reportPlayback("v1", 200L, 10L, newSession = false)
        awaitTrue {
            runBlocking {
                val stored = repository.snapshot.first().videos.first { it.id == "v1" }
                stored.lastPositionSeconds == 200L && stored.watchedSeconds == 40L
            }
        }
        assertEquals(1, runBlocking { repository.snapshot.first().videos.first { it.id == "v1" }.watchCount })
    }

    // ---- Refresh failure handling --------------------------------------

    @Test
    fun snapshotFailureIsCaughtResetsProgressAndAllowsRetry() {
        val real = newRepository()
        runBlocking {
            real.upsertChannel(channel("c1"))
            real.upsertVideos(listOf(video("v1", "c1", publishedAt = 1_000L)))
        }
        val repository = FaultyRepository(real, failSnapshotOnce = true)
        val coordinator = RefreshCoordinator(repository, FakeVideoBackend(), testScope)

        runBlocking { coordinator.refresh() }

        assertFalse(coordinator.progress.value.refreshing)
        assertTrue(coordinator.progress.value.errors.isNotEmpty())
        assertTrue(runBlocking { real.snapshot.first().videos.isNotEmpty() })

        // The gate was released, so an explicit retry is permitted and succeeds.
        runBlocking { coordinator.refresh() }
        assertFalse(coordinator.progress.value.refreshing)
        assertTrue(coordinator.progress.value.errors.isEmpty())
    }

    @Test
    fun pruneFailureIsCaughtResetsProgressAndPreservesCache() {
        val real = newRepository()
        runBlocking {
            real.upsertChannel(channel("c1"))
            real.upsertVideos(listOf(video("v1", "c1", publishedAt = 1_000L)))
        }
        val repository = FaultyRepository(real, failPrune = true)
        val coordinator = RefreshCoordinator(repository, FakeVideoBackend(), testScope)

        runBlocking { coordinator.refresh() }

        assertFalse(coordinator.progress.value.refreshing)
        assertTrue(coordinator.progress.value.errors.any { it.contains("prune", ignoreCase = true) })
        assertTrue(runBlocking { real.snapshot.first().videos.any { it.id == "v1" } })

        repository.failPrune = false
        runBlocking { coordinator.refresh() }
        assertFalse(coordinator.progress.value.refreshing)
        assertTrue(coordinator.progress.value.errors.isEmpty())
    }

    @Test
    fun loadOlderFailureIsCaughtAndResetsLoadingFlag() {
        val real = newRepository()
        runBlocking { real.upsertChannel(channel("c1")) }
        val repository = FaultyRepository(real, failPrune = true)
        val coordinator = RefreshCoordinator(repository, FakeVideoBackend(), testScope)

        runBlocking { coordinator.loadOlder(listOf(channel("c1"))) }

        assertFalse(coordinator.progress.value.loadingMore)
        assertTrue(coordinator.progress.value.errors.isNotEmpty())
    }

    // ---- Selected player survives cache removal ------------------------

    @Test
    fun selectedVideoSurvivesChannelRemovalWhilePlaying() {
        val repository = newRepository()
        val target = video("v1", "c1", publishedAt = 1_000L, durationSeconds = 600L)
        runBlocking {
            repository.upsertChannel(channel("c1"))
            repository.upsertVideos(listOf(target))
        }
        val graph = newGraph(FakeVideoBackend(), repository)
        val viewModel = newViewModel(graph)

        viewModel.openVideo(target)
        awaitTrue { viewModel.uiState.value.selectedVideo?.id == "v1" }

        runBlocking { repository.removeChannel("c1") }
        awaitTrue { runBlocking { repository.snapshot.first().videos.isEmpty() } }

        awaitTrue { viewModel.uiState.value.selectedVideo?.id == "v1" }
        assertEquals("Video v1", viewModel.uiState.value.selectedVideo?.title)

        viewModel.closePlayer()
        awaitTrue { viewModel.uiState.value.selectedVideo == null }
    }

    // ---- Settings change-set merging -----------------------------------

    @Test
    fun overlappingSettingsCallbacksMergeWithoutClobbering() {
        val repository = newRepository()
        val store = DataStoreSettingsStore(context, inMemory = true)
        val graph = newGraph(FakeVideoBackend(), repository, store)
        val viewModel = newViewModel(graph)

        awaitTrue { viewModel.uiState.value.settings == Settings() }
        val initial = viewModel.uiState.value.settings

        viewModel.onSettingsChange(initial.copy(playbackVolume = 40))
        viewModel.onSettingsChange(initial.copy(wifiMaximumVideoHeight = 1080))
        viewModel.onSettingsChange(initial.copy(dataMaximumVideoHeight = 480))
        viewModel.onSettingsChange(initial.copy(lastUsedVideoHeight = 720))
        viewModel.onSettingsChange(initial.copy(themeId = "nord"))
        viewModel.onSettingsChange(initial.copy(shortVideoCutoffMinutes = 7))
        viewModel.onSettingsChange(initial.copy(videoQualityOverrides = mapOf("a" to 720)))
        viewModel.onSettingsChange(initial.copy(videoQualityOverrides = mapOf("b" to 480)))

        awaitTrue {
            val s = viewModel.uiState.value.settings
            s.playbackVolume == 40 &&
                s.wifiMaximumVideoHeight == 1080 &&
                s.dataMaximumVideoHeight == 480 &&
                s.lastUsedVideoHeight == 720 &&
                s.themeId == "nord" &&
                s.shortVideoCutoffMinutes == 7 &&
                s.videoQualityOverrides["a"] == 720 &&
                s.videoQualityOverrides["b"] == 480
        }
    }

    @Test
    fun untouchedApiKeyIsPreservedWhenOtherFieldsChange() {
        val repository = newRepository()
        val store = DataStoreSettingsStore(context, inMemory = true)
        runBlocking {
            store.update { it.copy(apiKey = "session-key", rememberApiKey = false) }
        }
        val graph = newGraph(FakeVideoBackend(), repository, store)
        val viewModel = newViewModel(graph)

        awaitTrue { viewModel.uiState.value.settings.apiKey == "session-key" }
        val initial = viewModel.uiState.value.settings
        viewModel.onSettingsChange(initial.copy(playbackVolume = 55))

        awaitTrue {
            val s = viewModel.uiState.value.settings
            s.playbackVolume == 55 && s.apiKey == "session-key"
        }
    }

    // ---- Metadata freshness --------------------------------------------

    @Test
    fun freshMetadataSkipsChannelResolution() {
        val now = 1_800_000_000_000L
        val fresh = channel("c1").copy(
            originalInput = "input-c1",
            metadataFetchedAt = now,
        )
        val repository = newRepository()
        runBlocking { repository.upsertChannel(fresh) }
        val fake = FakeVideoBackend(
            channelsById = mapOf("c1" to fresh),
            channelsByInput = mapOf("input-c1" to fresh),
        )
        val coordinator = RefreshCoordinator(repository, fake, testScope, now = { now })

        runBlocking { coordinator.refresh() }

        assertEquals(0, fake.resolveCallCount)
    }

    @Test
    fun staleMetadataRefreshesChannelResolution() {
        val now = 1_800_000_000_000L
        val stale = channel("c1").copy(
            originalInput = "input-c1",
            metadataFetchedAt = now - 30L * 24L * 60L * 60L * 1_000L,
        )
        val refreshed = stale.copy(title = "Refreshed", metadataFetchedAt = now)
        val repository = newRepository()
        runBlocking { repository.upsertChannel(stale) }
        val fake = FakeVideoBackend(
            channelsById = mapOf("c1" to refreshed),
            channelsByInput = mapOf("input-c1" to refreshed),
        )
        val coordinator = RefreshCoordinator(repository, fake, testScope, now = { now })

        runBlocking { coordinator.refresh() }

        assertEquals(1, fake.resolveCallCount)
        assertEquals("Refreshed", runBlocking { repository.snapshot.first().channels.first().title })
    }

    // ---- Recent enrichment ---------------------------------------------

    @Test
    fun enrichmentUpgradesUnknownDurationWithoutOlderLoad() {
        val repository = newRepository()
        val unknown = video("v1", "c1", publishedAt = 1_000L, durationSeconds = -1L)
        runBlocking { repository.upsertChannel(channel("c1")) }
        val fake = FakeVideoBackend(
            recentProvider = { VideoPage(listOf(unknown), nextPage = "start") },
            enrichProvider = { listOf(unknown.copy(durationSeconds = 600L)) },
        )
        val coordinator = RefreshCoordinator(repository, fake, testScope)

        runBlocking { coordinator.refresh() }

        val stored = runBlocking { repository.snapshot.first().videos.first { it.id == "v1" } }
        assertEquals(600L, stored.durationSeconds)
        assertEquals(1, fake.enrichCallCount)
        assertEquals(0, fake.olderCallCount)
        assertEquals(null, runBlocking { repository.historyCursor("c1") })
    }

    @Test
    fun recentFeedVisibleBeforeDelayedEnrichmentCompletes() {
        val repository = newRepository()
        val unknown = video("v1", "c1", publishedAt = 1_000L, durationSeconds = -1L)
        runBlocking { repository.upsertChannel(channel("c1")) }
        val gate = CompletableDeferred<Unit>()
        val fake = FakeVideoBackend(
            recentProvider = { VideoPage(listOf(unknown), nextPage = "start") },
            enrichProvider = {
                gate.await()
                listOf(unknown.copy(durationSeconds = 600L))
            },
        )
        val coordinator = RefreshCoordinator(repository, fake, testScope)

        val job = testScope.launch { coordinator.refresh() }

        awaitTrue {
            runBlocking {
                repository.snapshot.first().videos.any { it.id == "v1" && it.durationSeconds < 0L }
            }
        }
        assertTrue(coordinator.progress.value.refreshing)

        gate.complete(Unit)
        runBlocking { job.join() }

        awaitTrue {
            runBlocking {
                repository.snapshot.first().videos.first { it.id == "v1" }.durationSeconds == 600L
            }
        }
    }

    @Test
    fun enrichmentFailurePreservesFeedAndLiveStillRuns() {
        val repository = newRepository()
        val unknown = video("v1", "c1", publishedAt = 1_000L, durationSeconds = -1L)
        runBlocking { repository.upsertChannel(channel("c1")) }
        val fake = FakeVideoBackend(
            recentProvider = { VideoPage(listOf(unknown), nextPage = "start") },
            enrichProvider = { throw IOException("enrichment unavailable") },
        )
        val coordinator = RefreshCoordinator(repository, fake, testScope)

        runBlocking { coordinator.refresh() }

        val stored = runBlocking { repository.snapshot.first().videos.first { it.id == "v1" } }
        assertEquals(-1L, stored.durationSeconds)
        assertTrue(coordinator.progress.value.errors.any { it.contains("enrichment", ignoreCase = true) })
        assertFalse(coordinator.progress.value.refreshing)
        assertEquals(1, fake.liveCallCount)
    }

    // ---- Automation graph identity across recreation --------------------

    @Test
    @Config(application = OmaTubeApplication::class)
    fun automationGraphPreservedAcrossActivityRecreation() {
        val app = ApplicationProvider.getApplicationContext<OmaTubeApplication>()
        ActivityScenario.launch<MainActivity>(automationIntent()).use { scenario ->
            shadowOf(Looper.getMainLooper()).idle()
            val graph = app.appGraph
            graphs.add(graph)
            assertEquals(2, runBlocking { graph.repository.snapshot.first().watchNext.size })
            val fake = graph.backend as FakeVideoBackend
            runBlocking { graph.repository.addWatchNext(AutomationFixture.PLAYER_VIDEO_ID) }
            assertEquals(3, runBlocking { graph.repository.snapshot.first().watchNext.size })

            scenario.recreate()
            shadowOf(Looper.getMainLooper()).idle()

            val recreated = app.appGraph
            assertSame(graph, recreated)
            assertEquals(3, runBlocking { recreated.repository.snapshot.first().watchNext.size })
            assertEquals(0, fake.totalCallCount)
        }
    }

    @Test
    @Config(application = OmaTubeApplication::class)
    fun automationGraphInstallIsIdempotentForSameLaunch() {
        val app = ApplicationProvider.getApplicationContext<OmaTubeApplication>()
        val launch = AutomationConfig.from(automationIntent())
        assertNotNull(launch)

        assertTrue(app.installGraph(launch))
        val first = app.appGraph
        graphs.add(first)
        runBlocking { first.repository.addWatchNext(AutomationFixture.PLAYER_VIDEO_ID) }
        assertEquals(3, runBlocking { first.repository.snapshot.first().watchNext.size })

        assertFalse(app.installGraph(launch))
        assertSame(first, app.appGraph)
        assertEquals(3, runBlocking { app.appGraph.repository.snapshot.first().watchNext.size })
        assertEquals(0, (first.backend as FakeVideoBackend).totalCallCount)
    }

    // ---- Graph shutdown / settings store release -----------------------

    @Test
    fun closeReleasesProductionIsolatedSettingsFileForReopen() {
        val fileName = newSettingsFileName()
        val storage = FakeApiKeyStorage()
        val cipher = FakeApiKeyCipher()
        val storeA = DataStoreSettingsStore(context, false, fileName, storage, cipher)
        val graphA = newGraph(FakeVideoBackend(), newRepository(), storeA)

        runBlocking {
            storeA.update { it.copy(themeId = "nord", playbackVolume = 42) }
            storeA.settings.first { it.playbackVolume == 42 }
        }
        graphA.close()

        // Reopening the same file must not throw "multiple DataStores active":
        // close awaited the old store's DataStore scope before returning.
        val storeB = DataStoreSettingsStore(context, false, fileName, storage, cipher)
        try {
            val reopened = runBlocking { storeB.settings.first { it.playbackVolume == 42 } }
            assertEquals("nord", reopened.themeId)
            assertEquals(42, reopened.playbackVolume)
        } finally {
            runBlocking { storeB.closeAndJoin() }
        }
    }

    @Test
    fun recoveryNoticeSurfacesThroughGraphErrors() {
        val fileName = newSettingsFileName()
        val storage = FakeApiKeyStorage()
        val cipher = FakeApiKeyCipher()
        val seed = DataStoreSettingsStore(context, false, fileName, storage, cipher)
        runBlocking {
            seed.update { it.copy(apiKey = "secret", rememberApiKey = true) }
            seed.closeAndJoin()
        }
        // Simulate a restore that kept preferences but lost the no-backup key.
        storage.clear()

        val recovered = DataStoreSettingsStore(context, false, fileName, storage, cipher)
        val graph = newGraph(FakeVideoBackend(), newRepository(), recovered)

        awaitTrue { runBlocking { recovered.recoveryNotice.first() } != null }
        awaitTrue { graph.errors.value != null }
        assertTrue(graph.errors.value!!.contains("API key", ignoreCase = true))
        graph.close()
    }

    // ---- Helpers -------------------------------------------------------

    private fun newRepository(): RoomLibraryRepository =
        RoomLibraryRepository(context, inMemory = true)

    private fun newSettingsFileName(): String =
        "settings-graph-${UUID.randomUUID()}".also { settingsFileNames.add(it) }

    private fun newGraph(
        backend: FakeVideoBackend,
        repository: LibraryRepository,
        settingsStore: SettingsStore = DataStoreSettingsStore(context, inMemory = true),
    ): AppGraph {
        val graph = AppGraph(
            repository = repository,
            settingsStore = settingsStore,
            backend = backend,
            automation = false,
            scope = testScope,
        )
        graphs.add(graph)
        return graph
    }

    private fun newViewModel(graph: AppGraph): AppViewModel =
        AppViewModel(graph, context, SavedStateHandle(), scope = testScope)

    private fun automationIntent(): Intent = Intent(
        ApplicationProvider.getApplicationContext(),
        MainActivity::class.java,
    )
        .putExtra(AutomationConfig.EXTRA_AUTOMATION, true)
        .putExtra(AutomationConfig.EXTRA_ROUTE, AutomationConfig.ROUTE_WATCH_NEXT)

    private fun channel(id: String): Channel = Channel(id = id, title = "Channel $id")

    private fun video(
        id: String,
        channelId: String,
        publishedAt: Long = 0L,
        durationSeconds: Long = -1L,
    ): Video = Video(
        id = id,
        channelId = channelId,
        title = "Video $id",
        publishedAt = publishedAt,
        durationSeconds = durationSeconds,
    )

    private fun awaitTrue(timeoutMillis: Long = 5_000L, condition: () -> Boolean) {
        runBlocking {
            withTimeout(timeoutMillis) {
                while (!condition()) {
                    delay(15L)
                }
            }
        }
    }

    private class RecordingRepository(
        private val delegate: LibraryRepository,
    ) : LibraryRepository by delegate {

        val historyCursorWrites = mutableListOf<Pair<String, String?>>()
        var pruneCount = 0
            private set

        override suspend fun setHistoryCursor(channelId: String, cursor: String?, complete: Boolean) {
            historyCursorWrites.add(channelId to cursor)
            delegate.setHistoryCursor(channelId, cursor, complete)
        }

        override suspend fun pruneCache() {
            pruneCount++
            delegate.pruneCache()
        }
    }

    /** Injects database failures to prove refresh never crashes the scope. */
    private class FaultyRepository(
        private val delegate: LibraryRepository,
        var failSnapshotOnce: Boolean = false,
        var failPrune: Boolean = false,
    ) : LibraryRepository by delegate {

        private var snapshotRequests = 0

        override val snapshot: Flow<LibrarySnapshot>
            get() {
                val request = snapshotRequests++
                if (failSnapshotOnce && request == 0) {
                    return flow { throw IllegalStateException("snapshot unavailable") }
                }
                return delegate.snapshot
            }

        override suspend fun pruneCache() {
            if (failPrune) throw IllegalStateException("prune unavailable")
            delegate.pruneCache()
        }
    }

    /** In-memory key ciphertext store so recovery can be simulated offline. */
    private class FakeApiKeyStorage : ApiKeyStorage {
        private var value: String? = null

        override fun read(): String? = value

        override fun write(ciphertext: String) {
            value = ciphertext
        }

        override fun clear() {
            value = null
        }
    }

    /** Reversible fake cipher; never touches the Android Keystore. */
    private class FakeApiKeyCipher : ApiKeyCipher {
        override fun encrypt(plaintext: String): String = "enc:$plaintext"

        override fun decrypt(ciphertext: String): String? =
            if (ciphertext.startsWith("enc:")) ciphertext.removePrefix("enc:") else null
    }
}
