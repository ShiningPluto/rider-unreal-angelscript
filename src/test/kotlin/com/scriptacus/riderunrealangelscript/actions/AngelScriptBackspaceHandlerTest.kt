package com.scriptacus.riderunrealangelscript.actions

import com.intellij.testFramework.UsefulTestCase
import com.scriptacus.riderunrealangelscript.actions.AngelScriptBackspaceHandler.Companion.closingFor
import com.scriptacus.riderunrealangelscript.actions.AngelScriptBackspaceHandler.Companion.shouldDeletePartner

/**
 * Covers when deleting one half of a pair also deletes the other.
 */
class AngelScriptBackspaceHandlerTest : UsefulTestCase() {

    /** Runs the decision with the caret marked `|` in [textWithCaret]. */
    private fun deletesPartner(textWithCaret: String, c: Char): Boolean {
        val offset = textWithCaret.indexOf('|')
        val text = textWithCaret.removeRange(offset, offset + 1)
        return shouldDeletePartner(text, offset, c)
    }

    fun testQuotesAreDeletedAsAPair() {
        assertTrue(deletesPartner("\"|\"", '"'))
        assertTrue(deletesPartner("'|'", '\''))
    }

    fun testBracketsAreDeletedAsAPair() {
        assertTrue(deletesPartner("(|)", '('))
        assertTrue(deletesPartner("[|]", '['))
        assertTrue(deletesPartner("{|}", '{'))
    }

    fun testDeletingInsideAStringDoesNotTouchTheClosingQuote() {
        assertFalse(deletesPartner("\"a|bc\"", 'a'))
        assertFalse(deletesPartner("\"|abc\"", '"'))
    }

    fun testMismatchedHalvesAreNotDeleted() {
        assertFalse(deletesPartner("(|]", '('))
        assertFalse(deletesPartner("\"|'", '"'))
    }

    fun testEdgesOfTheDocumentAreIgnored() {
        assertFalse(shouldDeletePartner("\"\"", 0, '"'))
        assertFalse(shouldDeletePartner("\"\"", 2, '"'))
    }

    fun testNonPairCharactersHaveNoClosingPartner() {
        assertNull(closingFor('x'))
        assertNull(closingFor(')'))
        assertEquals(')', closingFor('('))
        assertEquals('"', closingFor('"'))
    }
}
