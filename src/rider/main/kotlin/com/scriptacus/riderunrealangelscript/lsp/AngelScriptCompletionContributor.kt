package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.codeStyle.CodeStyleManager
import com.redhat.devtools.lsp4ij.LSPIJUtils
import org.eclipse.lsp4j.CompletionItem

/**
 * Formats what an AngelScript completion inserted, once lsp4ij has inserted it.
 *
 * lsp4ij's lookup element is internal API, so its insertion cannot be extended by subclassing it.
 * This contributor runs before lsp4ij's and wraps each element lsp4ij produces - after lsp4ij has
 * done with it, since lsp4ij casts the elements it adds to its own class - in a decorator that
 * inserts through the element and then formats. The decorator forwards everything else, and copies
 * the element's user data, including the flag that lets lsp4ij insert the text itself.
 */
class AngelScriptCompletionContributor : CompletionContributor(), DumbAware {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        result.runRemainingContributors(parameters) { completion ->
            val element = completion.lookupElement
            val item = element.getUserData(AngelScriptCompletionEdits.COMPLETION_ITEM)
            result.passResult(if (item == null) completion else completion.withLookupElement(FormatAfterInsert(element, item)))
        }
    }

    private class FormatAfterInsert(
        delegate: LookupElement,
        private val item: CompletionItem,
    ) : LookupElementDecorator<LookupElement>(delegate) {

        override fun handleInsert(context: InsertionContext) {
            super.handleInsert(context)
            formatInserted(context, item)
        }
    }

    private companion object {
        private val LOG = Logger.getInstance(AngelScriptCompletionContributor::class.java)

        /** Reformats the range the main edit and the item's additional edits touched. */
        fun formatInserted(context: InsertionContext, item: CompletionItem) {
            try {
                val document = context.document
                context.commitDocument()

                var start = context.startOffset
                var end = context.tailOffset
                item.additionalTextEdits?.forEach { edit ->
                    start = minOf(start, LSPIJUtils.toOffset(edit.range.start, document))
                    end = maxOf(end, LSPIJUtils.toOffset(edit.range.end, document) + edit.newText.orEmpty().length)
                }
                end = minOf(end, document.textLength)
                if (start >= end) return

                WriteCommandAction.runWriteCommandAction(context.project) {
                    CodeStyleManager.getInstance(context.project).reformatText(context.file, start, end)
                }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Failed to format after completion", e)
            }
        }
    }
}
