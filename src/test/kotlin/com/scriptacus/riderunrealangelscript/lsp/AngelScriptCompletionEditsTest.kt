package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.testFramework.UsefulTestCase
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptCompletionEdits.reusableLength
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptCompletionEdits.scopeAwarePrefixStart

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
}
