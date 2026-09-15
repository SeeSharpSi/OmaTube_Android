package dev.omatube.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.omatube.app.model.TranscriptCue
import dev.omatube.app.model.TranscriptWord
import dev.omatube.app.player.PlaybackQuality
import dev.omatube.app.player.PlayerUiState
import dev.omatube.app.ui.theme.OmaColors
import dev.omatube.app.ui.theme.OmaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Targeted visual-parity checks for the player chrome. These are the stable
 * tags the device driver should use instead of coordinates for the quality
 * selector, and they pin the desktop casing/geometry that changed.
 */
@RunWith(AndroidJUnit4::class)
class PlayerChromeTest {
    @get:Rule
    val compose = createComposeRule()

    private val colors = OmaColors.forTheme("default")

    @Test
    fun qualitySelectorShowsNaturalCasingAndStableTag() {
        compose.setContent {
            OmaTheme("default") {
                QualitySelector(
                    qualityValue = PlaybackQuality.DEFAULT,
                    colors = colors,
                    onQuality = {},
                )
            }
        }

        compose.onNodeWithTag("playerQualitySelector").assertExists()
        compose.onNodeWithText("Default").assertExists()
        compose.onNodeWithText("DEFAULT").assertDoesNotExist()
    }

    @Test
    fun qualityPopupUsesNaturalCasingAndReportsSelection() {
        var selected: Int = -1
        compose.setContent {
            OmaTheme("default") {
                QualitySelector(
                    qualityValue = PlaybackQuality.DEFAULT,
                    colors = colors,
                    onQuality = { selected = it },
                )
            }
        }

        compose.onNodeWithTag("playerQualitySelector").performClick()
        compose.onNodeWithText("720p").assertExists()
        compose.onNodeWithText("720P").assertDoesNotExist()
        compose.onNodeWithText("720p").performClick()
        assertEquals(720, selected)
    }

    @Test
    fun seekBarExposesStableTag() {
        compose.setContent {
            OmaTheme("default") {
                PlayerSeekBar(
                    positionMs = 1_000L,
                    durationMs = 10_000L,
                    live = false,
                    segments = emptyList(),
                    colors = colors,
                    onSeek = {},
                )
            }
        }

        compose.onNodeWithTag("playerSeekBar").assertExists()
    }

    @Test
    fun seekBarReportsScrubContactOnDownAndRelease() {
        var scrubbing = false
        compose.setContent {
            OmaTheme("default") {
                PlayerSeekBar(
                    positionMs = 1_000L,
                    durationMs = 10_000L,
                    live = false,
                    segments = emptyList(),
                    colors = colors,
                    onSeek = {},
                    onScrubbingChange = { scrubbing = it },
                )
            }
        }

        val seekBar = compose.onNodeWithTag("playerSeekBar")
        seekBar.performTouchInput { down(center) }
        compose.waitForIdle()
        assertTrue("pointer down should report active contact", scrubbing)

        seekBar.performTouchInput { up() }
        compose.waitForIdle()
        assertFalse("release should report inactive contact", scrubbing)
    }

    @Test
    fun scrubPreviewLabelSitsCenteredAboveBottomBarAndDisappearsOnRelease() {
        compose.setContent {
            OmaTheme("default") {
                Box(Modifier.fillMaxSize()) {
                    PlayerBottomBar(
                        state = PlayerUiState(positionMs = 0L, durationMs = 100_000L),
                        colors = colors,
                        compact = true,
                        fullscreen = false,
                        onTogglePlay = {},
                        onSeek = {},
                        onScrubbingChange = {},
                        onToggleMute = {},
                        onLive = {},
                        onFullscreen = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }

        compose.onNodeWithTag("playerSeekScrubLabel").assertDoesNotExist()

        val seekBar = compose.onNodeWithTag("playerSeekBar")
        seekBar.performTouchInput {
            down(Offset(0f, center.y))
            moveTo(Offset(width / 2f, center.y))
            moveTo(Offset(width / 2f, center.y))
        }
        compose.waitForIdle()

        compose.onNodeWithTag("playerSeekScrubLabel").assertExists()
        compose.onNodeWithText("0:50").assertExists()

        val labelBounds = compose.onNodeWithTag("playerSeekScrubLabel").fetchSemanticsNode().boundsInRoot
        val barBounds = compose.onNodeWithTag("playerBottomBar").fetchSemanticsNode().boundsInRoot
        val gapPx = with(compose.density) { 8.dp.toPx() }
        val tolerancePx = with(compose.density) { 1.dp.toPx() }
        assertEquals(
            "label center should match the bottom-bar center",
            barBounds.center.x,
            labelBounds.center.x,
            tolerancePx,
        )
        assertTrue(
            "label bottom ${labelBounds.bottom} must sit at least 8 dp above bar top ${barBounds.top}",
            barBounds.top - labelBounds.bottom >= gapPx - tolerancePx,
        )

        seekBar.performTouchInput { up() }
        compose.waitForIdle()
        compose.onNodeWithTag("playerSeekScrubLabel").assertDoesNotExist()
    }

    @Test
    fun muteButtonIsSquareIconWithoutText() {
        compose.setContent {
            OmaTheme("default") {
                MuteButton(muted = false, colors = colors, onClick = {})
            }
        }

        compose.onNodeWithTag("playerMuteButton").assertExists()
        compose.onNodeWithText("MUTE").assertDoesNotExist()
        compose.onNodeWithText("UNMUTE").assertDoesNotExist()
        val size = compose.onNodeWithTag("playerMuteButton").fetchSemanticsNode().size
        assertEquals(size.width, size.height)
    }

    @Test
    fun fullscreenButtonIsSquareIconWithoutText() {
        compose.setContent {
            OmaTheme("default") {
                FullscreenButton(fullscreen = false, colors = colors, onClick = {})
            }
        }

        compose.onNodeWithTag("playerFullscreenButton").assertExists()
        compose.onNodeWithText("FULLSCREEN").assertDoesNotExist()
        compose.onNodeWithText("EXIT").assertDoesNotExist()
        val size = compose.onNodeWithTag("playerFullscreenButton").fetchSemanticsNode().size
        assertEquals(size.width, size.height)
    }

    @Test
    fun fullscreenExitButtonRendersInvertedIcon() {
        compose.setContent {
            OmaTheme("default") {
                FullscreenButton(fullscreen = true, colors = colors, onClick = {})
            }
        }

        compose.onNodeWithTag("playerFullscreenButton").assertExists()
        compose.onNodeWithText("FULLSCREEN").assertDoesNotExist()
        compose.onNodeWithText("EXIT").assertDoesNotExist()
    }

    @Test
    fun loadingOverlayIs80dpSquare() {
        compose.setContent {
            OmaTheme("default") {
                PlayerCenterOverlay(
                    state = PlayerUiState(loading = true),
                    colors = colors,
                    chromeVisible = false,
                    onTogglePlay = {},
                )
            }
        }

        val size = compose.onNodeWithTag("playerOverlay").fetchSemanticsNode().size
        assertEquals(size.width, size.height)
        val expected = with(compose.density) { 80.dp.roundToPx() }
        assertEquals(expected, size.width)
    }

    @Test
    fun loadingCenterOverlayStaysVisibleWhenChromeHidden() {
        compose.setContent {
            OmaTheme("default") {
                PlayerCenterOverlay(
                    state = PlayerUiState(loading = true),
                    colors = colors,
                    chromeVisible = false,
                    onTogglePlay = {},
                )
            }
        }

        compose.onNodeWithTag("playerOverlay").assertExists()
    }

    @Test
    fun centerPlayPauseIsAn80dpSquareWithDistinctDescriptionAndCallsBack() {
        var clicks = 0
        compose.setContent {
            OmaTheme("default") {
                PlayerCenterOverlay(
                    state = PlayerUiState(loading = false),
                    colors = colors,
                    chromeVisible = true,
                    onTogglePlay = { clicks++ },
                )
            }
        }

        compose.onNodeWithTag("playerCenterPlayPauseButton").assertExists()
        compose.onNodeWithContentDescription("Center play").assertExists()
        val size = compose.onNodeWithTag("playerCenterPlayPauseButton").fetchSemanticsNode().size
        assertEquals(size.width, size.height)
        val expected = with(compose.density) { 80.dp.roundToPx() }
        assertEquals(expected, size.width)

        compose.onNodeWithTag("playerCenterPlayPauseButton").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun centerPlayPauseIsAbsentWhenChromeHidden() {
        compose.setContent {
            OmaTheme("default") {
                PlayerCenterOverlay(
                    state = PlayerUiState(loading = false),
                    colors = colors,
                    chromeVisible = false,
                    onTogglePlay = {},
                )
            }
        }

        compose.onNodeWithTag("playerCenterPlayPauseButton").assertDoesNotExist()
    }

    @Test
    fun errorOverlayStaysRectangle() {
        compose.setContent {
            OmaTheme("default") {
                PlayerOverlay(
                    state = PlayerUiState(loading = false, error = "boom"),
                    colors = colors,
                    onRetry = {},
                    onReplay = {},
                )
            }
        }

        val size = compose.onNodeWithTag("playerOverlay").fetchSemanticsNode().size
        org.junit.Assert.assertTrue(
            "error overlay should stay wider than tall but was ${size.width}x${size.height}",
            size.width > size.height,
        )
    }

    @Test
    fun transcriptShowsCueTimestampAndActiveMarker() {
        val cues = listOf(
            TranscriptCue(
                0L,
                5_000L,
                listOf(TranscriptWord("Opening", 0L), TranscriptWord("sentence", 2_000L)),
            ),
            TranscriptCue(5_000L, 10_000L, listOf(TranscriptWord("Next", 5_000L))),
        )
        val playerState = mutableStateOf(
            PlayerUiState(transcriptCues = cues, positionMs = 2_500L),
        )
        compose.setContent {
            OmaTheme("default") {
                TranscriptPanel(playerState.value, colors, onSeek = {})
            }
        }
        compose.onNodeWithTag("playerTranscript").assertExists()
        compose.onNodeWithTag("playerTranscriptCue_0").assertExists()
        compose.onNodeWithText("0:00").assertExists()
        compose.onNodeWithTag("playerTranscriptActive_0").assertExists()
        compose.onNodeWithTag("playerTranscriptActive_1").assertDoesNotExist()

        compose.runOnIdle {
            playerState.value = playerState.value.copy(positionMs = 5_500L)
        }

        compose.onNodeWithTag("playerTranscriptActive_0").assertDoesNotExist()
        compose.onNodeWithTag("playerTranscriptActive_1").assertExists()
    }

    @Test
    fun transcriptEmptyStateIsCompactAndTagged() {
        compose.setContent {
            OmaTheme("default") {
                TranscriptPanel(PlayerUiState(transcriptLoading = false), colors, onSeek = {})
            }
        }
        compose.onNodeWithTag("playerTranscript").assertExists()
        compose.onNodeWithTag("playerTranscriptEmpty").assertExists()
        compose.onNodeWithText("Transcript unavailable").assertExists()
    }

    @Test
    fun transcriptTapSeeksExactWordFromMeasuredTextNode() {
        var sought = -1L
        val cue = TranscriptCue(
            0L,
            10_000L,
            listOf(
                TranscriptWord("first", 0L),
                TranscriptWord("middle", 2_000L),
                TranscriptWord("target", 4_000L),
            ),
        )
        compose.setContent {
            OmaTheme("default") {
                TranscriptPanel(
                    PlayerUiState(transcriptCues = listOf(cue)),
                    colors,
                    onSeek = { sought = it },
                    modifier = Modifier.width(150.dp),
                )
            }
        }
        val text = compose.onNodeWithTag("playerTranscriptText_0")
        val bounds = text.fetchSemanticsNode().boundsInRoot
        text.performTouchInput {
            down(Offset(8f, bounds.height - 8f))
            up()
        }
        compose.waitForIdle()
        assertEquals(4_000L, sought)
    }

    @Test
    fun transcriptMarkerFollowsWrappedActiveWordLine() {
        val cue = TranscriptCue(
            0L,
            10_000L,
            listOf(
                TranscriptWord("first", 0L),
                TranscriptWord("second", 2_000L),
                TranscriptWord("third", 4_000L),
                TranscriptWord("fourth", 6_000L),
            ),
        )
        val state = mutableStateOf(PlayerUiState(transcriptCues = listOf(cue), positionMs = 0L))
        compose.setContent {
            OmaTheme("default") {
                TranscriptPanel(state.value, colors, onSeek = {}, modifier = Modifier.width(120.dp))
            }
        }
        val marker = compose.onNodeWithTag("playerTranscriptActive_0")
        val firstTop = marker.fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { state.value = state.value.copy(positionMs = 6_000L) }
        compose.waitForIdle()
        val laterTop = marker.fetchSemanticsNode().boundsInRoot.top
        assertTrue("wrapped active word should move marker down", laterTop > firstTop)
    }
}
