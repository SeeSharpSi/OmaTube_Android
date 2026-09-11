package dev.omatube.app.automation

import android.content.Intent
import dev.omatube.app.BuildConfig

/**
 * Debug-only automation launch description parsed from the Activity intent.
 *
 * The desktop `AUTOMATION.md` contract uses these extras. Automation is never
 * available in a release build and defaults to the full UI, the default theme,
 * the feed route, and a fake backend that performs no network work.
 */
data class AutomationLaunch(
    val simpleUi: Boolean,
    val themeId: String,
    val route: String,
    val playerVideoId: String?,
)

object AutomationConfig {

    const val EXTRA_AUTOMATION = "automation"
    const val EXTRA_UI = "automation_ui"
    const val EXTRA_ROUTE = "automation_route"
    const val EXTRA_THEME = "automation_theme"

    const val UI_FULL = "full"
    const val UI_SIMPLE = "simple"

    const val ROUTE_FEED = "feed"
    const val ROUTE_HISTORY = "history"
    const val ROUTE_WATCH_NEXT = "watchnext"
    const val ROUTE_SETTINGS = "settings"
    const val ROUTE_PLAYER = "player"

    /**
     * Returns a launch description only when this is a debug build and the
     * intent explicitly requests automation. Otherwise returns null so the
     * production graph is used and extras can never toggle fake mode.
     */
    fun from(intent: Intent?): AutomationLaunch? {
        if (!BuildConfig.DEBUG) return null
        if (intent?.getBooleanExtra(EXTRA_AUTOMATION, false) != true) return null

        val simpleUi = intent.getStringExtra(EXTRA_UI)?.trim()?.lowercase() == UI_SIMPLE
        val themeId = when (intent.getStringExtra(EXTRA_THEME)?.trim()?.lowercase()) {
            "rose-pine" -> "rose-pine"
            "nord" -> "nord"
            else -> "default"
        }
        val route = when (intent.getStringExtra(EXTRA_ROUTE)?.trim()?.lowercase()) {
            ROUTE_HISTORY -> ROUTE_HISTORY
            ROUTE_WATCH_NEXT -> ROUTE_WATCH_NEXT
            ROUTE_SETTINGS -> ROUTE_SETTINGS
            ROUTE_PLAYER -> ROUTE_PLAYER
            else -> ROUTE_FEED
        }
        return AutomationLaunch(
            simpleUi = simpleUi,
            themeId = themeId,
            route = route,
            playerVideoId = if (route == ROUTE_PLAYER) AutomationFixture.PLAYER_VIDEO_ID else null,
        )
    }
}
