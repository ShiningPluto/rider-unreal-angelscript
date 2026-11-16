package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.testFramework.UsefulTestCase
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptParenthesisInserter.Action
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptParenthesisInserter.decide

/**
 * Covers the decision the plugin makes after a function completion was inserted, which mirrors the
 * branches of the VS Code extension's `angelscript.paren` command.
 */
class AngelScriptParenthesisInserterTest : UsefulTestCase() {

    /** Runs the decision with the caret marked `|` in [textWithCaret]. */
    private fun decideAt(textWithCaret: String, enabled: Boolean = true): Action {
        val offset = textWithCaret.indexOf('|')
        val text = textWithCaret.removeRange(offset, offset + 1)
        val previous = if (offset > 0) text[offset - 1] else null
        val next = if (offset < text.length) text[offset] else null
        return decide(previous, next, enabled)
    }

    fun testInsertsPairAfterACompletedFunctionName() {
        assertEquals(Action.INSERT_PAIR, decideAt("BeginPlay|"))
        assertEquals(Action.INSERT_PAIR, decideAt("MyActor.DoThing|"))
        assertEquals(Action.INSERT_PAIR, decideAt("        Print|"))
    }

    fun testDoesNothingWhenTheUserTypedTheParenthesisAlready() {
        assertEquals(Action.NONE, decideAt("BeginPlay(|"))
    }

    fun testDoesNothingWhenCallParenthesesAreAlreadyThere() {
        assertEquals(Action.NONE, decideAt("BeginPlay|()"))
        assertEquals(Action.NONE, decideAt("BeginPlay|(float DeltaTime)"))
    }

    fun testDoesNothingWhenTheSettingIsOff() {
        assertEquals(Action.NONE, decideAt("BeginPlay|", enabled = false))
        assertEquals(Action.NONE, decideAt("MyActor.DoThing|", enabled = false))
    }

    fun testCompletedMemberAccessWithCommitDotGetsParenthesesInFront() {
        assertEquals(Action.INSERT_PAIR_BEFORE_COMMIT_DOT, decideAt("GetOwner().|"))
        assertEquals(Action.INSERT_PAIR_BEFORE_COMMIT_DOT, decideAt("MyActor.|"))
    }
}
