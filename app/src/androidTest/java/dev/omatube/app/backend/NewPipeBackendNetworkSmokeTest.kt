package dev.omatube.app.backend

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.omatube.app.model.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in, network-dependent smoke check for the public-VOD extraction path.
 *
 * It is NOT part of the default offline suite: it only runs when the instrumentation argument
 * `omatubeNetworkSmoke=true` is supplied, e.g.
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.omatubeNetworkSmoke=true \
 *   -Pandroid.testInstrumentationRunnerArguments.class=dev.omatube.app.backend.NewPipeBackendNetworkSmokeTest
 * ```
 *
 * Properties:
 *  - no user database (NewPipeBackend never opens one) and no API key (`Settings()` defaults);
 *  - one overall timeout bounds the whole network workflow;
 *  - diagnostics report title and per-kind source counts only;
 *  - never logs stream URLs or credentials, and never starts playback.
 *
 * The coordinator authorizes and runs this separately; it must not be wired into `./bin/test`.
 */
@RunWith(AndroidJUnit4::class)
class NewPipeBackendNetworkSmokeTest {

    @Before
    fun requireOptIn() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Set -e omatubeNetworkSmoke true to run this network smoke test",
            arguments.getString(SMOKE_ARGUMENT) == "true",
        )
    }

    @Test
    fun resolvesPublicChannelAndVideoStream() = runBlocking {
        withTimeout(NETWORK_TIMEOUT_MS) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val backend = NewPipeBackend(context) { Settings() }
            try {
                val channel = backend.resolveChannel(PUBLIC_CHANNEL)
                assertTrue(
                    "expected a canonical UC channel id for '${channel.title}'",
                    channel.id.startsWith("UC"),
                )
                assertTrue("expected a channel title", channel.title.isNotBlank())

                val stream = backend.resolveStream(PUBLIC_VIDEO_ID)
                val videoCount = stream.videoStreams.size
                val videoOnlyCount = stream.videoOnlyStreams.size
                val audioCount = stream.audioStreams.size
                val diagnostics = "stream '${stream.name}' " +
                    "(v=$videoCount, vo=$videoOnlyCount, a=$audioCount)"
                assertTrue("resolved stream id mismatch $diagnostics", stream.id == PUBLIC_VIDEO_ID)
                assertTrue("expected a stream title $diagnostics", stream.name.isNotBlank())
                assertTrue(
                    "expected at least one extractable source $diagnostics",
                    videoCount + videoOnlyCount + audioCount > 0,
                )
            } finally {
                backend.close()
            }
        }
    }

    private companion object {
        const val SMOKE_ARGUMENT = "omatubeNetworkSmoke"
        const val NETWORK_TIMEOUT_MS = 90_000L
        const val PUBLIC_CHANNEL = "https://www.youtube.com/@NASA"
        const val PUBLIC_VIDEO_ID = "dQw4w9WgXcQ"
    }
}
