package com.scriptacus.riderunrealangelscript.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.JViewport
import javax.swing.SwingUtilities
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.event.TreeModelEvent
import javax.swing.event.TreeModelListener
import javax.swing.tree.TreePath

/**
 * Keeps the rows that the tree's top row is nested in at the top of its viewport, as the editor's
 * sticky lines keep the scopes of the code on screen. Clicking one scrolls back to it, and clicking
 * its expand control collapses it.
 * The API browser has tens of thousands of rows, and without them the group or class being scrolled
 * through is found again only by dragging the scroll bar.
 *
 * The platform's trees have nothing like it in 2025.2. The rows are painted by the tree's own UI,
 * moved into place, so they look like the rows they stand for, handles and selection included.
 */
class StickyTreeRows private constructor(
    private val tree: JTree,
    private val scrollPane: JScrollPane,
) : JComponent() {

    companion object {
        /** More nesting than the browser has; a deeper tree would see little but its sticky rows. */
        private const val MAX_ROWS = 6

        /** [scrollPane], which shows [tree], with the sticky rows over it. */
        fun install(tree: JTree, scrollPane: JScrollPane): JComponent {
            val sticky = StickyTreeRows(tree, scrollPane)
            val pane = object : JPanel(null) {
                // The rows overlap the scroll pane, so neither can be painted without the other.
                override fun isOptimizedDrawingEnabled() = false

                override fun doLayout() {
                    scrollPane.setBounds(0, 0, width, height)
                    sticky.update()
                }

                override fun getPreferredSize() = scrollPane.preferredSize

                override fun getMinimumSize() = scrollPane.minimumSize
            }
            // The first child is painted last, over the others.
            pane.add(sticky)
            pane.add(scrollPane)
            return pane
        }

        /**
         * The rows to keep in sight with the tree scrolled to [top], outermost first: at each level,
         * the row holding the one under that level's place, as long as that one is nested deeper.
         */
        fun stickyPaths(tree: JTree, top: Int, maxRows: Int = MAX_ROWS): List<TreePath> {
            val paths = mutableListOf<TreePath>()
            // The path length of a row at the top level.
            val topLevel = if (tree.isRootVisible) 1 else 2
            var y = top
            while (paths.size < maxRows) {
                val row = tree.getClosestRowForLocation(0, y)
                val path = (if (row >= 0) tree.getPathForRow(row) else null) ?: break
                val level = paths.size
                if (path.pathCount - topLevel <= level) {
                    break
                }
                val holder = path.withPathCount(topLevel + level)
                if (level > 0 && holder.parentPath != paths.last()) {
                    break
                }
                paths += holder
                y += tree.getPathBounds(holder)?.height ?: break
            }
            return paths
        }

        /**
         * How far the last sticky row, [last], is pushed up by the row after everything it holds,
         * as that row reaches [bottom], the bottom of the sticky rows.
         */
        fun pushUp(tree: JTree, last: TreePath, bottom: Int): Int {
            val row = tree.getClosestRowForLocation(0, bottom)
            val path = (if (row >= 0) tree.getPathForRow(row) else null) ?: return 0
            if (last.isDescendant(path)) {
                return 0
            }
            val height = tree.getPathBounds(last)?.height ?: return 0
            return (bottom - (tree.getRowBounds(row)?.y ?: bottom)).coerceIn(0, height)
        }

        /**
         * Scrolls [tree] further up when it was just scrolled to show [rect] and left it under the
         * sticky rows, which it takes for part of its viewport: far enough for the rows holding it,
         * which are then the sticky rows above it.
         */
        fun uncover(tree: JTree, rect: Rectangle) {
            val viewport = tree.parent as? JViewport ?: return
            val top = viewport.viewPosition.y
            if (rect.y >= top + Layout.of(tree, top).height) {
                return
            }
            val row = tree.getClosestRowForLocation(0, rect.y)
            val path = (if (row >= 0) tree.getPathForRow(row) else null) ?: return
            val topLevel = if (tree.isRootVisible) 1 else 2
            val holders = generateSequence(path.parentPath) { it.parentPath }
                .filter { it.pathCount - topLevel in 0 until MAX_ROWS }
                .sumOf { tree.getPathBounds(it)?.height ?: 0 }
            val newTop = (rect.y - holders).coerceAtLeast(0)
            if (newTop < top) {
                viewport.viewPosition = Point(viewport.viewPosition.x, newTop)
            }
        }

        private fun TreePath.withPathCount(count: Int): TreePath {
            var path = this
            while (path.pathCount > count) {
                path = path.parentPath
            }
            return path
        }
    }

    /** The sticky rows with the tree scrolled to one place. */
    private class Layout(
        /** Outermost first. */
        val paths: List<TreePath>,
        /** How far the last row is moved up, by [pushUp]. */
        val lastPushedUp: Int,
        /** How much of the viewport's top they cover. */
        val height: Int,
    ) {
        companion object {
            fun of(tree: JTree, top: Int): Layout {
                val paths = stickyPaths(tree, top)
                val height = paths.sumOf { tree.getPathBounds(it)?.height ?: 0 }
                val pushedUp = paths.lastOrNull()?.let { pushUp(tree, it, top + height) } ?: 0
                return Layout(paths, pushedUp, height - pushedUp)
            }
        }
    }

    private var paths: List<TreePath> = emptyList()

    private var lastPushedUp = 0

    private var hoveredIndex = -1

    /** Where the mouse is over the rows, or -1, to find the row under it again once they change. */
    private var mouseY = -1

    private var updateScheduled = false

    init {
        isOpaque = true
        isVisible = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val index = indexAt(e.y)
                if (SwingUtilities.isLeftMouseButton(e) && index >= 0) {
                    if (isInExpandControl(index, e.x, e.y)) collapse(index) else goTo(index)
                }
                e.consume()
            }

            override fun mouseMoved(e: MouseEvent) {
                mouseY = e.y
                setHovered(indexAt(e.y))
            }

            override fun mouseExited(e: MouseEvent) {
                mouseY = -1
                setHovered(-1)
            }

            // The rows lie over the tree, which is still what the wheel scrolls.
            override fun mouseWheelMoved(e: MouseWheelEvent) {
                scrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(this@StickyTreeRows, e, scrollPane))
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)

        scrollPane.viewport.addChangeListener { update() }
        // Tree listeners are told before the tree's UI lays the rows out again, so the rows are
        // looked for once it has.
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) = scheduleUpdate()
            override fun treeCollapsed(event: TreeExpansionEvent) = scheduleUpdate()
        })
        tree.model.addTreeModelListener(object : TreeModelListener {
            override fun treeNodesChanged(e: TreeModelEvent) = scheduleUpdate()
            override fun treeNodesInserted(e: TreeModelEvent) = scheduleUpdate()
            override fun treeNodesRemoved(e: TreeModelEvent) = scheduleUpdate()
            override fun treeStructureChanged(e: TreeModelEvent) = scheduleUpdate()
        })
        // A sticky row stands for a row out of sight, which the tree does not repaint here.
        tree.addTreeSelectionListener { repaint() }
        tree.addFocusListener(object : FocusListener {
            override fun focusGained(e: FocusEvent) = repaint()
            override fun focusLost(e: FocusEvent) = repaint()
        })
    }

    private fun scheduleUpdate() {
        if (updateScheduled) {
            return
        }
        updateScheduled = true
        SwingUtilities.invokeLater {
            updateScheduled = false
            update()
        }
    }

    /** Finds the rows for where the tree is scrolled to, and lays them over the top of the viewport. */
    private fun update() {
        val parent = parent ?: return
        val viewport = scrollPane.viewport
        val layout = Layout.of(tree, viewport.viewPosition.y)
        paths = layout.paths
        lastPushedUp = layout.lastPushedUp
        val height = layout.height

        val area = SwingUtilities.convertRectangle(viewport.parent, viewport.bounds, parent)
        // A scroll bar laid over the viewport stays usable.
        val bar = scrollPane.verticalScrollBar
        if (bar.isVisible) {
            val barArea = SwingUtilities.convertRectangle(bar.parent, bar.bounds, parent)
            if (barArea.x > area.x && barArea.x < area.x + area.width) {
                area.width = barArea.x - area.x
            }
        }
        setBounds(area.x, area.y, area.width, height)
        isVisible = height > 0
        hoveredIndex = if (mouseY >= 0) indexAt(mouseY) else -1
        repaint()
    }

    private fun indexAt(y: Int): Int {
        var bottom = 0
        for ((index, path) in paths.withIndex()) {
            bottom += tree.getPathBounds(path)?.height ?: 0
            if (index == paths.lastIndex) {
                bottom -= lastPushedUp
            }
            if (y < bottom) {
                return index
            }
        }
        return -1
    }

    private fun setHovered(index: Int) {
        if (index != hoveredIndex) {
            hoveredIndex = index
            repaint()
        }
    }

    /** Where row [index] is drawn: under the rows before it, the last one pushed up. */
    private fun rowTop(index: Int): Int =
        paths.take(index).sumOf { tree.getPathBounds(it)?.height ?: 0 } - if (index == paths.lastIndex) lastPushedUp else 0

    /** Whether [x], [y] is on the expand control drawn on row [index], as the tree places it. */
    private fun isInExpandControl(index: Int, x: Int, y: Int): Boolean {
        val bounds = tree.getPathBounds(paths[index]) ?: return false
        return TreeUtil.isLocationInExpandControl(tree, paths[index], x + scrollPane.viewport.viewPosition.x, bounds.y + y - rowTop(index))
    }

    /** Selects the row and scrolls it to where it was shown. */
    private fun goTo(index: Int) {
        tree.selectionPath = paths[index]
        scrollToRow(index)
        tree.requestFocusInWindow()
    }

    /** Collapses the row, and keeps it where it was shown rather than leave the tree on what follows it. */
    private fun collapse(index: Int) {
        tree.collapsePath(paths[index])
        scrollToRow(index)
    }

    /** Scrolls row [index] to where it is shown, under the rows that hold it. */
    private fun scrollToRow(index: Int) {
        val bounds = tree.getPathBounds(paths[index]) ?: return
        val above = paths.take(index).sumOf { tree.getPathBounds(it)?.height ?: 0 }
        val viewport = scrollPane.viewport
        val maxY = maxOf(0, viewport.viewSize.height - viewport.extentSize.height)
        viewport.viewPosition = Point(viewport.viewPosition.x, (bounds.y - above).coerceIn(0, maxY))
    }

    override fun paintComponent(g: Graphics) {
        g.color = tree.background
        g.fillRect(0, 0, width, height)

        val viewX = scrollPane.viewport.viewPosition.x
        var y = 0
        for ((index, path) in paths.withIndex()) {
            val bounds = tree.getPathBounds(path) ?: continue
            val rowY = if (index == paths.lastIndex) y - lastPushedUp else y
            val row = g.create(0, rowY, width, bounds.height)
            try {
                if (index == hoveredIndex) {
                    row.color = JBUI.CurrentTheme.Tree.Hover.background(tree.hasFocus())
                    row.fillRect(0, 0, width, bounds.height)
                }
                // The tree's UI paints the rows the clip crosses, which is this one.
                row.translate(-viewX, -bounds.y)
                tree.ui.paint(row, tree)
            } finally {
                row.dispose()
            }
            y += bounds.height
        }

        g.color = JBColor.border()
        g.fillRect(0, height - 1, width, 1)
    }
}
