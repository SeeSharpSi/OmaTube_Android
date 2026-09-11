package dev.omatube.app.ui.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.omatube.app.model.Category
import dev.omatube.app.model.Channel
import dev.omatube.app.model.LibrarySnapshot
import dev.omatube.app.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val fixtureCategories = listOf(
        Category(id = 1L, name = "News"),
        Category(id = 2L, name = "Music"),
    )

    private val fixtureChannels = listOf(
        Channel(id = "chan-1", title = "First Channel", handle = "@first", categoryIds = setOf(1L)),
    )

    private fun content(
        settings: Settings = Settings(),
        library: LibrarySnapshot = LibrarySnapshot(
            categories = fixtureCategories,
            channels = fixtureChannels,
        ),
        addingChannel: Boolean = false,
        error: String? = null,
        onSettingsChange: (Settings) -> Unit = {},
        onRemoveChannel: (String) -> Unit = {},
        onSetChannelCategories: (String, Set<Long>) -> Unit = { _, _ -> },
        onAddChannel: (String, Set<Long>) -> Unit = { _, _ -> },
        onAddCategory: (String) -> Unit = {},
        onRenameCategory: (Long, String) -> Unit = { _, _ -> },
        onRemoveCategory: (Long) -> Unit = {},
        onClose: () -> Unit = {},
        onImport: (android.net.Uri) -> Unit = {},
        onExport: (android.net.Uri, Boolean) -> Unit = { _, _ -> },
        onDismissError: () -> Unit = {},
    ) {
        compose.setContent {
            SettingsScreen(
                library = library,
                settings = settings,
                addingChannel = addingChannel,
                error = error,
                onClose = onClose,
                onSettingsChange = onSettingsChange,
                onAddChannel = onAddChannel,
                onRemoveChannel = onRemoveChannel,
                onSetChannelCategories = onSetChannelCategories,
                onAddCategory = onAddCategory,
                onRenameCategory = onRenameCategory,
                onRemoveCategory = onRemoveCategory,
                onImport = onImport,
                onExport = onExport,
                onDismissError = onDismissError,
            )
        }
    }

    @Test
    fun exposesAllFiveTabs() {
        content()
        compose.onNodeWithTag("settingsChannelsTab").assertExists()
        compose.onNodeWithTag("settingsCategoriesTab").assertExists()
        compose.onNodeWithTag("settingsFeedTab").assertExists()
        compose.onNodeWithTag("settingsAppearanceTab").assertExists()
        compose.onNodeWithTag("settingsPlaybackTab").assertExists()
        compose.onNodeWithTag("settingsCloseButton").assertExists()
    }

    @Test
    fun switchingTabsRevealsTheirControls() {
        content()
        compose.onNodeWithTag("settingsFeedTab").performClick()
        compose.onNodeWithTag("shortVideoCutoff").assertExists()
        compose.onNodeWithTag("keyInput").assertExists()

        compose.onNodeWithTag("settingsPlaybackTab").performClick()
        compose.onNodeWithTag("sponsorBlockToggle").assertExists()
        compose.onNodeWithTag("sponsorAction_sponsor").assertExists()
        compose.onNodeWithTag("qualitySelector").assertExists()
    }

    @Test
    fun simpleUiCheckboxEmitsImmediately() {
        var latest: Settings? = null
        content(onSettingsChange = { latest = it })

        compose.onNodeWithTag("settingsAppearanceTab").performClick()
        compose.onNodeWithTag("simpleUiCheckBox").performClick()

        assertEquals(true, latest?.simpleUi)
    }

    @Test
    fun themeDropdownEmitsSelectedTheme() {
        var latest: Settings? = null
        content(onSettingsChange = { latest = it })

        compose.onNodeWithTag("settingsAppearanceTab").performClick()
        compose.onNodeWithTag("themeSelector").performClick()
        compose.onNodeWithTag("themeSelector_option_2").performClick()

        assertEquals("nord", latest?.themeId)
    }

    @Test
    fun sponsorActionsAreDisabledUntilSponsorBlockIsEnabled() {
        content()
        compose.onNodeWithTag("settingsPlaybackTab").performClick()
        compose.onNodeWithTag("sponsorAction_sponsor").assertIsNotEnabled()
    }

    @Test
    fun sponsorActionsAreEnabledWhenSponsorBlockIsOn() {
        content(settings = Settings(sponsorBlockEnabled = true))
        compose.onNodeWithTag("settingsPlaybackTab").performClick()
        compose.onNodeWithTag("sponsorAction_sponsor").assertIsEnabled()
    }

    @Test
    fun deleteCategoryConfirmsBeforeEmitting() {
        var removed: Long? = null
        content(onRemoveCategory = { removed = it })

        compose.onNodeWithTag("settingsCategoriesTab").performClick()
        compose.onNodeWithTag("deleteCategory_1").performClick()
        compose.onNodeWithText("Delete category?").assertExists()
        compose.onNodeWithTag("settingsConfirmYes").performClick()

        assertEquals(1L, removed)
    }

    @Test
    fun removeChannelConfirmsBeforeEmitting() {
        var removed: String? = null
        content(onRemoveChannel = { removed = it })

        compose.onNodeWithTag("removeChannel_chan-1").performClick()
        compose.onNodeWithText("Remove channel?").assertExists()
        compose.onNodeWithTag("settingsConfirmYes").performClick()

        assertEquals("chan-1", removed)
    }

    @Test
    fun errorBarDismissesAndCloseButtonCloses() {
        var dismissed = false
        var closed = false
        content(error = "boom", onDismissError = { dismissed = true }, onClose = { closed = true })

        compose.onNodeWithTag("settingsError").assertExists()
        compose.onNodeWithTag("settingsErrorClose").performClick()
        assertTrue(dismissed)

        compose.onNodeWithTag("settingsCloseButton").performClick()
        assertTrue(closed)
    }
}
