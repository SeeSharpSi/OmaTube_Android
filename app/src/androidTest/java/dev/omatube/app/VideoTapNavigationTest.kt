package dev.omatube.app

import android.content.Intent
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.omatube.app.automation.AutomationConfig
import dev.omatube.app.automation.AutomationFixture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end root navigation coverage for the user-reported bug where a real
 * tap on a feed card did nothing.
 *
 * Each case launches the real [MainActivity] with the debug automation extras,
 * so the process uses the in-memory fixture and the fake backend and never
 * opens a real database, network or media stream. A physical pointer tap on
 * the feed card must select the fixture video and show the automation player;
 * system Back must then return to the feed. Launching `automation_route=player`
 * directly would bypass the tap path under test, so the player is never the
 * starting route here.
 */
@RunWith(AndroidJUnit4::class)
class VideoTapNavigationTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun fullFeedTapOpensFakePlayerThenBackReturnsToFeed() {
        tapFeedVideoOpensPlayerThenBackReturns(
            ui = AutomationConfig.UI_FULL,
            feedContainerTag = "feedGrid",
        )
    }

    @Test
    fun simpleFeedTapOpensFakePlayerThenBackReturnsToFeed() {
        tapFeedVideoOpensPlayerThenBackReturns(
            ui = AutomationConfig.UI_SIMPLE,
            feedContainerTag = "feedList",
        )
    }

    private fun tapFeedVideoOpensPlayerThenBackReturns(
        ui: String,
        feedContainerTag: String,
    ) {
        val videoId = AutomationFixture.PLAYER_VIDEO_ID
        val playerText = "Automation player $videoId"
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(AutomationConfig.EXTRA_AUTOMATION, true)
            .putExtra(AutomationConfig.EXTRA_UI, ui)
            .putExtra(AutomationConfig.EXTRA_THEME, "default")

        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(TIMEOUT_MILLIS) {
                compose.onAllNodesWithTag("feedVideo_$videoId").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(feedContainerTag).assertExists()

            compose.onNodeWithTag("feedVideo_$videoId").performTouchInput { click() }

            compose.waitUntil(TIMEOUT_MILLIS) {
                compose.onAllNodesWithText(playerText).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(playerText).assertExists()

            Espresso.pressBack()

            compose.waitUntil(TIMEOUT_MILLIS) {
                compose.onAllNodesWithTag("feedVideo_$videoId").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(playerText).assertDoesNotExist()
            compose.onNodeWithTag(feedContainerTag).assertExists()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
