package com.scriptacus.riderunrealangelscript.typedb

import com.intellij.testFramework.UsefulTestCase
import java.io.IOException
import java.io.StringReader

/**
 * Reading the type database the language server saves, and what the API browser asks of it.
 *
 * The fixture is shaped like a real database: chunks of entries keyed by name, `__` names for
 * namespaces, constructors among the global functions, and members listed on the class that
 * declares them.
 */
class UnrealTypeDatabaseTest : UsefulTestCase() {

    private val chunk1 = """
        {
          "UObject": {"methods": [{"name": "GetName", "return": "FString", "const": true}]},
          "AActor": {
            "inherits": "UObject",
            "doc": "An actor.",
            "properties": {"Tags": ["TArray<FName>", "Tags on the actor."], "bHidden": ["bool", 4]},
            "methods": [
              {"name": "SetActorLocation", "return": "bool", "args": [{"name": "NewLocation", "type": "FVector"}, {"name": "bSweep", "type": "bool", "default": "false"}]},
              {"name": "SetActorLocation", "return": "bool", "args": [{"name": "X", "type": "float32"}]},
              {"name": "BeginPlay", "event": true, "callable": false, "unrealname": "ReceiveBeginPlay", "meta": null},
              {"name": "GetName", "return": "FString", "const": true}
            ]
          },
          "APawn": {
            "inherits": "AActor",
            "properties": {"Tags": ["TArray<FName>"]},
            "methods": [
              {"name": "GetController", "return": "AController", "doc": "* Returns the controller.\n * @param Unused nothing"},
              {"name": "SetActorLocation", "return": "bool", "args": [{"name": "X", "type": "float32"}]}
            ]
          }
        }
    """.trimIndent()

    private val chunk2 = """
        {
          "AMyCharacter": {"inherits": "APawn", "methods": [{"name": "GetStance", "return": "EMyStance"}]},
          "__APawn": {"methods": [{"name": "GetMovementBaseActor", "return": "AActor", "args": [{"name": "Pawn", "type": "const APawn"}]}]},
          "__Math": {"properties": {"PI": ["float64"]}, "methods": [{"name": "Lerp", "return": "float64"}]},
          "__Outer::Inner": {"methods": [{"name": "Nested"}]},
          "__EMyStance": {"isEnum": true, "properties": {"Stand": ["EMyStance"], "Crouch": ["EMyStance"], "Prone": ["EMyStance"]}},
          "FVector": {"isStruct": true, "properties": {"X": ["float64"]}},
          "FOnStanceChanged": {"isStruct": true, "methods": [{"name": "ExecuteIfBound", "args": [{"name": "Stance", "type": "EMyStance"}]}]},
          "FOnLanded": {"methods": [{"name": "Broadcast"}]},
          "__": {
            "properties": {"MAX_int32": ["int32"]},
            "methods": [
              {"name": "FVector", "return": "FVector", "isConstructor": true, "args": [{"name": "X", "type": "float64"}]},
              {"name": "Print", "args": [{"name": "Text", "type": "FString"}]}
            ]
          }
        }
    """.trimIndent()

    private val database = UnrealTypeDatabase.parse(
        StringReader("""{"version":1,"savedAt":1761120000000,"port":27099,"chunks":[$chunk1,$chunk2]}""")
    )

    private fun type(name: String) = database.type(name) ?: error("$name is missing")

    fun testTypesAndNamespacesAreToldApartByTheirName() {
        assertEquals(1761120000000L, database.savedAt)
        assertEquals(UnrealTypeKind.CLASS, type("AActor").kind)
        assertEquals(UnrealTypeKind.STRUCT, type("FVector").kind)
        // Enums are sent under a namespace name, but are types.
        assertEquals(UnrealTypeKind.ENUM, type("EMyStance").kind)
        assertNull(database.namespace("EMyStance"))
        // Flagged as a struct, but its ExecuteIfBound makes it a delegate, as upstream decides it.
        assertEquals(UnrealTypeKind.DELEGATE, type("FOnStanceChanged").kind)
        assertEquals(UnrealTypeKind.EVENT, type("FOnLanded").kind)

        assertEquals(listOf("Lerp"), database.namespace("Math")!!.functions.map { it.name })
        assertEquals("Math::Lerp", database.namespace("Math")!!.functions.single().qualifiedName)
    }

    fun testEnumValuesKeepTheirOrderAndAreNamedThroughTheEnum() {
        val values = type("EMyStance").properties
        assertEquals(listOf("Stand", "Crouch", "Prone"), values.map { it.name })
        assertEquals("EMyStance::Crouch", values[1].qualifiedName)
    }

    fun testConstructorsAreListedWithTheirTypeRatherThanAsGlobals() {
        assertEquals(listOf("FVector"), database.constructorsOf(type("FVector")).map { it.name })
        assertEquals(listOf("Print"), database.globals.functions.map { it.name })
        assertEquals(listOf("MAX_int32"), database.globals.properties.map { it.name })
        assertEquals("Print", database.globals.functions.single().qualifiedName)
    }

    fun testATypesStaticMembersAreTheNamespaceOfItsName() {
        assertEquals(listOf("GetMovementBaseActor"), database.staticsOf(type("APawn"))!!.functions.map { it.name })
        assertNull(database.staticsOf(type("AActor")))
        // Listed with the type, so not again among the namespaces.
        assertEquals(listOf("Math", "Outer::Inner"), database.standaloneNamespaces.map { it.name })
    }

    fun testNestedNamespacesAreDeclaredAtEveryLevel() {
        assertTrue(database.declaresNamespace("Outer"))
        assertTrue(database.declaresNamespace("Outer::Inner"))
        assertTrue(database.declaresNamespace("APawn"))
        assertFalse(database.declaresNamespace("Backend_chat"))
    }

    fun testSupertypesRunNearestFirst() {
        assertEquals(listOf("APawn", "AActor", "UObject"), database.supertypes(type("AMyCharacter")).map { it.name })
        assertEmpty(database.supertypes(type("UObject")))
    }

    fun testAnUnknownOrCyclicSupertypeEndsTheChain() {
        val cyclic = UnrealTypeDatabase.parse(StringReader(
            """{"version":1,"chunks":[{"A":{"inherits":"B"},"B":{"inherits":"A"},"C":{"inherits":"Missing"}}]}"""
        ))
        assertEquals(listOf("B"), cyclic.supertypes(cyclic.type("A")!!).map { it.name })
        assertEmpty(cyclic.supertypes(cyclic.type("C")!!))
    }

    fun testInheritedMembersLeaveOutWhatASubclassDeclaresAgain() {
        val character = type("AMyCharacter")
        val fromActor = database.membersInheritedFrom(character, type("AActor"))

        // APawn declares Tags and SetActorLocation(float32) again; the other overload still shows.
        assertEquals(listOf("bHidden"), fromActor.properties.map { it.name })
        assertEquals(
            listOf("SetActorLocation(FVector,bool)", "BeginPlay()", "GetName()"),
            fromActor.methods.map { it.signatureKey }
        )
        assertEquals(listOf("Tags"), database.membersInheritedFrom(character, type("APawn")).properties.map { it.name })
        assertEmpty(database.membersInheritedFrom(character, type("UObject")).methods)
    }

    fun testMembersKeepWhatTheEditorSaysAboutThem() {
        val actor = type("AActor")
        val beginPlay = actor.methods.single { it.name == "BeginPlay" }
        assertTrue(beginPlay.isBlueprintEvent)
        assertFalse(beginPlay.isCallable)
        // Events carry their C++ name without the "ufunction" flag that upstream's parse looks for.
        assertEquals("ReceiveBeginPlay", beginPlay.unrealName)
        assertEquals("void", beginPlay.returnType)

        val hidden = actor.properties.single { it.name == "bHidden" }
        assertTrue(hidden.isProtected)
        assertEquals("Tags on the actor.", actor.properties.single { it.name == "Tags" }.documentation)
        assertEquals("FVector NewLocation", actor.methods.first().args[0].format())
        assertEquals("bool bSweep = false", actor.methods.first().args[1].format())
    }

    fun testDocCommentStarsAreStripped() {
        assertEquals(
            "Returns the controller.\n@param Unused nothing",
            type("APawn").methods.single { it.name == "GetController" }.documentation
        )
    }

    fun testSearchPutsExactNamesFirstAndCountsEverything() {
        val result = database.search("SetActorLocation", limit = 2)
        assertEquals(3, result.total)
        assertEquals(2, result.hits.size)
        assertTrue(result.hits.all { it.name == "SetActorLocation" })

        val types = database.search("APawn", limit = 10).hits
        assertSame(type("APawn"), types.first())
    }

    fun testShortWordsMustStartTheName() {
        assertEquals(listOf("Lerp"), database.search("le", limit = 10).hits.map { it.name })
        // A long word may appear anywhere, and then a short one may too.
        assertEquals(listOf("GetController"), database.search("controller ge", limit = 10).hits.map { it.name })
    }

    fun testOwnerAndMemberNarrowTheSearchToOneType() {
        // The type's static members are part of what it offers, so they match as well.
        val hits = database.search("APawn.Get", limit = 10).hits.map { (it as UnrealMember).qualifiedName }
        assertEquals(listOf("APawn.GetController", "APawn::GetMovementBaseActor"), hits)

        assertEquals(listOf("Math::Lerp"), database.search("Math::Le", limit = 10).hits.map { (it as UnrealMember).qualifiedName })
        // Nothing after the separator lists the owner's members.
        assertEquals(setOf("Math::PI", "Math::Lerp"), database.search("Math::", limit = 10).hits.map { (it as UnrealMember).qualifiedName }.toSet())
    }

    fun testSearchFindsAnEventByItsCppName() {
        assertEquals(listOf("AActor.BeginPlay"), database.search("ReceiveBeginPlay", limit = 10).hits.map { (it as UnrealMember).qualifiedName })
    }

    fun testConstructorsAreNotSearchResults() {
        assertEquals(listOf("FVector"), database.search("FVector", limit = 10).hits.map { it.name })
        assertTrue(database.search("FVector", limit = 10).hits.single() is UnrealType)
    }

    fun testAnotherVersionIsRejected() {
        try {
            UnrealTypeDatabase.parse(StringReader("""{"version":2,"chunks":[]}"""))
            fail("Version 2 must not be read as version 1")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("version 2"))
        }
    }
}
