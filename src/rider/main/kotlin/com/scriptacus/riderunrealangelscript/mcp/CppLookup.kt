package com.scriptacus.riderunrealangelscript.mcp

import com.scriptacus.riderunrealangelscript.navigation.CppSymbolInfo
import com.scriptacus.riderunrealangelscript.typedb.UnrealMember
import com.scriptacus.riderunrealangelscript.typedb.UnrealMethod
import com.scriptacus.riderunrealangelscript.typedb.UnrealNamespace
import com.scriptacus.riderunrealangelscript.typedb.UnrealSymbol
import com.scriptacus.riderunrealangelscript.typedb.UnrealType
import com.scriptacus.riderunrealangelscript.typedb.UnrealTypeDatabase

/** What angelscript_get_api_details says about where C++ declares an item. */
internal sealed interface CppDeclaration {
    /** The declaration, as `path:line`. */
    class Found(val location: String) : CppDeclaration

    /** No class is recorded for it; these are where C++ declares or binds something of that [name]. */
    class ByName(val name: String, val locations: List<String>) : CppDeclaration

    /** Searched for, and not found in the project's C++ sources. */
    object NotFound : CppDeclaration

    /** Not found, and Rider has not indexed Unreal's C++ sources, so it could not have been. */
    object NoEngineSources : CppDeclaration

    /** Not searched for, because Rider is indexing. */
    object Indexing : CppDeclaration
}

/**
 * What to look for in the C++ sources for an item the API tools describe.
 *
 * The type database records which class a type's members belong to, including its static members,
 * which script calls as `AActor::Name`, and the C++ name where it differs from script's -
 * `K2_GetActorLocation` behind `GetActorLocation`, `ReceiveBeginPlay` behind `BeginPlay`. What a
 * script namespace such as `System::` holds, it gathers from C++ classes it does not record, so all
 * there is to look for is the name. What script declares has no C++ declaration to find.
 */
internal sealed interface CppQuery {
    class Symbol(val symbol: CppSymbolInfo) : CppQuery
    class Name(val name: String) : CppQuery

    companion object {
        fun of(symbol: UnrealSymbol, database: UnrealTypeDatabase): CppQuery? = when (symbol) {
            is UnrealType -> Symbol(CppSymbolInfo(symbol.name, "", ancestry(symbol, database)))
            is UnrealMember -> member(symbol, database)
            is UnrealNamespace -> null
        }

        private fun member(member: UnrealMember, database: UnrealTypeDatabase): CppQuery? {
            if (member.scriptOrigin != null) return null
            val name = (member as? UnrealMethod)?.unrealName ?: member.name
            val owner = member.owner?.let(database::type)
                ?: return Name(name)
            return Symbol(CppSymbolInfo(owner.name, name, ancestry(owner, database)))
        }

        /** [type] and what it inherits, the classes a member found outside [type]'s body may sit in. */
        private fun ancestry(type: UnrealType, database: UnrealTypeDatabase): List<String> =
            listOf(type.name) + database.supertypes(type).map { it.name }
    }
}
