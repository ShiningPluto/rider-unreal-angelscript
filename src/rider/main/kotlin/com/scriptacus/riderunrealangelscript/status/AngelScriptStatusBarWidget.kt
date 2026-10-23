package com.scriptacus.riderunrealangelscript.status

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent
import java.time.Duration
import java.time.Instant

/**
 * Reports why AngelScript analysis is unavailable, instead of leaving the editor silently inert.
 *
 * Clicking opens a menu for having the Unreal editor compile the files it reports errors in again,
 * and for the AngelScript settings, where the Unreal connection port lives.
 */
class AngelScriptStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation {

    companion object {
        const val WIDGET_ID = "AngelScriptUnrealStatus"

        /** Action group declared in plugin.xml, shown when the widget is clicked. */
        const val POPUP_GROUP_ID = "AngelScript.UnrealStatusBarPopup"
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
            AngelScriptUnrealStatus.READY -> statusTooltip(
                "Connected to the Unreal editor on port $port",
                "The C++ type database is live, so semantic highlighting and diagnostics are up to date."
            )

            AngelScriptUnrealStatus.EDITOR_CLOSED -> statusTooltip(
                "The Unreal editor on port $port has disconnected",
                "Highlighting and diagnostics keep working on the type database it sent " +
                    "${describeCacheAge(service)}, but nothing is keeping it up to date. " +
                    "Start the editor again to reconnect."
            )

            AngelScriptUnrealStatus.CACHED_TYPES -> statusTooltip(
                "Using the type database saved ${describeCacheAge(service)}",
                "No editor has been reachable this session. Highlighting and diagnostics work, but will " +
                    "not reflect engine C++ changed since then. Start the Unreal editor on port $port " +
                    "to replace it with a live database."
            )

            AngelScriptUnrealStatus.LOADING_TYPES -> statusTooltip(
                "Receiving the C++ type database",
                "Connected on port $port. Highlighting and diagnostics appear once it finishes loading."
            )

            AngelScriptUnrealStatus.SERVER_STOPPED -> statusTooltip(
                "The AngelScript language server is not running",
                "Only lexical highlighting is available."
            )

            AngelScriptUnrealStatus.NOT_CONFIGURED -> statusTooltip(
                "The language server has not received its configuration",
                "It is not connecting to any port. Change a setting under Languages & Frameworks | " +
                    "AngelScript to resend it."
            )

            AngelScriptUnrealStatus.DISCONNECTED -> statusTooltip(
                "No Unreal editor listening on port $port",
                "Semantic highlighting and diagnostics need the C++ type database, which only a running " +
                    "editor can provide. No cached database is available for this project yet."
            )
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

    // Disabled entries stay visible, so the recompile entry can be found before there is anything to recompile.
    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { event ->
        showStatusBarPopup(event, POPUP_GROUP_ID, showDisabledActions = true)
    }
}
