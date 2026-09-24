package com.scriptacus.riderunrealangelscript.debug

import com.intellij.execution.impl.RunDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.scriptacus.riderunrealangelscript.debug.AngelScriptDebugConfigurations.State
import com.scriptacus.riderunrealangelscript.status.AngelScriptUnrealStatusService

/**
 * Starts AngelScript debugging in one step, creating the configuration first if the project has
 * none. The text names which of those it is about to do, so the menu entry doubles as a report of
 * whether the setup exists.
 */
class StartAngelScriptDebuggingAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val state = AngelScriptDebugConfigurations.state(project)
        // Stay out of the Run menu of projects that have nothing to do with AngelScript.
        e.presentation.isEnabledAndVisible = state != State.NoConfiguration ||
            AngelScriptUnrealStatusService.getInstance(project).everObservedServer
        when (state) {
            State.NoConfiguration -> {
                e.presentation.text = "Create and Start AngelScript Debugging"
                e.presentation.description = "Creates the '${AngelScriptDebugConfigurations.DEFAULT_NAME}' debug " +
                    "configuration, then attaches the AngelScript debugger to the Unreal editor"
            }

            is State.NotAttached -> {
                e.presentation.text = "Start AngelScript Debugging"
                e.presentation.description = "Attaches the AngelScript debugger to the Unreal editor " +
                    "with '${state.configuration.name}'"
            }

            is State.Connecting, is State.Attached -> {
                e.presentation.text = "Show AngelScript Debugger"
                e.presentation.description = "The AngelScript debug session is running; brings it to the front"
            }
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        AngelScriptDebugConfigurations.startOrShow(project)
    }
}

/** Opens the AngelScript debug configuration on its own, without the full Run/Debug Configurations dialog. */
class EditAngelScriptDebugConfigurationAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && AngelScriptDebugConfigurations.find(project) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = AngelScriptDebugConfigurations.find(project) ?: return
        RunDialog.editConfiguration(project, settings, "Edit AngelScript Debug Configuration")
    }
}
