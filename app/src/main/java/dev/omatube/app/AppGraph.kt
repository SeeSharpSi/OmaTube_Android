package dev.omatube.app

import android.content.Context
import dev.omatube.app.automation.AutomationFixture
import dev.omatube.app.automation.FakeVideoBackend
import dev.omatube.app.backend.NewPipeBackend
import dev.omatube.app.backend.VideoBackend
import dev.omatube.app.data.DataStoreSettingsStore
import dev.omatube.app.data.LibraryRepository
import dev.omatube.app.data.RoomLibraryRepository
import dev.omatube.app.data.SettingsStore
import dev.omatube.app.model.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One application-wide graph of persistence, settings and backend instances.
 *
 * Exactly one graph exists per process. The production factory wires Room,
 * DataStore and NewPipeExtractor; the automation factory wires in-memory Room,
 * in-memory settings and a [FakeVideoBackend] that performs no network work.
 * The graph must be installed before the first [AppViewModel] is created.
 *
 * Shutdown order is deliberate: cancel the scope first so no collector or
 * refresh coroutine can touch the database, then release the settings store
 * file, then the backend, then close the Room database.
 */
class AppGraph(
    val repository: LibraryRepository,
    val settingsStore: SettingsStore,
    val backend: VideoBackend,
    val automation: Boolean,
    val scope: CoroutineScope,
    private val productionBackend: NewPipeBackend? = null,
) {

    @Volatile
    private var latestSettings: Settings = Settings()

    @Volatile
    private var closed = false

    /** True after [close]; a closed graph must never be reused. */
    val isClosed: Boolean get() = closed

    private val _errors = MutableStateFlow<String?>(null)

    /** Last uncaught background failure, routed to the UI instead of swallowed. */
    val errors: StateFlow<String?> = _errors.asStateFlow()

    /**
     * Process-wide startup latch. Startup refresh runs at most once per process
     * no matter how many times the Activity or ViewModel is recreated.
     */
    private val startupRequested = AtomicBoolean(false)

    init {
        scope.launch {
            try {
                settingsStore.settings.collect { latestSettings = it }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                reportError(throwable)
            }
        }
        observeRecoveryNotice()
    }

    /**
     * Surfaces a non-null API key recovery notice through [errors]. A null
     * notice is ignored so a healthy store never clears an unrelated error.
     */
    private fun observeRecoveryNotice() {
        val store = settingsStore as? DataStoreSettingsStore ?: return
        scope.launch {
            try {
                store.recoveryNotice.collect { notice ->
                    if (notice != null) {
                        _errors.value = notice
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                reportError(throwable)
            }
        }
    }

    /** Last observed settings, used by the backend's `settings()` provider. */
    fun currentSettings(): Settings = latestSettings

    /** Returns true only for the first caller in this process. */
    fun beginStartup(): Boolean = startupRequested.compareAndSet(false, true)

    fun reportError(throwable: Throwable) {
        _errors.value = throwable.message ?: "Background task failed."
    }

    fun clearError() {
        _errors.value = null
    }

    /**
     * Releases the whole graph in dependency order: stop graph coroutines,
     * release the settings store file, release the backend, then close Room.
     *
     * The settings store owns a private DataStore IO scope. Replacing a graph
     * (normal -> debug automation -> normal) must not reopen the same
     * preferences file while the old store still holds it, so [close] awaits
     * [DataStoreSettingsStore.closeAndJoin] with a small bounded handshake.
     * `runBlocking` here is bounded and only waits on DataStore's own IO job,
     * so Android's main thread is never blocked on unbounded disk or network
     * work. The store's scope is cancelled even if the handshake times out.
     */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        closeSettingsStore()
        productionBackend?.close()
        (repository as? RoomLibraryRepository)?.close()
    }

    private fun closeSettingsStore() {
        val store = settingsStore as? DataStoreSettingsStore ?: return
        // Dispatch the cancellation handshake to IO so the join never runs on
        // the main dispatcher; the caller still waits only for the bounded
        // handshake.
        runBlocking(Dispatchers.IO) {
            try {
                withTimeoutOrNull(SETTINGS_CLOSE_TIMEOUT_MILLIS) {
                    store.closeAndJoin()
                }
            } catch (cancellation: CancellationException) {
                // The store scope is already cancelled; nothing else to release.
            } catch (throwable: Throwable) {
                // Best-effort file release during graph shutdown.
            }
        }
    }

    companion object {

        /**
         * Upper bound for the settings-store cancellation handshake during
         * [close]. DataStore's pending write is tiny; the timeout only exists
         * so a wedged IO job can never block graph replacement indefinitely.
         */
        private const val SETTINGS_CLOSE_TIMEOUT_MILLIS = 2_000L

        fun production(context: Context): AppGraph {
            val appContext = context.applicationContext
            val repository = RoomLibraryRepository(appContext)
            val settingsStore = DataStoreSettingsStore(appContext)
            val errorRef = AtomicReference<AppGraph?>()
            val scope = graphScope(errorRef)

            val holder = arrayOfNulls<AppGraph>(1)
            val backend = NewPipeBackend(appContext) {
                holder[0]?.currentSettings() ?: Settings()
            }
            val graph = AppGraph(
                repository = repository,
                settingsStore = settingsStore,
                backend = backend,
                automation = false,
                scope = scope,
                productionBackend = backend,
            )
            holder[0] = graph
            errorRef.set(graph)
            return graph
        }

        fun automation(
            context: Context,
            simpleUi: Boolean,
            themeId: String,
        ): AppGraph {
            val appContext = context.applicationContext
            val repository = RoomLibraryRepository(appContext, inMemory = true)
            val settingsStore = DataStoreSettingsStore(appContext, inMemory = true)
            val errorRef = AtomicReference<AppGraph?>()
            val scope = graphScope(errorRef)
            runBlocking {
                AutomationFixture.seed(repository)
                settingsStore.update {
                    it.copy(simpleUi = simpleUi, themeId = themeId)
                }
            }
            val graph = AppGraph(
                repository = repository,
                settingsStore = settingsStore,
                backend = FakeVideoBackend.forFixture(),
                automation = true,
                scope = scope,
                productionBackend = null,
            )
            errorRef.set(graph)
            return graph
        }

        /**
         * Builds a scope whose uncaught failures are routed to the graph's
         * observable error flow instead of crashing the process silently.
         */
        private fun graphScope(errorRef: AtomicReference<AppGraph?>): CoroutineScope {
            val handler = CoroutineExceptionHandler { _, throwable ->
                errorRef.get()?.reportError(throwable)
            }
            return CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
        }
    }
}
