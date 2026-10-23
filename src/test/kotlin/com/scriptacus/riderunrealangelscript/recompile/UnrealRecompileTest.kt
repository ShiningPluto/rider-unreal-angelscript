package com.scriptacus.riderunrealangelscript.recompile

import com.intellij.testFramework.UsefulTestCase
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptDiagnosticsStore
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.FileTime

/**
 * Guards how the Unreal editor is made to compile a file again: by its modification time alone, since
 * the debug protocol has no message for it. A touch that silently fails, or that rewrites the file,
 * would either do nothing or put an edit into a file the user never changed.
 */
class UnrealRecompileTest : UsefulTestCase() {

    private lateinit var dir: Path

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("unreal-recompile")
    }

    override fun tearDown() {
        try {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach {
                setReadOnly(it, false)
                Files.delete(it)
            }
        } finally {
            super.tearDown()
        }
    }

    fun testTouchMovesTheTimeForwardAndLeavesTheContentsAlone() {
        val file = script("void Tick() {}\n", modifiedAt = 1_000_000L)
        assertTrue(UnrealRecompile.touch(file, now = 5_000_000L))
        assertEquals(5_000_000L, Files.getLastModifiedTime(file).toMillis())
        assertEquals("void Tick() {}\n", Files.readString(file))
    }

    /** Perforce leaves files that are not checked out read-only; the time can still be set on them. */
    fun testTouchWorksOnReadOnlyFiles() {
        val file = script("// read-only\n", modifiedAt = 1_000_000L)
        setReadOnly(file, true)
        assertTrue(UnrealRecompile.touch(file, now = 5_000_000L))
        assertEquals(5_000_000L, Files.getLastModifiedTime(file).toMillis())
        assertEquals("// read-only\n", Files.readString(file))
    }

    /** A file saved a moment ago would otherwise keep a time the editor has already seen. */
    fun testTouchMovesForwardEvenWhenTheFileWasJustSaved() {
        val file = script("// fresh\n", modifiedAt = 5_000_000L)
        assertTrue(UnrealRecompile.touch(file, now = 5_000_000L))
        assertTrue(Files.getLastModifiedTime(file).toMillis() >= 5_001_000L)
    }

    fun testTouchReportsAFileItCouldNotReach() {
        assertFalse(UnrealRecompile.touch(dir.resolve("Missing.as")))
    }

    /** Warnings mean the file compiled, and the server's own errors are not Unreal's to clear. */
    fun testOnlyFilesWithUnrealCompileErrorsAreRecompiled() {
        val store = AngelScriptDiagnosticsStore()
        store.record("file:///D:/Game/Script/Broken.as", listOf(diagnostic(DiagnosticSeverity.Error, "as")))
        store.record("file:///D:/Game/Script/Warned.as", listOf(diagnostic(DiagnosticSeverity.Warning, "as")))
        store.record("file:///D:/Game/Script/Analysed.as", listOf(diagnostic(DiagnosticSeverity.Error, "angelscript")))
        assertEquals(listOf("D:/Game/Script/Broken.as"), UnrealRecompile.filesWithUnrealErrors(store))
    }

    /** A diagnostic without a severity is an error by the LSP specification's default. */
    fun testAnUnrealDiagnosticWithoutSeverityIsAnError() {
        assertTrue(UnrealRecompile.isUnrealCompileError("as", null))
        assertFalse(UnrealRecompile.isUnrealCompileError("angelscript", null))
        assertFalse(UnrealRecompile.isUnrealCompileError("as", DiagnosticSeverity.Information))
    }

    private fun script(text: String, modifiedAt: Long): Path {
        val file = Files.createTempFile(dir, "Script", ".as")
        Files.writeString(file, text)
        Files.setLastModifiedTime(file, FileTime.fromMillis(modifiedAt))
        return file
    }

    private fun setReadOnly(file: Path, readOnly: Boolean) {
        val dos = Files.getFileAttributeView(file, DosFileAttributeView::class.java)
        if (dos != null) dos.setReadOnly(readOnly) else file.toFile().setWritable(!readOnly)
    }

    private fun diagnostic(severity: DiagnosticSeverity, source: String) =
        Diagnostic(Range(Position(0, 0), Position(0, 1)), "message", severity, source)
}
