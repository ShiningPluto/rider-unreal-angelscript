package com.scriptacus.riderunrealangelscript.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.redhat.devtools.lsp4ij.commands.LSPCommand
import com.redhat.devtools.lsp4ij.commands.LSPCommandAction
import java.awt.datatransfer.DataFlavor

/**
 * Implements the VS Code command `editor.action.insertSnippet` for the AngelScript language
 * server.
 *
 * The server uses it for the file-template code lenses it offers in an empty script file
 * ("Create Actor", "Create Component", ...): the command's first argument carries the template as
 * a VS Code snippet. Without an action for the command lsp4ij reports
 * `Missing 'editor.action.insertSnippet' command!`.
 */
class InsertSnippetCommandAction : LSPCommandAction() {

    override fun getCommandPerformedThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun commandPerformed(command: LSPCommand, e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val argument = command.getArgumentAt(0, InsertSnippetArgument::class.java)
        val snippet = argument?.snippet
        if (snippet.isNullOrEmpty()) {
            LOG.warn("insertSnippet command without a snippet argument: ${command.arguments}")
            return
        }

        val file = e.getData(CommonDataKeys.PSI_FILE)
        val context = AngelScriptSnippets.Context(
            fileName = file?.name ?: "",
            filePath = file?.virtualFile?.path ?: "",
            clipboard = clipboardText(),
        )
        val expanded = AngelScriptSnippets.expand(snippet, context)

        try {
            WriteCommandAction.runWriteCommandAction(project, "Insert Snippet", null, {
                val document = editor.document
                val selectionModel = editor.selectionModel
                val caret = editor.caretModel.offset
                val start = if (selectionModel.hasSelection()) selectionModel.selectionStart else caret
                val end = if (selectionModel.hasSelection()) selectionModel.selectionEnd else caret
                if (end > start) {
                    document.deleteString(start, end)
                }
                document.insertString(start, expanded.text)
                editor.caretModel.moveToOffset(start + expanded.caretOffset)
            })
        } catch (ex: ProcessCanceledException) {
            throw ex
        } catch (ex: Exception) {
            LOG.warn("Failed to insert snippet from the language server", ex)
        }
    }

    private fun clipboardText(): String =
        try {
            CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor) ?: ""
        } catch (ex: Exception) {
            ""
        }

    /** The command's first argument, as sent by the AngelScript language server. */
    class InsertSnippetArgument {
        var snippet: String? = null
        var name: String? = null
        var langId: String? = null
    }

    private companion object {
        private val LOG = Logger.getInstance(InsertSnippetCommandAction::class.java)
    }
}
