package com.scriptacus.riderunrealangelscript.status

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptLanguageServer
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptLanguageServerFactory
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks whether the language server has the Unreal type database, and therefore whether
 * AngelScript analysis can work at all.
 *
 * Without it the server is silent rather than broken-looking: no semantic tokens, no diagnostics,
 * no error. Polling the server for its own view of the connection is the only reliable signal -
 * probing the Unreal port from here would report the editor as reachable even when the server
 * never connected to it, which is exactly the failure this is meant to surface.
 */
@Service(Service.Level.PROJECT)
class AngelScriptUnrealStatusService(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(AngelScriptUnrealStatusService::class.java)

        /** Poll quickly while something is wrong: the user is likely looking at the status right now. */
        private const val POLL_INTERVAL_PENDING_MS = 2_000L

        /** Once analysis works, only poll slowly enough to notice the editor going away. */
        private const val POLL_INTERVAL_READY_MS = 15_000L

        /** Requests are answered from an event loop; a slow answer means the server is wedged. */
        private const val REQUEST_TIMEOUT_MS = 2_000L

        fun getInstance(project: Project): AngelScriptUnrealStatusService = project.service()

        /**
         * Maps an `angelscript/getUnrealStatus` reply to a status.
         *
         * Kept separate from the polling so the precedence between the fields is testable: only
         * [typesLoaded] decides whether analysis works, everything else explains why it does not.
         */
        fun classify(
            port: Int?,
            socketState: String?,
            typesLoaded: Boolean,
            usingCachedTypes: Boolean = false
        ): AngelScriptUnrealStatus = when {
            // Cached types are still types: analysis works, but it describes the engine as of the
            // last session, so it must not be reported as a live connection.
            typesLoaded && usingCachedTypes -> AngelScriptUnrealStatus.CACHED_TYPES
            typesLoaded -> AngelScriptUnrealStatus.READY
            // The bundled server starts at -1 and only dials Unreal once configuration arrives.
            port == null || port < 0 -> AngelScriptUnrealStatus.NOT_CONFIGURED
            socketState == "open" -> AngelScriptUnrealStatus.LOADING_TYPES
            // "connecting" and "disconnected" both mean the editor is not answering yet; the
            // server retries every 5s, so this settles on its own once an editor shows up.
            else -> AngelScriptUnrealStatus.DISCONNECTED
        }
    }

    @Volatile
    var status: AngelScriptUnrealStatus = AngelScriptUnrealStatus.SERVER_STOPPED
        private set

    /** Port the server is actually using, or null while it has not been configured. */
    @Volatile
    var configuredPort: Int? = null
        private set

    /** When the type database in use was captured, if it came from the cache rather than an editor. */
    @Volatile
    var cachedTypesSavedAt: Long? = null
        private set

    /**
     * Whether the server has ever been seen running in this project. Until then there is nothing
     * worth reporting, and a widget claiming "stopped" in an unrelated project is just noise.
     */
    @Volatile
    var everObservedServer: Boolean = false
        private set

    private val started = AtomicBoolean(false)
    private var scheduled: ScheduledFuture<*>? = null

    fun startPolling() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        schedule(POLL_INTERVAL_PENDING_MS)
    }

    private fun schedule(delayMs: Long) {
        if (project.isDisposed) {
            return
        }
        scheduled = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            val next = try {
                poll()
            } catch (e: Exception) {
                LOG.warn("Failed to poll AngelScript Unreal connection status", e)
                POLL_INTERVAL_PENDING_MS
            }
            schedule(next)
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** Refreshes [status] and returns the delay before the next poll. */
    private fun poll(): Long {
        if (project.isDisposed) {
            return POLL_INTERVAL_READY_MS
        }

        val manager = LanguageServerManager.getInstance(project)

        // Deliberately checked before getLanguageServer(), which starts the server as a side effect.
        // Polling must never be the reason the language server is running.
        if (manager.getServerStatus(AngelScriptLanguageServerFactory.SERVER_ID) != ServerStatus.started) {
            update(AngelScriptUnrealStatus.SERVER_STOPPED, null)
            return POLL_INTERVAL_PENDING_MS
        }
        everObservedServer = true

        val serverItem = manager.getLanguageServer(AngelScriptLanguageServerFactory.SERVER_ID)
            .get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val server = serverItem?.server as? AngelScriptLanguageServer
        if (server == null) {
            update(AngelScriptUnrealStatus.SERVER_STOPPED, null)
            return POLL_INTERVAL_PENDING_MS
        }

        val raw = server.getUnrealStatus().get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (raw == null) {
            update(AngelScriptUnrealStatus.SERVER_STOPPED, null)
            return POLL_INTERVAL_PENDING_MS
        }

        val port = (raw["configuredPort"] as? Number)?.toInt()
        val socketState = raw["socketState"] as? String
        val typesLoaded = raw["typesLoaded"] as? Boolean ?: false
        val usingCachedTypes = raw["usingCachedTypes"] as? Boolean ?: false
        cachedTypesSavedAt = (raw["cachedTypesSavedAt"] as? Number)?.toLong()

        val newStatus = classify(port, socketState, typesLoaded, usingCachedTypes)
        update(newStatus, port)
        return if (newStatus.isAnalysisAvailable) POLL_INTERVAL_READY_MS else POLL_INTERVAL_PENDING_MS
    }

    private fun update(newStatus: AngelScriptUnrealStatus, port: Int?) {
        val changed = newStatus != status || port != configuredPort
        status = newStatus
        configuredPort = port
        if (!changed) {
            return
        }

        LOG.info("AngelScript Unreal connection status: $newStatus (port: ${port ?: "unconfigured"})")
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) {
                return@invokeLater
            }
            WindowManager.getInstance().getStatusBar(project)
                ?.updateWidget(AngelScriptStatusBarWidget.WIDGET_ID)
        }, project.disposed)
    }

    override fun dispose() {
        scheduled?.cancel(false)
        scheduled = null
    }
}
