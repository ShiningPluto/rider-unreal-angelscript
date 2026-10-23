package com.scriptacus.riderunrealangelscript.status

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import java.awt.Point
import java.awt.event.MouseEvent

/**
 * Shows the action group [groupId], declared in plugin.xml, as the menu of the status bar item that
 * was clicked. A disabled action is shown greyed out only if [showDisabledActions].
 */
internal fun showStatusBarPopup(event: MouseEvent, groupId: String, showDisabledActions: Boolean) {
    val group = ActionManager.getInstance().getAction(groupId) as? ActionGroup ?: return
    val popup = JBPopupFactory.getInstance().createActionGroupPopup(
        null,
        group,
        DataManager.getInstance().getDataContext(event.component),
        JBPopupFactory.ActionSelectionAid.MNEMONICS,
        showDisabledActions
    )
    // Open upwards, since the status bar is at the bottom of the frame.
    popup.show(RelativePoint(event.component, Point(0, -popup.content.preferredSize.height)))
}
