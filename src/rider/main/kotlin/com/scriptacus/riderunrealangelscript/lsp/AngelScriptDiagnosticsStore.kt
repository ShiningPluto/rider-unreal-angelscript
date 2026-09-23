package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * The latest diagnostics the language server has published, for every file it has published them
 * for - not only the files open in an editor.
 *
 * lsp4ij keeps diagnostics only as editor annotations, and Rider's own problem tooling only looks at
 * files that belong to a solution project, which script files never do. This is what lets anything
 * outside the editor - the MCP tools - ask what is wrong with the scripts, including the compile
 * errors Unreal reports for files nobody has open.
 */
@Service(Service.Level.PROJECT)
class AngelScriptDiagnosticsStore {

    /** One diagnostic, reduced to what can be reported. [line] and [character] are 0-based, as in LSP. */
    data class Problem(
        val severity: DiagnosticSeverity?,
        val line: Int,
        val character: Int,
        val message: String,
        val source: String?,
    )

    /** What the server last said about one file, and when. An empty [problems] means it cleared them. */
    data class FileProblems(val path: String, val problems: List<Problem>, val updatedAt: Long)

    private val files = ConcurrentHashMap<String, FileProblems>()

    fun record(uri: String, diagnostics: List<Diagnostic>, now: Long = System.currentTimeMillis()) {
        val path = pathFromUri(uri) ?: return
        val problems = diagnostics.map { diagnostic ->
            Problem(
                severity = diagnostic.severity,
                line = diagnostic.range?.start?.line ?: 0,
                character = diagnostic.range?.start?.character ?: 0,
                message = messageText(diagnostic),
                source = diagnostic.source,
            )
        }
        // Keyed by path rather than URI: the server spells the same file differently depending on
        // whether Unreal, a disk scan or the editor told it about it, and each push carries the
        // file's complete set regardless of the spelling, so the latest one wins.
        files[keyOf(path)] = FileProblems(path, problems, now)
    }

    /** What a server that has stopped said no longer holds; the next one publishes from scratch. */
    fun clear() = files.clear()

    fun get(path: String): FileProblems? = files[keyOf(path)]

    /** Every file the server currently reports at least one problem for. */
    fun all(): List<FileProblems> = files.values.filter { it.problems.isNotEmpty() }

    companion object {
        fun getInstance(project: Project): AngelScriptDiagnosticsStore = project.service()

        /**
         * The file path a server URI names, with `/` separators, or null for anything but a file URI.
         *
         * Three spellings reach the client: the editor's `file:///D:/x.as`, the server's own
         * `file:///d%3A/x.as` for files it scanned from disk, and `file:///` glued in front of
         * whatever local path Unreal sent with its compile errors - unencoded, and on Windows with
         * backslashes. A strict URI parser rejects the last one, so this does not use one.
         */
        fun pathFromUri(uri: String): String? {
            if (!uri.startsWith("file:", ignoreCase = true)) return null
            val rest = percentDecode(uri.substring("file:".length).trimStart('/')).replace('\\', '/')
            val hasDriveLetter = rest.length >= 2 && rest[0].isLetter() && rest[1] == ':'
            return if (hasDriveLetter) rest else "/$rest"
        }

        /** What two spellings of the same path have in common. */
        fun keyOf(path: String): String {
            val slashes = path.replace('\\', '/')
            return if (SystemInfo.isFileSystemCaseSensitive) slashes else slashes.lowercase()
        }

        /** [path] relative to [basePath] when it lies under it, otherwise [path] unchanged. */
        fun relativeTo(basePath: String?, path: String): String {
            if (basePath == null) return path
            val base = basePath.replace('\\', '/').trimEnd('/')
            val under = path.length > base.length + 1 &&
                path[base.length] == '/' &&
                path.regionMatches(0, base, 0, base.length, ignoreCase = !SystemInfo.isFileSystemCaseSensitive)
            return if (under) path.substring(base.length + 1) else path
        }

        /**
         * The text of a diagnostic's message, whichever lsp4j it comes from.
         *
         * Two copies of lsp4j meet here. The Rider SDK this compiles against carries 0.21, where the
         * message is a `String`; lsp4ij carries 1.0, where it is `Either<String, MarkupContent>`, and
         * lsp4ij's are the objects actually passed in at runtime. A direct call compiles against the
         * first and fails on the second with NoSuchMethodError.
         */
        private fun messageText(diagnostic: Diagnostic): String =
            when (val message = diagnostic.javaClass.getMethod("getMessage").invoke(diagnostic)) {
                null -> ""
                is Either<*, *> ->
                    if (message.isLeft) message.left?.toString().orEmpty()
                    else (message.right as? MarkupContent)?.value.orEmpty()
                else -> message.toString()
            }

        /** Undoes `%XX` escapes, leaving a `%` that does not start one alone - Unreal's paths are raw. */
        private fun percentDecode(text: String): String {
            if ('%' !in text) return text
            val bytes = ByteArrayOutputStream()
            var i = 0
            while (i < text.length) {
                val high = if (text[i] == '%' && i + 2 < text.length) Character.digit(text[i + 1], 16) else -1
                val low = if (high >= 0) Character.digit(text[i + 2], 16) else -1
                if (low >= 0) {
                    bytes.write(high * 16 + low)
                    i += 3
                } else {
                    val codePoint = text.codePointAt(i)
                    bytes.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
                    i += Character.charCount(codePoint)
                }
            }
            return bytes.toString(Charsets.UTF_8)
        }
    }
}
