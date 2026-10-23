package com.scriptacus.riderunrealangelscript.recompile

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptDiagnosticsStore
import com.scriptacus.riderunrealangelscript.status.AngelScriptUnrealStatus
import com.scriptacus.riderunrealangelscript.status.AngelScriptUnrealStatusService
import org.eclipse.lsp4j.DiagnosticSeverity
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime

/**
 * Asks the Unreal editor to compile script files again.
 *
 * An error Unreal reported stays until the file is compiled again, even once its cause is gone - a
 * gameplay tag added since, say, re-sends the type database but compiles nothing. The debug protocol
 * has no message for compiling, but the editor notices a changed script by its modification time
 * alone, without comparing contents, and every reload also retries the files that failed before.
 * So a file is touched rather than rewritten, which also works on the read-only files of a Perforce
 * workspace. A file with unsaved edits is saved instead: that already changes it on disk, and
 * touching it would make Rider ask whether to keep the edits or the file.
 */
object UnrealRecompile {

    private val LOG = Logger.getInstance(UnrealRecompile::class.java)

    /** Only an editor that is connected now can be told; one starting later compiles everything anyway. */
    fun isEditorConnected(project: Project): Boolean =
        AngelScriptUnrealStatusService.getInstance(project).status == AngelScriptUnrealStatus.READY

    /** Whether a diagnostic is a compile error Unreal reported, rather than a warning or the server's own analysis. */
    fun isUnrealCompileError(source: String?, severity: DiagnosticSeverity?): Boolean =
        source == AngelScriptDiagnosticsStore.UNREAL_COMPILE_SOURCE &&
            (severity == null || severity == DiagnosticSeverity.Error)

    /** The files Unreal currently reports compile errors in. A file with only warnings compiled. */
    fun filesWithUnrealErrors(store: AngelScriptDiagnosticsStore): List<String> =
        store.all()
            .filter { file -> file.problems.any { isUnrealCompileError(it.source, it.severity) } }
            .map { it.path }

    /**
     * Makes the editor compile [paths] again. Call on the EDT: unsaved documents are saved here, and
     * the rest are touched off it.
     */
    fun recompile(project: Project, paths: Collection<String>) {
        val documents = FileDocumentManager.getInstance()
        val toTouch = mutableListOf<Path>()
        val touchedFiles = mutableListOf<VirtualFile>()
        var saved = 0
        for (path in paths) {
            val file = LocalFileSystem.getInstance().findFileByPath(path)
            val document = file?.let { documents.getCachedDocument(it) }
            if (document != null && documents.isDocumentUnsaved(document)) {
                documents.saveDocument(document)
                saved++
            } else {
                toTouch.add(Paths.get(path))
                file?.let { touchedFiles.add(it) }
            }
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val touched = toTouch.count { touch(it) }
            // So Rider takes the new time in now, rather than noticing it as an outside change later.
            if (touchedFiles.isNotEmpty()) {
                VfsUtil.markDirtyAndRefresh(true, false, false, *touchedFiles.toTypedArray())
            }
            val count = saved + touched
            ApplicationManager.getApplication().invokeLater({
                WindowManager.getInstance().getStatusBar(project)?.info =
                    if (count == 0) "Could not ask the Unreal editor to compile the script files again"
                    else "Asked the Unreal editor to compile $count script ${if (count == 1) "file" else "files"} again"
            }, project.disposed)
        }
    }

    /**
     * Moves [file]'s modification time forward without touching its contents. Returns false if the
     * time could not be set.
     */
    fun touch(file: Path, now: Long = System.currentTimeMillis()): Boolean = try {
        // At least a second later, so a file saved a moment ago still reads as changed to anything
        // that compares times at a resolution of seconds.
        val previous = Files.getLastModifiedTime(file).toMillis()
        Files.setLastModifiedTime(file, FileTime.fromMillis(maxOf(now, previous + 1000)))
        true
    } catch (e: IOException) {
        LOG.warn("Could not touch $file to make the Unreal editor compile it again", e)
        false
    }
}
