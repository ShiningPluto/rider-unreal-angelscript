package com.scriptacus.riderunrealangelscript.settings

import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * Builds the `UnrealAngelscript` configuration payload expected by the bundled language server.
 *
 * The server never reads `initializationOptions`; `workspace/didChangeConfiguration` is its only
 * source of settings. That matters for [AngelScriptLspSettings.State.unrealConnectionPort] in
 * particular: the server starts with `port = -1` and only dials the Unreal editor from inside its
 * `onDidChangeConfiguration` handler. Without a push it never receives the C++ type database, so
 * modules stay unresolved and neither semantic tokens nor diagnostics are ever produced.
 *
 * Every code path that starts or restarts the server therefore has to push this payload - see
 * [com.scriptacus.riderunrealangelscript.lsp.AngelScriptLanguageClient].
 */
object AngelScriptLspConfiguration {

    private val gson = Gson()

    /**
     * Returns the configuration as a [JsonObject] so it can be used both for
     * `workspace/didChangeConfiguration` pushes and for lsp4ij's section-based
     * `workspace/configuration` lookups, which require a `JsonObject`.
     */
    fun build(state: AngelScriptLspSettings.State): JsonObject =
        gson.toJsonTree(asMap(state)).asJsonObject

    private fun asMap(state: AngelScriptLspSettings.State): Map<String, Any> {
        // VSCode extension expects settings under "UnrealAngelscript" key
        // The LSP server reads specific properties, so we must provide ALL of them
        // to avoid "Cannot read properties of undefined" errors
        return mapOf(
            "UnrealAngelscript" to mapOf(
                // General
                "unrealConnectionPort" to state.unrealConnectionPort,
                "scriptIgnorePatterns" to state.scriptIgnorePatterns,

                // Top-level completion settings
                "insertParenthesisOnFunctionCompletion" to false,
                "mathCompletionShortcuts" to state.mathCompletionShortcuts,

                // Nested completion settings
                "completion" to mapOf(
                    "dependencyRestrictions" to emptyList<Any>()
                ),

                // Diagnostics
                "diagnosticsForUnrealNamingConvention" to state.diagnosticsForUnrealNamingConvention,
                "markUnreadVariablesAsUnused" to false,
                "correctFloatLiteralsWhenExpectingDoublePrecision" to state.correctFloatLiteralsWhenExpectingDoublePrecision,

                // Inlay Hints - LSP server requires these even though Rider handles inlay hints natively
                // We provide defaults matching VSCode extension defaults
                "inlayHints" to mapOf(
                    "inlayHintsEnabled" to true,
                    "parameterHintsForConstants" to true,
                    "parameterHintsForComplexExpressions" to true,
                    "parameterReferenceHints" to true,
                    "parameterHintsForSingleParameterFunctions" to false,
                    "typeHintsForAutos" to true,
                    "typeHintsForAutoIgnoredTypes" to emptyList<String>(),
                    "parameterHintsIgnoredParameterNames" to listOf(
                        "Object", "Actor", "FunctionName", "Value", "InValue",
                        "NewValue", "Condition", "Parameters", "Params"
                    ),
                    "parameterHintsIgnoredFunctionNames" to emptyList<String>()
                ),

                // Inline Values - for debug adapter
                "inlineValues" to mapOf(
                    "showInlineValueForFunctionThisObject" to true,
                    "showInlineValueForLocalVariables" to true,
                    "showInlineValueForParameters" to true,
                    "showInlineValueForMemberAssignment" to true
                ),

                // Code Lenses
                "codeLenses" to mapOf(
                    "showCreateBlueprintClasses" to state.showCreateBlueprintClasses
                ),

                // Advanced
                "projectCodeGeneration" to mapOf(
                    "enable" to state.projectCodeGenerationEnable,
                    "generators" to emptyList<Any>()
                )
            )
        )
    }
}
