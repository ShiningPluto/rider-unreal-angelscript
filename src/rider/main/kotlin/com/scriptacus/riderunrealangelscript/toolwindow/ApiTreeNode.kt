package com.scriptacus.riderunrealangelscript.toolwindow

import javax.swing.tree.DefaultMutableTreeNode

/**
 * A row of the API browser. An [expandable] row starts without children and is filled in when it is
 * first expanded; the tree model asks allowsChildren, so it shows as expandable until then.
 */
class ApiTreeNode(val node: ApiNode, expandable: Boolean) : DefaultMutableTreeNode(node, expandable) {
    var childrenLoaded: Boolean = !expandable

    /** What speed search matches: the row's text without the grey details after it. */
    override fun toString(): String = ApiTreeCellRenderer.text(node)
}
