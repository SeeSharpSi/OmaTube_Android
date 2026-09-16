package dev.omatube.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
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

    @Test
    fun transcriptFollowScrollsActiveCueIntoView() {
        val cues = (0 until 30).map { i ->
            TranscriptCue(
                i * 5_000L,
                (i + 1) * 5_000L,
                (0 until 12).map { w -> TranscriptWord("word${i}_$w", i * 5_000L + w * 300L) },
            )
        }
        val playerState = mutableStateOf(PlayerUiState(transcriptCues = cues, positionMs = 2_500L))
        compose.setContent {
            OmaTheme("default") {
                TranscriptPanel(
                    playerState.value,
                    colors,
                    onSeek = {},
                    modifier = Modifier.height(220.dp),
                )
            }
        }
        compose.onNodeWithTag("playerTranscriptCue_0").assertIsDisplayed()

        // Adjacent walk: exercises fixed-speed catch-up.
        (1..10).forEach { i ->
            compose.runOnIdle {
                playerState.value = playerState.value.copy(positionMs = i * 5_000L + 2_500L)
            }
            compose.waitForIdle()
        }
        compose.onNodeWithTag("playerTranscriptCue_10").assertIsDisplayed()

        // Far jump: fixed-speed catch-up must expose target within one second
        // of Compose test time, without relying on wall-clock waiting.
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle {
                playerState.value = playerState.value.copy(positionMs = 27 * 5_000L + 2_500L)
            }
            compose.mainClock.advanceTimeBy(1_000L)
            compose.waitForIdle()
            compose.onNodeWithTag("playerTranscriptCue_27").assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun transcriptGutterSpansFullCueHeight() {
        val cue = TranscriptCue(
            0L,
            10_000L,
            (0 until 12).map { w -> TranscriptWord("word$w", w * 500L) },
        )
        compose.setContent {
            OmaTheme("default") {
                TranscriptPanel(
                    PlayerUiState(transcriptCues = listOf(cue)),
                    colors,
                    onSeek = {},
                    modifier = Modifier.height(400.dp),
                )
            }
        }
        val gutter = compose.onNodeWithTag("playerTranscriptScrub").fetchSemanticsNode()
        val panel = compose.onNodeWithTag("playerTranscript").fetchSemanticsNode()
        val tolerance = with(compose.density) { 2.dp.toPx() }
        // The scrub overlay semantics sit inside its 8dp top/bottom padding
        // (same as the LazyColumn), so its bounds are the inner height:
        // panel height minus 16dp.
        val verticalPadding = with(compose.density) { 16.dp.toPx() }
        val expectedInner = panel.boundsInRoot.height - verticalPadding
        assertTrue("inner overlay height must stay positive", expectedInner > 0f)
        assertEquals(
            "inner overlay ${gutter.boundsInRoot.height} should be panel ${panel.boundsInRoot.height} minus 16dp",
            expectedInner,
            gutter.boundsInRoot.height,
            tolerance,
        )
    }

    @Test
    fun transcriptGutterScrubSeeksAndFollowsActiveCue() {
        val cues = (0 until 30).map { i ->
            TranscriptCue(
                i * 5_000L,
                (i + 1) * 5_000L,
                (0 until 12).map { w -> TranscriptWord("word${i}_$w", i * 5_000L + w * 300L) },
            )
        }
        val playerState = mutableStateOf(PlayerUiState(transcriptCues = cues, positionMs = 2_500L))
        compose.setContent {
            OmaTheme("default") {
                TranscriptPanel(
                    playerState.value,
                    colors,
                    onSeek = { playerState.value = playerState.value.copy(positionMs = it) },
                    modifier = Modifier.height(220.dp),
                )
            }
        }
        compose.onNodeWithTag("playerTranscriptCue_0").assertIsDisplayed()
        val gutter = compose.onNodeWithTag("playerTranscriptScrub")
        val gutterSize = gutter.fetchSemanticsNode().size
        gutter.performTouchInput {
            down(Offset(gutterSize.width / 2f, 10f))
            moveTo(Offset(gutterSize.width / 2f, gutterSize.height - 10f))
            moveTo(Offset(gutterSize.width / 2f, gutterSize.height - 10f))
            up()
        }
        compose.waitForIdle()
        val sought = playerState.value.positionMs
        assertTrue("scrub should advance position beyond the first cue, was $sought", sought > 5_000L)
        val active = (sought / 5_000L).toInt().coerceIn(0, 29)
        compose.onNodeWithTag("playerTranscriptCue_$active").assertIsDisplayed()
    }

    @Test
    fun transcriptScrubEdgeScrollContinuesWhileHeldOutside() {
        val cues = varyingTranscriptCues(30)
        val startPosition = 15 * 5_000L + 2_500L
        val playerState = mutableStateOf(
            PlayerUiState(transcriptCues = cues, positionMs = startPosition),
        )
        compose.setContent {
            OmaTheme("default") {
                TranscriptPanel(
                    playerState.value,
                    colors,
                    onSeek = { playerState.value = playerState.value.copy(positionMs = it) },
                    modifier = Modifier.height(320.dp),
                )
            }
        }
        compose.mainClock.autoAdvance = false
        try {
            val gutter = compose.onNodeWithTag("playerTranscriptScrub")
            val gutterSize = gutter.fetchSemanticsNode().size
            val centerX = gutterSize.width / 2f
            // Start inside near the bottom, then use a small realistic timed
            // sample to reach the edge. A huge instantaneous jump would imply
            // an unrealistic fling velocity and scroll the list offscreen.
            val downY = gutterSize.height - 45f
            val edgeDy = 30f
            val edgeDtMillis = 240L
            val initialCueTop = compose.onNodeWithTag("playerTranscriptCue_15")
                .fetchSemanticsNode().boundsInRoot.top
            gutter.performTouchInput {
                down(Offset(centerX, downY))
                moveTo(Offset(centerX, downY + edgeDy), edgeDtMillis)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onNodeWithTag("playerTranscriptScrubMarker").assertExists()
            // First held interval consumes the one-time overshoot residual.
            compose.mainClock.advanceTimeBy(200L)
            val heldCueTop = compose.onNodeWithTag("playerTranscriptCue_15")
                .fetchSemanticsNode().boundsInRoot.top
            // Second held interval with no moves must keep scrolling.
            compose.mainClock.advanceTimeBy(400L)
            val laterCueTop = compose.onNodeWithTag("playerTranscriptCue_15")
                .fetchSemanticsNode().boundsInRoot.top
            assertTrue(
                "edge scroll must start while held outside, was $initialCueTop then $heldCueTop",
                heldCueTop < initialCueTop,
            )
            assertTrue(
                "edge scroll must keep moving while held with no moves, was $heldCueTop then $laterCueTop",
                laterCueTop < heldCueTop,
            )
            var observed = 0L
            compose.runOnIdle { observed = playerState.value.positionMs }
            assertEquals(
                "controller seek should wait until scrub release",
                startPosition,
                observed,
            )
            gutter.performTouchInput { up() }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            var releasedPosition = 0L
            compose.runOnIdle { releasedPosition = playerState.value.positionMs }
            assertTrue(
                "release should commit the preview seek, was $releasedPosition",
                releasedPosition > startPosition,
            )
            compose.mainClock.advanceTimeBy(500L)
            compose.waitForIdle()
            var settledPosition = 0L
            compose.runOnIdle { settledPosition = playerState.value.positionMs }
            assertEquals(
                "release must stop the edge scroll loop",
                releasedPosition,
                settledPosition,
            )
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun varyingTranscriptCues(count: Int = 30): List<TranscriptCue> =
        (0 until count).map { i ->
            val wordCount = when (i % 4) {
                0 -> 3
                1 -> 12
                2 -> 5
                else -> 8
            }
            TranscriptCue(
                i * 5_000L,
                (i + 1) * 5_000L,
                (0 until wordCount).map { w -> TranscriptWord("word${i}_$w", i * 5_000L + w * 300L) },
            )
        }

    @Test
    fun transcriptScrubMarkerGainIsAmplifiedAcrossGap() {
        val cues = varyingTranscriptCues(30)
        val startPosition = 2_500L
        val playerState = mutableStateOf(PlayerUiState(transcriptCues = cues, positionMs = startPosition))
        var sought: Long? = null
        compose.setContent {
            OmaTheme("default") {
                TranscriptPanel(
                    playerState.value,
                    colors,
                    onSeek = { sought = it },
                    modifier = Modifier.height(320.dp),
                )
            }
        }
        compose.mainClock.autoAdvance = false
        try {
            val gutter = compose.onNodeWithTag("playerTranscriptScrub")
            val gutterSize = gutter.fetchSemanticsNode().size
            val centerX = gutterSize.width / 2f
            val gutterBounds = gutter.fetchSemanticsNode().boundsInRoot
            val preceding = compose.onNodeWithTag("playerTranscriptCue_5").fetchSemanticsNode()
            val following = compose.onNodeWithTag("playerTranscriptCue_6").fetchSemanticsNode()
            val gapMidpointRoot = (preceding.boundsInRoot.bottom + following.boundsInRoot.top) / 2f
            val downY = preceding.boundsInRoot.center.y - gutterBounds.top
            gutter.performTouchInput { down(Offset(centerX, downY)) }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onNodeWithTag("playerTranscriptScrubMarker").assertExists()
            val markerTop0 = gutterMarkerTop()
            val cue0Top = preceding.boundsInRoot.top
            val gapTop = preceding.boundsInRoot.bottom
            val gapBottom = following.boundsInRoot.top
            assertTrue("expected a 12dp row gap, was $gapTop to $gapBottom", gapBottom > gapTop)

            val gapMidpoint = gapMidpointRoot - gutterBounds.top
            val markerTop0Local = markerTop0 - gutterBounds.top
            val gapFingerY = downY + (gapMidpoint - markerTop0Local) / TRANSCRIPT_SCRUB_GAIN
            gutter.performTouchInput { moveTo(Offset(centerX, gapFingerY), 50) }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertEquals("marker must land at measured gap midpoint", gapMidpointRoot, gutterMarkerTop(), 2f)

            val followingMidpoint = following.boundsInRoot.center.y - gutterBounds.top
            val followingFingerY = gapFingerY + (followingMidpoint - gapMidpoint) / TRANSCRIPT_SCRUB_GAIN
            gutter.performTouchInput { moveTo(Offset(centerX, followingFingerY), 50) }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertEquals(
                "marker must land in following measured row",
                following.boundsInRoot.center.y,
                gutterMarkerTop(),
                2f,
            )
            compose.onNodeWithTag("playerTranscriptScrubMarker").assertExists()
            // List must not move while the marker stays inside the viewport.
            val cue0Later = compose.onNodeWithTag("playerTranscriptCue_5")
                .fetchSemanticsNode().boundsInRoot.top
            assertEquals("in-bounds scrub must not scroll the list", cue0Top, cue0Later, 1.5f)

            // External controller positions while held must not jump the marker.
            val heldTop = gutterMarkerTop()
            compose.runOnIdle {
                playerState.value = playerState.value.copy(positionMs = 20 * 5_000L + 1_000L)
            }
            compose.waitForIdle()
            assertEquals(
                "external position must not jump held marker",
                heldTop,
                gutterMarkerTop(),
                1.5f,
            )
            assertEquals("seek must wait for release", null, sought)

            gutter.performTouchInput { up() }
            compose.waitForIdle()
            assertTrue("release must commit exactly one seek", (sought ?: -1L) >= 0L)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun gutterMarkerTop(): Float =
        compose.onNodeWithTag("playerTranscriptScrubMarker").fetchSemanticsNode().boundsInRoot.top

    @Test
    fun transcriptScrubBottomEdgePinsAndScrollsAtAmplifiedSpeed() {
        val cues = varyingTranscriptCues(40)
        val startPosition = 5 * 5_000L + 2_500L
        val playerState = mutableStateOf(PlayerUiState(transcriptCues = cues, positionMs = startPosition))
        compose.setContent {
            OmaTheme("default") {
                TranscriptPanel(
                    playerState.value,
                    colors,
                    onSeek = { playerState.value = playerState.value.copy(positionMs = it) },
                    modifier = Modifier.height(320.dp),
                )
            }
        }
        compose.mainClock.autoAdvance = false
        try {
            val gutter = compose.onNodeWithTag("playerTranscriptScrub")
            val gutterSize = gutter.fetchSemanticsNode().size
            val gutterBounds = gutter.fetchSemanticsNode().boundsInRoot
            val density = compose.density
            val edgePx = with(density) { 2.dp.toPx() }
            val markerPx = with(density) { 1.dp.toPx() }
            val innerHeight = gutterSize.height
            assertTrue("inner overlay must stay positive", innerHeight > 0f)
            val centerX = gutterSize.width / 2f
            // Down 45px above the bottom pin, then a timed 30px push past it.
            val downOuterY = gutterSize.height - 45f
            val pushDy = 30f
            val pushDt = 240L
            gutter.performTouchInput {
                down(Offset(centerX, downOuterY))
                moveTo(Offset(centerX, downOuterY + pushDy), pushDt)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val expectedMarkerTop = gutterBounds.top + innerHeight - edgePx - markerPx
            val actualMarkerTop = gutterMarkerTop()
            val pinTolerance = with(density) { 1.dp.toPx() } + 1.5f
            assertEquals(
                "bottom marker must pin exactly 2dp inside inner viewport",
                expectedMarkerTop,
                actualMarkerTop,
                pinTolerance,
            )
            // Consume the one-time residual, then measure pure velocity speed.
            compose.mainClock.advanceTimeBy(200L)
            assertEquals(
                "pinned marker must stay stable while rows scroll",
                expectedMarkerTop,
                gutterMarkerTop(),
                pinTolerance,
            )
            val reference = compose.onNodeWithTag("playerTranscriptCue_8")
            reference.assertExists()
            val refTop0 = with(density) { reference.getUnclippedBoundsInRoot().top.toPx() }
            val intervalStartMs = compose.mainClock.currentTime
            compose.mainClock.advanceTimeBy(400L)
            compose.waitForIdle()
            val elapsedMs = compose.mainClock.currentTime - intervalStartMs
            reference.assertExists()
            val refTop1 = with(density) { reference.getUnclippedBoundsInRoot().top.toPx() }
            assertEquals(
                "pinned marker must remain while reference scrolls",
                expectedMarkerTop,
                gutterMarkerTop(),
                pinTolerance,
            )
            val displacement = refTop0 - refTop1
            assertTrue("bottom edge must scroll list, displacement was $displacement", displacement > 2f)
            val velocity = amplifiedVelocityPxPerSec(pushDy, pushDt)!!
            val expected = velocity * elapsedMs / 1000f
            val frameTolerance = kotlin.math.abs(velocity) * 2f * 16.667f / 1000f + 2f
            assertEquals(
                "list speed $displacement should match 1.75x finger velocity $velocity over ${elapsedMs}ms (expected $expected)",
                expected,
                displacement,
                frameTolerance,
            )
            val followPxPerSec = with(density) { 10_000.dp.toPx() }
            val followDistance = followPxPerSec * 0.4f
            assertTrue(
                "edge speed $displacement must not equal 10000dp/sec follow $followDistance",
                kotlin.math.abs(displacement - followDistance) > 4f,
            )
            // Second held interval with no moves keeps the same derived speed.
            val refTop2 = refTop1
            val secondIntervalStartMs = compose.mainClock.currentTime
            compose.mainClock.advanceTimeBy(400L)
            compose.waitForIdle()
            val secondElapsedMs = compose.mainClock.currentTime - secondIntervalStartMs
            reference.assertExists()
            val refTop3 = with(density) { reference.getUnclippedBoundsInRoot().top.toPx() }
            val displacement2 = refTop2 - refTop3
            assertTrue("second held interval must keep scrolling", displacement2 > 2f)
            val secondExpected = velocity * secondElapsedMs / 1000f
            assertEquals(
                "held speed must stay derived, was $displacement2 versus $secondExpected",
                secondExpected,
                displacement2,
                frameTolerance,
            )
            compose.mainClock.advanceTimeBy(1200L)
            compose.waitForIdle()
            compose.onNodeWithTag("playerTranscriptCue_5").assertDoesNotExist()
            compose.onNodeWithTag("playerTranscriptScrubMarker").assertExists()
            val laterCueStillComposed = (6 until cues.size).any { index ->
                compose.onAllNodes(hasTestTag("playerTranscriptCue_$index"))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            assertTrue("transcript must continue after origin cue disposal", laterCueStillComposed)
            gutter.performTouchInput { up() }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
    }

    @Test
    fun transcriptScrubTopEdgePinsAndScrollsAtAmplifiedSpeed() {
        val cues = varyingTranscriptCues(40)
        val startPosition = 30 * 5_000L + 2_500L
        val playerState = mutableStateOf(PlayerUiState(transcriptCues = cues, positionMs = startPosition))
        compose.setContent {
            OmaTheme("default") {
                TranscriptPanel(
                    playerState.value,
                    colors,
                    onSeek = { playerState.value = playerState.value.copy(positionMs = it) },
                    modifier = Modifier.height(320.dp),
                )
            }
        }
        compose.mainClock.autoAdvance = false
        try {
            val gutter = compose.onNodeWithTag("playerTranscriptScrub")
            val gutterSize = gutter.fetchSemanticsNode().size
            val gutterBounds = gutter.fetchSemanticsNode().boundsInRoot
            val density = compose.density
            val edgePx = with(density) { 2.dp.toPx() }
            val centerX = gutterSize.width / 2f
            // Slower input than the bottom test with a longer sample interval.
            val downOuterY = 45f
            val pushDy = -30f
            val pushDt = 480L
            gutter.performTouchInput {
                down(Offset(centerX, downOuterY))
                moveTo(Offset(centerX, downOuterY + pushDy), pushDt)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val expectedMarkerTop = gutterBounds.top + edgePx
            val pinTolerance = with(density) { 1.dp.toPx() } + 1.5f
            assertEquals(
                "top marker must pin exactly 2dp inside inner viewport",
                expectedMarkerTop,
                gutterMarkerTop(),
                pinTolerance,
            )
            compose.mainClock.advanceTimeBy(200L)
            assertEquals(
                "top pin must stay stable while rows scroll",
                expectedMarkerTop,
                gutterMarkerTop(),
                pinTolerance,
            )
            // Reference near the top that stays measurable with a slow push.
            val reference = compose.onNodeWithTag("playerTranscriptCue_30")
            reference.assertExists()
            val refTop0 = with(density) { reference.getUnclippedBoundsInRoot().top.toPx() }
            val intervalStartMs = compose.mainClock.currentTime
            compose.mainClock.advanceTimeBy(400L)
            val elapsedMs = compose.mainClock.currentTime - intervalStartMs
            reference.assertExists()
            val refTop1 = with(density) { reference.getUnclippedBoundsInRoot().top.toPx() }
            val displacement = refTop1 - refTop0
            assertTrue("top edge must scroll list downward, was $displacement", displacement > 2f)
            val velocity = amplifiedVelocityPxPerSec(pushDy, pushDt)!!
            val expected = kotlin.math.abs(velocity) * elapsedMs / 1000f
            val frameTolerance = kotlin.math.abs(velocity) * 2f * 16.667f / 1000f + 2f
            assertEquals(
                "top list speed $displacement should match 1.75x finger velocity ${-velocity} over ${elapsedMs}ms (expected $expected)",
                expected,
                displacement,
                frameTolerance,
            )
            val followPxPerSec = with(density) { 10_000.dp.toPx() }
            val followDistance = followPxPerSec * 0.4f
            assertTrue(
                "top edge speed $displacement must not equal 10000dp/sec follow $followDistance",
                kotlin.math.abs(displacement - followDistance) > 4f,
            )
            gutter.performTouchInput { up() }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
    }

    @Test
    fun transcriptScrubReleaseStopsAndReversalMovesInward() {
        val cues = varyingTranscriptCues(40)
        val startPosition = 12 * 5_000L + 2_500L
        val playerState = mutableStateOf(
            PlayerUiState(transcriptCues = cues, positionMs = startPosition, playing = true),
        )
        var seekCount = 0
        compose.setContent {
            OmaTheme("default") {
                TranscriptPanel(
                    playerState.value,
                    colors,
                    onSeek = {
                        seekCount++
                        playerState.value = playerState.value.copy(positionMs = it)
                    },
                    modifier = Modifier.height(320.dp),
                )
            }
        }
        compose.mainClock.autoAdvance = false
        try {
            val gutter = compose.onNodeWithTag("playerTranscriptScrub")
            val gutterSize = gutter.fetchSemanticsNode().size
            val centerX = gutterSize.width / 2f
            val density = compose.density
            val downOuterY = gutterSize.height - 45f
            gutter.performTouchInput {
                down(Offset(centerX, downOuterY))
                moveTo(Offset(centerX, downOuterY + 30f), 240)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(300L)
            // Move physical pointer outside overlay, then reverse 12px while it
            // remains outside. Marker must move inward by 1.75x that delta.
            val outsideY = gutterSize.height + 15f
            gutter.performTouchInput { moveTo(Offset(centerX, outsideY), 120) }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val outsideTop = gutterMarkerTop()
            gutter.performTouchInput { moveTo(Offset(centerX, outsideY - 12f), 120) }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val reversedTop = gutterMarkerTop()
            assertEquals(
                "reversal must move marker inward by amplified 12px while outside",
                outsideTop - 12f * TRANSCRIPT_SCRUB_GAIN,
                reversedTop,
                2f,
            )
            // After leaving the edge, a held interval must not keep scrolling.
            val reference = compose.onNodeWithTag("playerTranscriptCue_12")
            reference.assertExists()
            val stillTop0 = with(density) { reference.getUnclippedBoundsInRoot().top.toPx() }
            compose.mainClock.advanceTimeBy(300L)
            reference.assertExists()
            val stillTop1 = with(density) { reference.getUnclippedBoundsInRoot().top.toPx() }
            assertEquals(
                "leaving the edge must stop edge movement",
                stillTop0,
                stillTop1,
                2f,
            )
            // Release stops everything and commits.
            gutter.performTouchInput { up() }
            compose.waitForIdle()
            var committed = 0L
            compose.runOnIdle { committed = playerState.value.positionMs }
            assertTrue("release must commit", committed != startPosition)
            reference.assertExists()
            val frozenTop = with(density) { reference.getUnclippedBoundsInRoot().top.toPx() }
            compose.mainClock.advanceTimeBy(500L)
            compose.waitForIdle()
            reference.assertExists()
            val frozenLater = with(density) { reference.getUnclippedBoundsInRoot().top.toPx() }
            assertEquals("release must stop edge movement", frozenTop, frozenLater, 2f)
            var afterRelease = 0L
            compose.runOnIdle { afterRelease = playerState.value.positionMs }
            assertEquals("no extra seek after release", committed, afterRelease)
            // Cancel path: a fresh hold cancelled mid-edge commits nothing.
            val seeksBeforeCancel = seekCount
            gutter.performTouchInput {
                down(Offset(centerX, downOuterY))
                moveTo(Offset(centerX, downOuterY + 30f), 240)
            }
            compose.mainClock.advanceTimeBy(200L)
            gutter.performTouchInput { cancel() }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertEquals("cancel must not commit a seek", seeksBeforeCancel, seekCount)
            compose.onNodeWithTag("playerTranscriptScrubMarker").assertDoesNotExist()
            // Paused release at measured row-gap midpoint retains exact pixel.
            compose.runOnIdle {
                playerState.value = playerState.value.copy(playing = false)
            }
            compose.waitForIdle()
            val visibleCues = (0 until cues.size).mapNotNull { index ->
                compose.onAllNodes(hasTestTag("playerTranscriptCue_$index"))
                    .fetchSemanticsNodes()
                    .firstOrNull()
                    ?.let { index to it.boundsInRoot }
            }.sortedBy { it.second.top }
            val viewport = gutter.fetchSemanticsNode().boundsInRoot
            val visibleGap = visibleCues.zipWithNext()
                .firstOrNull { pair ->
                    val midpoint = (pair.first.second.bottom + pair.second.second.top) / 2f
                    pair.second.second.top > pair.first.second.bottom &&
                        midpoint > viewport.top && midpoint < viewport.bottom
                }
                ?: throw AssertionError("expected visible transcript row gap")
            val gapY = ((visibleGap.first.second.bottom + visibleGap.second.second.top) / 2f) -
                viewport.top
            gutter.performTouchInput { down(Offset(centerX, gapY)) }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val gapMarkerBeforeRelease = gutterMarkerTop()
            gutter.performTouchInput { up() }
            compose.waitForIdle()
            val gapMarkerAfterAck = gutterMarkerTop()
            assertEquals(
                "paused gap release must retain marker pixel after acknowledgement",
                gapMarkerBeforeRelease,
                gapMarkerAfterAck,
                1.5f,
            )
            compose.mainClock.advanceTimeBy(1_000L)
            compose.waitForIdle()
            assertEquals(
                "paused gap marker must not time out",
                gapMarkerAfterAck,
                gutterMarkerTop(),
                1.5f,
            )
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
