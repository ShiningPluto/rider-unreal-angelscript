package com.scriptacus.riderunrealangelscript.settings.ui

import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ComponentPredicate
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptLspVersion
import com.scriptacus.riderunrealangelscript.settings.AngelScriptLspSettings
import javax.swing.JPanel

class GeneralSettingsPanel {
    private var portField = JBTextField()
    private val useCachedTypesBox = JBCheckBox("Use the saved type database until an Unreal editor connects")
    private val ignorePatternsEditor = StringListEditor("Ignore pattern")

    fun createPanel(): JPanel = panel {
        row("Unreal Connection Port:") {
            cell(portField)
                .comment("Port number for connecting to Unreal Engine (default: 27099)")
                .validationOnInput {
                    val value = it.text.toIntOrNull()
                    if (value == null || value <= 0 || value > 65535) {
                        error("Port must be between 1 and 65535")
                    } else {
                        null
                    }
                }
        }
        row {
            cell(useCachedTypesBox)
                .comment(
                    "Highlighting and diagnostics need the C++ type database, which only a running " +
                        "Unreal editor provides. Every database an editor sends is saved to disk. " +
                        "When enabled, a session that has not received one yet loads the saved one, " +
                        "so analysis and the API browser work before an editor is running. " +
                        "A saved database can be out of date after engine C++ changes; the status " +
                        "bar says when one is in use.<br/>" +
                        "This does not affect a database an editor has already sent: those types " +
                        "stay loaded after the editor closes either way. Turning this off while a " +
                        "saved database is in use restarts the language server, since a loaded " +
                        "database cannot be discarded without it."
                )
        }
        // Informational rather than a setting, but this is where the status bar widget sends the
        // user, and nothing else in the IDE names the language server the plugin is actually running.
        row("Bundled language server:") {
            // Copyable because the one thing it is for is being quoted in a bug report.
            cell(JBLabel("Hazelight unreal-angelscript ${AngelScriptLspVersion.displayText}").apply {
                setCopyable(true)
            }).comment(
                "The upstream revision this plugin was built from, with the plugin's own patches " +
                    "applied on top."
            )
        }
        row {
            cell(ignorePatternsEditor.component)
                .label("Script ignore patterns:", LabelPosition.TOP)
                .comment(
                    "Glob patterns for script files the plugin leaves alone: they are not handed to " +
                        "the language server when opened, and navigation does not lead into them " +
                        "(e.g., **/Saved/**, **/.plastic/**)."
                )
        }
    }

    fun reset(state: AngelScriptLspSettings.State) {
        portField.text = state.unrealConnectionPort.toString()
        useCachedTypesBox.isSelected = state.useCachedTypeDatabase
        ignorePatternsEditor.items = state.scriptIgnorePatterns
    }

    fun apply(state: AngelScriptLspSettings.State) {
        state.unrealConnectionPort = portField.text.toIntOrNull() ?: 27099
        state.useCachedTypeDatabase = useCachedTypesBox.isSelected
        state.scriptIgnorePatterns.clear()
        state.scriptIgnorePatterns.addAll(ignorePatternsEditor.items)
    }

    fun isModified(state: AngelScriptLspSettings.State): Boolean {
        return state.unrealConnectionPort != (portField.text.toIntOrNull() ?: 27099) ||
                state.useCachedTypeDatabase != useCachedTypesBox.isSelected ||
                state.scriptIgnorePatterns != ignorePatternsEditor.items
    }
}
