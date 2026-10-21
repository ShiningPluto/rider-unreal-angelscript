package com.scriptacus.riderunrealangelscript.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderEx
import com.intellij.openapi.vfs.*
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap

private val ADDED_CONTENT_ROOTS = Key.create<MutableSet<String>>("angelscript.addedContentRoots")
private val DETECTED_COUNT = Key.create<Int>("angelscript.detectedScriptFolderCount")

/**
 * Automatically detects and registers AngelScript Script folders as module content roots.
 *
 * This ensures that Script folders (which follow Unreal Engine conventions) are indexed
 * and treated as first-class project source files, giving them proper search priority.
 *
 * Detection strategy:
 * 1. Find all .uproject files → check for <parent>/Script/
 * 2. Find all .uplugin files → check for <parent>/Script/
 *
 * Cache invalidation:
 * - Invalidates when .uproject/.uplugin files are added/deleted
 * - Invalidates when Script folders are created/deleted
 *
 * This catches:
 * - Project AngelScript code
 * - Project plugin AngelScript code
 * - Engine-level plugin AngelScript code
 *
 * Detection runs again whenever the project's roots change or indexing finishes, not only once:
 * Rider fills the project model in stages for minutes after the first smart mode, and on a project
 * it has never indexed the .uproject/.uplugin files are not in the project scope until then. See
 * docs/plans/0006-register-script-folders-on-first-open.md.
 */
class AngelScriptContentRootProvider : ProjectActivity {
    private val LOG = Logger.getInstance(AngelScriptContentRootProvider::class.java)

    override suspend fun execute(project: Project) {
        LOG.info("AngelScriptContentRootProvider initializing for project: ${project.name}")

        // Adding a content root changes the roots too, which schedules one more detection; by then
        // every folder is registered and nothing changes, so this does not loop.
        project.messageBus.connect().apply {
            subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) = scheduleDetection(project)
            })
            subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
                override fun exitDumbMode() = scheduleDetection(project)
            })
        }
        setupVfsListener(project)
        scheduleDetection(project)
    }

    /** Detects in smart mode off the EDT; requests arriving before one has run are merged into it. */
    private fun scheduleDetection(project: Project) {
        ReadAction.nonBlocking<List<VirtualFile>> { ScriptFolderDetector.detectScriptFolders(project) }
            .inSmartMode(project)
            .expireWhen { project.isDisposed }
            .coalesceBy(this, project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { folders ->
                // Detection runs many times a session; logging only changes keeps the progression
                // as the project model fills ("0", then the real count) without repeating it.
                if (project.getUserData(DETECTED_COUNT) != folders.size) {
                    project.putUserData(DETECTED_COUNT, folders.size)
                    LOG.info("Detected ${folders.size} Script folders")
                }
                registerScriptFolders(project, folders)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun findMainModule(project: Project) = ModuleManager.getInstance(project)
        .modules
        .firstOrNull { !it.name.contains("ReSharper") }

    /**
     * Adds the folders not yet covered by a content root. Runs on the EDT, and checks the module's
     * roots as they are now rather than any record of earlier additions, so it is safe to call as
     * often as detection runs.
     */
    private fun registerScriptFolders(project: Project, folders: Collection<VirtualFile>) {
        if (project.isDisposed || folders.isEmpty()) return

        val mainModule = findMainModule(project)
        if (mainModule == null) {
            LOG.warn("No main module found in project, cannot add content roots")
            return
        }

        val existingRoots = ModuleRootManager.getInstance(mainModule).contentRoots
        val missing = folders.filter { folder ->
            folder.isValid && existingRoots.none { VfsUtilCore.isAncestor(it, folder, false) }
        }
        if (missing.isEmpty()) return

        LOG.info("Adding ${missing.size} of ${folders.size} detected Script folders as content roots")
        ModuleRootModificationUtil.updateModel(mainModule) { model ->
            missing.forEach { folder ->
                LOG.info("  - ${folder.path}")
                model.addContentEntry(folder).addSourceFolder(folder, false)
            }
        }
        addedContentRoots(project).addAll(missing.map { it.path })
    }

    /** The content roots this session added, which are the ones it may remove again. */
    private fun addedContentRoots(project: Project): MutableSet<String> =
        project.getUserData(ADDED_CONTENT_ROOTS)
            ?: (project as UserDataHolderEx).putUserDataIfAbsent(ADDED_CONTENT_ROOTS, ConcurrentHashMap.newKeySet())

    private fun removeContentRootIfExists(project: Project, folder: VirtualFile) {
        val addedContentRoots = addedContentRoots(project)
        if (folder.path !in addedContentRoots) {
            return
        }

        val mainModule = findMainModule(project) ?: return

        LOG.info("Removing content root: ${folder.path}")
        ApplicationManager.getApplication().invokeLater {
            ModuleRootModificationUtil.updateModel(mainModule) { model ->
                model.contentEntries.forEach { entry ->
                    if (entry.url == folder.url) {
                        model.removeContentEntry(entry)
                    }
                }
            }
            addedContentRoots.remove(folder.path)
            LOG.info("Content root removed successfully: ${folder.path}")
        }
    }

    /** VFS listeners run inside the write action that changed the files, so roots change afterwards. */
    private fun registerLater(project: Project, folder: VirtualFile) {
        ApplicationManager.getApplication().invokeLater { registerScriptFolders(project, listOf(folder)) }
    }

    private fun setupVfsListener(project: Project) {
        project.messageBus.connect().subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    // Filter events to this project only
                    val projectBasePath = project.basePath ?: return

                    var shouldRefresh = false

                    for (event in events) {
                        val file = event.file ?: continue

                        // Only process events in this project
                        if (!file.path.startsWith(projectBasePath)) {
                            continue
                        }

                        when (event) {
                            is VFileCreateEvent -> {
                                // Script folder created
                                if (file.isDirectory && file.name == "Script") {
                                    // Check if it's next to .uproject or .uplugin
                                    val parent = file.parent
                                    if (parent != null) {
                                        val hasProjectFile = parent.children.any {
                                            it.extension == "uproject" || it.extension == "uplugin"
                                        }
                                        if (hasProjectFile) {
                                            registerLater(project, file)
                                        }
                                    }
                                }
                                // .uproject or .uplugin file created
                                else if (file.extension == "uproject" || file.extension == "uplugin") {
                                    val scriptFolder = file.parent?.findChild("Script")
                                    if (scriptFolder?.isDirectory == true) {
                                        registerLater(project, scriptFolder)
                                    }
                                }
                            }
                            is VFileDeleteEvent -> {
                                // Script folder deleted
                                if (file.name == "Script" && file.isDirectory) {
                                    removeContentRootIfExists(project, file)
                                }
                                // .uproject or .uplugin file deleted - trigger full refresh
                                else if (file.extension == "uproject" || file.extension == "uplugin") {
                                    shouldRefresh = true
                                }
                            }
                        }
                    }

                    // If a project file was deleted, we might need to remove orphaned content roots
                    if (shouldRefresh) {
                        LOG.info("Project file deleted, triggering refresh")
                        // For now, just log. In production, might want to clean up orphaned roots
                    }
                }
            }
        )
    }

}