package com.scriptacus.riderunrealangelscript.toolwindow

import com.intellij.testFramework.UsefulTestCase
import com.scriptacus.riderunrealangelscript.typedb.ScriptDeclarations
import com.scriptacus.riderunrealangelscript.typedb.UnrealMember
import com.scriptacus.riderunrealangelscript.typedb.UnrealMethod
import com.scriptacus.riderunrealangelscript.typedb.UnrealTypeDatabase
import java.io.StringReader

/**
 * What the API browser lists for a type, and what it says about the selected row. The point of the
 * browser is that a class shows everything script can reach on it, inherited members included.
 */
class ApiBrowserTest : UsefulTestCase() {

    private val database = UnrealTypeDatabase.parse(StringReader("""
        {"version":1,"chunks":[{
          "UObject": {"methods": [{"name": "GetName", "return": "FString"}]},
          "AActor": {
            "inherits": "UObject",
            "properties": {"Tags": ["TArray<FName>"]},
            "methods": [{"name": "GetComponentsByClass", "return": "TArray<UActorComponent>",
                         "args": [{"name": "ComponentClass", "type": "TSubclassOf<UActorComponent>"}],
                         "doc": "Gets all components of a class.\n@param ComponentClass The class to look for\n    and its subclasses\n@return The components found"}]
          },
          "APawn": {
            "inherits": "AActor",
            "properties": {"BaseEyeHeight": ["float32"]},
            "methods": [{"name": "GetController", "return": "AController", "const": true}, {"name": "AddMovementInput", "args": [{"name": "Direction", "type": "FVector"}]}]
          },
          "__APawn": {"methods": [{"name": "GetMovementBaseActor", "return": "AActor", "args": [{"name": "Pawn", "type": "const APawn"}]}]},
          "__Math": {"properties": {"PI": ["float64"]}, "methods": [{"name": "Lerp", "return": "float64"}]},
          "__EMode": {"isEnum": true, "properties": {"Walk": ["EMode"], "Fly": ["EMode"], "Swim": ["EMode"]}},
          "FVector": {"isStruct": true},
          "__": {"methods": [{"name": "FVector", "return": "FVector", "isConstructor": true}, {"name": "Print"}]}
        }]}
    """.trimIndent()))

    private fun labels(nodes: List<ApiNode>) = nodes.map { ApiTreeCellRenderer.text(it) }

    fun testTheTopLevelListsEachKindOnce() {
        val roots = ApiTreeStructure.roots(database)
        assertEquals(listOf("Classes", "Structs", "Enums", "Namespaces", "Globals"), labels(roots))
        assertEquals(3, (roots.first() as ApiNode.Group).count)
        // APawn's static members are listed with APawn, not as a namespace of their own.
        val namespaces = roots.single { (it as ApiNode.Group).kind == GroupKind.NAMESPACES }
        assertEquals(listOf("Math::"), labels(ApiTreeStructure.children(namespaces, database)))
    }

    private fun function(
        name: String,
        namespace: String = "",
        returnType: String = "void",
        args: List<Pair<String, String>> = emptyList(),
        isMixin: Boolean = false,
        doc: String? = null,
        module: String = "Debug",
    ) = mapOf(
        "name" to name,
        "namespace" to namespace,
        "module" to module,
        "file" to "file:///d:/Game/Script/${module.replace('.', '/')}.as",
        "returnType" to returnType,
        "args" to args.map { (type, argName) -> mapOf("type" to type, "name" to argName) },
        "isConst" to false,
        "isMixin" to isMixin,
        "doc" to doc,
    )

    private fun property(name: String, type: String, namespace: String = "", module: String = "Debug") = mapOf(
        "name" to name,
        "namespace" to namespace,
        "module" to module,
        "file" to "file:///d:/Game/Script/$module.as",
        "type" to type,
    )

    /** An angelscript/getScriptDeclarations answer, in the shape the bundled server sends it. */
    private val script = ScriptDeclarations.parse(
        mapOf(
            "functions" to listOf(
                function("PrintToScreen", args = listOf("FString" to "Text", "float32" to "Duration")),
                function("Highlight", args = listOf("AActor" to "Actor", "FLinearColor" to "Color"), isMixin = true, module = "Mixins"),
                function("Mute", namespace = "Backend_chat", args = listOf("APawn" to "Pawn"), isMixin = true, module = "Backend.Chat"),
                function("LengthSquared2D", returnType = "float64", args = listOf("const FVector&" to "Vector"), isMixin = true, module = "Mixins"),
                function("Send", namespace = "Backend_chat", returnType = "bool", args = listOf("FString" to "Message"), module = "Backend.Chat"),
                function("Start", namespace = "Backend_battle::Arena", module = "Backend.Arena"),
                function("SmoothStep01", namespace = "Math", returnType = "float64", args = listOf("float64" to "X"), module = "MathExtras"),
                // Unreal declares Lerp() too: this is an overload, not the same function.
                function("Lerp", namespace = "Math", returnType = "float32", args = listOf("float32" to "A"), module = "MathExtras"),
                function("Blend", namespace = "Math::Curves", module = "MathExtras"),
            ),
            "properties" to listOf(
                property("GScriptCounter", "int"),
                property("Tau", "float64", namespace = "Math", module = "MathExtras"),
            ),
        ),
        database,
    )

    fun testScriptNamespacesAreTheOnesTheDatabaseDoesNotDeclare() {
        // Backend_battle holds nothing but Arena, and is listed for it. Curves is nested in Unreal's Math.
        assertEquals(listOf("Backend_battle", "Backend_chat", "Math::Curves"), script.topLevelNamespaces.map { it.name })

        val battle = ApiNode.ScriptNamespace(script.topLevelNamespaces.first())
        val arena = ApiTreeStructure.children(battle, database, script).single() as ApiNode.ScriptNamespace
        assertEquals("Backend_battle::Arena::", ApiTreeCellRenderer.text(arena))
        assertEquals(listOf("Start()"), labels(ApiTreeStructure.children(arena, database, script)))
    }

    fun testScriptGlobalsAreListedAfterUnrealsUnderGlobals() {
        val globals = ApiTreeStructure.roots(database, script).single { (it as ApiNode.Group).kind == GroupKind.GLOBALS }
        assertEquals(3, (globals as ApiNode.Group).count)

        val children = ApiTreeStructure.children(globals, database, script).map { it as ApiNode.Member }
        // With their arguments and types, as Unreal's are, and the file that declares them.
        assertEquals(listOf("Print()", "GScriptCounter", "PrintToScreen(FString, float32)"), labels(children))
        assertEquals(listOf(null, "in Debug.as", "in Debug.as"), children.map { it.note })
        assertTrue(ApiTreeStructure.roots(database, script).any { (it as ApiNode.Group).kind == GroupKind.SCRIPT_NAMESPACES })
    }

    fun testAMixinIsListedWithTheTypeItExtends() {
        val children = ApiTreeStructure.children(ApiNode.Type(database.type("APawn")!!), database, script)
        // After what the type declares, without the object it is called on, which is its first argument.
        assertEquals(
            listOf(
                "Static members",
                "BaseEyeHeight",
                "AddMovementInput(FVector)",
                "GetController()",
                "Mute()",
                "Inherited from AActor",
                "Inherited from UObject",
            ),
            labels(children)
        )
        assertEquals("in Chat.as", (children[4] as ApiNode.Member).note)

        // A supertype's mixin is listed with what the type inherits from that supertype, and nowhere else.
        val fromActor = children.filterIsInstance<ApiNode.Inherited>().first()
        assertEquals(3, fromActor.size)
        val rows = ApiTreeStructure.children(fromActor, database, script).map { it as ApiNode.Member }
        assertEquals(listOf("Tags", "GetComponentsByClass(TSubclassOf<UActorComponent>)", "Highlight(FLinearColor)"), labels(rows))
        assertEquals(listOf(null, null, "in Mixins.as"), rows.map { it.note })

        // A struct mixin is found through the type its first argument names.
        val vector = ApiNode.Type(database.type("FVector")!!)
        assertEquals(listOf("FVector()", "LengthSquared2D()"), labels(ApiTreeStructure.children(vector, database, script)))
        assertEquals(listOf("GetName()"), labels(ApiTreeStructure.children(ApiNode.Type(database.type("UObject")!!), database, script)))
    }

    fun testAnAncestorIsListedForItsMixinsAlone() {
        // AActor's only member is one APawn declares again, so APawn inherits nothing else from it.
        val database = UnrealTypeDatabase.parse(StringReader("""
            {"version":1,"chunks":[{
              "AActor": {"methods": [{"name": "Reset"}]},
              "APawn": {"inherits": "AActor", "methods": [{"name": "Reset"}]}
            }]}
        """.trimIndent()))
        val script = ScriptDeclarations.parse(
            mapOf("functions" to listOf(function("Highlight", args = listOf("AActor" to "Actor"), isMixin = true, module = "Mixins"))),
            database,
        )

        val children = ApiTreeStructure.children(ApiNode.Type(database.type("APawn")!!), database, script)
        assertEquals(listOf("Reset()", "Inherited from AActor"), labels(children))
        assertEquals(listOf("Highlight()"), labels(ApiTreeStructure.children(children.last(), database, script)))
        // Without script, AActor has nothing left to list.
        assertEquals(listOf("Reset()"), labels(ApiTreeStructure.children(ApiNode.Type(database.type("APawn")!!), database)))
    }

    /**
     * The handler is injected by regex, so an upstream rewrite would leave the browser's script
     * declarations silently empty rather than fail the build.
     */
    fun testBundledServerListsScriptDeclarations() {
        val bundle = javaClass.getResourceAsStream("/js/angelscript-language-server.js")?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        assertNotNull("Bundled language server must be on the test classpath", bundle)

        val handler = bundle!!.substringAfter("connection.onRequest(\"angelscript/getScriptDeclarations\"", "")
            .substringBefore("\n});")
        assertFalse("bundle-lsp.js did not inject the angelscript/getScriptDeclarations handler", handler.isEmpty())
        // Only script's: Unreal's are in the type database already.
        assertTrue(handler.contains("if (!symbol.declaredModule)"))
        // Whole, so that nothing is asked by an id that a parse replaces.
        assertTrue(handler.contains("declaration.args") && handler.contains("declaration.returnType"))
        assertTrue(handler.contains("symbol.isConstructor"))
    }

    fun testAMixinExtendsTheTypeItsFirstArgumentNames() {
        assertEquals("FVector", ScriptDeclarations.mixinTarget("const FVector&"))
        assertEquals("FVector", ScriptDeclarations.mixinTarget("FVector &in"))
        assertEquals("AActor", ScriptDeclarations.mixinTarget("AActor"))
        assertEquals("TArray", ScriptDeclarations.mixinTarget("TArray<AActor>&"))
    }

    fun testScriptAdditionsToAnUnrealNamespaceAreListedAfterItsOwn() {
        val math = ApiNode.Namespace(database.namespace("Math")!!)
        val children = ApiTreeStructure.children(math, database, script).map { it as ApiNode.Member }

        // Unreal's Lerp() and script's Lerp(float32) are both there.
        assertEquals(listOf("PI", "Lerp()", "Tau", "Lerp(float32)", "SmoothStep01(float64)"), labels(children))
        assertEquals(listOf(null, null, "in MathExtras.as", "in MathExtras.as", "in MathExtras.as"), children.map { it.note })
    }

    fun testAScriptNamespaceListsWhatItDeclares() {
        val chat = ApiNode.ScriptNamespace(script.topLevelNamespaces.single { it.name == "Backend_chat" })
        val members = ApiTreeStructure.children(chat, database, script).map { it as ApiNode.Member }
        // Its mixin is listed with the type it extends instead.
        assertEquals(listOf("Send(FString)"), labels(members))
        assertNull(members.single().note)
    }

    fun testSearchFindsWhatScriptDeclares() {
        fun found(query: String) = script.search(query, limit = 10).hits.map { hit ->
            when (hit) {
                is UnrealMember -> ApiTreeCellRenderer.text(ApiNode.Member(hit, qualified = true))
                else -> hit.name
            }
        }

        assertEquals(listOf("PrintToScreen(FString, float32)"), found("PrintTo"))
        assertEquals(listOf("Math::SmoothStep01(float64)"), found("Smooth"))
        assertEquals(listOf("Math::Tau"), found("Tau"))
        // A mixin is a member of the type it extends.
        assertEquals(listOf("AActor.Highlight(FLinearColor)"), found("AActor.High"))
        assertEquals(listOf("Backend_chat", "Backend_battle", "Backend_battle::Arena"), found("Backend"))
    }

    fun testScriptDetailsSayWhereTheyAreDeclared() {
        val printToScreen = script.globals.functions.single()
        val html = ApiDetailsHtml.member(printToScreen)
        assertTrue(html, html.contains("void PrintToScreen(\n    FString Text,\n    float32 Duration\n)"))
        assertTrue(html, html.contains("Declared in <code>d:/Game/Script/Debug.as</code>"))

        val highlight = script.mixinsOn("AActor").single()
        assertEquals("void AActor.Highlight(\n    FLinearColor Color\n)", ApiDetailsHtml.signature(highlight))
        assertTrue(ApiDetailsHtml.member(highlight).contains("Script mixin: called on AActor as one of its methods."))
    }

    fun testOverloadsKeepTheirOwnDocumentation() {
        val overloads = ScriptDeclarations.parse(
            mapOf("functions" to listOf(
                function("Spawn", args = listOf("FName" to "Name"), doc = "Spawns one by name."),
                function("Spawn", args = listOf("int" to "Count"), doc = "Spawns several."),
            )),
            database,
        ).globals.functions

        assertEquals(listOf("Spawn(FName)", "Spawn(int)"), overloads.map { ApiTreeCellRenderer.text(ApiNode.Member(it, qualified = false)) })
        assertTrue(ApiDetailsHtml.member(overloads[0]).contains("Spawns one by name."))
        assertTrue(ApiDetailsHtml.member(overloads[1]).contains("Spawns several."))
    }

    fun testATypeListsStaticsThenItsOwnMembersThenEachAncestor() {
        val pawn = ApiNode.Type(database.type("APawn")!!)
        val children = ApiTreeStructure.children(pawn, database)

        assertEquals(
            listOf(
                "Static members",
                "BaseEyeHeight",
                "AddMovementInput(FVector)",
                "GetController()",
                "Inherited from AActor",
                "Inherited from UObject",
            ),
            labels(children)
        )

        val fromActor = children.filterIsInstance<ApiNode.Inherited>().first()
        assertEquals(listOf("Tags", "GetComponentsByClass(TSubclassOf<UActorComponent>)"), labels(ApiTreeStructure.children(fromActor, database)))
        assertEquals(listOf("GetMovementBaseActor(const APawn)"), labels(ApiTreeStructure.children(children.first(), database)))
    }

    fun testAStructListsItsConstructors() {
        val vector = ApiNode.Type(database.type("FVector")!!)
        assertTrue(ApiTreeStructure.isExpandable(vector, database))
        assertEquals(listOf("FVector()"), labels(ApiTreeStructure.children(vector, database)))
    }

    fun testEnumValuesStayInDeclarationOrder() {
        val mode = ApiNode.Type(database.type("EMode")!!)
        assertEquals(listOf("Walk", "Fly", "Swim"), labels(ApiTreeStructure.children(mode, database)))
    }

    fun testSearchResultsAreNamedThroughTheirOwner() {
        val member = database.search("GetController", limit = 5).hits.single() as UnrealMember
        assertEquals("APawn.GetController()", ApiTreeCellRenderer.text(ApiNode.Member(member, qualified = true)))
    }

    fun testDetailsEscapeTemplateTypesAndSetTheDocTagsApart() {
        val method = database.type("AActor")!!.methods.single()
        val html = ApiDetailsHtml.member(method)

        assertTrue(html, html.contains("TArray&lt;UActorComponent&gt; AActor.GetComponentsByClass(\n    TSubclassOf&lt;UActorComponent&gt; ComponentClass\n)"))
        assertFalse(html, html.contains("<UActorComponent>"))
        // The indented line continues the parameter it follows.
        assertTrue(html, html.contains("<li><code>ComponentClass</code> - The class to look for and its subclasses</li>"))
        assertTrue(html, html.contains("<p><b>Returns:</b> The components found</p>"))
        assertTrue(html, html.contains("<p>Gets all components of a class.</p>"))
    }

    fun testSignaturesShowConstAndLeaveTheReturnTypeOffConstructors() {
        val getController = database.type("APawn")!!.methods.first { it.name == "GetController" }
        assertEquals("AController APawn.GetController() const", ApiDetailsHtml.signature(getController))

        val constructor = database.constructorsOf(database.type("FVector")!!).single()
        assertEquals("FVector()", ApiDetailsHtml.signature(constructor))
    }

    fun testATypesDetailsCountWhatItInherits() {
        val html = ApiDetailsHtml.of(ApiNode.Type(database.type("APawn")!!), database)!!
        assertTrue(html, html.contains("class APawn : AActor"))
        assertTrue(html, html.contains("<b>Inherits:</b> AActor → UObject"))
        assertTrue(html, html.contains("Declares 2 functions and 1 property; inherits 2 functions and 1 property; has 1 static member."))
    }

    fun testGlobalFunctionsAreNamedWithoutAnOwner() {
        val print = database.globals.functions.single() as UnrealMethod
        assertEquals("void Print()", ApiDetailsHtml.signature(print))
        assertEquals("Print()", ApiTreeCellRenderer.text(ApiNode.Member(print, qualified = true)))
    }
}
