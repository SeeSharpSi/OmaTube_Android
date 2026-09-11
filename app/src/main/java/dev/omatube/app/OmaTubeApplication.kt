package dev.omatube.app

import android.app.Application
import dev.omatube.app.automation.AutomationLaunch

/**
 * Owns the single process-wide [AppGraph].
 *
 * The production graph is created lazily by [installGraph] so a debug
 * automation launch never constructs NewPipeExtractor, OkHttp or the PO-token
 * WebView. The automation graph uses in-memory Room and settings plus a fake
 * backend.
 *
 * Installation is identity-aware: Activity recreation passes the same
 * [AutomationLaunch] and the existing graph is preserved, including its seeded
 * in-memory fixture and any Watch Next mutations. Only a genuinely new debug
 * launch configuration replaces (and reseeds) the graph.
 */
class OmaTubeApplication : Application() {

    @Volatile
    private var graph: AppGraph? = null

    @Volatile
    private var automationLaunch: AutomationLaunch? = null

    val appGraph: AppGraph
        get() = checkNotNull(graph) { "AppGraph has not been installed yet." }

    val isAutomationGraph: Boolean
        get() = graph?.automation == true

    val currentAutomationLaunch: AutomationLaunch?
        get() = automationLaunch

    /**
     * Ensures the process graph matches [launch].
     *
     * @return true when a new graph was installed, false when an existing graph
     * was preserved (for example during Activity recreation).
     */
    @Synchronized
    fun installGraph(launch: AutomationLaunch?): Boolean {
        val existing = graph
        if (launch == null) {
            if (existing != null && !existing.automation && !existing.isClosed) return false
            existing?.close()
            graph = AppGraph.production(this)
            automationLaunch = null
            return true
        }
        if (existing != null &&
            existing.automation &&
            !existing.isClosed &&
            automationLaunch == launch
        ) {
            return false
        }
        existing?.close()
        graph = AppGraph.automation(
            context = this,
            simpleUi = launch.simpleUi,
            themeId = launch.themeId,
        )
        automationLaunch = launch
        return true
    }

    override fun onTerminate() {
        graph?.close()
        graph = null
        automationLaunch = null
        super.onTerminate()
    }
}
