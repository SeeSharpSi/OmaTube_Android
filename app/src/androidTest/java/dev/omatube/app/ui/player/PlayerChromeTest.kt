package dev.omatube.app.ui.player

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.omatube.app.player.PlaybackQuality
import dev.omatube.app.ui.theme.OmaColors
import dev.omatube.app.ui.theme.OmaTheme
import org.junit.Assert.assertEquals
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
    fun volumeSliderExposesStableTag() {
        compose.setContent {
            OmaTheme("default") {
                PlayerVolumeSlider(volume = 50, colors = colors, onVolume = {})
            }
        }

        compose.onNodeWithTag("playerVolumeSlider").assertExists()
    }
}
