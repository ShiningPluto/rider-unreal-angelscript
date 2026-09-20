package com.scriptacus.riderunrealangelscript.lsp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import com.scriptacus.riderunrealangelscript.project.ScriptFolderDetector
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
     * Tells the server where this project's AngelScript actually is.
     *
     * The server scans every workspace folder for `.as` files, and lsp4ij fills those from the
     * solution's content roots - in Rider that means the nuget cache, the .NET SDK analyzer
     * directories and the entire engine tree. Scanning them is slow (measured on a real project:
     * 53s for the engine tree, which contains no AngelScript at all, and 86s for the project
     * directory, against 0.1s for its Script folder), and there is no lsp4ij hook to narrow the
     * list: [com.redhat.devtools.lsp4ij.LSPIJUtils.toWorkspaceFolders] hands over every content
     * root and no client feature can override it.
     *
     * Naming the script folders here gives the scan a root it finishes in milliseconds. Since
     * v1.9.4 the server takes them as extra roots, and the bundler patch makes each root usable as
     * soon as it has been scanned rather than when the slowest one finishes - so analysis is
     * available almost immediately instead of after a minute of the server knowing only the files
     * that happen to be open.
     */
    override fun getInitializationOptions(rootUri: VirtualFile?): Any? {
        val scriptFolders = detectScriptRootFolders()
        if (scriptFolders.isEmpty()) {
            LOG.info("No AngelScript script folders detected; the server will scan the workspace roots")
            return null
        }

        val folders = JsonArray()
        for (folder in scriptFolders) {
            val entry = JsonObject()
            entry.addProperty("uri", LSPIJUtils.toUri(folder).toASCIIString())
            entry.addProperty("name", folder.name)
            folders.add(entry)
        }

        LOG.info("Script roots passed to the language server: " + scriptFolders.joinToString { it.path })
        return JsonObject().apply { add("additionalScriptRootFolders", folders) }
    }

    /**
     * The server can start while the IDE is still indexing, so the common layout is resolved
     * without the index and the index is only consulted when it can answer. Finding nothing is not
     * a failure: the server still scans the workspace roots, just as slowly as before.
     */
    private fun detectScriptRootFolders(): List<VirtualFile> {
        val folders = LinkedHashSet<VirtualFile>()

        // The Script folder next to the .uproject - the layout Unreal's AngelScript plugin expects.
        project.basePath?.let { basePath ->
            LocalFileSystem.getInstance().findFileByPath("$basePath/Script")
                ?.takeIf { it.isDirectory }
                ?.let { folders.add(it) }
        }

        // Script folders belonging to plugins, and any project laid out differently.
        if (!DumbService.isDumb(project)) {
            try {
                folders.addAll(ReadAction.compute<List<VirtualFile>, RuntimeException> {
                    ScriptFolderDetector.detectScriptFolders(project)
                })
            } catch (e: IndexNotReadyException) {
                LOG.info("Indexes are not ready; passing only the project's own Script folder")
            }
        }

        return folders.toList()
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
