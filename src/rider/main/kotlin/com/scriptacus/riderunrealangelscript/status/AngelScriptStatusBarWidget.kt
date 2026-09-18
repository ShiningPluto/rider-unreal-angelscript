package com.scriptacus.riderunrealangelscript.status

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.Consumer
import com.scriptacus.riderunrealangelscript.settings.AngelScriptSettingsConfigurable
import java.awt.Component
import java.awt.event.MouseEvent
import java.time.Duration
import java.time.Instant

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
        // Every state names what is actually true, so "working" is never indistinguishable from
        // "silently doing nothing" - which is the whole reason this widget exists.
        return when (service.status) {
            AngelScriptUnrealStatus.READY -> "AngelScript: Unreal ${service.configuredPort}"
            AngelScriptUnrealStatus.EDITOR_CLOSED -> "AngelScript: editor closed"
            AngelScriptUnrealStatus.CACHED_TYPES -> "AngelScript: cached types"
            AngelScriptUnrealStatus.LOADING_TYPES -> "AngelScript: loading types…"
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
                "Connected to the Unreal editor on port $port, with a live C++ type database. " +
                    "Semantic highlighting and diagnostics are up to date."

            AngelScriptUnrealStatus.EDITOR_CLOSED ->
                "The Unreal editor on port $port has disconnected. Highlighting and diagnostics " +
                    "keep working on the type database it sent ${describeCacheAge(service)}, but " +
                    "nothing is keeping it up to date. Start the editor again to reconnect."

            AngelScriptUnrealStatus.CACHED_TYPES ->
                "No editor has been reachable this session, so the type database saved " +
                    "${describeCacheAge(service)} is being used. Highlighting and diagnostics work, " +
                    "but will not reflect engine C++ changed since then. Start the Unreal editor " +
                    "on port $port to replace it with a live database."

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
                    "need the C++ type database, which only a running editor can provide. " +
                    "No cached database is available for this project yet."
        }
    }

    private fun describeCacheAge(service: AngelScriptUnrealStatusService): String {
        val capturedAt = service.typesCapturedAt ?: return "in an earlier session"
        val age = Duration.between(Instant.ofEpochMilli(capturedAt), Instant.now())
        return when {
            age.toMinutes() < 1L -> "just now"
            age.toHours() < 1L -> "${age.toMinutes()} minutes ago"
            age.toDays() < 1L -> "${age.toHours()} hours ago"
            age.toDays() == 1L -> "yesterday"
            else -> "${age.toDays()} days ago"
        }
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, AngelScriptSettingsConfigurable::class.java)
    }
}
