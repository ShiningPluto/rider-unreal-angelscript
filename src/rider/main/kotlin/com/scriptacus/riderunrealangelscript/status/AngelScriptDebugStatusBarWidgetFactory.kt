package com.scriptacus.riderunrealangelscript.status

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class AngelScriptDebugStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = AngelScriptDebugStatusBarWidget.WIDGET_ID

    override fun getDisplayName(): String = "AngelScript Debugger"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = AngelScriptDebugStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
