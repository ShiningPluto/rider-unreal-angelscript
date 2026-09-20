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
 * Pins the lsp4ij behaviour that [AngelScriptCompletionProposal] exists to work around, so that a
 * lsp4ij upgrade says whether the workaround is still needed instead of leaving it to be guessed.
 *
 * The LSP specification (3.17, 3.16.6.4) requires that multiple edits at the same position end up
 * in the document in the order they appear in the array. `LSPIJUtils.applyEdits` converts every
 * edit to a RangeMarker up front and then applies them in array order - and for two insertions at
 * the same offset that produces the array order *reversed* in the text, because a zero-length
 * marker at the insertion point does not move, so each later insertion lands in front of the
 * earlier one.
 *
 * This matters for real completions: completing an override of a Blueprint event sends
 * `UFUNCTION(BlueprintOverride)\n` inserted at `(line, 0)` together with an edit of the same line
 * that also starts at `(line, 0)`, and getting them the wrong way round indents the macro instead
 * of the declaration.
 *
 * Measured identical on lsp4ij 0.19.1 (what this build compiles against) and 0.21.0 (what Rider
 * ships today). **If this test fails, lsp4ij has changed how it orders same-position edits**: check
 * whether the stable sort in [AngelScriptCompletionProposal] can be dropped in favour of plain
 * `LSPIJUtils.applyEdits`, and update docs/reference/lsp-completion-workaround.md either way.
 */
class LspSamePositionTextEditTest : UsefulTestCase() {

    override fun setUp() {
        super.setUp()
        // A bare application: Rider's project-level services refuse to load without a solution.
        TestApplicationManager.getInstance()
    }

    private fun applyInOrder(vararg newTexts: String): String {
        val document = DocumentImpl("class UMyThing\n{\n}\n")
        val edits = newTexts.map { TextEdit(Range(Position(2, 0), Position(2, 0)), it) }
        CommandProcessor.getInstance().executeCommand(null, {
            ApplicationManager.getApplication().runWriteAction {
                LSPIJUtils.applyEdits(null, document, edits)
            }
        }, "apply edits", null)
        return document.text
    }

    fun testSamePositionEditsComeOutReversedSoTheWorkaroundIsStillNeeded() {
        assertEquals(
            "lsp4ij no longer reverses same-position edits - the completion workaround may be obsolete",
            "class UMyThing\n{\n\tUFUNCTION()\n}\n",
            applyInOrder("UFUNCTION()\n", "\t")
        )
    }

    /** The order AngelScriptCompletionProposal's comparator produces, and what it is worth. */
    fun testReversingTheArrayRestoresTheOrderTheSpecAsksFor() {
        assertEquals(
            "class UMyThing\n{\nUFUNCTION()\n\t}\n",
            applyInOrder("\t", "UFUNCTION()\n")
        )
    }
}
