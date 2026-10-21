package com.scriptacus.riderunrealangelscript.settings.ui

import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.ListSelectionModel

/**
 * An editable list of strings with add, edit and remove buttons, for the settings that are lists.
 * [itemName] names one entry in the add and edit dialogs, e.g. "Ignore pattern".
 */
class StringListEditor(private val itemName: String) {
    private val model = CollectionListModel<String>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    val component: JComponent = ToolbarDecorator.createDecorator(list)
        .setAddAction { prompt("Add $itemName", null)?.let { model.add(it) } }
        .setEditAction { editSelected() }
        .setRemoveAction { list.selectedIndex.takeIf { it >= 0 }?.let { model.remove(it) } }
        .disableUpDownActions()
        .setPreferredSize(JBUI.size(420, 110))
        .createPanel()

    init {
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                editSelected()
                return true
            }
        }.installOn(list)
    }

    var items: List<String>
        get() = model.items.toList()
        set(value) = model.replaceAll(value)

    private fun editSelected() {
        val index = list.selectedIndex.takeIf { it >= 0 } ?: return
        prompt("Edit $itemName", model.getElementAt(index))?.let { model.setElementAt(it, index) }
    }

    /** Asks for an entry; null when cancelled. Blank entries and duplicates are not accepted. */
    private fun prompt(title: String, current: String?): String? {
        val validator = object : InputValidator {
            override fun checkInput(input: String?): Boolean {
                val entry = input?.trim().orEmpty()
                return entry.isNotEmpty() && (entry == current || entry !in model.items)
            }

            override fun canClose(input: String?) = checkInput(input)
        }
        return Messages.showInputDialog(list, "$itemName:", title, null, current.orEmpty(), validator)?.trim()
    }
}
