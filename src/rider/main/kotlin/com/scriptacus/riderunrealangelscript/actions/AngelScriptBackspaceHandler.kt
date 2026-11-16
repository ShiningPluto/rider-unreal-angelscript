package com.scriptacus.riderunrealangelscript.actions

import com.intellij.codeInsight.editorActions.BackspaceHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.scriptacus.riderunrealangelscript.lang.psi.AngelScriptFile
import com.scriptacus.riderunrealangelscript.settings.AngelScriptLspSettings

/**
 * Deletes the closing delimiter together with the opening one, so removing one of a pair that was
 * inserted together removes both, as Rider's C# editor does.
 *
 * Runs before the character is deleted: with the caret between `"|"` (or `(|)`), deleting the
 * opening character also removes the closing one at the caret, then the platform deletes the
 * character the user asked for.
 */
class AngelScriptBackspaceHandler : BackspaceHandlerDelegate() {

    override fun beforeCharDeleted(c: Char, file: PsiFile, editor: Editor) {
        if (file !is AngelScriptFile) return
        if (!AngelScriptLspSettings.getInstance().state.deleteMatchingDelimiters) return

        val document = editor.document
        val text = document.charsSequence
        val offset = editor.caretModel.offset
        if (!shouldDeletePartner(text, offset, c)) return
        document.deleteString(offset, offset + 1)
    }

    override fun charDeleted(c: Char, file: PsiFile, editor: Editor): Boolean = false

    companion object {
        /**
         * True when the character at `offset - 1` is [c] and the character at [offset] is its
         * closing partner - the caret is between the two halves of a pair.
         */
        fun shouldDeletePartner(text: CharSequence, offset: Int, c: Char): Boolean {
            if (offset <= 0 || offset >= text.length) return false
            if (text[offset - 1] != c) return false
            return closingFor(c) == text[offset]
        }

        /** The character that closes [open], or null when it does not open a pair. */
        fun closingFor(open: Char): Char? = when (open) {
            '"' -> '"'
            '\'' -> '\''
            '(' -> ')'
            '[' -> ']'
            '{' -> '}'
            else -> null
        }
    }
}
