package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import com.scriptacus.riderunrealangelscript.settings.AngelScriptLspSettings
import com.scriptacus.riderunrealangelscript.util.NodeJsExecutableFinder
import com.scriptacus.riderunrealangelscript.util.TempFileManager
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Paths

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
     * Where the server keeps the last type database it received from Unreal.
     *
     * Per project, because the database describes that project's classes, and under the IDE system
     * directory rather than the workspace so a generated cache never shows up in version control.
     */
    private fun typeCachePath(): String =
        Paths.get(PathManager.getSystemPath(), "angelscript-lsp", project.locationHash, "unreal-type-database.json")
            .toString()

    init {
        LOG.info("AngelScriptConnectionProvider initializing for project: ${project.name}")

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
                // upstream settings handler.
                command.add("--type-cache=${typeCachePath()}")
                if (!AngelScriptLspSettings.getInstance().state.useCachedTypeDatabase) {
                    command.add("--no-type-cache")
                }

                commands = command
                LOG.info("AngelScript LSP server command configured: ${command.joinToString(" ")}")
            }
        } catch (e: Exception) {
            LOG.error("Failed to create command line for AngelScript language server", e)
        }
    }

}
