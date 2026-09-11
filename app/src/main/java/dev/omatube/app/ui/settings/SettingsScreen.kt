package dev.omatube.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omatube.app.model.Category
import dev.omatube.app.model.Channel
import dev.omatube.app.model.LibrarySnapshot
import dev.omatube.app.model.Settings

/**
 * Native Compose port of the desktop `SettingsDialog.qml` and
 * `SimpleSettingsDialog.qml`.
 *
 * All persistence is delegated through [onSettingsChange]; the screen never
 * writes files or storage itself. SAF import/export launchers live here because
 * the contract requires the screen to own them. Every state mutation is built
 * from the [settings] value received in the current composition, so concurrent
 * updates from the host are never overwritten by a stale copy.
 */
@Composable
fun SettingsScreen(
    library: LibrarySnapshot,
    settings: Settings,
    addingChannel: Boolean,
    error: String?,
    onClose: () -> Unit,
    onSettingsChange: (Settings) -> Unit,
    onAddChannel: (String, Set<Long>) -> Unit,
    onRemoveChannel: (String) -> Unit,
    onSetChannelCategories: (String, Set<Long>) -> Unit,
    onAddCategory: (String) -> Unit,
    onRenameCategory: (Long, String) -> Unit,
    onRemoveCategory: (Long) -> Unit,
    onImport: (Uri) -> Unit,
    onExport: (Uri, Boolean) -> Unit,
    onDismissError: () -> Unit,
) {
    val simpleUi = settings.simpleUi
    val chrome = !simpleUi
    val palette = remember(settings.themeId, simpleUi) {
        SettingsPalette.forTheme(settings.themeId, simpleUi)
    }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var confirmRequest by remember { mutableStateOf<ConfirmRequest?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) onImport(uri) }
    val channelsExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) onExport(uri, true) }
    val categoriesExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) onExport(uri, false) }

    val tabs = listOf(
        "Channels" to "settingsChannelsTab",
        "Categories" to "settingsCategoriesTab",
        "Feed" to "settingsFeedTab",
        "Appearance" to "settingsAppearanceTab",
        "Playback" to "settingsPlaybackTab",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settingsWindow")
            .background(palette.panel),
    ) {
        SettingsHeader(
            palette = palette,
            chrome = chrome,
            simpleUi = simpleUi,
            onClose = onClose,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settingsTabs"),
        ) {
            SettingsTabs(
                tabs = tabs,
                selectedIndex = selectedTab,
                palette = palette,
                chrome = chrome,
                height = if (simpleUi) 48.dp else 44.dp,
                onSelect = { selectedTab = it },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when (selectedTab) {
                0 -> ChannelsTab(
                    library = library,
                    palette = palette,
                    chrome = chrome,
                    addingChannel = addingChannel,
                    error = error,
                    onAddChannel = onAddChannel,
                    onSetChannelCategories = onSetChannelCategories,
                    onImportChannels = { importLauncher.launch(arrayOf("application/json")) },
                    onExportChannels = { channelsExportLauncher.launch("omatube-channels.json") },
                    onRequestRemove = { id, name -> confirmRequest = ConfirmRequest.Channel(id, name) },
                )

                1 -> CategoriesTab(
                    library = library,
                    palette = palette,
                    onAddCategory = onAddCategory,
                    onRenameCategory = onRenameCategory,
                    onImportCategories = { importLauncher.launch(arrayOf("application/json")) },
                    onExportCategories = { categoriesExportLauncher.launch("omatube-categories.json") },
                    onRequestDelete = { id, name -> confirmRequest = ConfirmRequest.Category(id, name) },
                )

                2 -> FeedTab(
                    settings = settings,
                    palette = palette,
                    chrome = chrome,
                    onSettingsChange = onSettingsChange,
                )

                3 -> AppearanceTab(
                    settings = settings,
                    palette = palette,
                    chrome = chrome,
                    onSettingsChange = onSettingsChange,
                )

                else -> PlaybackTab(
                    settings = settings,
                    palette = palette,
                    chrome = chrome,
                    onSettingsChange = onSettingsChange,
                )
            }
        }
        if (!error.isNullOrEmpty()) {
            SettingsErrorBar(
                message = error,
                palette = palette,
                onDismiss = onDismissError,
            )
        }
    }

    confirmRequest?.let { request ->
        SettingsConfirmDialog(
            title = when (request) {
                is ConfirmRequest.Category -> "Delete category?"
                is ConfirmRequest.Channel -> "Remove channel?"
            },
            message = when (request) {
                is ConfirmRequest.Category ->
                    "Delete '${request.name}'? Channels and cached videos will be kept."

                is ConfirmRequest.Channel ->
                    "Remove '${request.name}' and its cached videos?"
            },
            confirmLabel = "Yes",
            palette = palette,
            onConfirm = {
                when (request) {
                    is ConfirmRequest.Category -> onRemoveCategory(request.id)
                    is ConfirmRequest.Channel -> onRemoveChannel(request.id)
                }
                confirmRequest = null
            },
            onDismiss = { confirmRequest = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelsTab(
    library: LibrarySnapshot,
    palette: SettingsPalette,
    chrome: Boolean,
    addingChannel: Boolean,
    error: String?,
    onAddChannel: (String, Set<Long>) -> Unit,
    onSetChannelCategories: (String, Set<Long>) -> Unit,
    onImportChannels: () -> Unit,
    onExportChannels: () -> Unit,
    onRequestRemove: (String, String) -> Unit,
) {
    var channelText by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var selectedCategoryIds by remember { mutableStateOf(emptyList<Long>()) }

    LaunchedEffect(addingChannel, error) {
        if (submitted && !addingChannel && error == null) {
            channelText = ""
            submitted = false
        }
    }

    val submit = {
        if (SettingsLogic.isChannelInputValid(channelText) && !addingChannel) {
            submitted = true
            onAddChannel(channelText.trim(), selectedCategoryIds.toSet())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsSectionLabel(
                text = "Add a channel",
                palette = palette,
                chrome = false,
                modifier = Modifier.weight(1f),
            )
            SettingsButton(
                text = "Import JSON",
                palette = palette,
                testTag = "channelImportButton",
                onClick = onImportChannels,
            )
            Spacer(Modifier.width(8.dp))
            SettingsButton(
                text = "Export JSON",
                palette = palette,
                testTag = "channelExportButton",
                onClick = onExportChannels,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsTextField(
                value = channelText,
                onValueChange = { channelText = it },
                placeholder = "Handle with or without @, channel URL, or UC channel ID",
                palette = palette,
                enabled = !addingChannel,
                modifier = Modifier.weight(1f),
                imeAction = ImeAction.Done,
                testTag = "channelInput",
                onSubmit = { submit() },
            )
            Spacer(Modifier.width(8.dp))
            SettingsButton(
                text = if (addingChannel) "Adding..." else "Add",
                palette = palette,
                enabled = SettingsLogic.isChannelInputValid(channelText) && !addingChannel,
                testTag = "addChannelButton",
                onClick = { submit() },
            )
        }

        if (library.categories.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                library.categories.forEach { category ->
                    SettingsCheckbox(
                        checked = category.id in selectedCategoryIds,
                        text = category.name,
                        palette = palette,
                        chrome = chrome,
                        testTag = "addChannelCategory_${category.id}",
                        onCheckedChange = { checked ->
                            selectedCategoryIds = SettingsLogic
                                .toggleCategory(selectedCategoryIds.toSet(), category.id, checked)
                                .toList()
                        },
                    )
                }
            }
        }

        SettingsDivider(palette.rule)

        SettingsSectionLabel(
            text = "Subscribed channels",
            palette = palette,
            chrome = false,
        )

        if (library.channels.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = "No channels added yet.",
                    style = settingsTextStyle(palette.mutedInk, chrome = false, fontSize = 13.sp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(library.channels, key = { it.id }) { channel ->
                    ChannelRow(
                        channel = channel,
                        categories = library.categories,
                        palette = palette,
                        chrome = chrome,
                        onSetChannelCategories = onSetChannelCategories,
                        onRequestRemove = onRequestRemove,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelRow(
    channel: Channel,
    categories: List<Category>,
    palette: SettingsPalette,
    chrome: Boolean,
    onSetChannelCategories: (String, Set<Long>) -> Unit,
    onRequestRemove: (String, String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.paper)
            .border(1.dp, palette.rule)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                BasicText(
                    text = channel.title,
                    style = settingsTextStyle(palette.ink, chrome = false, fontSize = 14.sp, weight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (channel.handle.isNotEmpty()) {
                    BasicText(
                        text = channel.handle,
                        style = settingsTextStyle(palette.mutedInk, chrome = false, fontSize = 11.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            SettingsButton(
                text = "Remove",
                palette = palette,
                flat = true,
                testTag = "removeChannel_${channel.id}",
                onClick = { onRequestRemove(channel.id, channel.title) },
            )
        }

        if (categories.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                categories.forEach { category ->
                    SettingsCheckbox(
                        checked = category.id in channel.categoryIds,
                        text = category.name,
                        palette = palette,
                        chrome = chrome,
                        testTag = "channelCategory_${channel.id}_${category.id}",
                        onCheckedChange = { checked ->
                            onSetChannelCategories(
                                channel.id,
                                SettingsLogic.toggleCategory(channel.categoryIds, category.id, checked),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoriesTab(
    library: LibrarySnapshot,
    palette: SettingsPalette,
    onAddCategory: (String) -> Unit,
    onRenameCategory: (Long, String) -> Unit,
    onImportCategories: () -> Unit,
    onExportCategories: () -> Unit,
    onRequestDelete: (Long, String) -> Unit,
) {
    var newCategory by rememberSaveable { mutableStateOf("") }

    val add = {
        if (SettingsLogic.isCategoryNameValid(newCategory)) {
            onAddCategory(newCategory.trim())
            newCategory = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsButton(
                text = "Import JSON",
                palette = palette,
                testTag = "categoryImportButton",
                onClick = onImportCategories,
            )
            Spacer(Modifier.width(8.dp))
            SettingsButton(
                text = "Export JSON",
                palette = palette,
                testTag = "categoryExportButton",
                onClick = onExportCategories,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsTextField(
                value = newCategory,
                onValueChange = { newCategory = it },
                placeholder = "New category",
                palette = palette,
                modifier = Modifier.weight(1f),
                imeAction = ImeAction.Done,
                testTag = "newCategoryInput",
                onSubmit = { add() },
            )
            Spacer(Modifier.width(8.dp))
            SettingsButton(
                text = "Add",
                palette = palette,
                enabled = SettingsLogic.isCategoryNameValid(newCategory),
                testTag = "addCategoryButton",
                onClick = { add() },
            )
        }

        if (library.categories.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = "No categories yet.",
                    style = settingsTextStyle(palette.mutedInk, chrome = false, fontSize = 13.sp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(library.categories, key = { it.id }) { category ->
                    CategoryRow(
                        category = category,
                        palette = palette,
                        onRenameCategory = onRenameCategory,
                        onRequestDelete = onRequestDelete,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    category: Category,
    palette: SettingsPalette,
    onRenameCategory: (Long, String) -> Unit,
    onRequestDelete: (Long, String) -> Unit,
) {
    var edited by remember(category.id) { mutableStateOf(category.name) }
    LaunchedEffect(category.name) { edited = category.name }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(palette.paper)
            .border(1.dp, palette.rule)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsTextField(
            value = edited,
            onValueChange = { edited = it },
            placeholder = "New category",
            palette = palette,
            modifier = Modifier.weight(1f),
            imeAction = ImeAction.Done,
            testTag = "categoryName_${category.id}",
            onSubmit = {
                if (SettingsLogic.canRenameCategory(edited, category.name)) {
                    onRenameCategory(category.id, edited.trim())
                }
            },
        )
        Spacer(Modifier.width(6.dp))
        SettingsButton(
            text = "Save",
            palette = palette,
            enabled = SettingsLogic.canRenameCategory(edited, category.name),
            testTag = "saveCategory_${category.id}",
            onClick = { onRenameCategory(category.id, edited.trim()) },
        )
        Spacer(Modifier.width(6.dp))
        SettingsButton(
            text = "Delete",
            palette = palette,
            flat = true,
            testTag = "deleteCategory_${category.id}",
            onClick = { onRequestDelete(category.id, category.name) },
        )
    }
}

@Composable
private fun FeedTab(
    settings: Settings,
    palette: SettingsPalette,
    chrome: Boolean,
    onSettingsChange: (Settings) -> Unit,
) {
    var keyText by rememberSaveable { mutableStateOf("") }
    val cutoff = SettingsLogic.clampCutoff(settings.shortVideoCutoffMinutes)

    val useKey = {
        if (SettingsLogic.isApiKeyValid(keyText)) {
            onSettingsChange(SettingsLogic.withApiKey(settings, keyText, settings.rememberApiKey))
            keyText = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsSectionLabel(text = "Short video filter", palette = palette, chrome = false)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(palette.popup)
                .border(1.dp, palette.rule),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = "Hide videos up to",
                    style = settingsTextStyle(palette.ink, chrome = false, fontSize = 13.sp),
                )
                Spacer(Modifier.width(10.dp))
                SettingsStepper(
                    value = cutoff,
                    palette = palette,
                    modifier = Modifier.width(150.dp),
                    testTag = "shortVideoCutoff",
                    onChange = { onSettingsChange(SettingsLogic.withCutoff(settings, it)) },
                )
                Spacer(Modifier.width(10.dp))
                BasicText(
                    text = "minutes",
                    style = settingsTextStyle(palette.ink, chrome = false, fontSize = 13.sp),
                )
            }
        }

        SettingsDivider(palette.rule)

        SettingsSectionLabel(text = "YouTube Data API", palette = palette, chrome = false)

        SettingsTextField(
            value = keyText,
            onValueChange = { keyText = it },
            placeholder = "YouTube Data API key",
            palette = palette,
            password = true,
            testTag = "keyInput",
            onSubmit = { useKey() },
        )

        SettingsCheckbox(
            checked = settings.rememberApiKey,
            text = "Remember locally",
            palette = palette,
            chrome = chrome,
            testTag = "rememberApiKey",
            onCheckedChange = { onSettingsChange(SettingsLogic.withRememberApiKey(settings, it)) },
        )

        BasicText(
            text = "Remembered keys are encrypted and stored on this device. " +
                "Leave this unchecked to keep the key only for the current session.",
            style = settingsTextStyle(palette.mutedInk, chrome = false, fontSize = 12.sp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (settings.apiKey.isNotEmpty()) {
                SettingsButton(
                    text = "Clear current key",
                    palette = palette,
                    danger = true,
                    testTag = "clearApiKey",
                    onClick = { onSettingsChange(SettingsLogic.withClearedApiKey(settings)) },
                )
            }
            Spacer(Modifier.weight(1f))
            SettingsButton(
                text = "Use key",
                palette = palette,
                enabled = SettingsLogic.isApiKeyValid(keyText),
                testTag = "saveApiKey",
                onClick = { useKey() },
            )
        }

        BasicText(
            text = "Key changes apply on the next feed refresh.",
            style = settingsTextStyle(palette.mutedInk, chrome = false, fontSize = 12.sp),
        )
    }
}

@Composable
private fun AppearanceTab(
    settings: Settings,
    palette: SettingsPalette,
    chrome: Boolean,
    onSettingsChange: (Settings) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsSectionLabel(text = "Theme", palette = palette, chrome = false)

        SettingsDropdown(
            options = SettingsThemes.labels,
            selectedIndex = SettingsLogic.themeIndex(settings.themeId),
            palette = palette,
            modifier = Modifier.widthIn(max = 260.dp),
            testTag = "themeSelector",
            onSelect = { index ->
                onSettingsChange(SettingsLogic.withTheme(settings, SettingsThemes.ids[index]))
            },
        )

        SettingsDivider(palette.rule)

        SettingsSectionLabel(text = "Interface", palette = palette, chrome = false)

        SettingsCheckbox(
            checked = settings.simpleUi,
            text = "Use simple UI",
            palette = palette,
            chrome = chrome,
            testTag = "simpleUiCheckBox",
            onCheckedChange = { onSettingsChange(SettingsLogic.withSimpleUi(settings, it)) },
        )
    }
}

@Composable
private fun PlaybackTab(
    settings: Settings,
    palette: SettingsPalette,
    chrome: Boolean,
    onSettingsChange: (Settings) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsSectionLabel(text = "Playback", palette = palette, chrome = false)

        SettingsSectionLabel(text = "Backend", palette = palette, chrome = false, fontSize = 13.sp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(palette.paper)
                .border(1.dp, palette.rule)
                .padding(horizontal = 12.dp)
                .testTag("backendInfo"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsDot(palette.accent)
            Spacer(Modifier.width(10.dp))
            BasicText(
                text = "ExoPlayer",
                style = settingsTextStyle(palette.ink, chrome = false, fontSize = 13.sp),
                modifier = Modifier.weight(1f),
            )
            BasicText(
                text = "Media3",
                style = settingsTextStyle(palette.mutedInk, chrome = false, fontSize = 11.sp),
            )
        }

        SettingsSectionLabel(
            text = "Preferred maximum quality",
            palette = palette,
            chrome = false,
            fontSize = 13.sp,
        )

        SettingsDropdown(
            options = SettingsLogic.qualityLabels,
            selectedIndex = SettingsLogic.qualityIndex(settings.maximumVideoHeight),
            palette = palette,
            testTag = "qualitySelector",
            onSelect = { index ->
                onSettingsChange(SettingsLogic.withQuality(settings, SettingsLogic.qualityValues[index]))
            },
        )

        BasicText(
            text = "ExoPlayer picks the closest available quality at or below this maximum.",
            style = settingsTextStyle(palette.mutedInk, chrome = false, fontSize = 12.sp),
        )

        SettingsDivider(palette.rule)

        SettingsSectionLabel(text = "SponsorBlock", palette = palette, chrome = false, fontSize = 13.sp)

        SettingsCheckbox(
            checked = settings.sponsorBlockEnabled,
            text = "Enable SponsorBlock (default off)",
            palette = palette,
            chrome = chrome,
            testTag = "sponsorBlockToggle",
            onCheckedChange = { onSettingsChange(SettingsLogic.withSponsorEnabled(settings, it)) },
        )

        SettingsLogic.sponsorRows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsDot(palette.sponsorColor(row.colorKey))
                Spacer(Modifier.width(8.dp))
                BasicText(
                    text = row.label,
                    style = settingsTextStyle(palette.ink, chrome = false, fontSize = 13.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                SettingsDropdown(
                    options = SettingsLogic.sponsorActionLabels,
                    selectedIndex = SettingsLogic.sponsorActionValue(settings.sponsorActions[row.key]),
                    palette = palette,
                    enabled = settings.sponsorBlockEnabled,
                    modifier = Modifier.width(160.dp),
                    testTag = "sponsorAction_${row.key}",
                    onSelect = { index ->
                        onSettingsChange(
                            SettingsLogic.withSponsorAction(
                                settings,
                                row.key,
                                SettingsLogic.sponsorActionFromValue(index),
                            ),
                        )
                    },
                )
            }
        }
    }
}

private sealed interface ConfirmRequest {
    val name: String

    data class Category(val id: Long, override val name: String) : ConfirmRequest
    data class Channel(val id: String, override val name: String) : ConfirmRequest
}
