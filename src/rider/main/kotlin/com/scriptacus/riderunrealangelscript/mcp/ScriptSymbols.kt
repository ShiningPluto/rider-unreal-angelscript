package com.scriptacus.riderunrealangelscript.mcp

import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.WorkspaceSymbol

/** One result of angelscript_search_symbols. Gson leaves out what is null. */
internal data class ScriptSymbolItem(
    /** As the server names it: `AMyActor.TakeHit(…)`, the ellipsis standing for arguments. */
    val label: String,
    /** class (a class or a struct), enum, namespace, function, event or property. */
    val kind: String,
    /** The type or namespace a member is declared in. */
    val container: String?,
    val path: String,
    /** 1-based, at the declared name, which is where angelscript_find_references wants to be pointed. */
    val line: Int?,
    val column: Int?,
    /** Where the declaration's body ends, for a type, a namespace or a function. */
    val endLine: Int?,
)

/**
 * What angelscript_search_symbols asks the language server, and which of its answers it keeps.
 *
 * The server's `workspace/symbol` lists only what script declares - Unreal's own API is what
 * angelscript_search_api is for - and matches one piece of text against a type's or member's name.
 * It lists members only for a query of [MEMBER_QUERY_LENGTH] letters or more, and with them every
 * member of every type or namespace whose name matches, as `Type.` listing does. The tool accepts
 * the query forms angelscript_search_api does instead: words that must all occur in the name, and
 * `Owner.words` or `Owner::words` for members. So it asks the server for the longest piece it was
 * given and filters what comes back.
 */
internal class ScriptSymbolQuery private constructor(
    /** The type or namespace a member must be declared in, matched as a part of its name. */
    val owner: String?,
    /** Words that must all occur in the symbol's own name; none lists every member of [owner]. */
    val words: List<String>,
    /** The text to send as `workspace/symbol`'s query. */
    val serverQuery: String,
) {

    /** Whether the server's answer can hold members at all. */
    val findsMembers: Boolean get() = serverQuery.length >= MEMBER_QUERY_LENGTH

    /** The symbols [symbols] holds that this query asks for, best matches first. */
    fun select(symbols: List<WorkspaceSymbol>): List<WorkspaceSymbol> = symbols
        .filter(::matches)
        .distinctBy { Triple(it.name, it.kind, uriOf(it)) }
        .sortedWith(compareBy<WorkspaceSymbol>({ rank(ownName(it.name)) }, { ownName(it.name).length }, { it.name }, { uriOf(it) }))

    private fun matches(symbol: WorkspaceSymbol): Boolean {
        val container = symbol.containerName
        val isMember = !container.isNullOrEmpty() || symbol.kind in MEMBER_KINDS
        if (owner != null && (!isMember || !container.orEmpty().contains(owner, ignoreCase = true))) {
            return false
        }
        val name = ownName(symbol.name)
        return words.all { name.contains(it, ignoreCase = true) }
    }

    /** 0 for a name that is the query, 1 for one that starts with it, 2 for the rest. */
    private fun rank(name: String): Int {
        val first = words.firstOrNull() ?: return 2
        return when {
            words.size == 1 && name.equals(first, ignoreCase = true) -> 0
            name.startsWith(first, ignoreCase = true) -> 1
            else -> 2
        }
    }

    companion object {
        /** Below this many letters the server lists types and namespaces only, for speed. */
        const val MEMBER_QUERY_LENGTH = 5

        private val MEMBER_KINDS = setOf(SymbolKind.Method, SymbolKind.Event, SymbolKind.Field)

        /**
         * The query [text] stands for, or null when it cannot find anything: when it names an
         * owner, members are what it asks for, and the server lists those only for a long enough
         * query.
         */
        fun parse(text: String): ScriptSymbolQuery? {
            val trimmed = text.trim()
            val colons = trimmed.lastIndexOf("::")
            val dot = trimmed.lastIndexOf('.')
            if (colons < 0 && dot < 0) {
                val words = words(trimmed)
                val longest = words.maxByOrNull { it.length } ?: return null
                return ScriptSymbolQuery(null, words, longest)
            }
            val (at, length) = if (dot > colons) dot to 1 else colons to 2
            val owner = trimmed.substring(0, at).trim()
            val words = words(trimmed.substring(at + length))
            val longest = words.maxByOrNull { it.length }
            // The server lists a matching type's or namespace's every member, so asking for the owner finds them all.
            val serverQuery = when {
                owner.length >= MEMBER_QUERY_LENGTH -> owner
                longest != null && longest.length >= MEMBER_QUERY_LENGTH -> longest
                else -> return null
            }
            return ScriptSymbolQuery(owner.ifEmpty { null }, words, serverQuery)
        }

        private fun words(text: String) = text.split(' ', '\t').filter { it.isNotEmpty() }

        /** `AMyActor.TakeHit(…)` -> `TakeHit`, `Outer::Inner` -> `Inner`. */
        fun ownName(name: String): String {
            val bare = name.removeSuffix("(…)").removeSuffix("()")
            val start = maxOf(bare.lastIndexOf('.') + 1, bare.lastIndexOf("::").let { if (it < 0) 0 else it + 2 })
            return bare.substring(start)
        }

        fun kind(kind: SymbolKind?): String = when (kind) {
            SymbolKind.Class, SymbolKind.Struct -> "class"
            SymbolKind.Enum -> "enum"
            SymbolKind.Namespace -> "namespace"
            SymbolKind.Event -> "event"
            SymbolKind.Field, SymbolKind.Property, SymbolKind.Variable, SymbolKind.EnumMember -> "property"
            else -> "function"
        }

        /** The URI the symbol is in, whether or not the server has resolved where in it. */
        fun uriOf(symbol: WorkspaceSymbol): String? {
            val location = symbol.location ?: return null
            return if (location.isLeft) location.left?.uri else location.right?.uri
        }
    }
}
