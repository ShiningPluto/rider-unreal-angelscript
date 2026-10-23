package com.scriptacus.riderunrealangelscript.toolwindow

import com.intellij.testFramework.UsefulTestCase
import com.intellij.util.ui.tree.TreeUtil
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** Which rows stay at the top of the API browser as it scrolls, and when the next one pushes them out. */
class StickyTreeRowsTest : UsefulTestCase() {

    private val root = DefaultMutableTreeNode("root")
    private val tree = JTree(DefaultTreeModel(root))

    private fun node(parent: DefaultMutableTreeNode, name: String, vararg children: String) =
        DefaultMutableTreeNode(name).also { node ->
            parent.add(node)
            children.forEach { node.add(DefaultMutableTreeNode(it)) }
        }

    override fun setUp() {
        super.setUp()
        // Rows 10 pixels high, one per line:
        //  0 Classes
        //  1   AActor        2-6 its five members
        //  7   APawn         8-10 its three members
        // 11 Structs
        val classes = node(root, "Classes")
        val actor = node(classes, "AActor", "a1", "a2", "a3", "a4", "a5")
        val pawn = node(classes, "APawn", "p1", "p2", "p3")
        node(root, "Structs", "FVector")
        (tree.model as DefaultTreeModel).reload()
        tree.isRootVisible = false
        tree.rowHeight = 10
        tree.expandPath(TreePath(classes.path))
        tree.expandPath(TreePath(actor.path))
        tree.expandPath(TreePath(pawn.path))
        tree.setSize(200, 1000)
    }

    private fun sticky(top: Int, maxRows: Int = 6) =
        StickyTreeRows.stickyPaths(tree, top, maxRows).map { it.lastPathComponent.toString() }

    private fun pushUp(top: Int): Int {
        val paths = StickyTreeRows.stickyPaths(tree, top)
        return StickyTreeRows.pushUp(tree, paths.last(), top + paths.size * 10)
    }

    fun testNothingSticksAtTheTop() {
        assertEquals(emptyList<String>(), sticky(0))
        assertEquals(emptyList<String>(), sticky(5))
    }

    fun testAnExpandedRowSticksUnderItsGroup() {
        // AActor at the top: its group stays, and it stays under it while its members scroll by.
        assertEquals(listOf("Classes", "AActor"), sticky(10))
        assertEquals(listOf("Classes", "AActor"), sticky(40))
        assertEquals(0, pushUp(40))
        assertEquals(listOf("Classes"), sticky(10, maxRows = 1))
    }

    fun testTheNextRowPushesTheLastOneOut() {
        // APawn, at row 7, reaches the bottom of the sticky rows and moves AActor up.
        assertEquals(listOf("Classes", "AActor"), sticky(55))
        assertEquals(5, pushUp(55))
        // Then takes its place.
        assertEquals(listOf("Classes"), sticky(60))
        assertEquals(0, pushUp(60))
        assertEquals(listOf("Classes", "APawn"), sticky(70))
    }

    fun testTheNextGroupPushesOutItsGroup() {
        assertEquals(listOf("Classes"), sticky(105))
        assertEquals(5, pushUp(105))
        assertEquals(emptyList<String>(), sticky(110))
    }

    fun testTheRowsLieOverTheTopOfTheViewport() {
        val scrollPane = JScrollPane(tree)
        val pane = StickyTreeRows.install(tree, scrollPane)
        val sticky = pane.getComponent(0)
        pane.setSize(200, 50)
        pane.doLayout()
        scrollPane.doLayout()
        scrollPane.viewport.doLayout()
        assertFalse(sticky.isVisible)

        scrollPane.viewport.viewPosition = Point(0, 55)
        val viewport = SwingUtilities.convertRectangle(scrollPane, scrollPane.viewport.bounds, pane)
        // Two rows, the last pushed up by 5 pixels, and clear of the scroll bar.
        assertTrue(sticky.isVisible)
        assertEquals(Rectangle(viewport.x, viewport.y, viewport.width, 15), sticky.bounds)

        val image = BufferedImage(sticky.width, sticky.height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            sticky.paint(graphics)
        } finally {
            graphics.dispose()
        }

        scrollPane.viewport.viewPosition = Point(0, 0)
        assertFalse(sticky.isVisible)
    }

    fun testClickingAStickyRowsExpandControlCollapsesIt() {
        val scrollPane = JScrollPane(tree)
        val pane = StickyTreeRows.install(tree, scrollPane)
        val sticky = pane.getComponent(0)
        pane.setSize(200, 50)
        pane.doLayout()
        scrollPane.doLayout()
        scrollPane.viewport.doLayout()
        val actor = tree.getPathForRow(1)
        val handleX = (0 until tree.getPathBounds(actor)!!.x).first { TreeUtil.isLocationInExpandControl(tree, actor, it, 15) }

        // Classes and AActor are sticky, AActor pushed up by 5 pixels: its row is drawn from 5 to 15.
        scrollPane.viewport.viewPosition = Point(0, 55)
        fun click(x: Int) = sticky.dispatchEvent(MouseEvent(sticky, MouseEvent.MOUSE_PRESSED, 0, 0, x, 10, 1, false, MouseEvent.BUTTON1))

        // Its name goes to it.
        click(tree.getPathBounds(actor)!!.x + 5)
        assertEquals(actor, tree.selectionPath)
        assertTrue(tree.isExpanded(actor))
        assertEquals(0, scrollPane.viewport.viewPosition.y)

        // Its expand control collapses it, and leaves it where it was drawn.
        scrollPane.viewport.viewPosition = Point(0, 55)
        click(handleX)
        assertFalse(tree.isExpanded(actor))
        assertEquals(0, scrollPane.viewport.viewPosition.y)
    }

    fun testScrollingToARowKeepsItClearOfTheStickyRows() {
        val scrollPane = JScrollPane(tree)
        scrollPane.setSize(200, 50)
        scrollPane.doLayout()
        scrollPane.viewport.doLayout()
        val viewport = scrollPane.viewport

        // a4, at 50, is in the viewport, but under AActor's sticky row.
        viewport.viewPosition = Point(0, 45)
        StickyTreeRows.uncover(tree, tree.getRowBounds(5))
        // Under Classes and AActor instead, which are then the sticky rows.
        assertEquals(30, viewport.viewPosition.y)
        assertEquals(listOf("Classes", "AActor"), sticky(30))

        // a5, below them, stays where it is.
        StickyTreeRows.uncover(tree, tree.getRowBounds(6))
        assertEquals(30, viewport.viewPosition.y)
    }
}
