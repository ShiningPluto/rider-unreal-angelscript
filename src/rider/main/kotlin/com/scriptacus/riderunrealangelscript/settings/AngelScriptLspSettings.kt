package com.scriptacus.riderunrealangelscript.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "AngelScriptLspSettings",
    storages = [Storage("angelscript-lsp.xml")]
)
class AngelScriptLspSettings : PersistentStateComponent<AngelScriptLspSettings.State> {

    enum class CppNavigationStrategy {
        /** Open the declaration in Rider, found in the project's own C++ sources. Requires them to be there. */
        TEXT_SEARCH {
            override fun toString() = "Rider";
        },
        /** Delegate C++ navigation to Unreal Engine. Opens in configured IDE via RiderLink. Works without C++ sources in Rider. */
        UNREAL_ENGINE {
            override fun toString() = "Unreal";
        }
    }

    data class State(
        // General
        var unrealConnectionPort: Int = 27099,
        var scriptIgnorePatterns: MutableList<String> = mutableListOf("**/Saved/**", "**/.plastic/**"),

        /**
         * Load the last type database received from Unreal when no editor has sent one, so analysis
         * works offline. The database is saved either way; this only decides whether it is used.
         * The cached API can be out of date after engine C++ changes, which the status bar reports;
         * turn this off to get no analysis rather than possibly stale analysis.
         */
        var useCachedTypeDatabase: Boolean = true,

        // Completion
        var mathCompletionShortcuts: Boolean = true,

        /**
         * Insert `()` after a completed function or method name and put the caret between them,
         * as the VS Code extension does for its `UnrealAngelscript.insertParenthesisOnFunctionCompletion`
         * setting.
         */
        var insertParenthesisOnFunctionCompletion: Boolean = true,

        /**
         * Commit the selected completion when one of its LSP `commitCharacters` is typed, as VS Code
         * does. lsp4ij does not implement commit characters.
         */
        var useCommitCharacters: Boolean = true,

        /**
         * Type `;` after the closing parenthesis when the caret is still inside it, as Rider's C#
         * editor does: `Move(|)` + `;` becomes `Move();`.
         */
        var moveSemicolonOutsideParentheses: Boolean = true,

        /**
         * Pair a typed `"` or `'` with a closing one and step over an existing one, as Rider's C#
         * editor does.
         */
        var autoCloseQuotes: Boolean = true,

        /**
         * Delete both halves when the caret is between a pair and one of them is removed:
         * backspace between `""` or `()` removes both characters.
         */
        var deleteMatchingDelimiters: Boolean = true,

        // Diagnostics
        var diagnosticsForUnrealNamingConvention: Boolean = true,
        var correctFloatLiteralsWhenExpectingDoublePrecision: Boolean = false,

        // Code Lenses
        var showCreateBlueprintClasses: MutableList<String> = mutableListOf("AActor", "UUserWidget"),

        // Navigation
        var cppNavigationStrategy: CppNavigationStrategy = CppNavigationStrategy.TEXT_SEARCH,

        // Advanced
        var projectCodeGenerationEnable: Boolean = false,

        // LSP Initialization
        var lspInitialDelayMs: Int = 2000,
        var lspMaxRetries: Int = 3,
        var lspRetryBackoffMs: Int = 1000
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    fun notifySettingsChanged() {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(AngelScriptSettingsListener.TOPIC)
            .settingsChanged(state)
    }

    companion object {
        fun getInstance(): AngelScriptLspSettings =
            ApplicationManager.getApplication().getService(AngelScriptLspSettings::class.java)
    }
}
