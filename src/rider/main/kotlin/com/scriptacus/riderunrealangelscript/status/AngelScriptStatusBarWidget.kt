package com.scriptacus.riderunrealangelscript.status

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.Consumer
import com.scriptacus.riderunrealangelscript.settings.AngelScriptSettingsConfigurable
import java.awt.Component
import java.awt.event.MouseEvent

/**
 * Reports why AngelScript analysis is unavailable, instead of leaving the editor silently inert.
 *
 * Clicking opens the AngelScript settings, where the Unreal connection port lives - the one thing
 * the user can actually act on from here.
 */
class AngelScriptStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation {

    companion object {
        const val WIDGET_ID = "AngelScriptUnrealStatus"
    }

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        AngelScriptUnrealStatusService.getInstance(project).startPolling()
    }

    override fun dispose() {
        // The polling itself is owned by the project service, which outlives this widget.
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getText(): String {
        val service = AngelScriptUnrealStatusService.getInstance(project)
        // Stay invisible in projects that never start the server rather than reporting on nothing.
        if (!service.everObservedServer) {
            return ""
        }
        return when (service.status) {
            AngelScriptUnrealStatus.READY -> "AngelScript"
            AngelScriptUnrealStatus.LOADING_TYPES -> "AngelScript: loading types"
            AngelScriptUnrealStatus.SERVER_STOPPED -> "AngelScript: server stopped"
            AngelScriptUnrealStatus.NOT_CONFIGURED -> "AngelScript: not configured"
            AngelScriptUnrealStatus.DISCONNECTED -> "AngelScript: no Unreal editor"
        }
    }

    override fun getTooltipText(): String {
        val service = AngelScriptUnrealStatusService.getInstance(project)
        val port = service.configuredPort
        return when (service.status) {
            AngelScriptUnrealStatus.READY ->
                "Connected to the Unreal editor on port $port. Highlighting and diagnostics are available."

            AngelScriptUnrealStatus.LOADING_TYPES ->
                "Connected on port $port, receiving the C++ type database. " +
                    "Highlighting and diagnostics appear once it finishes loading."

            AngelScriptUnrealStatus.SERVER_STOPPED ->
                "The AngelScript language server is not running. Only lexical highlighting is available."

            AngelScriptUnrealStatus.NOT_CONFIGURED ->
                "The language server has not received its configuration, so it is not connecting to " +
                    "any port. Change a setting under Languages & Frameworks | AngelScript to resend it."

            AngelScriptUnrealStatus.DISCONNECTED ->
                "No Unreal editor listening on port $port. Semantic highlighting and diagnostics " +
                    "need the C++ type database, which only a running editor can provide."
        }
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, AngelScriptSettingsConfigurable::class.java)
    }
}
