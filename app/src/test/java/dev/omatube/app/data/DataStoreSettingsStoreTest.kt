package dev.omatube.app.data

import androidx.test.core.app.ApplicationProvider
import dev.omatube.app.model.Settings
import dev.omatube.app.model.SponsorAction
import dev.omatube.app.player.PlaybackQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class DataStoreSettingsStoreTest {

    private lateinit var store: DataStoreSettingsStore

    @Before
    fun setUp() {
        store = DataStoreSettingsStore(ApplicationProvider.getApplicationContext(), inMemory = true)
    }

    @Test
    fun defaultsMatchDesktop() = runBlocking {
        val settings = store.settings.first()
        assertEquals("default", settings.themeId)
        assertFalse(settings.simpleUi)
        assertEquals(3, settings.shortVideoCutoffMinutes)
        assertEquals(0, settings.wifiMaximumVideoHeight)
        assertEquals(0, settings.dataMaximumVideoHeight)
        assertEquals(0, settings.lastUsedVideoHeight)
        assertEquals(100, settings.playbackVolume)
        assertFalse(settings.sponsorBlockEnabled)
        assertTrue(settings.sponsorActions.isEmpty())
        assertFalse(settings.rememberApiKey)
        assertTrue(settings.videoQualityOverrides.isEmpty())
    }

    @Test
    fun updatePersistsAllFields() = runBlocking {
        store.update {
            it.copy(
                themeId = "nord",
                simpleUi = true,
                shortVideoCutoffMinutes = 8,
                wifiMaximumVideoHeight = 1080,
                dataMaximumVideoHeight = 480,
                lastUsedVideoHeight = 720,
                playbackVolume = 40,
                sponsorBlockEnabled = true,
                sponsorActions = mapOf("sponsor" to SponsorAction.AUTO, "intro" to SponsorAction.MANUAL),
                videoQualityOverrides = mapOf("video-a" to 1080),
            )
        }

        val settings = store.settings.first()
        assertEquals("nord", settings.themeId)
        assertTrue(settings.simpleUi)
        assertEquals(8, settings.shortVideoCutoffMinutes)
        assertEquals(1080, settings.wifiMaximumVideoHeight)
        assertEquals(480, settings.dataMaximumVideoHeight)
        assertEquals(720, settings.lastUsedVideoHeight)
        assertEquals(40, settings.playbackVolume)
        assertTrue(settings.sponsorBlockEnabled)
        assertEquals(SponsorAction.AUTO, settings.sponsorActions["sponsor"])
        assertEquals(SponsorAction.MANUAL, settings.sponsorActions["intro"])
        assertEquals(1080, settings.videoQualityOverrides["video-a"])
    }

    @Test
    fun lastUsedConnectionPreferenceIsAccepted() = runBlocking {
        store.update {
            it.copy(
                wifiMaximumVideoHeight = PlaybackQuality.LAST_USED,
                dataMaximumVideoHeight = PlaybackQuality.LAST_USED,
                lastUsedVideoHeight = 1440,
            )
        }
        val settings = store.settings.first()
        assertEquals(PlaybackQuality.LAST_USED, settings.wifiMaximumVideoHeight)
        assertEquals(PlaybackQuality.LAST_USED, settings.dataMaximumVideoHeight)
        assertEquals(1440, settings.lastUsedVideoHeight)
    }

    @Test
    fun inMemoryInstancesAreIsolated() = runBlocking {
        store.update { it.copy(themeId = "rose-pine") }
        assertEquals("rose-pine", store.settings.first().themeId)

        val other = DataStoreSettingsStore(ApplicationProvider.getApplicationContext(), inMemory = true)
        assertEquals("default", other.settings.first().themeId)
        assertTrue(other.settings.first().videoQualityOverrides.isEmpty())
    }

    @Test
    fun cutoffZeroIsAcceptedAndPersisted() = runBlocking {
        store.update { it.copy(shortVideoCutoffMinutes = 0) }
        assertEquals(0, store.settings.first().shortVideoCutoffMinutes)

        store.update { it.copy(shortVideoCutoffMinutes = 60) }
        assertEquals(60, store.settings.first().shortVideoCutoffMinutes)
    }

    @Test
    fun sessionApiKeyRemainsUntilClearedWithinInstance() = runBlocking {
        store.update { it.copy(apiKey = "session-secret", rememberApiKey = false) }
        store.update { it.copy(playbackVolume = 55) }
        assertEquals("session-secret", store.settings.first().apiKey)
        assertEquals(55, store.settings.first().playbackVolume)

        store.update { it.copy(apiKey = "", rememberApiKey = false) }
        assertEquals("", store.settings.first().apiKey)
    }

    @Test
    fun concurrentUpdatesAreAtomic() = runBlocking {
        withContext(Dispatchers.Default) {
            (1..50).map { index ->
                launch {
                    store.update { current ->
                        current.copy(videoQualityOverrides = current.videoQualityOverrides + ("video-$index" to index))
                    }
                }
            }.joinAll()
        }

        val overrides = store.settings.first().videoQualityOverrides
        assertEquals(50, overrides.size)
        assertEquals(50, overrides["video-50"])
    }

    @Test
    fun invalidValuesAreRejected() = runBlocking {
        expectFailure(IllegalArgumentException::class.java) {
            store.update { it.copy(playbackVolume = 200) }
        }
        expectFailure(IllegalArgumentException::class.java) {
            store.update { it.copy(shortVideoCutoffMinutes = -1) }
        }
        expectFailure(IllegalArgumentException::class.java) {
            store.update { it.copy(shortVideoCutoffMinutes = 61) }
        }
        expectFailure(IllegalArgumentException::class.java) {
            store.update { it.copy(wifiMaximumVideoHeight = -5) }
        }
        expectFailure(IllegalArgumentException::class.java) {
            store.update { it.copy(dataMaximumVideoHeight = -3) }
        }
        expectFailure(IllegalArgumentException::class.java) {
            store.update { it.copy(lastUsedVideoHeight = -5) }
        }
        expectFailure(IllegalArgumentException::class.java) {
            store.update { it.copy(lastUsedVideoHeight = PlaybackQuality.LAST_USED) }
        }
        expectFailure(IllegalArgumentException::class.java) {
            store.update { it.copy(videoQualityOverrides = mapOf("v" to -1)) }
        }
        assertEquals(Settings(), store.settings.first())
    }
}
