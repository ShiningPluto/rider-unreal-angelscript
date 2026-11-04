package com.scriptacus.riderunrealangelscript.mcp

import com.scriptacus.riderunrealangelscript.typedb.ScriptDeclarations
import com.scriptacus.riderunrealangelscript.typedb.ScriptOrigin
import com.scriptacus.riderunrealangelscript.typedb.UnrealDeclaration
import com.scriptacus.riderunrealangelscript.typedb.UnrealMember
import com.scriptacus.riderunrealangelscript.typedb.UnrealMethod
import com.scriptacus.riderunrealangelscript.typedb.UnrealNamespace
import com.scriptacus.riderunrealangelscript.typedb.UnrealProperty
import com.scriptacus.riderunrealangelscript.typedb.UnrealSymbol
import com.scriptacus.riderunrealangelscript.typedb.UnrealType
import com.scriptacus.riderunrealangelscript.typedb.UnrealTypeDatabase
import com.scriptacus.riderunrealangelscript.typedb.UnrealTypeKind

/**
 * What angelscript_get_api_details says about an API item: its declaration in a code block, its
 * documentation, and a list of what the declaration alone does not say.
 *
 * The facts are the ones the API tool window's details show (see ApiDetailsHtml), but the result is
 * read by an agent as text, never rendered, so the documentation goes out as the C++ comment has it:
 * the window joins its hand-wrapped lines into paragraphs for a narrow pane, which an agent does not
 * need, and nothing is escaped.
 */
internal object ApiDetailsMarkdown {

    /** How many of an enum's values are listed; past that, the search lists them. */
    private const val ENUM_VALUE_LIMIT = 200

    /** How many of the files script declares a namespace's members in are named. */
    private const val FILE_LIMIT = 8

    /** [cpp] is where C++ declares [symbol], looked up beforehand; null leaves it unsaid. */
    fun of(
        symbol: UnrealSymbol,
        database: UnrealTypeDatabase,
        script: ScriptDeclarations,
        path: (ScriptOrigin) -> String,
        cpp: CppDeclaration? = null,
    ): String =
        when (symbol) {
            is UnrealMember -> member(symbol, path, cpp)
            is UnrealType -> type(symbol, database, script, cpp)
            is UnrealNamespace -> namespace(symbol, database, script, path)
        }

    fun member(member: UnrealMember, path: (ScriptOrigin) -> String, cpp: CppDeclaration? = null): String = buildString {
        code(declaration(member).toString())
        documentation(member.documentation)
        val facts = mutableListOf<String>()
        if (member is UnrealMethod) {
            if (member.isBlueprintEvent) {
                facts += "Blueprint event: " +
                    if (member.isCallable) "script can override it and call it." else "script can override it, but not call it."
            }
            if (member.isMixin) {
                facts += "Script mixin: called on ${member.owner} as one of its methods."
            }
            member.unrealName?.takeIf { it != member.name }?.let { facts += "C++ name: `$it`" }
        }
        cpp?.let { facts += cppFact(it) }
        member.scriptOrigin?.let { facts += "Declared in: `${path(it)}`" }
        facts(facts)
    }

    private fun cppFact(cpp: CppDeclaration): String = when (cpp) {
        is CppDeclaration.Found -> "C++ declaration: `${cpp.location}`"
        is CppDeclaration.ByName ->
            "C++ declaration: which class it belongs to is not recorded; `${cpp.name}` is declared or bound to script at " +
                cpp.locations.joinToString(", ") { "`$it`" }
        CppDeclaration.NotFound -> "C++ declaration: not found in the project's C++ sources"
        CppDeclaration.NoEngineSources ->
            "C++ declaration: Rider has not indexed Unreal's C++ sources for this project, as when it is opened as a folder " +
                "rather than as an Unreal project, so none of what Unreal declares can be found"
        CppDeclaration.Indexing -> "C++ declaration: not looked up while Rider is indexing; ask again once it has finished"
    }

    private fun declaration(member: UnrealMember): UnrealDeclaration = when (member) {
        is UnrealMethod -> UnrealDeclaration.of(member)
        is UnrealProperty -> UnrealDeclaration.of(member)
    }

    fun type(type: UnrealType, database: UnrealTypeDatabase, script: ScriptDeclarations, cpp: CppDeclaration? = null): String = buildString {
        code(UnrealDeclaration.of(type).toString())
        documentation(type.documentation)

        val facts = mutableListOf<String>()
        val supertypes = database.supertypes(type)
        if (supertypes.isNotEmpty()) {
            facts += "Inherits: " + supertypes.joinToString(" → ") { it.name }
        }
        cpp?.let { facts += cppFact(it) }
        when (type.kind) {
            UnrealTypeKind.ENUM -> {
                val values = type.properties.map { it.name }
                facts += "Values: " + values.take(ENUM_VALUE_LIMIT).joinToString(", ") +
                    if (values.size > ENUM_VALUE_LIMIT) ", and ${values.size - ENUM_VALUE_LIMIT} more: search `${type.name}::` for them" else ""
            }
            // Their members are the same few on every one: binding, calling, unbinding.
            UnrealTypeKind.DELEGATE, UnrealTypeKind.EVENT -> {}
            UnrealTypeKind.CLASS, UnrealTypeKind.STRUCT -> {
                counts(type.methods.size, type.properties.size)?.let { facts += "Declares: $it" }
                val inherited = supertypes.map { database.membersInheritedFrom(type, it) }
                counts(inherited.sumOf { it.methods.size }, inherited.sumOf { it.properties.size })?.let { facts += "Inherits members: $it" }
                val statics = database.staticsOf(type)
                statics?.let { counts(it.functions.size, it.properties.size) }?.let { facts += "Static members: $it, called as ${type.name}::Name" }
                script.mixinsOn(type.name).takeIf { it.isNotEmpty() }?.let { facts += "Script mixins: ${count(it.size, "function")}" }
                facts += "Search `${type.name}.` to list its members, inherited ones included" +
                    (if (statics != null) ", and `${type.name}::` for its static members." else ".")
            }
        }
        facts(facts)

        // Search leaves constructors out, so this is where they are listed.
        val constructors = database.constructorsOf(type)
        if (constructors.isNotEmpty()) {
            append("\nConstructors:\n")
            code(constructors.joinToString("\n") { UnrealDeclaration.of(it).toString() })
        }
    }

    fun namespace(namespace: UnrealNamespace, database: UnrealTypeDatabase, script: ScriptDeclarations, path: (ScriptOrigin) -> String): String =
        buildString {
            code("namespace ${namespace.name}")
            val facts = mutableListOf<String>()
            val scriptOnly = script.declaresNamespace(namespace.name)
            if (scriptOnly) {
                append("\nA namespace only script declares.\n")
            } else if (database.type(namespace.name) != null) {
                append("\nThe static members of ${namespace.name}, called as ${namespace.name}::Name.\n")
            }
            counts(namespace.functions.size, namespace.properties.size)?.let { facts += "Declares: $it" }
            // What script declares in a namespace of Unreal's is kept apart from it.
            val additions = if (scriptOnly) namespace else script.additionsTo(namespace.name)
            if (!scriptOnly) {
                counts(additions.functions.size, additions.properties.size)?.let { facts += "Script adds: $it" }
            }
            script.namespacesIn(namespace.name).takeIf { it.isNotEmpty() }?.let { nested ->
                facts += "Namespaces: " + nested.joinToString(", ") { "`${it.name}`" }
            }
            val files = (additions.functions + additions.properties).mapNotNull { it.scriptOrigin?.let(path) }.distinct().sorted()
            if (files.isNotEmpty()) {
                facts += "Declared in: " + files.take(FILE_LIMIT).joinToString(", ") { "`$it`" } +
                    if (files.size > FILE_LIMIT) ", and ${count(files.size - FILE_LIMIT, "more file")}" else ""
            }
            if (!namespace.isEmpty || !additions.isEmpty) {
                facts += "Search `${namespace.name}::` to list its members."
            }
            facts(facts)
        }

    private fun StringBuilder.code(text: String) {
        append("```angelscript\n").append(text).append("\n```\n")
    }

    private fun StringBuilder.documentation(doc: String?) {
        val text = doc?.lines()?.joinToString("\n") { it.trimEnd() }?.trim()
        if (!text.isNullOrEmpty()) {
            append('\n').append(text).append('\n')
        }
    }

    private fun StringBuilder.facts(facts: List<String>) {
        if (facts.isNotEmpty()) {
            append('\n')
            facts.forEach { append("- ").append(it).append('\n') }
        }
    }

    /** "2 functions, 1 property", leaving out what there is none of; null when there is nothing. */
    private fun counts(functions: Int, properties: Int): String? = listOfNotNull(
        functions.takeIf { it > 0 }?.let { count(it, "function") },
        properties.takeIf { it > 0 }?.let { count(it, "property", "properties") },
    ).takeIf { it.isNotEmpty() }?.joinToString(", ")

    private fun count(n: Int, singular: String, plural: String = singular + "s") = "$n ${if (n == 1) singular else plural}"
}
