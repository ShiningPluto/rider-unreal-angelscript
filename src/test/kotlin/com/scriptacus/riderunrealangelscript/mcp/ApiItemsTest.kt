package com.scriptacus.riderunrealangelscript.mcp

import com.intellij.testFramework.UsefulTestCase
import com.scriptacus.riderunrealangelscript.mcp.ApiFixture.database
import com.scriptacus.riderunrealangelscript.mcp.ApiFixture.path
import com.scriptacus.riderunrealangelscript.mcp.ApiFixture.script
import com.scriptacus.riderunrealangelscript.typedb.UnrealNamespace

/**
 * What angelscript_search_api lists, and the `ref` that gets each item's details. A ref is all
 * angelscript_get_api_details has to go on, so every kind of item has to come back from its own.
 */
class ApiItemsTest : UsefulTestCase() {

    private fun items(query: String) = database.search(query, limit = 20, script).hits.map { ApiItems.item(it, path) }

    fun testEveryKindOfItemIsFoundAgainByItsRef() {
        val queries = listOf(
            "AActor", "Math", "Backend", "FOnLanded", "EMode", // types and namespaces, Unreal's and script's
            "SetActorLocation", "GetName", "Fly", "GetMovementBase", "Print", // members: overloads, const, enum value, static, globals
            "AActor.High", "Tau", "Send", // what script declares
            "APawn.Tags", // inherited
        )
        for (query in queries) {
            val hits = database.search(query, limit = 20, script).hits
            assertFalse("Nothing found for $query", hits.isEmpty())
            for (hit in hits) {
                val ref = ApiItems.ref(hit.symbol)
                val resolved = ApiItems.resolve(ref, database, script)
                if (hit.symbol is UnrealNamespace) {
                    // A script namespace holding only others is made up afresh each time it is asked for.
                    assertEquals(query, ref, resolved?.let(ApiItems::ref))
                } else {
                    assertSame(query, hit.symbol, resolved)
                }
            }
        }
    }

    fun testOverloadsHaveRefsOfTheirOwn() {
        assertEquals(
            setOf(
                listOf("function", "AActor", ".", "SetActorLocation(FVector,bool)"),
                listOf("function", "AActor", ".", "SetActorLocation(float32)"),
            ),
            items("SetActorLocation").map { it.ref }.toSet()
        )
        assertEquals(listOf("function", "UObject", ".", "GetName() const"), items("GetName").single().ref)
    }

    fun testItemsSayHowScriptNamesThem() {
        val sweep = items("APawn.SetActorLocation").first()
        assertEquals(
            ApiSearchItem(
                label = "APawn.SetActorLocation(FVector, bool)",
                kind = "function",
                type = "bool",
                inheritedFrom = "AActor",
                ref = listOf("function", "AActor", ".", "SetActorLocation(FVector,bool)"),
            ),
            sweep
        )

        assertEquals(
            ApiSearchItem("AActor.Highlight(FLinearColor)", "function", "void", declaredIn = "Script/Mixins.as", ref = listOf("function", "AActor", ".", "Highlight(FLinearColor)")),
            items("Highlight").single()
        )
        assertEquals(ApiSearchItem("EMode::Fly", "enumValue", ref = listOf("property", "EMode", "::", "Fly")), items("Fly").single())
        assertEquals(ApiSearchItem("APawn::GetMovementBaseActor(const APawn)", "function", "AActor", ref = listOf("function", "APawn", "::", "GetMovementBaseActor(const APawn)")), items("GetMovementBase").single())
        assertEquals(ApiSearchItem("Print(FString)", "function", "void", ref = listOf("function", "", "::", "Print(FString)")), items("Print").first())
        assertEquals(ApiSearchItem("FVector", "struct", ref = listOf("type", "FVector")), items("FVector").first())
        assertEquals(ApiSearchItem("Backend::Chat", "namespace", ref = listOf("namespace", "Backend::Chat")), items("Chat").first())
    }

    fun testScriptAndUnrealResultsAreRankedTogether() {
        // An exact name first, whoever declares it.
        assertEquals(listOf("Print(FString)", "PrintToScreen(FString)"), items("Print").map { it.label })
    }

    fun testARefToNothingIsNotResolved() {
        for (ref in listOf(
            listOf("property", "AActor", ".", "Removed"),
            // As upstream's search used to hand them out.
            listOf("property", "AActor", "Tags"),
            listOf("function", "AActor", ".", "SetActorLocation(int)"),
            listOf("type"),
            listOf("type", "AMissing"),
            listOf("namespace", "Nowhere"),
            listOf("something", "else"),
            emptyList(),
        )) {
            assertNull(ref.toString(), ApiItems.resolve(ref, database, script))
        }
    }
}
