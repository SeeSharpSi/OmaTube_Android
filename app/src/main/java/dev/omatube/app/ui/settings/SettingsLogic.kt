package dev.omatube.app.ui.settings

import dev.omatube.app.model.Settings
import dev.omatube.app.model.SponsorAction

/**
 * Pure, Compose-free settings logic so behavior can be unit tested without a
 * device or the shared UI module. The composables in this package treat these
 * functions as the single source of truth for option ordering, clamping, and
 * validation.
 */
internal object SettingsLogic {

    const val CUTOFF_MIN = 0
    const val CUTOFF_MAX = 60

    /** Auto plus the exact maximum heights offered by the desktop Playback tab. */
    val qualityValues: List<Int> = listOf(0, 2160, 1440, 1080, 720, 480, 360)
    val qualityLabels: List<String> = listOf("Auto", "2160p", "1440p", "1080p", "720p", "480p", "360p")

    val sponsorActionLabels: List<String> = listOf("Nothing", "Manual skip", "Auto skip")

    data class SponsorRow(val key: String, val label: String, val colorKey: String)

    /** Order, labels and dot colors mirror the desktop SponsorBlock section. */
    val sponsorRows: List<SponsorRow> = listOf(
        SponsorRow("sponsor", "Sponsor", "green"),
        SponsorRow("selfpromo", "Self promotion", "bright_green"),
        SponsorRow("interaction", "Interaction reminder", "orange"),
        SponsorRow("intro", "Intro", "cyan"),
        SponsorRow("outro", "Outro", "blue"),
        SponsorRow("preview", "Preview / recap", "yellow"),
        SponsorRow("music_offtopic", "Music: non-music", "magenta"),
        SponsorRow("poi_highlight", "Highlight", "red"),
    )

    fun themeIndex(themeId: String): Int = SettingsThemes.ids.indexOf(themeId).coerceAtLeast(0)

    fun qualityIndex(height: Int): Int = qualityValues.indexOf(height).coerceAtLeast(0)

    fun clampCutoff(minutes: Int): Int = minutes.coerceIn(CUTOFF_MIN, CUTOFF_MAX)

    fun sponsorActionValue(action: SponsorAction?): Int = when (action) {
        SponsorAction.MANUAL -> 1
        SponsorAction.AUTO -> 2
        else -> 0
    }

    fun sponsorActionFromValue(value: Int): SponsorAction = when (value) {
        1 -> SponsorAction.MANUAL
        2 -> SponsorAction.AUTO
        else -> SponsorAction.NONE
    }

    fun toggleCategory(current: Set<Long>, categoryId: Long, checked: Boolean): Set<Long> =
        if (checked) current + categoryId else current - categoryId

    fun isChannelInputValid(input: String): Boolean = input.trim().isNotEmpty()

    fun isCategoryNameValid(name: String): Boolean = name.trim().isNotEmpty()

    fun canRenameCategory(edited: String, original: String): Boolean =
        edited.trim().isNotEmpty() && edited.trim() != original

    fun isApiKeyValid(key: String): Boolean = key.trim().isNotEmpty()

    fun withCutoff(settings: Settings, minutes: Int): Settings =
        settings.copy(shortVideoCutoffMinutes = clampCutoff(minutes))

    fun withTheme(settings: Settings, themeId: String): Settings =
        settings.copy(themeId = themeId)

    fun withSimpleUi(settings: Settings, simpleUi: Boolean): Settings =
        settings.copy(simpleUi = simpleUi)

    fun withQuality(settings: Settings, height: Int): Settings =
        settings.copy(maximumVideoHeight = height)

    fun withSponsorEnabled(settings: Settings, enabled: Boolean): Settings =
        settings.copy(sponsorBlockEnabled = enabled)

    fun withSponsorAction(settings: Settings, category: String, action: SponsorAction): Settings =
        settings.copy(sponsorActions = settings.sponsorActions + (category to action))

    fun withApiKey(settings: Settings, key: String, remember: Boolean): Settings =
        settings.copy(apiKey = key.trim(), rememberApiKey = remember)

    fun withRememberApiKey(settings: Settings, remember: Boolean): Settings =
        settings.copy(rememberApiKey = remember)

    fun withClearedApiKey(settings: Settings): Settings =
        settings.copy(apiKey = "")
}
