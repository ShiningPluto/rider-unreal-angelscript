package com.scriptacus.riderunrealangelscript.status

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class AngelScriptStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = AngelScriptStatusBarWidget.WIDGET_ID

    override fun getDisplayName(): String = "AngelScript Unreal Connection"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = AngelScriptStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
