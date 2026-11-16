package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.testFramework.UsefulTestCase
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptCommitCharacters.matches

/**
 * Covers the commit character check the plugin adds for LSP `CompletionItem.commitCharacters`,
 * which lsp4ij does not implement.
 */
class AngelScriptCommitCharactersTest : UsefulTestCase() {

    fun testTypingAnOpenParenthesisCommitsAFunctionCompletion() {
        assertTrue(matches('(', listOf("(", ".")))
    }

    fun testTypingADotCommitsAFunctionCompletion() {
        assertTrue(matches('.', listOf("(", ".")))
    }

    fun testAnUnrelatedCharacterDoesNotCommit() {
        assertFalse(matches('x', listOf("(", ".")))
        assertFalse(matches(' ', listOf("(", ".")))
    }

    fun testNoCommitCharactersNeverCommits() {
        assertFalse(matches('(', null))
        assertFalse(matches('(', emptyList()))
    }

    fun testMultiCharacterEntriesAreIgnored() {
        // The LSP spec defines commit characters as single characters.
        assertFalse(matches('(', listOf("()")))
        assertFalse(matches('(', listOf("")))
    }

    fun testMatchesOnlyTheExactCharacter() {
        assertFalse(matches('(', listOf("[")))
        assertTrue(matches('[', listOf("[")))
    }
}
