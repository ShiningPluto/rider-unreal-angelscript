package com.scriptacus.riderunrealangelscript.overrides

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.concurrency.AppExecutorUtil
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import com.scriptacus.riderunrealangelscript.lang.AngelScriptFileType
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptLanguageServer
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptLanguageServerFactory
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The script overrides of C++ blueprint events, kept current for the C++ side to show.
 *
 * Only the language server can answer this - it takes the script classes and Unreal's type database
 * together to say which C++ class declares the event an override overrides - but the answer is
 * needed synchronously: [NotImplementedWarningSuppressor] decides on the EDT, as each backend
 * highlighter arrives, and cannot wait for a request. So the whole answer is fetched ahead of time
 * and replaced as scripts change, and readers only ever look at [overrides].
 */
@Service(Service.Level.PROJECT)
class ScriptOverrideService(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(ScriptOverrideService::class.java)

        /**
         * How long after a script edit to ask again. The server parses a changed file on a throttle
         * of its own, so asking straight away returns the answer from before the edit; this also
         * folds a burst of typing into one request.
         */
        private const val AFTER_EDIT_DELAY_MS = 2_000L

        /** How often to ask while the server is still loading scripts or waiting for C++ types. */
        private const val UNSETTLED_RETRY_MS = 3_000L

        /** Measured at 20-35 ms for ~2,300 overrides on a real project; anything near this is a wedged server. */
        private const val REQUEST_TIMEOUT_MS = 5_000L

        fun getInstance(project: Project): ScriptOverrideService = project.service()
    }

    /** The latest answer; empty until the server has given one. */
    @Volatile
    var overrides: ScriptOverrides = ScriptOverrides.EMPTY
        private set

    private val lock = Any()
    private var scheduled: ScheduledFuture<*>? = null

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (file.fileType is AngelScriptFileType) {
                    requestRefresh(AFTER_EDIT_DELAY_MS)
                }
            }
        }, this)

        // Changes that never pass through an editor: version control updates, other tools.
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path.endsWith(".as", ignoreCase = true) }) {
                    requestRefresh(AFTER_EDIT_DELAY_MS)
                }
            }
        })
    }

    /** Asks the server again after [delayMs], replacing any request already waiting. */
    fun requestRefresh(delayMs: Long = 0L) {
        if (project.isDisposed) {
            return
        }
        synchronized(lock) {
            scheduled?.cancel(false)
            scheduled = AppExecutorUtil.getAppScheduledExecutorService().schedule({
                try {
                    refresh()
                } catch (e: Exception) {
                    LOG.warn("Failed to ask the language server for script overrides", e)
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun refresh() {
        if (project.isDisposed) {
            return
        }
        val manager = LanguageServerManager.getInstance(project)
        // Checked before getLanguageServer(), which would start the server: this must never be the
        // reason it runs. The client asks again once it has started.
        if (manager.getServerStatus(AngelScriptLanguageServerFactory.SERVER_ID) != ServerStatus.started) {
            return
        }
        val server = manager.getLanguageServer(AngelScriptLanguageServerFactory.SERVER_ID)
            .get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)?.server as? AngelScriptLanguageServer
            ?: return
        val reply = server.getScriptOverrides().get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: return

        val settled = reply["settled"] as? Boolean ?: false
        val fresh = ScriptOverrides.fromReply(reply["overrides"] as? List<*> ?: emptyList<Any>())
        if (fresh != overrides) {
            overrides = fresh
            LOG.info("Script overrides of C++ blueprint events: $fresh${if (settled) "" else " (server still loading)"}")
            ScriptOverridesCodeVisionProvider.refresh(project)
        }
        if (!settled) {
            requestRefresh(UNSETTLED_RETRY_MS)
        }
    }

    override fun dispose() {
        synchronized(lock) {
            scheduled?.cancel(false)
            scheduled = null
        }
    }
}
