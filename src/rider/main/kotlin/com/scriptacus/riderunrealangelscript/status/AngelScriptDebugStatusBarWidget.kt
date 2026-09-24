package com.scriptacus.riderunrealangelscript.status

import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Consumer
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import com.scriptacus.riderunrealangelscript.debug.AngelScriptDebugConfigurations
import com.scriptacus.riderunrealangelscript.debug.AngelScriptDebugConfigurations.State
import java.awt.Component
import java.awt.Point
import java.awt.event.MouseEvent

/**
 * Reports whether breakpoints in script files can currently be hit. That is separate from the Unreal
 * connection the other widget reports on: the language server can be connected with no debugger
 * attached, and debugging the editor's C++ looks as if it should hit script breakpoints but does not.
 *
 * Shown only while a debug session is running or a script file has an enabled breakpoint. Otherwise
 * "not attached" would sit in the status bar of everyone who never debugs scripts, and appearing when
 * the first breakpoint is set puts it in front of the user at the moment it matters.
 *
 * Clicking opens a menu for starting the debugger (creating its configuration if there is none) and
 * editing that configuration.
 */
class AngelScriptDebugStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation {

    companion object {
        const val WIDGET_ID = "AngelScriptDebugStatus"

        /** Action group declared in plugin.xml, shown when the widget is clicked. */
        const val POPUP_GROUP_ID = "AngelScript.DebugStatusBarPopup"

        fun update(project: Project) {
            ApplicationManager.getApplication().invokeLater({
                WindowManager.getInstance().getStatusBar(project)?.updateWidget(WIDGET_ID)
            }, project.disposed)
        }
    }

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        val connection = project.messageBus.connect(this)
        connection.subscribe(XBreakpointListener.TOPIC, object : XBreakpointListener<XBreakpoint<*>> {
            override fun breakpointAdded(breakpoint: XBreakpoint<*>) = update(project)
            override fun breakpointRemoved(breakpoint: XBreakpoint<*>) = update(project)
            override fun breakpointChanged(breakpoint: XBreakpoint<*>) = update(project)
        })
        connection.subscribe(RunManagerListener.TOPIC, object : RunManagerListener {
            override fun runConfigurationAdded(settings: RunnerAndConfigurationSettings) = update(project)
            override fun runConfigurationRemoved(settings: RunnerAndConfigurationSettings) = update(project)
            override fun runConfigurationChanged(settings: RunnerAndConfigurationSettings) = update(project)
        })
        connection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
            override fun processStarted(debugProcess: XDebugProcess) = update(project)
            override fun processStopped(debugProcess: XDebugProcess) = update(project)
        })
    }

    override fun dispose() {
        // The message bus connection is disposed along with this widget.
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getText(): String {
        val state = AngelScriptDebugConfigurations.state(project)
        val sessionRunning = state is State.Connecting || state is State.Attached
        if (!sessionRunning && !AngelScriptDebugConfigurations.hasScriptBreakpoints(project)) {
            return ""
        }
        return when (state) {
            is State.Attached -> "AngelScript debugger: attached"
            is State.Connecting -> "AngelScript debugger: connecting…"
            is State.NotAttached -> "AngelScript debugger: not attached"
            State.NoConfiguration -> "AngelScript debugger: not set up"
        }
    }

    /**
     * Breakpoints in script files are accepted whether or not anything will ever hit them, and the
     * editor's C++ debug session looks like it should - so this says which it is, and what is missing.
     */
    override fun getTooltipText(): String = when (val state = AngelScriptDebugConfigurations.state(project)) {
        is State.Attached ->
            "The AngelScript debugger is attached to the Unreal editor, so breakpoints in script files are live."

        is State.Connecting ->
            "'${state.session.sessionName}' is running, but the Unreal editor has not answered it yet, so " +
                "breakpoints in script files are not live. The session ends by itself if no editor is " +
                "listening; if it stays here, the editor is not responding - for example because it is " +
                "stopped in the C++ debugger."

        is State.NotAttached ->
            "Script files have breakpoints, but the AngelScript debugger is not attached. They are only " +
                "hit while '${state.configuration.name}' is running under Debug - click to start it."

        State.NoConfiguration ->
            "Script files have breakpoints, but no AngelScript debug configuration exists, so they are " +
                "never hit. Debugging the editor's C++ does not attach the script debugger - click to " +
                "create the configuration and start it."
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { event ->
        val group = ActionManager.getInstance().getAction(POPUP_GROUP_ID) as? ActionGroup ?: return@Consumer
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            null,
            group,
            DataManager.getInstance().getDataContext(event.component),
            JBPopupFactory.ActionSelectionAid.MNEMONICS,
            false
        )
        // Open upwards, since the status bar is at the bottom of the frame.
        popup.show(RelativePoint(event.component, Point(0, -popup.content.preferredSize.height)))
    }
}
