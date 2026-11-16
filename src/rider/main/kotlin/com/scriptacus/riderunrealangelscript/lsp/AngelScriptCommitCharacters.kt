package com.scriptacus.riderunrealangelscript.lsp

import org.eclipse.lsp4j.CompletionItem

/**
 * Support for LSP `CompletionItem.commitCharacters`, which lsp4ij 0.21 does not implement: it never
 * reads the field, so typing `(` or `.` while a function is selected closes the lookup through
 * IntelliJ's own behaviour instead of committing the selected item.
 *
 * The plugin knows each inserted item, because [AngelScriptCompletionContributor] wraps lsp4ij's
 * lookup element in a decorator to format what was inserted. That decorator carries the item, and
 * [AngelScriptCommitCharacterHandler] uses it to decide whether a typed character is a commit
 * character.
 */
interface AngelScriptCompletionItemHolder {
    /** The LSP item the wrapped lookup element was built from. */
    val completionItem: CompletionItem
}

/**
 * Set while a commit character is being handled, so that the parenthesis inserter knows the
 * character is about to be inserted by the lookup itself and must not add a second pair.
 */
object AngelScriptCommitCharacters {

    @Volatile
    var suppressParenthesisInsertion: Boolean = false

    /**
     * True when [character] is one of the item's commit characters.
     *
     * The LSP spec defines commit characters as single characters; anything else is ignored.
     */
    fun matches(character: Char, commitCharacters: List<String>?): Boolean {
        if (commitCharacters.isNullOrEmpty()) return false
        return commitCharacters.any { it.length == 1 && it[0] == character }
    }
}
