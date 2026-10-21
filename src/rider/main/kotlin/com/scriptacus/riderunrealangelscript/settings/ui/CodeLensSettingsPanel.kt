package com.scriptacus.riderunrealangelscript.settings.ui

import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.panel
import com.scriptacus.riderunrealangelscript.settings.AngelScriptLspSettings
import javax.swing.JPanel

class CodeLensSettingsPanel {
    private val blueprintClassesEditor = StringListEditor("Class name")

    fun createPanel(): JPanel = panel {
        row {
            cell(blueprintClassesEditor.component)
                .label("Show 'Create Blueprint' code lens for classes inheriting from:", LabelPosition.TOP)
                .comment(
                    "Script classes that inherit from one of these get a code lens that creates a " +
                        "Blueprint of them in the connected Unreal editor."
                )
        }
    }

    fun reset(state: AngelScriptLspSettings.State) {
        blueprintClassesEditor.items = state.showCreateBlueprintClasses
    }

    fun apply(state: AngelScriptLspSettings.State) {
        state.showCreateBlueprintClasses.clear()
        state.showCreateBlueprintClasses.addAll(blueprintClassesEditor.items)
    }

    fun isModified(state: AngelScriptLspSettings.State): Boolean {
        return state.showCreateBlueprintClasses != blueprintClassesEditor.items
    }
}
