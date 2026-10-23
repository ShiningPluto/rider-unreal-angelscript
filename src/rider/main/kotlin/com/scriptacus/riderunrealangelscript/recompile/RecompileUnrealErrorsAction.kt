package com.scriptacus.riderunrealangelscript.recompile

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptDiagnosticsStore

/** Has the Unreal editor compile again every script file it currently reports compile errors in. */
class RecompileUnrealErrorsAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val count = UnrealRecompile.filesWithUnrealErrors(AngelScriptDiagnosticsStore.getInstance(project)).size
        e.presentation.isVisible = true
        e.presentation.isEnabled = count > 0 && UnrealRecompile.isEditorConnected(project)
        e.presentation.text = when (count) {
            0 -> "Recompile Files with Unreal Errors"
            1 -> "Recompile 1 File with Unreal Errors"
            else -> "Recompile $count Files with Unreal Errors"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        UnrealRecompile.recompile(project, UnrealRecompile.filesWithUnrealErrors(AngelScriptDiagnosticsStore.getInstance(project)))
    }
}
