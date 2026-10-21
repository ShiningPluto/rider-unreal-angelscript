package com.scriptacus.riderunrealangelscript.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
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
 * 2. Finds the declaration in the project's C++ sources - see [CppSource] for what "finds" means
 *    here, and why Rider's own C++ symbol table is not an option
 * 3. Or hands the jump to the Unreal editor, which opens it in the IDE configured there
 */
class AngelScriptCppNavigationHandler : GotoDeclarationHandler {

    private val LOG = Logger.getInstance(AngelScriptCppNavigationHandler::class.java)

    companion object {
        /**
         * The file a symbol was last found in, keyed by `class|member`.
         *
         * Only the file is remembered, never the offset: an edit moves declarations around, and
         * re-reading one known file is cheap next to searching the engine again.
         */
        private val declarationFileCache = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** How many files a single lookup is willing to open, in ranked order. */
        private const val MAX_CANDIDATES = 12

        /** How many files the content index may hand back before the ranking has to decide. */
        private const val MAX_CONTENT_HITS = 200

        /**
         * The cap when two index answers are being intersected. Higher, because nothing is read
         * here - the names are collected only to find which files answer to both words, and a type
         * as common as `FTransformFragment` is named by more than a couple of hundred files.
         */
        private const val MAX_INDEX_HITS = 5000

        /** Headers this large are generated blobs, not something to navigate into. */
        private const val MAX_FILE_BYTES = 4L * 1024 * 1024

        /**
         * How long the search may run while the user waits for the jump.
         *
         * Measured on a real project, a first lookup takes 1-80 ms and a repeat is instant, so this
         * is generous; what it protects against is the cold case where a dozen engine headers have
         * to come off disk, which finishes in the background instead.
         */
        private const val SYNCHRONOUS_SEARCH_BUDGET_MS = 250L

        /** Clear all caches. Called when project structure changes. */
        fun clearCaches() {
            declarationFileCache.clear()
        }
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
            ReadAction.nonBlocking<CppTarget?> { findDeclaration(project, cppSymbol, Long.MAX_VALUE) }
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

    /** Where a declaration was found: a file and an offset into its text. */
    private data class CppTarget(val file: VirtualFile, val offset: Int)

    /** What one file had to offer: the member itself, and where its type is declared. */
    private data class FileMatch(val member: CppTarget?, val type: CppTarget?)

    /**
     * Find where [cppSymbol] is declared.
     *
     * In order, because each step is less certain than the one before it:
     *
     * 1. files that name both the type and the member - where a member of that type is declared,
     *    and a far smaller set than either name alone: `FTransformFragment` is mentioned by
     *    hundreds of files in a Mass-based project, but with `SetTransform` beside it by a handful;
     * 2. files that look like they declare the type at all;
     * 3. the member declared outside its type - an inherited one, or one Unreal binds onto an
     *    engine type from its own sources - where the class the declaration sits in has to be the
     *    type or one of its bases;
     * 4. the type's own declaration, when its member cannot be found anywhere. Not the line that
     *    was asked for, but the right type in the right file, which beats a same-named member of
     *    something unrelated.
     *
     * Steps 1 and 2 confirm the type is declared in the file and search the member inside that
     * type's body, so nothing else in the same header can be mistaken for it.
     *
     * [deadline] is `System.nanoTime()` past which the search gives up, for the caller that runs it
     * while the user waits.
     */
    private fun findDeclaration(project: Project, cppSymbol: CppSymbolInfo, deadline: Long): CppTarget? {
        val typeNames = typeSearchNames(cppSymbol.className)
        val memberNames = memberSearchNames(cppSymbol.symbolName)
        val cacheKey = "${cppSymbol.className}|${cppSymbol.symbolName}"

        declarationFileCache[cacheKey]?.let { path ->
            val cached = LocalFileSystem.getInstance().findFileByPath(path)
            val target = cached?.let { file ->
                val match = inspect(file, typeNames, memberNames)
                match?.member ?: match?.type ?: memberOutsideItsType(file, cppSymbol, typeNames, memberNames)
            }
            if (target != null) return target
            declarationFileCache.remove(cacheKey)
        }

        var typeOnly: CppTarget? = null

        for (files in listOf(
            candidatesNaming(project, typeNames, memberNames),
            typeNames.flatMap { candidateFiles(project, it) }
        )) {
            for (file in files) {
                if (System.nanoTime() > deadline) return null

                val match = inspect(file, typeNames, memberNames) ?: continue
                match.member?.let { target ->
                    declarationFileCache[cacheKey] = file.path
                    return target
                }
                if (typeOnly == null) typeOnly = match.type
            }
        }

        for (member in memberNames) {
            for (file in candidateFiles(project, member)) {
                if (System.nanoTime() > deadline) return null

                memberOutsideItsType(file, cppSymbol, typeNames, listOf(member))?.let { target ->
                    declarationFileCache[cacheKey] = file.path
                    return target
                }
            }
        }

        typeOnly?.let { declarationFileCache[cacheKey] = it.file.path }
        return typeOnly
    }

    /**
     * A declaration of the member in a file that does not declare the type.
     *
     * The class a declaration sits in is the only thing that distinguishes the member that was
     * asked for from another class's member of the same name, so that is what is checked here:
     * either the enclosing class is the type or one of its bases, or there is no enclosing class
     * and the file is about the type anyway, which is what a binding or a mixin looks like.
     */
    private fun memberOutsideItsType(
        file: VirtualFile,
        cppSymbol: CppSymbolInfo,
        typeNames: List<String>,
        memberNames: List<String>
    ): CppTarget? {
        val source = loadSource(file) ?: return null
        val declaring = (cppSymbol.ancestry + typeNames).toSet()

        for (member in memberNames) {
            val offset = source.findMemberDeclaration(null, member).takeIf { it >= 0 }
            if (offset != null) {
                val enclosing = source.enclosingTypeAt(offset)
                val acceptable = when {
                    enclosing != null -> enclosing in declaring
                    declaring.isEmpty() -> true
                    else -> declaring.any { source.mentions(it) }
                }
                if (acceptable) return CppTarget(file, offset)
            }

            val bound = source.findScriptBinding(member)
            if (bound >= 0) return CppTarget(file, bound)
        }
        return null
    }

    /** [findDeclaration] with a wall-clock budget, for use while the user waits. */
    private fun findDeclarationWithin(project: Project, cppSymbol: CppSymbolInfo, budgetMs: Long): CppTarget? {
        val deadline = System.nanoTime() + budgetMs * 1_000_000
        return try {
            ApplicationManager.getApplication().runReadAction<CppTarget?> {
                findDeclaration(project, cppSymbol, deadline)
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

    /**
     * The C++ names a script type can have.
     *
     * UE5 moved the core math types to large-world coordinates, where `FVector` is an alias for
     * `UE::Math::TVector<double>` produced by a macro - `Vector.h` declares `TVector` and the name
     * `FVector` appears nowhere in it. Looking for the template as well lands on the real
     * definition instead of failing.
     */
    private fun typeSearchNames(className: String): List<String> = when {
        className.isEmpty() -> emptyList()
        className.length > 1 && className[0] == 'F' && className[1].isUpperCase() ->
            listOf(className, "T" + className.substring(1))

        else -> listOf(className)
    }

    /**
     * The C++ names a script member can have.
     *
     * AngelScript exposes C++ accessors as properties: `Actor.ActorLocation` in script is
     * `GetActorLocation()` in C++, and the language server reports the script spelling, so the
     * plain name may not appear in any header.
     */
    private fun memberSearchNames(symbolName: String): List<String> = when {
        symbolName.isEmpty() -> emptyList()
        symbolName.startsWith("Get") || symbolName.startsWith("Set") -> listOf(symbolName)
        else -> listOf(symbolName, "Get$symbolName", "Set$symbolName")
    }

    /**
     * The files worth opening for [name], best first.
     *
     * Ranking only decides the order; the caller confirms the declaration is actually in the file
     * before navigating, so a wrong guess costs a file read rather than a wrong jump.
     */
    private fun candidateFiles(project: Project, name: String): List<VirtualFile> {
        if (name.isEmpty()) return emptyList()

        val scope = GlobalSearchScope.allScope(project)
        val byName = CppFileRanking.likelyFileNames(name)
            .flatMap { FilenameIndex.getVirtualFilesByName(it, scope) }

        return (byName + filesMentioning(project, name, scope))
            .asSequence()
            .distinct()
            .filter { CppFileRanking.isCppSource(it.name) && !CppFileRanking.isBuildArtifact(it.path) }
            .sortedBy { CppFileRanking.rank(it.path, name) }
            .take(MAX_CANDIDATES)
            .toList()
    }

    /**
     * The files that name a type and one of its members, best first.
     *
     * Asking the index for two words and keeping what both answers have in common is what makes
     * the declaring file findable at all when neither its name nor its path says anything: hundreds
     * of files name `FTransformFragment`, but only a handful name `SetTransform` as well, and one
     * of those is the header that declares it.
     */
    private fun candidatesNaming(project: Project, typeNames: List<String>, memberNames: List<String>): List<VirtualFile> {
        if (typeNames.isEmpty() || memberNames.isEmpty()) return emptyList()

        val scope = GlobalSearchScope.allScope(project)
        val withMember = memberNames.flatMap { filesMentioning(project, it, scope, MAX_INDEX_HITS) }.toSet()
        if (withMember.isEmpty()) return emptyList()

        return typeNames.asSequence()
            .flatMap { typeName ->
                filesMentioning(project, typeName, scope, MAX_INDEX_HITS)
                    .filter { it in withMember }
                    .sortedBy { CppFileRanking.rank(it.path, typeName) }
            }
            .distinct()
            .take(MAX_CANDIDATES)
            .toList()
    }

    /**
     * Files whose contents mention [word].
     *
     * Rider keeps C++ symbols in its backend, where a plugin cannot reach them, so this is the
     * frontend's own word index - the one "Find in Files" runs on. If it has nothing to say about
     * C++ files the result is empty and the file-name candidates carry the lookup on their own,
     * which is how this worked before.
     */
    private fun filesMentioning(
        project: Project,
        word: String,
        scope: GlobalSearchScope,
        limit: Int = MAX_CONTENT_HITS
    ): List<VirtualFile> {
        val found = ArrayList<VirtualFile>()
        try {
            PsiSearchHelper.getInstance(project).processCandidateFilesForText(
                scope,
                UsageSearchContext.ANY,
                true,
                word
            ) { file ->
                if (CppFileRanking.isCppSource(file.name) && !CppFileRanking.isBuildArtifact(file.path)) {
                    found.add(file)
                }
                found.size < limit
            }
        } catch (e: Exception) {
            LOG.debug("Content search for $word failed: ${e.message}")
        }
        return found
    }

    /**
     * What [file] has for the symbol: the type's declaration if it declares one of [owners], and
     * the member's declaration if it is inside that type's body.
     *
     * Null when the file declares none of the owners, which is most of them - the point of going
     * through the type first is that a member found this way cannot belong to another class.
     */
    private fun inspect(file: VirtualFile, owners: List<String>, members: List<String>): FileMatch? {
        val source = loadSource(file) ?: return null

        val owner = owners.firstOrNull { source.findTypeDeclaration(it) >= 0 } ?: return null
        val type = CppTarget(file, source.findTypeDeclaration(owner))
        if (members.isEmpty()) return FileMatch(type, type)

        for (member in members) {
            val offset = source.findMemberDeclaration(owner, member)
            if (offset >= 0) return FileMatch(CppTarget(file, offset), type)
        }
        return FileMatch(null, type)
    }

    private fun loadSource(file: VirtualFile): CppSource? {
        if (file.length > MAX_FILE_BYTES) return null
        return try {
            // Loaded rather than taken from a Document: most of these files are never opened, and
            // LoadTextUtil normalises line separators, so the offsets still match the editor's.
            CppSource(LoadTextUtil.loadText(file).toString())
        } catch (e: Exception) {
            LOG.debug("Could not read ${file.path}: ${e.message}")
            null
        }
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
