package dev.omatube.app.player

import dev.omatube.app.connectivity.ConnectionType
import dev.omatube.app.model.Settings

/**
 * Player quality vocabulary shared by the resolver, the settings bridge and the
 * chrome. Mirrors the desktop `PlayerControls.qml` quality options exactly.
 *
 * Values are maximum preferred video heights. `DEFAULT` (-1) is only used by the
 * per-video override UI: it means "no override, follow the connection
 * preference". `AUTO` (0) means let the extractor/player choose the best
 * available track. `LAST_USED` (-2) is only used by the connection preference
 * dropdowns: it means "resolve through the shared last-used concrete height".
 */
object PlaybackQuality {
    const val DEFAULT = -1
    const val AUTO = 0
    const val LAST_USED = -2

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

    /** True when [value] is a concrete global maximum: [AUTO] or a known height. */
    fun isConcrete(value: Int): Boolean = value == AUTO || value in HEIGHTS

    /** True when [value] is valid for a connection preference, including [LAST_USED]. */
    fun isPreference(value: Int): Boolean = value == LAST_USED || isConcrete(value)

    /**
     * Normalizes a persisted global height the same way the desktop
     * `PlaybackSettings::normalizeMaximumVideoHeight` does: unknown values become
     * [AUTO]. [DEFAULT] and [LAST_USED] are not valid concrete values.
     */
    fun normalizeGlobalHeight(value: Int): Int = if (isConcrete(value)) value else AUTO

    /** Resolves a connection preference, following [LAST_USED] to the shared value. */
    fun resolvePreference(settings: Settings, connection: ConnectionType): Int {
        val preference = when (connection) {
            ConnectionType.WIFI -> settings.wifiMaximumVideoHeight
            ConnectionType.DATA -> settings.dataMaximumVideoHeight
        }
        return if (preference == LAST_USED) settings.lastUsedVideoHeight else preference
    }

    /**
     * Effective maximum height for a single video. The per-video override wins
     * when present; otherwise the connection preference is used, resolving
     * [LAST_USED] through the shared concrete height.
     */
    fun effectiveHeight(settings: Settings, videoId: String, connection: ConnectionType): Int {
        val override = settings.videoQualityOverrides[videoId]
        if (override != null) return normalizeGlobalHeight(override)
        return normalizeGlobalHeight(resolvePreference(settings, connection))
    }

    /**
     * Records an in-player quality choice for one video. [DEFAULT] removes the
     * override without touching the shared last-used height; [AUTO] or a fixed
     * height updates both the override and the shared last-used height. Returns
     * a new settings instance; the caller persists it in one update.
     */
    fun applyChoice(settings: Settings, videoId: String, value: Int): Settings {
        val overrides = settings.videoQualityOverrides.toMutableMap()
        if (value == DEFAULT) {
            overrides.remove(videoId)
            return settings.copy(videoQualityOverrides = overrides)
        }
        val concrete = normalizeGlobalHeight(value)
        overrides[videoId] = concrete
        return settings.copy(
            videoQualityOverrides = overrides,
            lastUsedVideoHeight = concrete,
        )
    }
}
