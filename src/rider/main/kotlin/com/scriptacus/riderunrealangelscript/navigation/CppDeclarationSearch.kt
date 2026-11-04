package com.scriptacus.riderunrealangelscript.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import java.util.concurrent.ConcurrentHashMap

/** Where a C++ declaration was found: a file and an offset into its text. */
data class CppTarget(val file: VirtualFile, val offset: Int)

/**
 * Finds C++ declarations in the project's sources, for go to declaration from script and for the
 * MCP API details.
 *
 * Rider keeps C++ symbols in its backend, out of a plugin's reach, so this reads headers: the
 * frontend's file-name and word indexes say which files are worth opening, and [CppSource] reads a
 * declaration out of each - see decision 0005.
 *
 * Everything here reads indexes, so it runs in a read action and not in dumb mode.
 */
object CppDeclarationSearch {

    private val LOG = Logger.getInstance(CppDeclarationSearch::class.java)

    /**
     * The file a symbol was last found in, keyed by `class|member`.
     *
     * Only the file is remembered, never the offset: an edit moves declarations around, and
     * re-reading one known file is cheap next to searching the engine again.
     */
    private val declarationFileCache = ConcurrentHashMap<String, String>()

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

    /** A header every engine has and no game's own sources do. */
    private const val ENGINE_HEADER = "CoreMinimal.h"

    /** Clear all caches. */
    fun clearCaches() {
        declarationFileCache.clear()
    }

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
    fun find(project: Project, cppSymbol: CppSymbolInfo, deadline: Long = Long.MAX_VALUE): CppTarget? {
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
     * Where a function or variable named [name] is declared or bound to script, when nothing says
     * which class it belongs to: what a script namespace holds, such as `System::`, is gathered
     * from C++ classes that Unreal's type database does not record.
     *
     * Best first, at most [limit]: in each of the files most likely to hold it, the string the
     * AngelScript plugin binds it by, which is what script calls, and else a declaration of the
     * name in code. Nothing ties either to the function script means but the name.
     */
    fun findByName(project: Project, name: String, limit: Int, deadline: Long = Long.MAX_VALUE): List<CppTarget> {
        val found = mutableListOf<CppTarget>()
        for (file in candidateFiles(project, name)) {
            if (found.size >= limit || System.nanoTime() > deadline) break
            val offset = loadSource(file)?.findNamed(name)?.takeIf { it >= 0 } ?: continue
            found += CppTarget(file, offset)
        }
        return found
    }

    /** The 1-based line [target] is on, or null when its file cannot be read. */
    fun lineOf(target: CppTarget): Int? = loadSource(target.file)?.lineAt(target.offset)

    /**
     * Whether Rider has indexed Unreal's own C++ sources, without which nothing Unreal declares can
     * be found: not when a project with only script is opened as a folder rather than as an Unreal
     * project. Every engine has `CoreMinimal.h`, and a game's own sources do not.
     */
    fun indexesEngine(project: Project): Boolean =
        FilenameIndex.getVirtualFilesByName(ENGINE_HEADER, GlobalSearchScope.allScope(project)).isNotEmpty()

    /** What one file had to offer: the member itself, and where its type is declared. */
    private data class FileMatch(val member: CppTarget?, val type: CppTarget?)

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
}
