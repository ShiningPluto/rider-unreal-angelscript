package com.scriptacus.riderunrealangelscript.mcp

import com.scriptacus.riderunrealangelscript.typedb.ScriptDeclarations
import com.scriptacus.riderunrealangelscript.typedb.ScriptOrigin
import com.scriptacus.riderunrealangelscript.typedb.UnrealMember
import com.scriptacus.riderunrealangelscript.typedb.UnrealMethod
import com.scriptacus.riderunrealangelscript.typedb.UnrealNamespace
import com.scriptacus.riderunrealangelscript.typedb.UnrealProperty
import com.scriptacus.riderunrealangelscript.typedb.UnrealSearchHit
import com.scriptacus.riderunrealangelscript.typedb.UnrealSymbol
import com.scriptacus.riderunrealangelscript.typedb.UnrealType
import com.scriptacus.riderunrealangelscript.typedb.UnrealTypeDatabase
import com.scriptacus.riderunrealangelscript.typedb.UnrealTypeKind

/** One result of angelscript_search_api. Gson leaves out what is null. */
internal data class ApiSearchItem(
    /** How script names it: `AActor.SetActorLocation(FVector, bool)`, with argument types for overloads. */
    val label: String,
    /** class, struct, enum, delegate, event, namespace, function, property or enumValue. */
    val kind: String,
    /** A function's return type, or a property's type. */
    val type: String? = null,
    /** For a member the searched type inherits, the supertype that declares it. */
    val inheritedFrom: String? = null,
    /** For what script declares, the file it is in. */
    val declaredIn: String? = null,
    val ref: List<String>,
)

/**
 * How the MCP API tools name what the type database and script declare: as a search result, and as
 * the `ref` that angelscript_get_api_details is handed back.
 *
 * A ref names an item by what identifies it in the database rather than by a position in a list,
 * so it holds across searches and across a reload of the same database: `["type", name]`,
 * `["namespace", name]`, and for a member `["function" or "property", owner, separator, key]`. The
 * owner is empty outside any namespace, and the separator is `.` for a type's member or a mixin and
 * `::` for a namespace's, a type's static members and an enum's values. A function's key is its name
 * with its argument types, and `const`, which is what tells its overloads apart.
 */
internal object ApiItems {

    private const val TYPE = "type"
    private const val NAMESPACE = "namespace"
    private const val FUNCTION = "function"
    private const val PROPERTY = "property"

    fun item(hit: UnrealSearchHit, path: (ScriptOrigin) -> String): ApiSearchItem = when (val symbol = hit.symbol) {
        is UnrealType -> ApiSearchItem(symbol.displayName, symbol.kind.keyword, ref = ref(symbol))
        is UnrealNamespace -> ApiSearchItem(symbol.name, NAMESPACE, ref = ref(symbol))
        is UnrealMember -> {
            // An inherited member is named through the type searched, as script calls it.
            val owner = hit.inheritedBy?.name ?: symbol.owner
            val name = owner?.let { it + symbol.ownerSeparator + symbol.name } ?: symbol.name
            ApiSearchItem(
                label = if (symbol is UnrealMethod) symbol.args.joinToString(", ", "$name(", ")") { it.type } else name,
                kind = kind(symbol),
                type = when (symbol) {
                    is UnrealMethod -> symbol.returnType.takeUnless { symbol.isConstructor }
                    is UnrealProperty -> symbol.type.takeUnless { symbol.isEnumValue }
                },
                inheritedFrom = hit.inheritedBy?.let { symbol.owner },
                declaredIn = symbol.scriptOrigin?.let(path),
                ref = ref(symbol),
            )
        }
    }

    private fun kind(member: UnrealMember): String = when (member) {
        is UnrealMethod -> FUNCTION
        is UnrealProperty -> if (member.isEnumValue) "enumValue" else PROPERTY
    }

    fun ref(symbol: UnrealSymbol): List<String> = when (symbol) {
        is UnrealType -> listOf(TYPE, symbol.name)
        is UnrealNamespace -> listOf(NAMESPACE, symbol.name)
        is UnrealMethod -> listOf(FUNCTION, symbol.owner.orEmpty(), symbol.ownerSeparator, key(symbol))
        is UnrealProperty -> listOf(PROPERTY, symbol.owner.orEmpty(), symbol.ownerSeparator, symbol.name)
    }

    /** What [ref] names, or null when neither Unreal's API nor script has it. */
    fun resolve(ref: List<String>, database: UnrealTypeDatabase, script: ScriptDeclarations): UnrealSymbol? =
        when (ref.firstOrNull()) {
            TYPE -> ref.singleName()?.let(database::type)
            NAMESPACE -> ref.singleName()?.let { name ->
                database.namespace(name) ?: script.namespace(name).takeIf { script.declaresNamespace(name) }
            }
            FUNCTION, PROPERTY -> if (ref.size != 4) null else {
                val (kind, owner, separator, key) = ref
                members(owner, separator, database, script).firstOrNull { member ->
                    if (kind == FUNCTION) member is UnrealMethod && key(member) == key else member is UnrealProperty && member.name == key
                }
            }
            else -> null
        }

    private fun List<String>.singleName(): String? = if (size == 2) this[1] else null

    private fun key(method: UnrealMethod): String = method.signatureKey + if (method.isConst) " const" else ""

    /** Every member script names as `owner` `separator` name, Unreal's and script's. */
    private fun members(owner: String, separator: String, database: UnrealTypeDatabase, script: ScriptDeclarations): List<UnrealMember> =
        when {
            owner.isEmpty() -> all(database.globals) + all(script.globals)
            separator == "." -> database.type(owner)?.let { it.properties + it.methods }.orEmpty() + script.mixinsOn(owner)
            else -> database.namespace(owner)?.let(::all).orEmpty() +
                database.type(owner)?.takeIf { it.kind == UnrealTypeKind.ENUM }?.properties.orEmpty() +
                all(script.namespace(owner))
        }

    private fun all(namespace: UnrealNamespace): List<UnrealMember> = namespace.properties + namespace.functions
}
