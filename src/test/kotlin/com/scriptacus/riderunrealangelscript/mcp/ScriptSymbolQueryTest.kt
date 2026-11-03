package com.scriptacus.riderunrealangelscript.mcp

import com.intellij.testFramework.UsefulTestCase
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolLocation
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Which of the server's `workspace/symbol` answers angelscript_search_symbols keeps. The symbols are
 * named the way the server names them: a member after its type and `.`, or its namespace and `::`,
 * a function with `(…)` when it takes arguments, and a global with an empty container.
 */
class ScriptSymbolQueryTest : UsefulTestCase() {

    private val symbols = listOf(
        symbol("AMyCharacter", SymbolKind.Class),
        symbol("UMyHealthComponent", SymbolKind.Class),
        symbol("EMyMode", SymbolKind.Enum),
        symbol("MyUtils", SymbolKind.Namespace),
        symbol("AMyCharacter.TakeDamage(…)", SymbolKind.Method, "AMyCharacter"),
        symbol("AMyCharacter.Health", SymbolKind.Field, "AMyCharacter"),
        symbol("UMyHealthComponent.Health", SymbolKind.Field, "UMyHealthComponent"),
        symbol("UMyHealthComponent.HealthChanged()", SymbolKind.Event, "UMyHealthComponent"),
        symbol("MyUtils::ComputeDamage(…)", SymbolKind.Method, "MyUtils"),
        symbol("GlobalHealthScale", SymbolKind.Field, ""),
    )

    private fun select(query: String) = ScriptSymbolQuery.parse(query)!!.select(symbols).map { it.name }

    fun testEveryWordMustOccurInTheSymbolsOwnName() {
        assertEquals(listOf("AMyCharacter.TakeDamage(…)"), select("Take Damage"))
        // AMyCharacter's members are not listed for matching through their type's name
        assertEquals(listOf("AMyCharacter"), select("Character"))
    }

    fun testExactNamesComeFirstThenNamesStartingWithTheQuery() {
        assertEquals(
            listOf(
                "AMyCharacter.Health", "UMyHealthComponent.Health",
                "UMyHealthComponent.HealthChanged()",
                "GlobalHealthScale", "UMyHealthComponent",
            ),
            select("health"),
        )
    }

    fun testOwnerListsTheMembersOfMatchingTypesAndNamespaces() {
        assertEquals(listOf("AMyCharacter.Health", "AMyCharacter.TakeDamage(…)"), select("AMyCharacter."))
        assertEquals(listOf("AMyCharacter.TakeDamage(…)"), select("AMyCharacter.Take"))
        assertEquals(listOf("MyUtils::ComputeDamage(…)"), select("MyUtils::"))
        // A global's container is empty, so no owner matches it
        assertFalse(select("Global.Health").contains("GlobalHealthScale"))
    }

    fun testTheServerIsAskedForWhatFindsMembers() {
        // It lists members only for five letters or more, and with a matching type's name, every member.
        val owner = ScriptSymbolQuery.parse("AMyCharacter.Take")!!
        assertEquals("AMyCharacter", owner.serverQuery)
        assertTrue(owner.findsMembers)

        val shortOwner = ScriptSymbolQuery.parse("UMy.Health")!!
        assertEquals("Health", shortOwner.serverQuery)
        assertEquals(listOf("UMyHealthComponent.Health", "UMyHealthComponent.HealthChanged()"), shortOwner.select(symbols).map { it.name })

        assertEquals("Damage", ScriptSymbolQuery.parse("Take Damage")!!.serverQuery)
        assertFalse(ScriptSymbolQuery.parse("Mode")!!.findsMembers)
    }

    fun testNothingIsAskedThatCannotFindAnything() {
        assertNull(ScriptSymbolQuery.parse("UMy.Hp"))
        assertNull(ScriptSymbolQuery.parse("Abc::"))
        assertNull(ScriptSymbolQuery.parse("   "))
    }

    fun testOwnNameDropsTheOwnerAndTheArguments() {
        assertEquals("TakeDamage", ScriptSymbolQuery.ownName("AMyCharacter.TakeDamage(…)"))
        assertEquals("HealthChanged", ScriptSymbolQuery.ownName("UMyHealthComponent.HealthChanged()"))
        assertEquals("Inner", ScriptSymbolQuery.ownName("Outer::Inner"))
        assertEquals("Fast", ScriptSymbolQuery.ownName("EMyMode::Fast"))
        assertEquals("GlobalHealthScale", ScriptSymbolQuery.ownName("GlobalHealthScale"))
    }

    fun testKindsAreNamedForAnAgent() {
        assertEquals("class", ScriptSymbolQuery.kind(SymbolKind.Class))
        assertEquals("enum", ScriptSymbolQuery.kind(SymbolKind.Enum))
        assertEquals("namespace", ScriptSymbolQuery.kind(SymbolKind.Namespace))
        assertEquals("function", ScriptSymbolQuery.kind(SymbolKind.Method))
        assertEquals("event", ScriptSymbolQuery.kind(SymbolKind.Event))
        assertEquals("property", ScriptSymbolQuery.kind(SymbolKind.Field))
    }

    fun testTheSameSymbolIsListedOnce() {
        val twice = symbols + symbol("AMyCharacter.Health", SymbolKind.Field, "AMyCharacter")
        assertEquals(1, ScriptSymbolQuery.parse("AMyCharacter.Health")!!.select(twice).size)
    }

    private fun symbol(name: String, kind: SymbolKind, container: String? = null) =
        WorkspaceSymbol(name, kind, Either.forRight(WorkspaceSymbolLocation("file:///d:/p/Script/My.as")), container)
}
