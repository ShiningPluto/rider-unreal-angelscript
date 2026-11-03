package com.scriptacus.riderunrealangelscript.mcp

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TypeHierarchyItem

/** A type in angelscript_get_type_hierarchy's answer. Gson leaves out what is null. */
internal data class TypeHierarchyNode(
    val name: String,
    /** class, struct or enum. */
    val kind: String,
    /** The script file that declares it; null for a type Unreal declares in C++. */
    val declaredIn: String?,
    /** 1-based, at the declared name. */
    val line: Int?,
    /** For a subtype, the type it derives from directly. */
    val supertype: String?,
)

/**
 * Walks the language server's type hierarchy for angelscript_get_type_hierarchy.
 *
 * The server answers `typeHierarchy/supertypes` and `typeHierarchy/subtypes` one level at a time,
 * and finds the type they start from by the name in the item's `data` alone - `GetTypeByName(
 * item.data)`, which `TypeHierarchyBundleTest` pins. So an item made from a name starts a walk, with
 * no position to prepare one from, which a type declared in C++ does not have.
 *
 * The server describes a type - where it is declared, and whether it is a class, struct or enum -
 * only as another's supertype or subtype, or as the type at a position, so the toolset finds the one
 * a walk starts from among those.
 */
internal object ScriptTypeHierarchy {

    /** Deeper than any Unreal class hierarchy; guards against a cycle the server might report. */
    private const val MAX_DEPTH = 64

    /** An item the server starts a walk from: everything else in it is required, and ignored. */
    fun item(name: String): TypeHierarchyItem {
        val nowhere = Range(Position(0, 0), Position(0, 0))
        return TypeHierarchyItem(name, SymbolKind.Class, "", nowhere, nowhere).apply { data = name }
    }

    /** The supertypes of [start], nearest first. [ask] answers one level, or null when it cannot. */
    suspend fun supertypes(start: TypeHierarchyItem, ask: suspend (TypeHierarchyItem) -> List<TypeHierarchyItem>?): List<TypeHierarchyItem> {
        val chain = mutableListOf<TypeHierarchyItem>()
        val seen = mutableSetOf(start.name)
        var current = start
        while (chain.size < MAX_DEPTH) {
            val next = ask(current)?.firstOrNull() ?: break
            if (!seen.add(next.name)) break
            chain += next
            current = next
        }
        return chain
    }

    /** What [walkSubtypes] found: each subtype with its direct supertype, and whether there were more. */
    class Subtypes(val found: List<Pair<TypeHierarchyItem, String>>, val truncated: Boolean)

    /**
     * The subtypes of [start] at every depth, breadth first and by name within a level, up to
     * [limit] of them.
     */
    suspend fun walkSubtypes(
        start: TypeHierarchyItem,
        limit: Int,
        ask: suspend (TypeHierarchyItem) -> List<TypeHierarchyItem>?,
    ): Subtypes {
        val found = mutableListOf<Pair<TypeHierarchyItem, String>>()
        val seen = mutableSetOf(start.name)
        var level = listOf(start)
        var depth = 0
        while (level.isNotEmpty() && depth < MAX_DEPTH) {
            val next = mutableListOf<TypeHierarchyItem>()
            for (parent in level) {
                for (child in ask(parent).orEmpty().sortedBy { it.name }) {
                    if (!seen.add(child.name)) continue
                    if (found.size == limit) return Subtypes(found, truncated = true)
                    found += child to parent.name
                    next += child
                }
            }
            level = next
            depth++
        }
        return Subtypes(found, truncated = false)
    }

    /** [item] as reported, [path] turning a server URI into the path to show. */
    fun node(item: TypeHierarchyItem, supertype: String?, path: (String) -> String?): TypeHierarchyNode {
        val declared = item.uri.takeIf { it.isNotEmpty() }?.let(path)
        return TypeHierarchyNode(
            name = item.name,
            kind = kind(item),
            declaredIn = declared,
            line = item.selectionRange?.start?.line?.plus(1)?.takeIf { declared != null },
            supertype = supertype,
        )
    }

    /**
     * The server marks a type C++ declares as an interface, whatever it is, and says what it is in
     * the detail it writes: `class AMyActor : AActor`, `struct FMyData` or `enum EMyMode`.
     */
    private fun kind(item: TypeHierarchyItem): String = when (item.detail?.substringBefore(' ')) {
        "struct" -> "struct"
        "enum" -> "enum"
        "class" -> "class"
        else -> when (item.kind) {
            SymbolKind.Struct -> "struct"
            SymbolKind.Enum -> "enum"
            else -> "class"
        }
    }
}
