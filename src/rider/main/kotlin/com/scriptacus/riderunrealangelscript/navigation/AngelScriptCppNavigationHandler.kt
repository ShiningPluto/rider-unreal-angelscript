package com.scriptacus.riderunrealangelscript.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.scriptacus.riderunrealangelscript.lang.psi.AngelScriptFile
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptLanguageServer
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptLanguageServerFactory
import com.scriptacus.riderunrealangelscript.settings.AngelScriptLspSettings
import com.scriptacus.riderunrealangelscript.util.GlobMatcher
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.LSPIJUtils
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.DefinitionParams

/**
 * GotoDeclarationHandler that filters navigation to excluded files and provides C++ navigation.
 *
 * This handler runs BEFORE lsp4ij's default navigation handler and:
 * 1. Blocks navigation to files matching scriptIgnorePatterns (e.g., build artifacts in Saved/)
 * 2. Provides C++ navigation for Unreal Engine symbols (when enabled in settings)
 *
 * Navigation filtering:
 * - Queries LSP server for definition location before navigation occurs
 * - Checks target path against ignore patterns
 * - Returns empty array to block navigation to excluded files
 *
 * C++ navigation (when the target is a C++ symbol):
 * 1. Resolves the AngelScript symbol to a C++ type and member via the language server
 * 2. Finds the declaration in the project's C++ sources through [CppDeclarationSearch] - see
 *    [CppSource] for what "finds" means here, and why Rider's own C++ symbol table is not an option
 * 3. Or hands the jump to the Unreal editor, which opens it in the IDE configured there
 */
class AngelScriptCppNavigationHandler : GotoDeclarationHandler {

    private val LOG = Logger.getInstance(AngelScriptCppNavigationHandler::class.java)

    companion object {
        /**
         * How long the search may run while the user waits for the jump.
         *
         * Measured on a real project, a first lookup takes 1-80 ms and a repeat is instant, so this
         * is generous; what it protects against is the cold case where a dozen engine headers have
         * to come off disk, which finishes in the background instead.
         */
        private const val SYNCHRONOUS_SEARCH_BUDGET_MS = 250L
    }

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?
    ): Array<PsiElement>? {
        // Only handle AngelScript files
        if (sourceElement == null || editor == null) return null
        val psiFile = sourceElement.containingFile
        if (psiFile !is AngelScriptFile) return null

        // Don't compete with LSP navigation if indices aren't ready
        val project = editor.project ?: return null
        if (DumbService.isDumb(project)) {
            return null
        }

        try {
            // First, check if navigation target is an excluded file
            val virtualFile = psiFile.virtualFile ?: return null
            val document = editor.document
            val lineNumber = document.getLineNumber(offset)
            val lineStartOffset = document.getLineStartOffset(lineNumber)
            val character = offset - lineStartOffset
            val position = Position(lineNumber, character)
            val lspUri = LSPIJUtils.toUri(virtualFile).toString()

            if (shouldBlockNavigation(project, lspUri, position)) {
                // Block navigation by returning empty array
                LOG.info("Blocked navigation to excluded file")
                return emptyArray()
            }

            // Try to get C++ symbol info with a short timeout
            // This blocks briefly to check if this is a C++ symbol before returning null
            val cppSymbol = getCppSymbolWithTimeout(project, lspUri, position, 300)

            if (cppSymbol != null) {
                // This is a C++ symbol - handle navigation
                val logMsg = if (cppSymbol.symbolName.isEmpty()) {
                    "C++ class: ${cppSymbol.className}"
                } else {
                    "C++ symbol: ${cppSymbol.className}.${cppSymbol.symbolName}"
                }
                LOG.info(logMsg)

                // Answer here when the search is quick enough, which it is once a symbol has been
                // looked up before. Handing the platform a real target is what keeps it from
                // reporting "Cannot find declaration to go to" while our own jump is on its way -
                // returning null means no handler found anything, whatever happens afterwards.
                val target = findDeclarationWithin(project, cppSymbol, SYNCHRONOUS_SEARCH_BUDGET_MS)
                if (target != null) {
                    LOG.info("Found ${cppSymbol.describe()} in ${target.file.path}")
                    navigationTarget(psiFile, target, cppSymbol)?.let { return arrayOf(it) }
                }

                // Too slow, or a strategy that goes through the Unreal editor: finish in the
                // background and navigate from there.
                ApplicationManager.getApplication().executeOnPooledThread {
                    performCppNavigation(project, lspUri, position, cppSymbol)
                }

                return null
            }

        } catch (e: Exception) {
            LOG.debug("Error in C++ navigation handler", e)
        }

        // Not a C++ symbol or error occurred - let lsp4ij's default navigation handler take over
        return null
    }

    /**
     * Check if navigation should be blocked because the target is in an excluded directory.
     * Queries the LSP server for the definition location and checks against ignore patterns.
     *
     * @return true if navigation should be blocked, false otherwise
     */
    private fun shouldBlockNavigation(
        project: Project,
        uri: String,
        position: Position
    ): Boolean {
        try {
            // Check if we have any ignore patterns configured
            val settings = AngelScriptLspSettings.getInstance()
            val ignorePatterns = settings.state.scriptIgnorePatterns
            if (ignorePatterns.isEmpty()) {
                return false
            }

            // Query LSP server for definition location with a short timeout
            val manager = LanguageServerManager.getInstance(project)
            val serverItemFuture = manager.getLanguageServer(AngelScriptLanguageServerFactory.SERVER_ID)
            val serverItem = serverItemFuture.get(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                ?: return false

            val server = serverItem.server as? org.eclipse.lsp4j.services.LanguageServer ?: return false

            // Request definition
            val textDocumentIdentifier = TextDocumentIdentifier(uri)
            val definitionParams = DefinitionParams(textDocumentIdentifier, position)
            val definitionFuture = server.textDocumentService.definition(definitionParams)
            val result = definitionFuture.get(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                ?: return false

            // Check if any of the returned locations are in excluded directories
            // The result is Either<List<Location>, List<LocationLink>>
            val locationsAny: List<*> = if (result.isLeft) {
                // Left side: List<Location>
                result.left as? List<*> ?: emptyList<Any>()
            } else {
                // Right side: List<LocationLink> - we ignore these since we only need URIs
                emptyList<Any>()
            }
            val locations: List<org.eclipse.lsp4j.Location> = locationsAny.mapNotNull { it as? org.eclipse.lsp4j.Location }

            // Check each location against ignore patterns
            for (location in locations) {
                val targetUri = location.uri
                // Convert file:// URI to path
                val targetPath = java.net.URI(targetUri).path

                if (GlobMatcher.matchesAny(targetPath, ignorePatterns)) {
                    LOG.info("Navigation target matches ignore pattern: $targetPath")
                    return true
                }
            }

            return false

        } catch (e: java.util.concurrent.TimeoutException) {
            LOG.debug("Timeout checking navigation target, allowing navigation")
            return false
        } catch (e: Exception) {
            LOG.debug("Error checking navigation target: ${e.message}, allowing navigation")
            return false
        }
    }

    /**
     * Get C++ symbol information with a timeout.
     * Blocks for up to timeoutMs waiting for the LSP server response.
     * Returns null if timeout expires or no C++ symbol found.
     */
    private fun getCppSymbolWithTimeout(
        project: Project,
        uri: String,
        position: Position,
        timeoutMs: Long
    ): CppSymbolInfo? {
        try {
            val manager = LanguageServerManager.getInstance(project)
            val serverItemFuture = manager.getLanguageServer(AngelScriptLanguageServerFactory.SERVER_ID)

            // Wait for server with timeout
            val serverItem = serverItemFuture.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                ?: return null

            val server = serverItem.server as? AngelScriptLanguageServer ?: return null

            val params = mapOf(
                "uri" to uri,
                "position" to mapOf(
                    "line" to position.line,
                    "character" to position.character
                )
            )

            // Wait for C++ symbol info with timeout
            val cppSymbolResponse = server.getCppSymbol(params)
                .get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                ?: return null

            val className = cppSymbolResponse["className"] as? String ?: ""
            val symbolName = cppSymbolResponse["symbolName"] as? String ?: ""
            val ancestry = (cppSymbolResponse["ancestry"] as? List<*>)?.filterIsInstance<String>().orEmpty()

            // If LSP returns className="" for class navigation, swap values
            return if (className.isEmpty() && symbolName.isNotEmpty()) {
                CppSymbolInfo(symbolName, "", ancestry)
            } else {
                CppSymbolInfo(className, symbolName, ancestry)
            }

        } catch (e: java.util.concurrent.TimeoutException) {
            LOG.debug("Timeout waiting for C++ symbol info")
            return null
        } catch (e: Exception) {
            LOG.debug("Error getting C++ symbol info", e)
            return null
        }
    }

    /**
     * Perform C++ navigation based on the configured strategy.
     */
    private fun performCppNavigation(
        project: Project,
        uri: String,
        position: Position,
        cppSymbol: CppSymbolInfo
    ) {
        // Get navigation strategy from settings
        val settings = AngelScriptLspSettings.getInstance()
        val strategy = settings.state.cppNavigationStrategy

        when (strategy) {
            AngelScriptLspSettings.CppNavigationStrategy.TEXT_SEARCH -> {
                LOG.info("Looking for the declaration in the project's C++ sources")
                navigateInRider(project, cppSymbol)
            }
            AngelScriptLspSettings.CppNavigationStrategy.UNREAL_ENGINE -> {
                LOG.info("Using Unreal Engine navigation strategy")
                delegateToUnrealEngine(project, uri, position)
            }
        }
    }

    /**
     * Delegate navigation to Unreal Engine via LSP implementation request.
     * The server's onImplementation handler will send GoToDefinition to UE.
     */
    private fun delegateToUnrealEngine(
        project: Project,
        uri: String,
        position: Position
    ) {
        try {
            val manager = LanguageServerManager.getInstance(project)
            val serverItemFuture = manager.getLanguageServer(AngelScriptLanguageServerFactory.SERVER_ID)

            serverItemFuture.thenAccept { serverItem ->
                if (serverItem != null) {
                    val server = serverItem.server as? org.eclipse.lsp4j.services.LanguageServer
                    if (server != null) {
                        val textDocumentIdentifier = org.eclipse.lsp4j.TextDocumentIdentifier(uri)
                        val implementationParams = org.eclipse.lsp4j.ImplementationParams(textDocumentIdentifier, position)

                        server.textDocumentService.implementation(implementationParams).thenAccept { result ->
                            LOG.info("Implementation request completed, result: $result")
                        }.exceptionally { throwable ->
                            LOG.warn("Implementation request failed: ${throwable.message}")
                            null
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to delegate to Unreal Engine", e)
        }
    }

    /**
     * Find the C++ declaration for [cppSymbol] and open it, however long it takes.
     *
     * The fallback for when the search the user waited for ran out of its budget: same search, no
     * deadline, off the EDT, and it navigates on its own once it is done.
     */
    private fun navigateInRider(project: Project, cppSymbol: CppSymbolInfo) {
        val target = try {
            ReadAction.nonBlocking<CppTarget?> { CppDeclarationSearch.find(project, cppSymbol) }
                .executeSynchronously()
        } catch (e: Exception) {
            LOG.warn("C++ declaration search failed for ${cppSymbol.describe()}", e)
            null
        }

        if (target == null) {
            LOG.info("No C++ declaration found for ${cppSymbol.describe()}")
            return
        }

        LOG.info("Navigating to ${cppSymbol.describe()} in ${target.file.path}")
        ApplicationManager.getApplication().invokeLater {
            openAt(project, target.file, target.offset, true)
        }
    }

    /** [CppDeclarationSearch.find] with a wall-clock budget, for use while the user waits. */
    private fun findDeclarationWithin(project: Project, cppSymbol: CppSymbolInfo, budgetMs: Long): CppTarget? {
        val deadline = System.nanoTime() + budgetMs * 1_000_000
        return try {
            ApplicationManager.getApplication().runReadAction<CppTarget?> {
                CppDeclarationSearch.find(project, cppSymbol, deadline)
            }
        } catch (e: Exception) {
            LOG.debug("C++ declaration search failed: ${e.message}")
            null
        }
    }

    /**
     * Wraps a found declaration as something the platform can navigate to.
     *
     * C++ has no PSI on Rider's frontend - the language lives in the backend - so there is no
     * element to return. The file and offset are enough: the platform navigates any target that is
     * [com.intellij.pom.Navigatable], and the point of returning one at all is that "go to
     * declaration" then knows a declaration was found.
     *
     * The parent is the target's own file, for two reasons: the platform short-circuits a target
     * whose containing file is the one the user is in into a jump inside the current editor, and
     * the navigation path that does not call [com.intellij.pom.Navigatable.navigate] builds its
     * request from the containing file and the text offset instead. Without a file for the target
     * there is nothing safe to return, and the background jump handles it as before.
     */
    private fun navigationTarget(sourceFile: PsiElement, target: CppTarget, cppSymbol: CppSymbolInfo): PsiElement? {
        val targetFile = PsiManager.getInstance(sourceFile.project).findFile(target.file) ?: return null
        return CppDeclarationTarget(targetFile, target.file, target.offset, cppSymbol.describe())
    }

    private class CppDeclarationTarget(
        private val parent: PsiElement,
        private val file: VirtualFile,
        private val offset: Int,
        private val label: String
    ) : FakePsiElement() {

        override fun getParent(): PsiElement = parent
        override fun getName(): String = label
        override fun getPresentableText(): String = label
        override fun getLocationString(): String = file.presentableUrl
        override fun getTextOffset(): Int = offset
        // Rider's own protocol-backed targets (ProtocolNavigationItem) answer all four of these;
        // between them they cover both routes the platform takes to open a target.
        override fun getTextRange(): TextRange = TextRange.from(offset, 0)
        override fun getTextLength(): Int = 0
        override fun canNavigate(): Boolean = file.isValid
        override fun canNavigateToSource(): Boolean = canNavigate()

        override fun navigate(requestFocus: Boolean) = openAt(project, file, offset, requestFocus)
    }

    private fun CppSymbolInfo.describe(): String =
        if (symbolName.isEmpty()) className else "$className::$symbolName"
}

/**
 * Opens [file] with the caret at [offset]. Navigatable.navigate is override-only API, which the
 * Marketplace verifier reports when a plugin calls it; opening the editor directly does the same.
 */
private fun openAt(project: Project, file: VirtualFile, offset: Int, focus: Boolean) {
    FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file, offset), focus)
}
