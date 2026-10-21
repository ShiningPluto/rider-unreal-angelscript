package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.testFramework.UsefulTestCase
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptCompletionEdits.mergeSamePositionEdits
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptCompletionEdits.reusableLength
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptCompletionEdits.scopeAwarePrefixStart
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

class AngelScriptCompletionEditsTest : UsefulTestCase() {

    /** The offset the prefix starts at, for text with the caret marked `|`. */
    private fun prefixStart(textWithCaret: String): Int {
        val offset = textWithCaret.indexOf('|')
        return scopeAwarePrefixStart(textWithCaret.removeRange(offset, offset + 1), offset)
    }

    fun testPrefixStartsAfterTheScopeOperator() {
        assertEquals("FVector::".length, prefixStart("FVector::|"))
        assertEquals("FVector::".length, prefixStart("FVector::Ze|"))
    }

    fun testPrefixStartsAfterAnIncompleteScopeOperator() {
        assertEquals("FVector:".length, prefixStart("FVector:|"))
    }

    fun testPrefixIsTheIdentifierBeingTyped() {
        assertEquals("Actor.".length, prefixStart("Actor.GetActor_2|"))
        assertEquals("\t".length, prefixStart("\tBeginPl|"))
        assertEquals(0, prefixStart("Begin|"))
    }

    fun testCharactersAfterTheCaretThatContinueTheInsertTextAreReused() {
        val text = "FVector::Zero;"
        val offset = "FVector::".length
        assertEquals("Zero".length, reusableLength(text, offset, offset, "ZeroVector"))
        // Typed "Ze" of "FVector::Ze|ro"
        assertEquals("ro".length, reusableLength(text, offset, offset + 2, "ZeroVector"))
        assertEquals(0, reusableLength(text, offset, offset, "OneVector"))
    }

    private fun edit(line: Int, start: Int, end: Int, newText: String) =
        TextEdit(Range(Position(line, start), Position(line, end)), newText)

    fun testInsertionsAtOnePositionBecomeOneInArrayOrder() {
        val merged = mergeSamePositionEdits(listOf(edit(2, 0, 0, "UFUNCTION()\n"), edit(2, 0, 0, "\t")))!!
        assertSize(1, merged)
        assertEquals("UFUNCTION()\n\t", merged.single().newText)
        assertEquals(Range(Position(2, 0), Position(2, 0)), merged.single().range)
    }

    fun testAnInsertionAndAReplacementAtOnePositionKeepTheReplacedRange() {
        val merged = mergeSamePositionEdits(listOf(edit(2, 0, 0, "\tUFUNCTION()\n"), edit(2, 0, 1, "\tvoid ")))!!
        assertSize(1, merged)
        assertEquals("\tUFUNCTION()\n\tvoid ", merged.single().newText)
        assertEquals(Range(Position(2, 0), Position(2, 1)), merged.single().range)
    }

    fun testEditsAtDifferentPositionsAreLeftAlone() {
        val edits = listOf(edit(3, 0, 0, "}\n"), edit(2, 0, 0, "UFUNCTION()\n"))
        assertSame(edits, mergeSamePositionEdits(edits))
    }

    fun testOverlappingReplacementsAreLeftAlone() {
        val edits = listOf(edit(2, 0, 1, "a"), edit(2, 0, 2, "b"), edit(4, 0, 0, "c"))
        assertEquals(edits, mergeSamePositionEdits(edits))
    }

    fun testNoEditsStayNone() {
        assertNull(mergeSamePositionEdits(null))
        assertEmpty(mergeSamePositionEdits(emptyList())!!)
    }
}
