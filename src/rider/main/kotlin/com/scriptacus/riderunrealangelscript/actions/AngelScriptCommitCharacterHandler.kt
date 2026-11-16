package com.scriptacus.riderunrealangelscript.actions

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.scriptacus.riderunrealangelscript.lang.psi.AngelScriptFile
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptCommitCharacters
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptCompletionItemHolder
import com.scriptacus.riderunrealangelscript.settings.AngelScriptLspSettings

/**
 * Implements LSP `CompletionItem.commitCharacters`, which lsp4ij leaves unsupported.
 *
 * When the lookup is open, the selected element came from an LSP item with commit characters, and
 * the typed character is one of them, the completion is committed and the character is typed in
 * the same step - what VS Code does. The check runs before the character is inserted, so the
 * completion can still be committed.
 */
class AngelScriptCommitCharacterHandler : TypedHandlerDelegate() {

    override fun beforeCharTyped(
        c: Char,
        project: Project,
        editor: Editor,
        file: PsiFile,
        fileType: FileType,
    ): Result {
        if (file !is AngelScriptFile) return Result.CONTINUE
        if (!AngelScriptLspSettings.getInstance().state.useCommitCharacters) return Result.CONTINUE

        val lookup = LookupManager.getActiveLookup(editor) as? LookupImpl ?: return Result.CONTINUE
        val element = lookup.currentItem ?: return Result.CONTINUE
        val holder = findItemHolder(element) ?: return Result.CONTINUE
        if (!AngelScriptCommitCharacters.matches(c, holder.completionItem.commitCharacters)) {
            return Result.CONTINUE
        }

        // `(` is inserted by finishLookup itself, so the completion must not add its own pair;
        // without this the result would be `Foo()(`. A `.` needs the parentheses before it, which
        // the parenthesis inserter already adds when the completion is inserted.
        val suppressParentheses = c == '('
        try {
            if (suppressParentheses) {
                AngelScriptCommitCharacters.suppressParenthesisInsertion = true
            }
            lookup.finishLookup(c)
            return Result.STOP
        } catch (e: Exception) {
            LOG.warn("Failed to commit the selected AngelScript completion for '$c'", e)
            return Result.CONTINUE
        } finally {
            if (suppressParentheses) {
                AngelScriptCommitCharacters.suppressParenthesisInsertion = false
            }
        }
    }

    /** Walks the decorator chain added by lsp4ij and the plugin to find the LSP item. */
    private fun findItemHolder(element: LookupElement): AngelScriptCompletionItemHolder? {
        var current: LookupElement? = element
        var guard = 0
        while (current != null && guard++ < MAX_DECORATORS) {
            if (current is AngelScriptCompletionItemHolder) return current
            current = (current as? LookupElementDecorator<*>)?.delegate
        }
        return null
    }

    private companion object {
        private val LOG = Logger.getInstance(AngelScriptCommitCharacterHandler::class.java)
        private const val MAX_DECORATORS = 10
    }
}
