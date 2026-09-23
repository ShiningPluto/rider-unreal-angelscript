package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.UsefulTestCase
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/**
 * Guards the record of what the language server has reported, which the MCP tools answer from.
 * The server names one file in several spellings, and a file recorded twice under two of them
 * would keep reporting problems that a later push under the other spelling had already cleared.
 */
class AngelScriptDiagnosticsStoreTest : UsefulTestCase() {

    /** The editor's spelling, the server's own for files it scanned, and Unreal's raw path. */
    fun testEverySpellingOfAFileUriBecomesAPath() {
        assertEquals("D:/Game/Script/Cart.as", AngelScriptDiagnosticsStore.pathFromUri("file:///D:/Game/Script/Cart.as"))
        assertEquals("d:/Game/Script/Cart.as", AngelScriptDiagnosticsStore.pathFromUri("file:///d%3A/Game/Script/Cart.as"))
        assertEquals("D:/Game/Script/Cart.as", AngelScriptDiagnosticsStore.pathFromUri("file:///D:\\Game\\Script\\Cart.as"))
        assertEquals("/home/dev/Script/Cart.as", AngelScriptDiagnosticsStore.pathFromUri("file:///home/dev/Script/Cart.as"))
    }

    /** Escapes are undone as UTF-8; a `%` that starts no escape is part of a raw Unreal path. */
    fun testEscapesAreDecodedAndStrayPercentSignsKept() {
        assertEquals("D:/My Game/翻倒.as", AngelScriptDiagnosticsStore.pathFromUri("file:///D:/My%20Game/%E7%BF%BB%E5%80%92.as"))
        assertEquals("D:/Game/100%.as", AngelScriptDiagnosticsStore.pathFromUri("file:///D:/Game/100%.as"))
    }

    fun testNonFileUrisAreIgnored() {
        assertNull(AngelScriptDiagnosticsStore.pathFromUri("untitled:Untitled-1"))

        val store = AngelScriptDiagnosticsStore()
        store.record("untitled:Untitled-1", listOf(diagnostic("x", DiagnosticSeverity.Error)))
        assertEmpty(store.all())
    }

    /** Each push carries the file's complete set, so the latest one replaces whatever came before. */
    fun testTheLatestPushForAFileWinsWhateverItsSpelling() {
        val store = AngelScriptDiagnosticsStore()
        store.record("file:///D:/Game/Script/Cart.as", listOf(diagnostic("old", DiagnosticSeverity.Error)), now = 1)
        store.record("file:///D%3A/Game/Script/Cart.as", listOf(diagnostic("new", DiagnosticSeverity.Warning)), now = 2)

        val file = store.get("D:/Game/Script/Cart.as")!!
        assertEquals(listOf("new"), file.problems.map { it.message })
        assertEquals(2L, file.updatedAt)
        assertEquals(1, store.all().size)

        if (!SystemInfo.isFileSystemCaseSensitive) {
            // Rider and the server disagree on the case of the drive letter; on Windows both are one file.
            store.record("file:///d%3A/Game/Script/Cart.as", emptyList(), now = 3)
            assertEquals(3L, store.get("D:\\Game\\Script\\Cart.as")!!.updatedAt)
        }
    }

    /** A cleared file stays answerable - with when it was cleared - but is no longer a file with problems. */
    fun testAnEmptyPushClearsAFile() {
        val store = AngelScriptDiagnosticsStore()
        store.record("file:///D:/Game/Script/Cart.as", listOf(diagnostic("broken", DiagnosticSeverity.Error)), now = 1)
        store.record("file:///D:/Game/Script/Cart.as", emptyList(), now = 2)

        assertEmpty(store.all())
        assertEquals(2L, store.get("D:/Game/Script/Cart.as")!!.updatedAt)
        assertEmpty(store.get("D:/Game/Script/Cart.as")!!.problems)

        store.clear()
        assertNull(store.get("D:/Game/Script/Cart.as"))
    }

    /**
     * The message is read reflectively - see messageText - so a broken read would compile fine.
     * This test compiles against the SDK's lsp4j 0.21 but runs on lsp4ij's 1.0, exactly as the
     * plugin does inside the IDE, so it exercises the `Either` spelling that a direct call breaks on.
     */
    fun testMessagesAreRecorded() {
        val store = AngelScriptDiagnosticsStore()
        store.record("file:///D:/Game/Script/Cart.as", listOf(diagnostic("broken", DiagnosticSeverity.Error)))

        val problem = store.get("D:/Game/Script/Cart.as")!!.problems.single()
        assertEquals("broken", problem.message)
        assertEquals(3, problem.line)
        assertEquals(4, problem.character)
        assertEquals("angelscript", problem.source)
    }

    fun testPathsUnderTheProjectAreMadeRelative() {
        assertEquals("Script/Cart.as", AngelScriptDiagnosticsStore.relativeTo("D:/Game/Proj", "D:/Game/Proj/Script/Cart.as"))
        assertEquals("Script/Cart.as", AngelScriptDiagnosticsStore.relativeTo("D:\\Game\\Proj\\", "D:/Game/Proj/Script/Cart.as"))
        // A sibling directory sharing a prefix is not under the project.
        assertEquals("D:/Game/Proj2/Cart.as", AngelScriptDiagnosticsStore.relativeTo("D:/Game/Proj", "D:/Game/Proj2/Cart.as"))
        assertEquals("D:/Game/Proj/Cart.as", AngelScriptDiagnosticsStore.relativeTo(null, "D:/Game/Proj/Cart.as"))
    }

    private fun diagnostic(message: String, severity: DiagnosticSeverity) =
        Diagnostic(Range(Position(3, 4), Position(3, 9)), message, severity, "angelscript")
}
