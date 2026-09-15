package dev.omatube.app.ui.player

import androidx.compose.ui.graphics.Color
import dev.omatube.app.model.TranscriptCue
import dev.omatube.app.model.TranscriptWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic JVM tests for the pure portrait stack solver that drives the
 * reserved bars and the aspect-fitted video viewport.
 */
class PlayerGeometryTest {
    private val sixteenByNine = 16f / 9f

    @Test
    fun wideVideoFillsTheSafeWidthAndPinsStackToTop() {
        val geometry = computePortraitStackGeometry(
            containerWidth = 1080f,
            containerHeight = 2160f,
            videoAspect = sixteenByNine,
            topSlotHeight = 156f,
            bottomSlotHeight = 300f,
            minTranscriptHeight = 540f,
            bottomPadding = 36f,
        )

        assertEquals(1080f, geometry.videoWidth, 0.01f)
        assertEquals(607.5f, geometry.videoHeight, 0.01f)
        assertEquals(0f, geometry.videoLeft, 0.01f)
        assertEquals(0f, geometry.topSlotTop, 0.01f)
        assertEquals(156f, geometry.videoTop, 0.01f)
        assertEquals(763.5f, geometry.transcriptTop, 0.01f)
        assertEquals(1060.5f, geometry.transcriptHeight, 0.01f)
        assertEquals(1824f, geometry.bottomSlotTop, 0.01f)
        assertEquals(geometry.videoTop + geometry.videoHeight, geometry.transcriptTop, 0.01f)
        assertEquals(
            geometry.transcriptTop + geometry.transcriptHeight,
            geometry.bottomSlotTop,
            0.01f,
        )
        assertEquals(2160f, geometry.bottomSlotTop + 300f + 36f, 0.01f)
    }

    @Test
    fun tallVideoFitsTheRemainingHeightAndNarrows() {
        val geometry = computePortraitStackGeometry(
            containerWidth = 1080f,
            containerHeight = 2160f,
            videoAspect = 9f / 16f,
            topSlotHeight = 156f,
            bottomSlotHeight = 300f,
            minTranscriptHeight = 540f,
            bottomPadding = 36f,
        )

        // At 3x density, 540 px reserves 180 dp for transcript.
        assertEquals(1128f, geometry.videoHeight, 0.01f)
        assertEquals(634.5f, geometry.videoWidth, 0.01f)
        assertEquals(222.75f, geometry.videoLeft, 0.01f)
        assertEquals(0f, geometry.topSlotTop, 0.01f)
        assertEquals(156f, geometry.videoTop, 0.01f)
        assertEquals(1824f, geometry.bottomSlotTop, 0.01f)
        assertEquals(1284f, geometry.transcriptTop, 0.01f)
        assertEquals(540f, geometry.transcriptHeight, 0.01f)
    }

    @Test
    fun invalidAspectFallsBackToSixteenByNine() {
        val invalid = listOf(Float.NaN, 0f, -2f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        invalid.forEach { aspect ->
            val geometry = computePortraitStackGeometry(
                containerWidth = 1080f,
                containerHeight = 2160f,
                videoAspect = aspect,
                topSlotHeight = 156f,
                bottomSlotHeight = 300f,
                minTranscriptHeight = 540f,
                bottomPadding = 36f,
            )
            assertEquals("aspect=$aspect width", 1080f, geometry.videoWidth, 0.01f)
            assertEquals("aspect=$aspect height", 607.5f, geometry.videoHeight, 0.01f)
        }
    }

    @Test
    fun invalidContainerAndSlotInputsClampToNonNegative() {
        val geometry = computePortraitStackGeometry(
            containerWidth = Float.NaN,
            containerHeight = -100f,
            videoAspect = sixteenByNine,
            topSlotHeight = -50f,
            bottomSlotHeight = 20f,
            minTranscriptHeight = 180f,
            bottomPadding = 12f,
        )

        assertTrue(geometry.videoWidth >= 0f)
        assertTrue(geometry.videoHeight >= 0f)
        assertTrue(geometry.videoLeft >= 0f)
        assertTrue(geometry.videoTop >= 0f)
        assertTrue(geometry.topSlotTop >= 0f)
        assertTrue(geometry.bottomSlotTop >= 0f)
        assertEquals(0f, geometry.videoWidth, 0.001f)
        assertEquals(0f, geometry.videoHeight, 0.001f)
    }

    @Test
    fun squareVideoStaysInsideTheAvailableHeight() {
        val geometry = computePortraitStackGeometry(
            containerWidth = 1000f,
            containerHeight = 2000f,
            videoAspect = 1f,
            topSlotHeight = 100f,
            bottomSlotHeight = 100f,
            minTranscriptHeight = 180f,
            bottomPadding = 36f,
        )

        assertEquals(1000f, geometry.videoWidth, 0.01f)
        assertEquals(1000f, geometry.videoHeight, 0.01f)
        assertEquals(0f, geometry.topSlotTop, 0.01f)
        assertEquals(100f, geometry.videoTop, 0.01f)
        assertEquals(1864f, geometry.bottomSlotTop, 0.01f)
        assertEquals(1100f, geometry.transcriptTop, 0.01f)
        assertEquals(764f, geometry.transcriptHeight, 0.01f)
    }

    @Test
    fun activeCueLookupUsesHalfOpenRangesAndHandlesGaps() {
        val cues = listOf(
            TranscriptCue(100L, 200L, listOf(TranscriptWord("one", 100L))),
            TranscriptCue(300L, 400L, listOf(TranscriptWord("two", 300L))),
        )
        assertEquals(-1, activeTranscriptCueIndex(cues, 99L))
        assertEquals(0, activeTranscriptCueIndex(cues, 100L))
        assertEquals(-1, activeTranscriptCueIndex(cues, 200L))
        assertEquals(1, activeTranscriptCueIndex(cues, 399L))
        assertEquals(-1, activeTranscriptCueIndex(cues, 400L))
        assertEquals(-1, activeTranscriptCueIndex(cues, 9_999L))
    }

    @Test
    fun portraitChromeNeverAutoHides() {
        assertFalse(
            shouldAutoHideChrome(
                isPortrait = true,
                playing = true,
                chromeVisible = true,
                scrubbing = false,
            ),
        )
        assertTrue(
            shouldAutoHideChrome(
                isPortrait = false,
                playing = true,
                chromeVisible = true,
                scrubbing = false,
            ),
        )
    }

    @Test
    fun portraitCenterTransportAutoHidesOnlyWhilePlaying() {
        assertTrue(shouldAutoHidePortraitCenter(true, true, true))
        assertFalse(shouldAutoHidePortraitCenter(true, false, true))
        assertFalse(shouldAutoHidePortraitCenter(true, true, false))
        assertFalse(shouldAutoHidePortraitCenter(false, true, true))
    }

    @Test
    fun transcriptCharacterOffsetsMapExactWordsAndInsertedSpaces() {
        val cue = TranscriptCue(
            0L,
            3_000L,
            listOf(TranscriptWord("one", 0L), TranscriptWord("two", 1_000L)),
        )
        assertEquals(null, activeTranscriptWordCharacterOffset(cue, -1L))
        assertEquals(0, activeTranscriptWordCharacterOffset(cue, 0L))
        assertEquals(4, activeTranscriptWordCharacterOffset(cue, 1_000L))
        assertEquals(cue.words[0], transcriptWordAtCharacterOffset(cue, 0))
        assertEquals(cue.words[0], transcriptWordAtCharacterOffset(cue, 2))
        assertEquals(cue.words[1], transcriptWordAtCharacterOffset(cue, 3))
        assertEquals(cue.words[1], transcriptWordAtCharacterOffset(cue, 4))
        assertEquals(null, transcriptWordAtCharacterOffset(cue, 7))
    }

    @Test
    fun onlySpokenWordsInActiveCueReceiveHighlightStyles() {
        val cue = TranscriptCue(
            startMs = 0L,
            endMs = 3_000L,
            words = listOf(
                TranscriptWord("one", 0L),
                TranscriptWord("two", 1_000L),
                TranscriptWord("three", 2_000L),
            ),
        )

        val activeText = transcriptText(cue, 1_500L, active = true, highlight = Color.Red)
        val pastText = transcriptText(cue, 4_000L, active = false, highlight = Color.Red)

        assertEquals("one two three", activeText.text)
        assertEquals(2, activeText.spanStyles.size)
        assertTrue(pastText.spanStyles.isEmpty())
    }
}
