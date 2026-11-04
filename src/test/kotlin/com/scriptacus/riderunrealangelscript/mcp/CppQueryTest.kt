package com.scriptacus.riderunrealangelscript.mcp

import com.intellij.testFramework.UsefulTestCase
import com.scriptacus.riderunrealangelscript.mcp.ApiFixture.database
import com.scriptacus.riderunrealangelscript.mcp.ApiFixture.script
import com.scriptacus.riderunrealangelscript.navigation.CppSymbolInfo
import com.scriptacus.riderunrealangelscript.typedb.UnrealSymbol

/**
 * What angelscript_get_api_details looks for in the C++ sources: a type, a member of its class by
 * its C++ name, or, where the database records no class, only a name.
 */
class CppQueryTest : UsefulTestCase() {

    /** The best match for [query], as angelscript_search_api would list it first. */
    private fun found(query: String): UnrealSymbol = database.search(query, limit = 1, script).symbols.first()

    private fun resolved(ref: List<String>): UnrealSymbol = ApiItems.resolve(ref, database, script)!!

    private fun symbolOf(symbol: UnrealSymbol): CppSymbolInfo = (CppQuery.of(symbol, database) as CppQuery.Symbol).symbol

    private fun nameOf(symbol: UnrealSymbol): String = (CppQuery.of(symbol, database) as CppQuery.Name).name

    fun testATypeIsLookedUpWithWhatItInherits() {
        assertEquals(CppSymbolInfo("APawn", "", listOf("APawn", "AActor", "UObject")), symbolOf(resolved(listOf("type", "APawn"))))
    }

    fun testAMemberIsLookedUpInItsClassByItsCppName() {
        assertEquals(CppSymbolInfo("AActor", "SetActorLocation", listOf("AActor", "UObject")), symbolOf(found("AActor.SetActorLocation")))
        // A blueprint event goes by its C++ name, which script does not use.
        assertEquals("ReceiveBeginPlay", symbolOf(found("BeginPlay")).symbolName)
        // So do a type's static members and an enum's values, though script names them with ::.
        assertEquals(CppSymbolInfo("APawn", "GetMovementBaseActor", listOf("APawn", "AActor", "UObject")), symbolOf(found("APawn::GetMovementBaseActor")))
        assertEquals(CppSymbolInfo("EMode", "Fly", listOf("EMode")), symbolOf(resolved(listOf("property", "EMode", "::", "Fly"))))
    }

    fun testANamespaceFunctionOrAGlobalIsLookedUpByNameAlone() {
        assertEquals("Lerp", nameOf(found("Math::Lerp")))
        assertEquals("Print", nameOf(found("Print")))
    }

    fun testWhatScriptDeclaresHasNoCppDeclaration() {
        for (symbol in listOf(found("PrintToScreen"), found("Math::Tau"), found("Highlight"), resolved(listOf("namespace", "Math")))) {
            assertNull(symbol.name, CppQuery.of(symbol, database))
        }
    }
}
