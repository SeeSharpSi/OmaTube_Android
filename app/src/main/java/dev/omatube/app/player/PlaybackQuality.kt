package dev.omatube.app.player

import dev.omatube.app.model.Settings

/**
 * Player quality vocabulary shared by the resolver, the settings bridge and the
 * chrome. Mirrors the desktop `PlayerControls.qml` quality options exactly.
 *
 * Values are maximum preferred video heights. `DEFAULT` (-1) is only used by the
 * per-video override UI: it means "no override, follow the global setting".
 * `AUTO` (0) means let the extractor/player choose the best available track.
 */
object PlaybackQuality {
    const val DEFAULT = -1
    const val AUTO = 0

    val HEIGHTS: List<Int> = listOf(2160, 1440, 1080, 720, 480, 360)

    data class Option(val value: Int, val label: String)

    val OPTIONS: List<Option> = buildList {
        add(Option(DEFAULT, "Default"))
        add(Option(AUTO, "Auto"))
        HEIGHTS.forEach { add(Option(it, "${it}p")) }
    }

    fun labelFor(value: Int): String = when (value) {
        DEFAULT -> "Default"
        AUTO -> "Auto"
        else -> "${value}p"
    }

    /**
     * Normalizes a persisted global height the same way the desktop
     * `PlaybackSettings::normalizeMaximumVideoHeight` does: unknown values become
     * [AUTO]. [DEFAULT] is not a valid global value.
     */
    fun normalizeGlobalHeight(value: Int): Int = if (value in HEIGHTS || value == AUTO) value else AUTO

    /**
     * Effective maximum height for a single video. The per-video override wins
     * when present; otherwise the global setting is used.
     */
    fun effectiveHeight(settings: Settings, videoId: String): Int {
        val override = settings.videoQualityOverrides[videoId]
        return if (override != null) normalizeGlobalHeight(override) else normalizeGlobalHeight(settings.maximumVideoHeight)
    }

    /**
     * Records a quality choice for one video. [DEFAULT] removes the override.
     * Returns a new settings instance; the caller persists it.
     */
    fun applyChoice(settings: Settings, videoId: String, value: Int): Settings {
        val overrides = settings.videoQualityOverrides.toMutableMap()
        if (value == DEFAULT) {
            overrides.remove(videoId)
        } else {
            overrides[videoId] = normalizeGlobalHeight(value)
        }
        return settings.copy(videoQualityOverrides = overrides)
    }
}
