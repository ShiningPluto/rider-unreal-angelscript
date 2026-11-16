package com.scriptacus.riderunrealangelscript.actions

import com.intellij.testFramework.UsefulTestCase
import com.scriptacus.riderunrealangelscript.actions.AngelScriptSemicolonHandler.Companion.semicolonTarget

/**
 * Covers where a typed `;` is placed when the caret is inside a call, the behaviour Rider's C#
 * editor has and this plugin adds for AngelScript.
 */
class AngelScriptSemicolonHandlerTest : UsefulTestCase() {

    /** Runs the decision with the caret marked `|` in [textWithCaret]. */
    private fun target(textWithCaret: String): Int? {
        val offset = textWithCaret.indexOf('|')
        val text = textWithCaret.removeRange(offset, offset + 1)
        return semicolonTarget(text, offset)
    }

    fun testSemicolonGoesAfterTheClosingParenthesis() {
        assertEquals("Move()".length, target("Move(|)"))
    }

    fun testWhitespaceBeforeTheParenthesisIsSkipped() {
        assertEquals("Move(  )".length, target("Move(|  )"))
        assertEquals("Move(\t)".length, target("Move(|\t)"))
    }

    fun testNestedCallsPutTheSemicolonAfterTheOutermostParenthesis() {
        assertEquals("Move(Add())".length, target("Move(Add(|))"))
    }

    fun testNoParenthesisAfterTheCaretTypesTheSemicolonInPlace() {
        assertNull(target("Move(|"))
        assertNull(target("Move()|"))
        assertNull(target("Move(| { }"))
    }

    fun testANewlineStopsTheScan() {
        assertNull(target("Move(|\n)"))
        assertNull(target("Move()|\n"))
    }

    fun testOutOfRangeOffsetsAreIgnored() {
        assertNull(semicolonTarget("abc", -1))
        assertNull(semicolonTarget("abc", 10))
    }
}
