package com.scriptacus.riderunrealangelscript.mcp

import com.intellij.testFramework.UsefulTestCase
import com.scriptacus.riderunrealangelscript.mcp.ApiFixture.database
import com.scriptacus.riderunrealangelscript.mcp.ApiFixture.path
import com.scriptacus.riderunrealangelscript.mcp.ApiFixture.script
import com.scriptacus.riderunrealangelscript.typedb.UnrealMember

/**
 * What angelscript_get_api_details says. An agent reads it as text, so it is checked whole: the
 * declaration in a code block, the C++ comment as written, and the facts the declaration leaves out.
 */
class ApiDetailsMarkdownTest : UsefulTestCase() {

    private fun details(ref: List<String>): String =
        ApiDetailsMarkdown.of(ApiItems.resolve(ref, database, script)!!, database, script, path)

    private fun member(query: String): UnrealMember = database.search(query, limit = 1, script).symbols.single() as UnrealMember

    fun testAFunctionGivesItsDeclarationAndItsCommentAsWritten() {
        assertEquals(
            """
            |```angelscript
            |bool AActor.SetActorLocation(
            |    FVector NewLocation,
            |    bool bSweep = false
            |)
            |```
            |
            |Moves the actor.
            |@param NewLocation Where to
            |@return Whether it moved
            |""".trimMargin(),
            details(listOf("function", "AActor", ".", "SetActorLocation(FVector,bool)"))
        )
    }

    fun testWhatTheDeclarationLeavesOutIsListed() {
        assertEquals(
            """
            |```angelscript
            |void AActor.BeginPlay()
            |```
            |
            |- Blueprint event: script can override it, but not call it.
            |- C++ name: `ReceiveBeginPlay`
            |""".trimMargin(),
            ApiDetailsMarkdown.member(member("BeginPlay"), path)
        )
        assertEquals(
            """
            |```angelscript
            |void AActor.Highlight(FLinearColor Color)
            |```
            |
            |- Script mixin: called on AActor as one of its methods.
            |- Declared in: `Script/Mixins.as`
            |""".trimMargin(),
            ApiDetailsMarkdown.member(member("Highlight"), path)
        )
        // An enum value is named through its enum, which is its type too.
        assertEquals("```angelscript\nEMode::Fly\n```\n", details(listOf("property", "EMode", "::", "Fly")))
    }

    fun testAClassSaysWhatItInheritsAndHowToListItsMembers() {
        assertEquals(
            """
            |```angelscript
            |class APawn : AActor
            |```
            |
            |- Inherits: AActor → UObject
            |- Declares: 1 property
            |- Inherits members: 4 functions, 1 property
            |- Static members: 1 function, called as APawn::Name
            |- Search `APawn.` to list its members, inherited ones included, and `APawn::` for its static members.
            |""".trimMargin(),
            details(listOf("type", "APawn"))
        )
        val actor = details(listOf("type", "AActor"))
        // The comment keeps its lines as written.
        assertTrue(actor, actor.contains("\nAn actor.\n    Placed in a level.\n"))
        assertTrue(actor, actor.contains("- Script mixins: 1 function\n"))
    }

    fun testAStructListsItsConstructorsAndAnEnumItsValues() {
        val vector = details(listOf("type", "FVector"))
        assertTrue(vector, vector.endsWith("\nConstructors:\n```angelscript\nFVector(float64 X)\n```\n"))
        assertTrue(details(listOf("type", "EMode")).contains("\n- Values: Walk, Fly\n"))
        // A delegate or event is declared by what it is called with.
        assertEquals("```angelscript\nevent void FOnLanded(float32 Speed)\n```\n", details(listOf("type", "FOnLanded")))
    }

    fun testANamespaceSaysWhatScriptAddsToIt() {
        assertEquals(
            """
            |```angelscript
            |namespace Math
            |```
            |
            |- Declares: 1 function
            |- Script adds: 1 property
            |- Declared in: `Script/MathExtras.as`
            |- Search `Math::` to list its members.
            |""".trimMargin(),
            details(listOf("namespace", "Math"))
        )
        assertEquals(
            """
            |```angelscript
            |namespace Backend
            |```
            |
            |A namespace only script declares.
            |
            |- Namespaces: `Backend::Chat`
            |""".trimMargin(),
            details(listOf("namespace", "Backend"))
        )
    }
}
