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
 * Types `;` after the closing parenthesis when the caret is still inside it.
 *
 * With the caret in `Move(|)` typing `;` gives `Move();` and leaves the caret after it, instead of
 * `Move(;)`. This is what Rider's C# editor does and what makes editing a call feel the same; the
 * platform's own handling does not apply to AngelScript.
 */
class AngelScriptSemicolonHandler : TypedHandlerDelegate() {

    override fun beforeCharTyped(
        c: Char,
        project: Project,
        editor: Editor,
        file: PsiFile,
        fileType: FileType,
    ): Result {
        if (c != ';') return Result.CONTINUE
        if (file !is AngelScriptFile) return Result.CONTINUE
        if (!AngelScriptLspSettings.getInstance().state.moveSemicolonOutsideParentheses) return Result.CONTINUE

        val document = editor.document
        val target = semicolonTarget(document.charsSequence, editor.caretModel.offset) ?: return Result.CONTINUE
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(target, ";")
            editor.caretModel.moveToOffset(target + 1)
        }
        return Result.STOP
    }

    companion object {
        /**
         * Where a typed `;` belongs, or null when it should just be typed at the caret.
         *
         * The run of `)` directly after the caret (spaces and tabs allowed) is skipped, so
         * `Move(|)`, `Move(|  )` and `Move(Add(|))` all put the semicolon after the last `)`.
         * A newline stops the scan: the caret is then at the end of a line, not inside a call.
         */
        fun semicolonTarget(text: CharSequence, offset: Int): Int? {
            if (offset < 0 || offset > text.length) return null
            var index = offset
            while (index < text.length && (text[index] == ' ' || text[index] == '\t')) index++
            if (index >= text.length || text[index] != ')') return null
            while (index < text.length && text[index] == ')') index++
            return index
        }
    }
}
