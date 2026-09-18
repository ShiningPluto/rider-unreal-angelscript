package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.ServerStatus
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.scriptacus.riderunrealangelscript.settings.AngelScriptLspConfiguration
import com.scriptacus.riderunrealangelscript.settings.AngelScriptLspSettings

/**
 * Custom language client for AngelScript.
 *
 * Owns the `workspace/didChangeConfiguration` push that every server start depends on. The bundled
 * server ignores `initializationOptions` and keeps `port = -1` until a configuration push arrives,
 * so a server that starts without one never connects to the Unreal editor, never receives the C++
 * type database, and therefore produces neither semantic tokens nor diagnostics.
 *
 * lsp4ij creates a fresh client for every server start, so re-pushing from here covers restarts
 * (crash, `Restart` action, LSP console) and not just the initial project open.
 */
class AngelScriptLanguageClient(project: Project) : LanguageClientImpl(project) {

    companion object {
        private val LOG = Logger.getInstance(AngelScriptLanguageClient::class.java)
    }

    /**
     * Settings used for both `workspace/didChangeConfiguration` pushes (via
     * [triggerChangeConfiguration]) and `workspace/configuration` pulls.
     */
    override fun createSettings(): Any = AngelScriptLspConfiguration.build(AngelScriptLspSettings.getInstance().state)

    override fun handleServerStatusChanged(serverStatus: ServerStatus) {
        if (serverStatus != ServerStatus.started) {
            return
        }

        // Must not block the language server startup sequence, hence the pooled thread.
        ApplicationManager.getApplication().executeOnPooledThread {
            if (isDisposed) {
                return@executeOnPooledThread
            }
            try {
                LOG.info("AngelScript LSP server started, sending configuration")
                triggerChangeConfiguration()
            } catch (e: Exception) {
                // Can legitimately happen when the server dies again before the push lands.
                LOG.warn("Failed to send configuration to AngelScript LSP server on start", e)
            }
        }
    }
}
