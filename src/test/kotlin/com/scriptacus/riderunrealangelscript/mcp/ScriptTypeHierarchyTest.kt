package com.scriptacus.riderunrealangelscript.mcp

import com.google.gson.JsonParser
import com.intellij.testFramework.UsefulTestCase
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TypeHierarchyItem
import org.eclipse.lsp4j.TypeHierarchySupertypesParams
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import org.eclipse.lsp4j.services.LanguageServer

/**
 * How angelscript_get_type_hierarchy walks the server's one-level answers, against a hierarchy that
 * stands in for the server's:
 *
 * ```
 * UObject <- AActor <- AMyCharacterBase <- AMyCharacter
 *                   |                   <- AMyEnemy <- AMyEnemyRanged
 *                   <- AOtherActor
 * ```
 */
class ScriptTypeHierarchyTest : UsefulTestCase() {

    private val supertypeOf = mapOf(
        "AActor" to "UObject",
        "AOtherActor" to "AActor",
        "AMyCharacterBase" to "AActor",
        "AMyEnemy" to "AMyCharacterBase",
        "AMyCharacter" to "AMyCharacterBase",
        "AMyEnemyRanged" to "AMyEnemy",
    )

    private val supertypes: suspend (TypeHierarchyItem) -> List<TypeHierarchyItem>? =
        { item -> listOfNotNull(supertypeOf[item.data as String]?.let(ScriptTypeHierarchy::item)) }

    private val subtypes: suspend (TypeHierarchyItem) -> List<TypeHierarchyItem>? =
        { item -> supertypeOf.filterValues { it == item.data }.keys.map(ScriptTypeHierarchy::item) }

    fun testSupertypesRunFromTheNearestUp() = runBlocking {
        val chain = ScriptTypeHierarchy.supertypes(ScriptTypeHierarchy.item("AMyEnemyRanged"), supertypes)
        assertEquals(listOf("AMyEnemy", "AMyCharacterBase", "AActor", "UObject"), chain.map { it.name })
    }

    fun testSubtypesAreListedBreadthFirstWithTheTypeEachDerivesFrom() = runBlocking {
        val found = ScriptTypeHierarchy.walkSubtypes(ScriptTypeHierarchy.item("AMyCharacterBase"), 10, subtypes)
        assertEquals(
            listOf("AMyCharacter" to "AMyCharacterBase", "AMyEnemy" to "AMyCharacterBase", "AMyEnemyRanged" to "AMyEnemy"),
            found.found.map { (item, parent) -> item.name to parent },
        )
        assertFalse(found.truncated)
    }

    fun testSubtypesStopAtTheLimit() = runBlocking {
        val start = ScriptTypeHierarchy.item("AActor")
        val cut = ScriptTypeHierarchy.walkSubtypes(start, 2, subtypes)
        assertEquals(listOf("AMyCharacterBase", "AOtherActor"), cut.found.map { it.first.name })
        assertTrue(cut.truncated)
        // Exactly as many as there are is not cut
        assertFalse(ScriptTypeHierarchy.walkSubtypes(start, 5, subtypes).truncated)
    }

    fun testACycleEndsTheWalk() = runBlocking {
        val loop: suspend (TypeHierarchyItem) -> List<TypeHierarchyItem>? =
            { item -> listOf(ScriptTypeHierarchy.item(if (item.name == "A") "B" else "A")) }
        assertEquals(listOf("B"), ScriptTypeHierarchy.supertypes(ScriptTypeHierarchy.item("A"), loop).map { it.name })
        assertEquals(listOf("B"), ScriptTypeHierarchy.walkSubtypes(ScriptTypeHierarchy.item("A"), 10, loop).found.map { it.first.name })
    }

    fun testANodeSaysWhereScriptDeclaresItAndWhatItIs() {
        val script = TypeHierarchyItem(
            "AMyEnemy", SymbolKind.Class, "file:///d%3A/p/Script/MyEnemy.as",
            Range(Position(2, 0), Position(20, 1)), Range(Position(2, 6), Position(2, 14)),
        ).apply { detail = "class AMyEnemy : AMyCharacterBase" }
        assertEquals(
            TypeHierarchyNode("AMyEnemy", "class", "Script/MyEnemy.as", 3, "AMyCharacterBase"),
            ScriptTypeHierarchy.node(script, "AMyCharacterBase") { "Script/MyEnemy.as" },
        )

        // The server marks whatever C++ declares as an interface, with nowhere to point at
        val cpp = TypeHierarchyItem("FVector", SymbolKind.Interface, "", Range(Position(0, 0), Position(0, 0)), Range(Position(0, 0), Position(0, 0)))
            .apply { detail = "struct FVector" }
        assertEquals(TypeHierarchyNode("FVector", "struct", null, null, null), ScriptTypeHierarchy.node(cpp, null) { error("no path for $it") })
    }

    fun testAnItemMadeFromANameReachesTheServerWithTheNameAsItsData() {
        val request = RequestMessage().apply {
            jsonrpc = "2.0"; id = "1"; method = "typeHierarchy/supertypes"
            params = TypeHierarchySupertypesParams(ScriptTypeHierarchy.item("AMyEnemy"))
        }
        val json = MessageJsonHandler(ServiceEndpoints.getSupportedMethods(LanguageServer::class.java)).serialize(request)
        val item = JsonParser.parseString(json).asJsonObject["params"].asJsonObject["item"].asJsonObject
        assertEquals("AMyEnemy", item["data"].asString)
        assertEquals("AMyEnemy", item["name"].asString)
    }
}
