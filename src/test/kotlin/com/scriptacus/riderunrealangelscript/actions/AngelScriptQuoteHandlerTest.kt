package com.scriptacus.riderunrealangelscript.actions

import com.intellij.testFramework.UsefulTestCase
import com.scriptacus.riderunrealangelscript.actions.AngelScriptQuoteHandler.Companion.isInsideLineComment
import com.scriptacus.riderunrealangelscript.actions.AngelScriptQuoteHandler.Companion.isInsideString

/**
 * Covers the decisions behind quote pairing: whether the caret is inside a literal, and whether it
 * is in a line comment (where quotes are typed as they are).
 */
class AngelScriptQuoteHandlerTest : UsefulTestCase() {

    fun testCaretInsideAnUnclosedLiteral() {
        val text = "Foo(\"abc\")"
        assertTrue(isInsideString(text, 0, 6, '"'))
        assertFalse(isInsideString(text, 0, 9, '"'))
    }

    fun testEscapedQuotesDoNotCloseTheLiteral() {
        // Foo("a\"b")
        val text = "Foo(\"a\\\"b\")"
        assertTrue(isInsideString(text, 0, 9, '"'))
    }

    fun testSingleQuotesAreTrackedSeparately() {
        val text = "x = 'a';"
        assertTrue(isInsideString(text, 0, 6, '\''))
        assertFalse(isInsideString(text, 0, 8, '\''))
        assertFalse(isInsideString(text, 0, 6, '"'))
    }

    fun testLineCommentIsDetected() {
        assertTrue(isInsideLineComment("// \"x\"", 0, 6))
        assertTrue(isInsideLineComment("int a; // note", 0, 12))
    }

    fun testSlashSlashInsideAStringIsNotAComment() {
        assertFalse(isInsideLineComment("Foo(\"//\")", 0, 8))
    }

    fun testCodeWithoutCommentIsNotAComment() {
        assertFalse(isInsideLineComment("Foo(a, b);", 0, 9))
    }
}
