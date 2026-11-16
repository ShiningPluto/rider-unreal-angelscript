package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.scriptacus.riderunrealangelscript.settings.AngelScriptLspSettings
import org.eclipse.lsp4j.CompletionItem

/**
 * Handles the `angelscript.paren` command the language server attaches to every function and
 * method completion item.
 *
 * In VS Code that command inserts `($0)` after the completed name, puts the caret between the
 * parentheses and opens signature help. lsp4ij only inserts the item's text, so without this the
 * completed call has no parentheses. Rider's own AngelScript support used a no-op action for the
 * command, so the behaviour was lost entirely.
 *
 * The insertion happens after lsp4ij has inserted the item, from
 * [AngelScriptCompletionContributor], because the element lsp4ij builds is internal API.
 */
object AngelScriptParenthesisInserter {

    private val LOG = Logger.getInstance(AngelScriptParenthesisInserter::class.java)

    /** The command the server attaches to completion items that need call parentheses. */
    const val PAREN_COMMAND: String = "angelscript.paren"

    /** What to do after a function completion was inserted. */
    enum class Action {
        /** Parentheses are already in place; insert nothing. */
        NONE,

        /** Insert `()` at the caret and put the caret between the parentheses. */
        INSERT_PAIR,

        /** The completion was committed with `.`; put `()` in front of that dot. */
        INSERT_PAIR_BEFORE_COMMIT_DOT,
    }

    /**
     * Decides what to insert, mirroring the branches of the VS Code `angelscript.paren` command:
     *
     * - a `(` directly before the caret means the user typed the parenthesis already,
     * - a `(` directly after the caret means the call parentheses are already there,
     * - a `.` directly before the caret is the commit character of the completion, which has to
     *   end up after the call parentheses,
     * - anything else gets a fresh pair of parentheses.
     */
    fun decide(previousChar: Char?, nextChar: Char?, enabled: Boolean): Action {
        if (previousChar == '(') return Action.NONE
        if (nextChar == '(') return Action.NONE
        if (!enabled) return Action.NONE
        if (previousChar == '.') return Action.INSERT_PAIR_BEFORE_COMMIT_DOT
        return Action.INSERT_PAIR
    }


    private fun scheduleSignatureHelp(context: InsertionContext) {
        val project = context.project
        val editor = context.editor
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || editor.isDisposed) return@invokeLater
            try {
                AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Failed to open signature help after completion", e)
            }
        }
    }

    fun insertIfNeeded(context: InsertionContext, item: CompletionItem) {
        if (item.command?.command != PAREN_COMMAND) return
        // A commit character is being typed: `(` is inserted by the lookup itself, so adding a pair
        // here would give `Foo()(`.
        if (AngelScriptCommitCharacters.suppressParenthesisInsertion) return
        try {
            context.commitDocument()
            val document = context.document
            val text = document.charsSequence
            val caret = context.editor.caretModel.offset
            val previous = if (caret > 0) text[caret - 1] else null
            val next = if (caret < text.length) text[caret] else null
            val enabled = AngelScriptLspSettings.getInstance().state.insertParenthesisOnFunctionCompletion

            when (decide(previous, next, enabled)) {
                Action.NONE -> Unit
                Action.INSERT_PAIR -> {
                    WriteCommandAction.runWriteCommandAction(context.project) {
                        document.insertString(caret, "()")
                        context.editor.caretModel.moveToOffset(caret + 1)
                    }
                    // The VS Code command opens signature help after inserting the parentheses,
                    // because typing the `(` is what would normally trigger it. The caret now sits
                    // between them, which is where the parameter info handler looks.
                    scheduleSignatureHelp(context)
                }
                Action.INSERT_PAIR_BEFORE_COMMIT_DOT -> WriteCommandAction.runWriteCommandAction(context.project) {
                    document.insertString(caret - 1, "()")
                }
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Failed to insert call parentheses after completion", e)
        }
    }
}
