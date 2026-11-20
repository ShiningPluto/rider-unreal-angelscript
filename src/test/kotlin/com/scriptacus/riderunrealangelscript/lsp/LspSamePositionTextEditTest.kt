package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.testFramework.TestApplicationManager
import com.intellij.testFramework.UsefulTestCase
import com.redhat.devtools.lsp4ij.LSPIJUtils
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

/**
 * Pins that lsp4ij applies edits at the same position in the order of the array, which completing
 * an override relies on.
 *
 * The LSP specification (3.17, 3.16.6.4) requires that multiple edits at the same position end up
 * in the document in the order they appear in the array. Completing an override of a Blueprint
 * event at column 0 sends `UFUNCTION(BlueprintOverride)\n` and the return type as two insertions at
 * the start of the line, and the other way round the return type lands in front of the macro.
 *
 * Up to lsp4ij 0.21 `LSPIJUtils.applyEdits` reversed two insertions at one offset, and the plugin
 * merged them into one edit before lsp4ij saw them. lsp4ij 0.30 joins them itself (#1833). **If this
 * test fails, lsp4ij reverses them again**: see docs/reference/lsp-completion-workaround.md.
 */
class LspSamePositionTextEditTest : UsefulTestCase() {

    override fun setUp() {
        super.setUp()
        // A bare application: Rider's project-level services refuse to load without a solution.
        TestApplicationManager.getInstance()
    }

    private fun edit(line: Int, start: Int, end: Int, newText: String) =
        TextEdit(Range(Position(line, start), Position(line, end)), newText)

    private fun apply(text: String, edits: List<TextEdit>): String {
        val document = DocumentImpl(text)
        CommandProcessor.getInstance().executeCommand(null, {
            ApplicationManager.getApplication().runWriteAction {
                LSPIJUtils.applyEdits(null, document, edits)
            }
        }, "apply edits", null)
        return document.text
    }

    fun testSamePositionInsertionsComeOutInArrayOrder() {
        assertEquals(
            "class UMyThing\n{\nUFUNCTION()\n\t}\n",
            apply("class UMyThing\n{\n}\n", listOf(edit(2, 0, 0, "UFUNCTION()\n"), edit(2, 0, 0, "\t")))
        )
    }

    /** The edits the server sends for an override of a Blueprint event typed at column 0. */
    fun testBlueprintOverrideAtColumnZeroKeepsTheMacroFirst() {
        val edits = listOf(
            edit(3, 0, 0, "}\n"),
            edit(2, 0, 0, "UFUNCTION(BlueprintOverride)\n"),
            edit(2, 0, 0, "void "),
        )
        assertEquals(
            "class AMyActor\n{\nUFUNCTION(BlueprintOverride)\nvoid BeginPlay()\n}\n}\n",
            apply("class AMyActor\n{\nBeginPlay()\n}\n", edits)
        )
    }

    /** The same typed after indentation, where the edit that adds the return type replaces it. */
    fun testInsertionBeforeAReplacementAtTheSamePositionComesFirst() {
        val edits = listOf(
            edit(2, 0, 0, "\tUFUNCTION(BlueprintOverride)\n"),
            edit(2, 0, 1, "\tvoid "),
        )
        assertEquals(
            "class AMyActor\n{\n\tUFUNCTION(BlueprintOverride)\n\tvoid BeginPlay()\n}\n",
            apply("class AMyActor\n{\n\tBeginPlay()\n}\n", edits)
        )
    }
}
