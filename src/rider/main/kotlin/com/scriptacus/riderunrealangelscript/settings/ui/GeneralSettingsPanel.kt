package com.scriptacus.riderunrealangelscript.settings.ui

import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ComponentPredicate
import com.scriptacus.riderunrealangelscript.settings.AngelScriptLspSettings
import javax.swing.JPanel

class GeneralSettingsPanel {
    private var portField = JBTextField()
    private val useCachedTypesBox = JBCheckBox("Use the last type database when no Unreal editor is running")
    private var ignorePatterns = mutableListOf<String>()

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
                        "Unreal editor provides. When enabled, the last database received is reused " +
                        "offline; it can be out of date after engine C++ changes, and the status bar " +
                        "says when cached types are in use."
                )
        }
//        row {
//            label("Script Ignore Patterns:")
//                .comment("Glob patterns for scripts to ignore (e.g., **/Saved/**, **/.plastic/**)")
//        }
        // TODO: Add list editor for ignore patterns
        // This would use ToolbarDecorator with a JBList to allow adding/removing patterns
    }

    fun reset(state: AngelScriptLspSettings.State) {
        portField.text = state.unrealConnectionPort.toString()
        useCachedTypesBox.isSelected = state.useCachedTypeDatabase
        ignorePatterns.clear()
        ignorePatterns.addAll(state.scriptIgnorePatterns)
    }

    fun apply(state: AngelScriptLspSettings.State) {
        state.unrealConnectionPort = portField.text.toIntOrNull() ?: 27099
        state.useCachedTypeDatabase = useCachedTypesBox.isSelected
        state.scriptIgnorePatterns.clear()
        state.scriptIgnorePatterns.addAll(ignorePatterns)
    }

    fun isModified(state: AngelScriptLspSettings.State): Boolean {
        return state.unrealConnectionPort != (portField.text.toIntOrNull() ?: 27099) ||
                state.useCachedTypeDatabase != useCachedTypesBox.isSelected ||
                state.scriptIgnorePatterns != ignorePatterns
    }
}
