package com.scriptacus.riderunrealangelscript.settings.ui

import com.intellij.ui.dsl.builder.panel
import com.scriptacus.riderunrealangelscript.settings.AngelScriptLspSettings
import javax.swing.JPanel

class NavigationSettingsPanel(private val state: AngelScriptLspSettings.State) {

    fun createPanel(): JPanel = panel {
        row("C++ Navigation Strategy:") {
            comboBox(AngelScriptLspSettings.CppNavigationStrategy.values().asList())
                .apply {
                    component.selectedItem = state.cppNavigationStrategy
                    component.addActionListener {
                        state.cppNavigationStrategy = component.selectedItem as? AngelScriptLspSettings.CppNavigationStrategy
                            ?: AngelScriptLspSettings.CppNavigationStrategy.TEXT_SEARCH
                    }
                }
                .comment("""
                    Controls how navigation from AngelScript to C++ symbols works:
                    <ul>
                    <li><b>Rider</b>: Open the declaration in Rider, found in the project's C++ sources.</li>
                    <li><b>Unreal</b>: Let the Unreal editor open it, in the IDE configured there.</li>
                    </ul>
                """.trimIndent())
        }
    }

    fun reset(state: AngelScriptLspSettings.State) {
        // Nothing to do - panel is created with direct state reference
    }

    fun apply(state: AngelScriptLspSettings.State) {
        // Nothing to do - state is updated directly via action listener
    }

    fun isModified(state: AngelScriptLspSettings.State): Boolean {
        // Always return false since we update state directly
        return false
    }
}
