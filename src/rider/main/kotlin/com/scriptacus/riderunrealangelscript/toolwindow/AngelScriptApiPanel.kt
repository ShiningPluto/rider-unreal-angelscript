package com.scriptacus.riderunrealangelscript.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptLanguageServerFactory
import com.scriptacus.riderunrealangelscript.services.AngelScriptApiService
import com.scriptacus.riderunrealangelscript.settings.AngelScriptLspSettings
import com.scriptacus.riderunrealangelscript.status.AngelScriptUnrealStatus
import com.scriptacus.riderunrealangelscript.typedb.ScriptDeclarations
import com.scriptacus.riderunrealangelscript.typedb.UnrealMember
import com.scriptacus.riderunrealangelscript.typedb.UnrealNamespace
import com.scriptacus.riderunrealangelscript.typedb.UnrealSearchResult
import com.scriptacus.riderunrealangelscript.typedb.UnrealType
import com.scriptacus.riderunrealangelscript.typedb.UnrealTypeDatabase
import com.scriptacus.riderunrealangelscript.typedb.UnrealTypeDatabaseService
import java.awt.BorderLayout
import java.awt.event.HierarchyEvent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * The AngelScript API browser: the C++ API Unreal binds to script, with every class's own and
 * inherited members, a search box, and the selected item's details below.
 *
 * Unreal's API comes from the type database the language server saved ([UnrealTypeDatabaseService]),
 * because the server's own API requests never list a class's members. What script declares is not
 * in that database, so it is asked from the server, whole, in one request ([ScriptDeclarations]).
 * Everything after that - the tree, search and details - is worked out here.
 *
 * Without a database the browser only says why: there would be no Unreal API to put script's beside.
 */
class AngelScriptApiPanel(
    private val project: Project,
    /** Lives as long as the tool window. */
    private val parentDisposable: Disposable,
) : JPanel(BorderLayout()) {

    companion object {
        /** Enough to find something by scrolling; past that the search wants narrowing. */
        private const val SEARCH_RESULT_LIMIT = 500

        /** How long typing has to pause before anything is searched. */
        private const val SEARCH_DELAY_MS = 200

        /** A single character matches too much to be useful, as in upstream's own browser. */
        private const val MIN_SEARCH_LENGTH = 2
    }

    private val databaseService = UnrealTypeDatabaseService.getInstance(project)
    private val apiService = project.service<AngelScriptApiService>()

    private val rootNode = DefaultMutableTreeNode()

    // Asks allowsChildren, so a row shows as expandable before its children are built.
    private val treeModel = DefaultTreeModel(rootNode, true)
    private val tree = Tree(treeModel)
    private val detailsPane = JEditorPane()
    private val searchField = SearchTextField(true)
    private val searchAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, parentDisposable)

    // Only touched on the EDT, like everything below.

    private var state: UnrealTypeDatabaseService.State = databaseService.state

    /** What Unreal's API is built from, or null while there is none. */
    private var database: UnrealTypeDatabase? = null

    /**
     * What script declares, as the server last listed it for [database]; null until it has. Asked
     * again with every new database, whenever the browser is shown again, and on Refresh, since
     * script changes without the database doing so.
     */
    private var script: ScriptDeclarations? = null

    /** The server's answer [script] was read from, to tell whether a new one changes anything. */
    private var scriptReply: Map<String, Any>? = null

    /** The database script declarations are being asked for, so that they are asked once at a time. */
    private var scriptRequestedFor: UnrealTypeDatabase? = null

    /** Bumped whenever the tree is rebuilt, so that answers meant for an older one are dropped. */
    private var generation = 0

    init {
        searchField.textEditor.emptyText.text = "Search API... (Type.member narrows to one type)"
        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = searchTextChanged()
            override fun removeUpdate(e: DocumentEvent) = searchTextChanged()
            override fun changedUpdate(e: DocumentEvent) = searchTextChanged()
        })

        val refreshAction = object : AnAction("Refresh", "Read the type database and ask for script declarations again", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                startServerIfStopped()
                databaseService.reload()
                database?.let(::fetchScriptDeclarations)
            }
        }
        val refreshButton = ActionButton(
            refreshAction,
            Presentation().apply {
                icon = AllIcons.Actions.Refresh
                text = "Refresh"
            },
            "AngelScriptApiToolbar",
            ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE
        )

        val toolbarPanel = JPanel(BorderLayout())
        toolbarPanel.add(searchField, BorderLayout.CENTER)
        toolbarPanel.add(refreshButton, BorderLayout.EAST)

        tree.cellRenderer = ApiTreeCellRenderer()
        tree.isRootVisible = false
        tree.showsRootHandles = true
        TreeSpeedSearch.installOn(tree, true) { path -> path.lastPathComponent.toString() }

        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                (event.path.lastPathComponent as? ApiTreeNode)?.let(::loadChildren)
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) {
            }
        })
        tree.addTreeSelectionListener { event ->
            showDetails(event.newLeadSelectionPath?.lastPathComponent as? ApiTreeNode)
        }

        detailsPane.editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        detailsPane.isEditable = false
        detailsPane.border = JBUI.Borders.empty(4, 8)
        setDetails(null)

        val splitter = JBSplitter(true, 0.6f)
        splitter.firstComponent = JBScrollPane(tree)
        splitter.secondComponent = JBScrollPane(detailsPane)

        add(toolbarPanel, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        // The database takes tens of megabytes once read, so nothing is read before the panel is seen.
        var activated = false
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() == 0L || !isShowing) {
                return@addHierarchyListener
            }
            if (!activated) {
                activated = true
                activate()
            } else {
                // Script may have changed while the browser was hidden.
                database?.let(::fetchScriptDeclarations)
            }
        }
    }

    private fun activate() {
        project.messageBus.connect(parentDisposable).subscribe(
            UnrealTypeDatabaseService.TOPIC,
            UnrealTypeDatabaseService.Listener { state ->
                ApplicationManager.getApplication().invokeLater({ show(state) }, project.disposed)
            }
        )
        startServerIfStopped()
        databaseService.activate()
        show(databaseService.state, force = true)
    }

    /**
     * Opening the browser has always started the language server, because the requests it sent
     * started it on the way. Nothing is asked of a stopped server here, so it is started explicitly.
     */
    private fun startServerIfStopped() {
        val manager = LanguageServerManager.getInstance(project)
        val status = manager.getServerStatus(AngelScriptLanguageServerFactory.SERVER_ID)
        if (status == ServerStatus.none || status == ServerStatus.stopped) {
            manager.start(AngelScriptLanguageServerFactory.SERVER_ID)
        }
    }

    private fun show(state: UnrealTypeDatabaseService.State, force: Boolean = false) {
        val db = (state as? UnrealTypeDatabaseService.State.Loaded)?.database
        // The same database, or the same reason for having none: what is on screen still holds.
        if (!force && db === database && (db != null || state == this.state)) {
            return
        }
        this.state = state
        database = db
        script = null
        scriptReply = null
        rebuildTree(0)
    }

    /** Why there is no Unreal API to show, as rows in place of it. */
    private fun stateMessages(): List<String> = when (val current = state) {
        is UnrealTypeDatabaseService.State.Unavailable -> when (current.status) {
            AngelScriptUnrealStatus.SERVER_STOPPED ->
                listOf("The AngelScript language server is not running", "Refresh starts it")
            AngelScriptUnrealStatus.NOT_CONFIGURED ->
                listOf("The language server is starting")
            AngelScriptUnrealStatus.LOADING_TYPES ->
                listOf("Receiving the type database from the Unreal editor...")
            else -> listOf(
                "No Unreal type database: no Unreal editor has connected",
                if (AngelScriptLspSettings.getInstance().state.useCachedTypeDatabase) {
                    "Start the Unreal editor to load it"
                } else {
                    "Start the Unreal editor, or turn on the saved type database in Settings | Languages & Frameworks | AngelScript"
                }
            )
        }
        UnrealTypeDatabaseService.State.Loading -> listOf("Reading the type database...")
        is UnrealTypeDatabaseService.State.Failed -> listOf("Could not read the saved type database", current.reason)
        is UnrealTypeDatabaseService.State.Loaded -> emptyList()
    }

    private fun searchTextChanged() {
        // Typing the first letter leaves the tree as it is, expanded rows included, until the second.
        if (searchField.text.trim().length in 1 until MIN_SEARCH_LENGTH) {
            return
        }
        rebuildTree(SEARCH_DELAY_MS)
    }

    /**
     * Shows the whole API, or what matches the search box once typing has paused for [delayMs].
     * [keepExpansion] when the API itself is what changed: the rows that were expanded, and the one
     * selected, are found again by their text.
     */
    private fun rebuildTree(delayMs: Int, keepExpansion: Boolean = false) {
        searchAlarm.cancelAllRequests()
        val current = ++generation
        val query = searchField.text.trim()
        val db = database

        if (db == null) {
            setRoots(stateMessages().map { ApiNode.Message(it) })
            return
        }

        // Unreal's API does not wait for script's; the tree is built again once that arrives.
        val declarations = script ?: ScriptDeclarations.NONE.also { fetchScriptDeclarations(db) }

        if (query.length < MIN_SEARCH_LENGTH) {
            val expanded = if (keepExpansion) expandedRows() else emptyList()
            val selected = if (keepExpansion) tree.selectionPath?.let(::rowTexts) else null
            setRoots(ApiTreeStructure.roots(db, declarations))
            tree.emptyText.text = "The type database is empty"
            restoreRows(expanded, selected)
            return
        }

        searchAlarm.addRequest({
            val result = db.search(query, SEARCH_RESULT_LIMIT)
            val scriptResult = declarations.search(query, SEARCH_RESULT_LIMIT)
            ApplicationManager.getApplication().invokeLater({
                if (current == generation) {
                    showSearchResults(query, result, scriptResult)
                }
            }, project.disposed)
        }, delayMs)
    }

    private fun showSearchResults(query: String, result: UnrealSearchResult, scriptResult: UnrealSearchResult) {
        setRoots(searchHits(result, script = false))
        tree.emptyText.text = "Nothing matches \"$query\""

        val scriptHits = searchHits(scriptResult, script = true)
        if (scriptHits.isNotEmpty()) {
            val group = ApiTreeNode(ApiNode.Group(GroupKind.SCRIPT_RESULTS, scriptResult.total), true)
            group.childrenLoaded = true
            scriptHits.forEach { group.add(treeNode(it)) }
            rootNode.add(group)
            treeModel.nodesWereInserted(rootNode, intArrayOf(rootNode.childCount - 1))
            tree.expandPath(TreePath(group.path))
        }
    }

    private fun searchHits(result: UnrealSearchResult, script: Boolean): List<ApiNode> {
        val hits = result.hits.map { hit ->
            when (hit) {
                is UnrealType -> ApiNode.Type(hit)
                is UnrealNamespace -> if (script) ApiNode.ScriptNamespace(hit) else ApiNode.Namespace(hit)
                is UnrealMember -> ApiNode.Member(hit, qualified = true)
            }
        }
        val more = result.total - result.hits.size
        return hits + if (more > 0) listOf(ApiNode.Message("$more more - narrow the search to see them")) else emptyList()
    }

    /** The text of every expanded row's path, parents before their children. */
    private fun expandedRows(): List<List<String>> =
        tree.getExpandedDescendants(TreePath(rootNode))?.toList().orEmpty()
            .map(::rowTexts)
            .filter { it.isNotEmpty() }
            .sortedBy { it.size }

    private fun rowTexts(path: TreePath): List<String> = path.path.drop(1).map { it.toString() }

    private fun restoreRows(expanded: List<List<String>>, selected: List<String>?) {
        // Expanding a row builds its children, so a parent's are there before its children are looked for.
        expanded.forEach { texts -> findRow(texts)?.let(tree::expandPath) }
        selected?.let(::findRow)?.let { tree.selectionPath = it }
    }

    private fun findRow(texts: List<String>): TreePath? {
        var node: DefaultMutableTreeNode = rootNode
        for (text in texts) {
            node = node.children().asSequence().firstOrNull { it.toString() == text } as? DefaultMutableTreeNode ?: return null
        }
        return TreePath(node.path)
    }

    private fun setRoots(nodes: List<ApiNode>) {
        rootNode.removeAllChildren()
        nodes.forEach { rootNode.add(treeNode(it)) }
        treeModel.reload()
        setDetails(null)
    }

    private fun treeNode(node: ApiNode): ApiTreeNode =
        ApiTreeNode(node, database?.let { ApiTreeStructure.isExpandable(node, it, script ?: ScriptDeclarations.NONE) } ?: false)

    private fun loadChildren(treeNode: ApiTreeNode) {
        if (treeNode.childrenLoaded) {
            return
        }
        treeNode.childrenLoaded = true
        val db = database ?: return
        setChildren(treeNode, ApiTreeStructure.children(treeNode.node, db, script ?: ScriptDeclarations.NONE))
    }

    private fun setChildren(parent: ApiTreeNode, children: List<ApiNode>) {
        val path = TreePath(parent.path)
        val wasExpanded = tree.isExpanded(path)
        parent.removeAllChildren()
        children.forEach { parent.add(treeNode(it)) }
        treeModel.nodeStructureChanged(parent)
        if (wasExpanded) {
            tree.expandPath(path)
        }
    }

    /**
     * Asks the server what script declares, and builds the tree again when that changed. Once at a
     * time: the tree may be built again while an answer is awaited.
     */
    private fun fetchScriptDeclarations(db: UnrealTypeDatabase) {
        if (scriptRequestedFor === db) {
            return
        }
        scriptRequestedFor = db
        apiService.getScriptDeclarations { reply ->
            val declarations = reply?.let { ScriptDeclarations.parse(it, db) }
            ApplicationManager.getApplication().invokeLater({
                if (scriptRequestedFor === db) {
                    scriptRequestedFor = null
                }
                // Without an answer, script stays as it is, and is asked for again with the next rebuild.
                if (declarations == null || db !== database || (script != null && reply == scriptReply)) {
                    return@invokeLater
                }
                script = declarations
                scriptReply = reply
                rebuildTree(0, keepExpansion = true)
            }, project.disposed)
        }
    }

    private fun showDetails(treeNode: ApiTreeNode?) {
        setDetails(treeNode?.node?.let { node -> database?.let { ApiDetailsHtml.of(node, it) } })
    }

    private fun setDetails(body: String?) {
        detailsPane.text = "<html><body>${body ?: "<p>Select an item to see its details.</p>"}</body></html>"
        detailsPane.caretPosition = 0
    }
}
