package com.scriptacus.riderunrealangelscript.settings.ui

import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import com.scriptacus.riderunrealangelscript.settings.AngelScriptLspSettings
import javax.swing.JPanel

class CompletionSettingsPanel {
    private val mathShortcutsCheckbox = JBCheckBox("Enable math completion shortcuts")
    private val insertParenthesisCheckbox = JBCheckBox("Insert parentheses after function completion")
    private val commitCharactersCheckbox = JBCheckBox("Commit the selected completion on typing ( or .")
    private val semicolonCheckbox = JBCheckBox("Type ; after the closing parenthesis")
    private val quoteCheckbox = JBCheckBox("Automatically close double and single quotes")
    private val backspaceCheckbox = JBCheckBox("Delete matching quote or bracket pairs together")

    fun createPanel(): JPanel = panel {
        row {
            cell(mathShortcutsCheckbox)
                .comment("Enable shortcuts for common math operations")
        }
        row {
            cell(insertParenthesisCheckbox)
                .comment(
                    "Insert () when a function or method name is completed, and place the caret " +
                        "between the parentheses"
                )
        }
        row {
            cell(commitCharactersCheckbox)
                .comment(
                    "When a completion declares commit characters, typing one commits it, as in " +
                        "VS Code. Turn off if it interferes with typing"
                )
        }
        row {
            cell(semicolonCheckbox)
                .comment("With the caret inside the parentheses, `;` is typed after the closing one")
        }
        row {
            cell(quoteCheckbox)
                .comment("Typing a quote inserts its pair and steps over an existing closing quote")
        }
        row {
            cell(backspaceCheckbox)
                .comment("Backspace between a pair removes both characters")
        }
    }

    fun reset(state: AngelScriptLspSettings.State) {
        mathShortcutsCheckbox.isSelected = state.mathCompletionShortcuts
        insertParenthesisCheckbox.isSelected = state.insertParenthesisOnFunctionCompletion
        commitCharactersCheckbox.isSelected = state.useCommitCharacters
        semicolonCheckbox.isSelected = state.moveSemicolonOutsideParentheses
        quoteCheckbox.isSelected = state.autoCloseQuotes
        backspaceCheckbox.isSelected = state.deleteMatchingDelimiters
    }

    fun apply(state: AngelScriptLspSettings.State) {
        state.mathCompletionShortcuts = mathShortcutsCheckbox.isSelected
        state.insertParenthesisOnFunctionCompletion = insertParenthesisCheckbox.isSelected
        state.useCommitCharacters = commitCharactersCheckbox.isSelected
        state.moveSemicolonOutsideParentheses = semicolonCheckbox.isSelected
        state.autoCloseQuotes = quoteCheckbox.isSelected
        state.deleteMatchingDelimiters = backspaceCheckbox.isSelected
    }

    fun isModified(state: AngelScriptLspSettings.State): Boolean {
        return state.mathCompletionShortcuts != mathShortcutsCheckbox.isSelected ||
            state.insertParenthesisOnFunctionCompletion != insertParenthesisCheckbox.isSelected ||
            state.useCommitCharacters != commitCharactersCheckbox.isSelected ||
            state.moveSemicolonOutsideParentheses != semicolonCheckbox.isSelected ||
            state.autoCloseQuotes != quoteCheckbox.isSelected ||
            state.deleteMatchingDelimiters != backspaceCheckbox.isSelected
    }
}
