package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Corrections made to a completion item before lsp4ij builds its lookup element from it, so that
 * lsp4ij's own insertion does the right thing. lsp4ij's lookup element is internal API and cannot
 * be subclassed or replaced - its completion feature casts every element back to it - so the item
 * is the one place left to change. See docs/reference/lsp-completion-workaround.md.
 */
object AngelScriptCompletionEdits {

    /** The completion item a lookup element was built from, for the work done after insertion. */
    val COMPLETION_ITEM: Key<CompletionItem> = Key.create("angelscript.completionItem")

    fun prepare(item: CompletionItem, document: Document, file: PsiFile, offset: Int) {
        fixPrefixAfterScope(item, document, file, offset)
        item.additionalTextEdits = mergeSamePositionEdits(item.additionalTextEdits)
    }

    /**
     * Gives the item the range it replaces when lsp4ij would get it wrong: right after `::`.
     *
     * The server sends no text edit, so lsp4ij takes the replaced range from the PSI leaf before the
     * caret, and after `::` that leaf is the operator itself, which would be replaced. Anywhere else
     * the leaf is the identifier being typed and lsp4ij's range is right, so the item is left alone:
     * a text edit also changes how lsp4ij filters, which goes wrong for the many items whose filter
     * text is not the start of their label.
     */
    private fun fixPrefixAfterScope(item: CompletionItem, document: Document, file: PsiFile, offset: Int) {
        if (item.textEdit != null) return
        val text = document.charsSequence
        val start = scopeAwarePrefixStart(text, offset)
        val lsp4ijStart = LSPIJUtils.getWordRangeAt(document, file, offset)?.startOffset ?: return
        if (lsp4ijStart == start) return

        val insertText = item.insertText ?: item.label
        val end = offset + reusableLength(text, start, offset, insertText)
        val range = Range(LSPIJUtils.toPosition(start, document), LSPIJUtils.toPosition(end, document))
        item.setTextEdit(Either.forLeft(TextEdit(range, insertText)))
    }

    /**
     * Where the identifier being completed starts: after the nearest character that cannot be part
     * of one, which includes the `:` of a scope operator. `FVector::Z|` gives the offset of `Z`.
     */
    internal fun scopeAwarePrefixStart(text: CharSequence, offset: Int): Int {
        var pos = offset
        while (pos > 0) {
            val char = text[pos - 1]
            if (!char.isLetterOrDigit() && char != '_') break
            pos--
        }
        return pos
    }

    /**
     * How many characters after the caret already continue [insertText], so that completing inside a
     * word replaces its tail instead of repeating it - as lsp4ij does for an item with no text edit.
     */
    internal fun reusableLength(text: CharSequence, start: Int, offset: Int, insertText: String): Int {
        val typed = offset - start
        var common = 0
        while (typed + common < insertText.length && offset + common < text.length &&
            text[offset + common] == insertText[typed + common]
        ) {
            common++
        }
        return common
    }

    /**
     * Combines the edits that start at the same position into one, in their order.
     *
     * The LSP says edits at the same position apply in array order. lsp4ij applies each edit through
     * a range marker, and an empty marker at an insertion point stays in front of text inserted
     * there, so every later insertion at a position lands before the earlier ones: `UFUNCTION()\n`
     * then `\t` came out as `\tUFUNCTION()\n`. One edit has no order to lose. Edits that would
     * overlap - two at one position that both replace text - are left as they are.
     */
    internal fun mergeSamePositionEdits(edits: List<TextEdit>?): List<TextEdit>? {
        if (edits == null || edits.size < 2) return edits
        val byStart = edits.groupBy { it.range.start }
        if (byStart.size == edits.size) return edits

        val merged = ArrayList<TextEdit>(byStart.size)
        for (group in byStart.values) {
            val replacing = group.filter { it.range.start != it.range.end }
            if (group.size == 1 || replacing.size > 1) {
                merged.addAll(group)
                continue
            }
            val end: Position = replacing.singleOrNull()?.range?.end ?: group.first().range.end
            val newText = group.joinToString("") { it.newText.orEmpty() }
            merged.add(TextEdit(Range(group.first().range.start, end), newText))
        }
        return merged
    }
}
