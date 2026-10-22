package com.scriptacus.riderunrealangelscript.lsp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import com.scriptacus.riderunrealangelscript.project.ScriptFolderDetector
import com.scriptacus.riderunrealangelscript.settings.AngelScriptLspSettings
import com.scriptacus.riderunrealangelscript.typedb.UnrealTypeDatabaseService
import com.scriptacus.riderunrealangelscript.util.NodeJsExecutableFinder
import com.scriptacus.riderunrealangelscript.util.TempFileManager
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

class AngelScriptConnectionProvider(private val project: Project) : ProcessStreamConnectionProvider() {
    private val LOG = Logger.getInstance(AngelScriptConnectionProvider::class.java)
    private val tempFileManager = TempFileManager()

    private var wrappedInputStream: InputStream? = null
    private var wrappedOutputStream: OutputStream? = null

    override fun start() {
        LOG.info("Starting AngelScript LSP server process...")
        try {
            super.start()
            LOG.info("AngelScript LSP server process started successfully, PID: $pid")
        } catch (e: Exception) {
            LOG.error("Failed to start AngelScript LSP server process", e)
            throw e
        }
    }

    override fun stop() {
        LOG.info("Stopping AngelScript LSP server process...")
        super.stop()
    }

    /**
     * Names the project's script folders a second time, as initialization options.
     *
     * [AngelScriptWorkspaceFolderFeature] already sends them as the workspace, and the server
     * ignores roots it has seen, so in the normal case this is a no-op. It is here for the case
     * where that feature does not apply - an lsp4ij version without it, or a future change to how
     * it computes folders - because the cost of the server falling back to Rider's content roots
     * is a scan measured in minutes, during which it knows only the files the editor has open.
     */
    override fun getInitializationOptions(rootUri: VirtualFile?): Any? {
        val scriptFolders = ScriptFolderDetector.detectScriptRoots(project)
        if (scriptFolders.isEmpty()) {
            LOG.info("No AngelScript script folders detected; the server will scan the workspace roots")
            return null
        }

        val folders = JsonArray()
        for (folder in scriptFolders) {
            val entry = JsonObject()
            entry.addProperty("uri", AngelScriptFileUriSupport.normalizedUri(folder))
            entry.addProperty("name", folder.name)
            folders.add(entry)
        }

        LOG.info("Script roots passed to the language server: " + scriptFolders.joinToString { it.path })
        return JsonObject().apply { add("additionalScriptRootFolders", folders) }
    }

    init {
        LOG.info("AngelScriptConnectionProvider initializing for project: ${project.name}")
        // Logged on every start so a report about language server behaviour carries the revision
        // that produced it; the bundle itself is unversioned.
        LOG.info("Bundled language server: Hazelight unreal-angelscript ${AngelScriptLspVersion.displayText}")

        // Register temp file manager for cleanup when project is disposed
        Disposer.register(project, tempFileManager)

        try {
            val lspBundleStream = javaClass.getResourceAsStream("/js/angelscript-language-server.js")
            if (lspBundleStream == null) {
                LOG.error("Could not find AngelScript language server bundle (angelscript-language-server.js) in plugin resources.")
            } else {
                val tempFile = tempFileManager.createTempFile("angelscript-language-server", ".js")
                FileOutputStream(tempFile).use { lspBundleStream.copyTo(it) }

                val lspBundlePath = tempFile.absolutePath
                val nodePath = NodeJsExecutableFinder.find(project)
                    ?: if (SystemInfo.isWindows) "node.exe" else "node"

                val command = mutableListOf<String>()
                command.add(nodePath)
                command.add(lspBundlePath)
                command.add("--stdio")

                // Passed on the command line rather than through didChangeConfiguration so it is
                // available before any configuration arrives, and so it stays clear of the
                // upstream settings handler. The database is saved either way, since the API
                // browser reads it; the setting only decides whether a saved one is loaded.
                command.add("--type-cache=${UnrealTypeDatabaseService.cacheFile(project)}")
                if (!AngelScriptLspSettings.getInstance().state.useCachedTypeDatabase) {
                    command.add("--no-type-cache-load")
                }

                commands = command
                LOG.info("AngelScript LSP server command configured: ${command.joinToString(" ")}")
            }
        } catch (e: Exception) {
            LOG.error("Failed to create command line for AngelScript language server", e)
        }
    }

}
