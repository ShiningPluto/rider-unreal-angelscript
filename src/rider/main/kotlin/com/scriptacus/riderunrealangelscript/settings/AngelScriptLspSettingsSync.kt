package com.scriptacus.riderunrealangelscript.settings

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptLanguageServerFactory
import org.eclipse.lsp4j.DidChangeConfigurationParams

/**
 * Pushes the current settings to a running language server.
 *
 * This covers changes made while the server is already up (Settings | Languages & Frameworks |
 * AngelScript). Pushing on server start / restart is handled by
 * [com.scriptacus.riderunrealangelscript.lsp.AngelScriptLanguageClient] instead, since no component
 * outside the server's own lifecycle can observe a restart reliably.
 */
class AngelScriptLspSettingsSync(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(AngelScriptLspSettingsSync::class.java)
    }

    fun syncSettingsToLsp() {
        val config = AngelScriptLspConfiguration.build(AngelScriptLspSettings.getInstance().state)

        // Send workspace/didChangeConfiguration to AngelScript language server
        val manager = LanguageServerManager.getInstance(project)
        val serverFuture = manager.getLanguageServer(AngelScriptLanguageServerFactory.SERVER_ID)

        serverFuture.thenAccept { serverItem ->
            if (serverItem == null) {
                LOG.warn("AngelScript LSP server not available - cannot sync settings")
                return@thenAccept
            }

            try {
                val params = DidChangeConfigurationParams(config)
                serverItem.server.workspaceService.didChangeConfiguration(params)
            } catch (e: Exception) {
                LOG.error("Failed to sync settings to LSP server", e)
            }
        }
    }
}
