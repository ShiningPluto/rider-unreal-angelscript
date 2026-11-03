package com.scriptacus.riderunrealangelscript.mcp

import com.scriptacus.riderunrealangelscript.typedb.ScriptDeclarations
import com.scriptacus.riderunrealangelscript.typedb.ScriptOrigin
import com.scriptacus.riderunrealangelscript.typedb.UnrealTypeDatabase
import java.io.StringReader

/** A type database and script declarations with one of everything the MCP API tools name. */
internal object ApiFixture {

    val database: UnrealTypeDatabase = UnrealTypeDatabase.parse(StringReader("""
        {"version":1,"chunks":[{
          "UObject": {"methods": [{"name": "GetName", "return": "FString", "const": true}]},
          "AActor": {
            "inherits": "UObject",
            "doc": "An actor.\n    Placed in a level.",
            "properties": {"Tags": ["TArray<FName>", "Tags on the actor."]},
            "methods": [
              {"name": "SetActorLocation", "return": "bool",
               "args": [{"name": "NewLocation", "type": "FVector"}, {"name": "bSweep", "type": "bool", "default": "false"}],
               "doc": "Moves the actor.   \n@param NewLocation Where to\n@return Whether it moved"},
              {"name": "SetActorLocation", "return": "bool", "args": [{"name": "X", "type": "float32"}]},
              {"name": "BeginPlay", "event": true, "callable": false, "unrealname": "ReceiveBeginPlay"}
            ]
          },
          "APawn": {"inherits": "AActor", "properties": {"BaseEyeHeight": ["float32"]}},
          "__APawn": {"methods": [{"name": "GetMovementBaseActor", "return": "AActor", "args": [{"name": "Pawn", "type": "const APawn"}]}]},
          "__Math": {"methods": [{"name": "Lerp", "return": "float64"}]},
          "__EMode": {"isEnum": true, "properties": {"Walk": ["EMode"], "Fly": ["EMode"]}},
          "FVector": {"isStruct": true, "properties": {"X": ["float64"]}},
          "FOnLanded": {"methods": [{"name": "Broadcast", "args": [{"name": "Speed", "type": "float32"}]}]},
          "__": {"methods": [
            {"name": "FVector", "return": "FVector", "isConstructor": true, "args": [{"name": "X", "type": "float64"}]},
            {"name": "Print", "args": [{"name": "Text", "type": "FString"}]}
          ]}
        }]}
    """.trimIndent()))

    private fun declaration(name: String, namespace: String, module: String, vararg entries: Pair<String, Any?>) = mapOf(
        "name" to name,
        "namespace" to namespace,
        "module" to module,
        "file" to "file:///d:/Game/Script/${module.replace('.', '/')}.as",
    ) + entries

    private fun args(vararg args: Pair<String, String>) = args.map { (type, name) -> mapOf("type" to type, "name" to name) }

    /** An angelscript/getScriptDeclarations answer, in the shape the bundled server sends it. */
    val script: ScriptDeclarations = ScriptDeclarations.parse(
        mapOf(
            "functions" to listOf(
                declaration("PrintToScreen", "", "Debug", "returnType" to "void", "args" to args("FString" to "Text")),
                declaration(
                    "Highlight", "", "Mixins",
                    "returnType" to "void", "args" to args("AActor" to "Actor", "FLinearColor" to "Color"), "isMixin" to true,
                ),
                declaration("Send", "Backend::Chat", "Backend.Chat", "returnType" to "bool", "args" to args("FString" to "Message")),
            ),
            "properties" to listOf(
                declaration("Tau", "Math", "MathExtras", "type" to "float64"),
            ),
        ),
        database,
    )

    /** As the tools name a script file: relative to the project, which is d:/Game here. */
    val path: (ScriptOrigin) -> String = { it.path!!.removePrefix("d:/Game/") }
}
