package com.scriptacus.riderunrealangelscript.project

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Shared utility for efficiently detecting AngelScript Script folders in a project.
 *
 * Uses FilenameIndex for O(1) lookup of .uproject and .uplugin files instead of
 * recursive VFS traversal which is O(n) where n = total files in project.
 */
object ScriptFolderDetector {

    private val LOG = Logger.getInstance(ScriptFolderDetector::class.java)

    /**
     * The folders to hand the language server as its workspace, usable at any time.
     *
     * [detectScriptFolders] needs the file index, and the language server can start while the IDE
     * is still indexing, so the layout Unreal's AngelScript plugin prescribes - a `Script` folder
     * next to the `.uproject` - is resolved from the filesystem directly and the index is only
     * consulted once it can answer. Finding nothing is not a failure: callers fall back to the
     * project's content roots, which is correct, just far slower to scan.
     */
    fun detectScriptRoots(project: Project): List<VirtualFile> {
        val roots = LinkedHashSet<VirtualFile>()

        project.basePath?.let { basePath ->
            LocalFileSystem.getInstance().findFileByPath("$basePath/Script")
                ?.takeIf { it.isDirectory }
                ?.let { roots.add(it) }
        }

        if (!DumbService.isDumb(project)) {
            try {
                roots.addAll(ReadAction.compute<List<VirtualFile>, RuntimeException> {
                    detectScriptFolders(project)
                })
            } catch (e: IndexNotReadyException) {
                LOG.info("Indexes are not ready; using the project's own Script folder only")
            }
        }

        return roots.toList()
    }
    /**
     * Finds all Script folders next to .uproject or .uplugin files.
     *
     * @param project The project to search
     * @return List of Script folder VirtualFiles
     */
    fun detectScriptFolders(project: Project): List<VirtualFile> {
        val scriptFolders = mutableSetOf<VirtualFile>()
        val scope = GlobalSearchScope.projectScope(project)

        // Find all .uproject files and check for Script folders
        val uprojectFiles = FilenameIndex.getAllFilesByExt(project, "uproject", scope)
        for (uprojectFile in uprojectFiles) {
            val scriptFolder = uprojectFile.parent?.findChild("Script")
            if (scriptFolder?.isDirectory == true) {
                scriptFolders.add(scriptFolder)
            }
        }

        // Find all .uplugin files and check for Script folders
        val upluginFiles = FilenameIndex.getAllFilesByExt(project, "uplugin", scope)
        for (upluginFile in upluginFiles) {
            val scriptFolder = upluginFile.parent?.findChild("Script")
            if (scriptFolder?.isDirectory == true) {
                scriptFolders.add(scriptFolder)
            }
        }

        return scriptFolders.toList()
    }
}