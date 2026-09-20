package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import org.eclipse.lsp4j.services.LanguageServer

class AngelScriptLanguageServerFactory : LanguageServerFactory {
    private val LOG = Logger.getInstance(AngelScriptLanguageServerFactory::class.java)

    companion object {
        /**
         * Id of the `com.redhat.devtools.lsp4ij.server` extension declared in plugin.xml.
         *
         * Always use this constant when talking to [com.redhat.devtools.lsp4ij.LanguageServerManager]:
         * an id that does not match the descriptor silently resolves to no server, which reports a
         * `null` status forever rather than failing.
         */
        const val SERVER_ID = "angelscript-lsp"
    }

    override fun createConnectionProvider(project: Project): StreamConnectionProvider {
        return AngelScriptConnectionProvider(project)
    }

    override fun createLanguageClient(project: Project): LanguageClientImpl {
        return AngelScriptLanguageClient(project)
    }

    override fun getServerInterface(): Class<out LanguageServer> {
        return AngelScriptLanguageServer::class.java
    }

    override fun createClientFeatures(): com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures {
        LOG.info("Creating client features with AngelScriptCompletionFeature")
        return com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures()
            .setFileUriSupport(AngelScriptFileUriSupport())
            .setWorkspaceFolderFeature(AngelScriptWorkspaceFolderFeature())
            .setCompletionFeature(AngelScriptCompletionFeature())
    }
}
