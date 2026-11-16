package com.scriptacus.riderunrealangelscript.actions

/**
 * Expands the subset of VS Code snippet syntax the AngelScript language server uses.
 *
 * The server sends file templates as `editor.action.insertSnippet` arguments, for example
 *
 * ```
 * class U${TM_FILENAME_BASE} : UActorComponent
 * {
 * $0
 *     UFUNCTION(BlueprintOverride)
 *     void BeginPlay()
 *     {
 *     }
 * };
 * ```
 *
 * Supported syntax: `$0`/`${0}` (final caret), `$1`…`$n` and `${n:default}` (placeholder with a
 * default value; the first one becomes the caret when the snippet has no `$0`), the variables
 * `${TM_FILENAME}`, `${TM_FILENAME_BASE}`, `${TM_DIRECTORY}`, `${TM_DIRECTORY_BASE}`, `${TM_FILEPATH}`,
 * `${CLIPBOARD}` and `${CURRENT_YEAR}`, and the escapes `\$`, `\\` and `\}`.
 */
object AngelScriptSnippets {

    /** Expanded snippet text plus where the caret belongs inside it. */
    data class Expanded(val text: String, val caretOffset: Int)

    /** Values the snippet variables are resolved from. */
    data class Context(
        val fileName: String = "",
        val filePath: String = "",
        val clipboard: String = "",
    ) {
        val fileNameBase: String
            get() {
                val dot = fileName.lastIndexOf('.')
                return if (dot > 0) fileName.substring(0, dot) else fileName
            }

        val directory: String
            get() {
                if (filePath.isEmpty()) return ""
                val normalized = filePath.replace('\\', '/')
                val slash = normalized.lastIndexOf('/')
                return if (slash > 0) normalized.substring(0, slash) else ""
            }

        val directoryName: String
            get() = directory.substringAfterLast('/')
    }

    fun expand(snippet: String, context: Context): Expanded {
        val out = StringBuilder(snippet.length)
        var caret = -1
        var firstPlaceholderCaret = -1
        var index = 0

        while (index < snippet.length) {
            val c = snippet[index]
            if (c == '\\' && index + 1 < snippet.length) {
                val next = snippet[index + 1]
                if (next == '$' || next == '\\' || next == '}') {
                    out.append(next)
                    index += 2
                    continue
                }
            }
            if (c == '$') {
                val parsed = parseDollar(snippet, index, context)
                if (parsed != null) {
                    when (parsed.kind) {
                        Kind.CARET -> if (caret < 0) caret = out.length
                        Kind.PLACEHOLDER -> {
                            if (firstPlaceholderCaret < 0) firstPlaceholderCaret = out.length
                            out.append(parsed.text)
                        }
                        Kind.VARIABLE -> out.append(parsed.text)
                    }
                    index = parsed.end
                    continue
                }
            }
            out.append(c)
            index++
        }

        val caretOffset = when {
            caret >= 0 -> caret
            firstPlaceholderCaret >= 0 -> firstPlaceholderCaret
            else -> out.length
        }
        return Expanded(out.toString(), caretOffset)
    }

    private enum class Kind { CARET, PLACEHOLDER, VARIABLE }

    private class Parsed(val kind: Kind, val text: String, val end: Int)

    /** Parses a `$…` construct starting at [start]; null when the `$` is literal. */
    private fun parseDollar(text: String, start: Int, context: Context): Parsed? {
        val next = start + 1
        if (next >= text.length) return null

        if (text[next] == '{') {
            val close = text.indexOf('}', next + 1)
            if (close < 0) return null
            val body = text.substring(next + 1, close)
            val colon = body.indexOf(':')
            val name = if (colon >= 0) body.substring(0, colon) else body
            val defaultValue = if (colon >= 0) body.substring(colon + 1) else ""

            if (name.isEmpty()) return Parsed(Kind.PLACEHOLDER, defaultValue, close + 1)

            val number = name.toIntOrNull()
            if (number != null) {
                return if (number == 0) {
                    Parsed(Kind.CARET, "", close + 1)
                } else {
                    Parsed(Kind.PLACEHOLDER, defaultValue, close + 1)
                }
            }
            return Parsed(Kind.VARIABLE, resolveVariable(name, defaultValue, context), close + 1)
        }

        var end = next
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
        if (end == next) return null
        val name = text.substring(next, end)

        val number = name.toIntOrNull()
        if (number != null) {
            return if (number == 0) Parsed(Kind.CARET, "", end) else Parsed(Kind.PLACEHOLDER, "", end)
        }
        return Parsed(Kind.VARIABLE, resolveVariable(name, "", context), end)
    }

    private fun resolveVariable(name: String, defaultValue: String, context: Context): String =
        when (name.uppercase()) {
            "TM_FILENAME" -> context.fileName
            "TM_FILENAME_BASE" -> context.fileNameBase
            "TM_DIRECTORY" -> context.directory
            "TM_DIRECTORY_BASE" -> context.directoryName
            "TM_FILEPATH" -> context.filePath
            "CLIPBOARD" -> context.clipboard
            "CURRENT_YEAR" -> java.time.Year.now().toString()
            else -> defaultValue
        }
}
