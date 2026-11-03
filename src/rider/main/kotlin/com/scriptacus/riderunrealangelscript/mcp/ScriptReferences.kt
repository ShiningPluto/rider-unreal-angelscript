package com.scriptacus.riderunrealangelscript.mcp

import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector.Access
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.scriptacus.riderunrealangelscript.usages.AngelScriptAccess
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/** One place angelscript_find_references found. Gson leaves out what is null. */
internal data class ReferenceItem(
    /** 1-based. */
    val line: Int,
    val column: Int,
    /** The line the reference is on, without its indentation. */
    val text: String,
    /** read, write or readWrite, for a variable or property; null for anything else. */
    val access: String?,
)

internal data class ReferenceFile(val path: String, val references: List<ReferenceItem>)

/** How angelscript_find_references reports the locations the language server found in one file. */
internal object ScriptReferences {

    /** Long enough for any line written to be read; longer ones are generated or minified. */
    private const val MAX_TEXT = 200

    /**
     * The references at [ranges] of a file whose text is [text], in the order given.
     *
     * Whether each one reads or writes comes from [file]'s parse tree, which must be of [text]: the
     * server reports locations only ([AngelScriptAccess] says why its own idea of a write is not
     * used). Without a parse tree, or where it has no identifier exactly at a range, there is none.
     */
    fun inFile(text: CharSequence, file: PsiFile?, ranges: List<Range>): List<ReferenceItem> {
        val lines = LineStarts(text)
        return ranges.map { range ->
            val start = lines.offset(range.start)
            val access = file?.let { AngelScriptAccess.at(it, TextRange(start, maxOf(start, lines.offset(range.end)))) }
            ReferenceItem(
                line = range.start.line + 1,
                column = range.start.character + 1,
                text = lines.text(range.start.line),
                access = access?.let(::name),
            )
        }
    }

    /** The text at [range] of [text], such as the name a reference is to. */
    fun textAt(text: CharSequence, range: Range): String {
        val lines = LineStarts(text)
        val start = lines.offset(range.start)
        return text.subSequence(start, maxOf(start, lines.offset(range.end))).toString()
    }

    private fun name(access: Access) = when (access) {
        Access.Read -> "read"
        Access.Write -> "write"
        Access.ReadWrite -> "readWrite"
    }

    /** Lines as the language server counts them: ended by `\n`, `\r\n` or `\r`. */
    private class LineStarts(private val text: CharSequence) {
        private val starts: IntArray = buildList {
            add(0)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                    i++
                }
                if (c == '\r' || c == '\n') {
                    add(i + 1)
                }
                i++
            }
        }.toIntArray()

        /** The offset of [position], kept within its line; a column is counted in UTF-16 units, as Java's are. */
        fun offset(position: Position): Int {
            if (position.line >= starts.size) return text.length
            return minOf(starts[position.line] + position.character, end(position.line))
        }

        fun text(line: Int): String {
            if (line >= starts.size) return ""
            val content = text.subSequence(starts[line], end(line)).toString().trim()
            return if (content.length > MAX_TEXT) content.take(MAX_TEXT) + "…" else content
        }

        /** Where [line]'s content ends, before its line break. */
        private fun end(line: Int): Int {
            var end = if (line + 1 < starts.size) starts[line + 1] else text.length
            while (end > starts[line] && (text[end - 1] == '\n' || text[end - 1] == '\r')) {
                end--
            }
            return end
        }
    }
}
