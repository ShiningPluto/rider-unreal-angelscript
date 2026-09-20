package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.client.features.LSPWorkspaceFolderFeature
import com.scriptacus.riderunrealangelscript.project.ScriptFolderDetector
import org.eclipse.lsp4j.WorkspaceFolder

/**
 * Sends the language server the folders that actually hold AngelScript, instead of every content
 * root of the solution.
 *
 * The server scans each workspace folder for `.as` files at startup, and lsp4ij's default strategy
 * hands it every content root: in Rider that is the nuget cache, the .NET SDK analyzer
 * directories, and the entire engine tree. Measured on a real project, scanning those took about
 * two and a half minutes - 53 seconds of it an engine tree with no AngelScript in it at all, and
 * 86 seconds a project directory whose `Script` folder alone scans in 0.1 - and until the scan
 * finishes the server knows only the files the editor happens to have open.
 *
 * Falling back to the default matters: a project whose script lives somewhere the detection does
 * not recognise is better served slowly than not at all.
 */
class AngelScriptWorkspaceFolderFeature : LSPWorkspaceFolderFeature() {

    private val LOG = Logger.getInstance(AngelScriptWorkspaceFolderFeature::class.java)

    override fun getInitialWorkspaceFolders(project: Project): MutableList<WorkspaceFolder> {
        val scriptRoots = ScriptFolderDetector.detectScriptRoots(project)
        if (scriptRoots.isEmpty()) {
            LOG.info("No AngelScript script folders detected; falling back to the project's content roots")
            return super.getInitialWorkspaceFolders(project)
        }

        val folders = scriptRoots.mapTo(mutableListOf()) { folder ->
            WorkspaceFolder(AngelScriptFileUriSupport.normalizedUri(folder), folder.name)
        }
        LOG.info("Workspace folders for the language server: " + folders.joinToString { it.uri })
        return folders
    }
}
