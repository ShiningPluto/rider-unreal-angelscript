package com.scriptacus.riderunrealangelscript.actions

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.scriptacus.riderunrealangelscript.lang.psi.AngelScriptFile
import com.scriptacus.riderunrealangelscript.settings.AngelScriptLspSettings

/**
 * Pairs a typed quote with a closing one and steps over an existing one, the way Rider's C# editor
 * does. The platform only does this for languages that register a quote handler.
 *
 * Behaviour, for `"` and `'`:
 * - outside a string: `Foo(|)` + `"` gives `Foo("")` with the caret between the quotes;
 * - an existing quote right after the caret is step over instead of duplicated;
 * - inside a string the quote closes it, so nothing is paired;
 * - in a line comment, or right after a backslash, the quote is typed as is.
 */
class AngelScriptQuoteHandler : TypedHandlerDelegate() {

    override fun beforeCharTyped(
        c: Char,
        project: Project,
        editor: Editor,
        file: PsiFile,
        fileType: FileType,
    ): Result {
        if (c != '"' && c != '\'') return Result.CONTINUE
        if (file !is AngelScriptFile) return Result.CONTINUE
        if (!AngelScriptLspSettings.getInstance().state.autoCloseQuotes) return Result.CONTINUE

        val document = editor.document
        val text = document.charsSequence
        val offset = editor.caretModel.offset
        if (offset > 0 && text[offset - 1] == '\\') return Result.CONTINUE

        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        if (isInsideLineComment(text, lineStart, offset)) return Result.CONTINUE

        val nextIsSameQuote = offset < text.length && text[offset] == c
        if (nextIsSameQuote) {
            editor.caretModel.moveToOffset(offset + 1)
            return Result.STOP
        }

        // Inside a literal the quote closes it.
        if (isInsideString(text, lineStart, offset, c)) return Result.CONTINUE

        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(offset, "$c$c")
            editor.caretModel.moveToOffset(offset + 1)
        }
        return Result.STOP
    }

    companion object {
        /**
         * True when an odd number of [quote] characters precedes the caret on this line, ignoring
         * escaped ones: the caret is then inside a literal.
         */
        fun isInsideString(text: CharSequence, lineStart: Int, offset: Int, quote: Char): Boolean {
            var count = 0
            var index = lineStart
            val end = minOf(offset, text.length)
            while (index < end) {
                val ch = text[index]
                if (ch == '\\') {
                    index += 2
                    continue
                }
                if (ch == quote) count++
                index++
            }
            return count % 2 == 1
        }

        /** True when `//` appears before the caret outside any literal on this line. */
        fun isInsideLineComment(text: CharSequence, lineStart: Int, offset: Int): Boolean {
            var quotes = 0
            var index = lineStart
            val end = minOf(offset, text.length)
            while (index < end - 1) {
                val ch = text[index]
                if (ch == '\\') {
                    index += 2
                    continue
                }
                if (ch == '"') quotes++
                if (ch == '/' && text[index + 1] == '/' && quotes % 2 == 0) return true
                index++
            }
            return false
        }
    }
}
