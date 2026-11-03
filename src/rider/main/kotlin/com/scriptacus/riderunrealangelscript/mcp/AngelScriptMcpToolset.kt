package com.scriptacus.riderunrealangelscript.mcp

import com.google.gson.GsonBuilder
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptDiagnosticsStore
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptLanguageServer
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptLanguageServerFactory
import com.scriptacus.riderunrealangelscript.services.AngelScriptApiService
import com.scriptacus.riderunrealangelscript.status.AngelScriptUnrealStatus
import com.scriptacus.riderunrealangelscript.status.AngelScriptUnrealStatusService
import com.scriptacus.riderunrealangelscript.typedb.ScriptDeclarations
import com.scriptacus.riderunrealangelscript.typedb.ScriptOrigin
import com.scriptacus.riderunrealangelscript.typedb.UnrealTypeDatabase
import com.scriptacus.riderunrealangelscript.typedb.UnrealTypeDatabaseService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.DiagnosticSeverity
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/**
 * AngelScript analysis for AI agents, through the MCP server built into the IDE.
 *
 * The built-in tools cannot see script files: `get_file_problems` rejects them because they belong
 * to no solution project, and `search_symbol` finds none of their symbols. What an agent most needs
 * to know about AngelScript is not in the files anyway - which C++ API is bound to script, and
 * whether Unreal accepted the script it compiled - but inside the language server.
 *
 * Registered only when the MCP server plugin is present, through the optional dependency in
 * `angelscript-mcp.xml`; nothing else in the plugin may refer to this class.
 */
class AngelScriptMcpToolset : McpToolset {

    private companion object {
        /** Starting node and initialising takes a second or two; this only guards against a hang. */
        const val SERVER_START_TIMEOUT_MS = 30_000L

        /** How long a freshly started server gets to load its type database before a search gives up. */
        const val TYPES_WAIT_MS = 10_000L
        const val TYPES_POLL_MS = 500L

        /** Answered from memory once types are loaded; a slow answer means the server is wedged. */
        const val REQUEST_TIMEOUT_MS = 15_000L

        /**
         * Reading the saved database takes about a second for a large project, after a status poll
         * of up to two seconds notices the types; this only guards against waiting forever.
         */
        const val DATABASE_WAIT_MS = 30_000L

        val gson = GsonBuilder().disableHtmlEscaping().create()
    }

    @McpTool(name = "angelscript_status")
    @McpDescription("""
        |Reports whether the AngelScript language server can analyse Unreal AngelScript (.as) files
        |right now, and if not, why not.
        |Analysis needs the C++ type database of the Unreal editor. Without it the server reports no
        |problems at all and has no API to search, so check this before trusting an empty result.
    """)
    suspend fun status(): String {
        val project = currentCoroutineContext().project
        val snapshot = querySnapshot(project)
        return gson.toJson(
            StatusReport(
                state = snapshot?.status?.name ?: NOT_RESPONDING,
                analysisAvailable = snapshot?.status?.isAnalysisAvailable ?: false,
                explanation = explain(snapshot),
                languageServerRunning = snapshot?.serverRunning ?: true,
                unrealPort = snapshot?.configuredPort?.takeIf { it >= 0 },
                typesCapturedAt = snapshot?.typesCapturedAt?.let { Instant.ofEpochMilli(it).toString() },
            )
        )
    }

    @McpTool(name = "angelscript_get_problems")
    @McpDescription("""
        |Returns the errors and warnings in Unreal AngelScript (.as) files: both the language server's
        |own analysis and the compile errors the Unreal editor reported when it hot-reloaded the
        |script. Use it after editing .as files; Rider's get_file_problems cannot analyse them.
        |Covers every script file the server has reported on, not only files open in the editor.
        |Each file carries updatedAt, when the server last reported on it: diagnostics follow an edit
        |asynchronously, so an updatedAt older than the edit means the result may not reflect it yet.
        |`reportedBy` is "unreal" for Unreal's compile errors and "languageServer" for analysis.
        |Lines and columns are 1-based. Check `analysis.available` before reading an empty result as
        |"no problems".
    """)
    suspend fun getProblems(
        @McpDescription("Path of one .as file, relative to the project root or absolute. Omit for every file with problems.")
        filePath: String? = null,
        @McpDescription("Only report errors, leaving out warnings and informational messages.")
        errorsOnly: Boolean = false,
        @McpDescription("Maximum number of problems to return, across all files.")
        limit: Int = 200,
    ): String {
        val project = currentCoroutineContext().project
        val store = AngelScriptDiagnosticsStore.getInstance(project)
        val snapshot = querySnapshot(project)
        val maxProblems = limit.coerceIn(1, 5_000)

        val requested = filePath?.takeIf { it.isNotBlank() }?.let { resolveScriptFile(project, it) }
        val files = if (requested != null) {
            listOf(store.get(requested) ?: AngelScriptDiagnosticsStore.FileProblems(requested, emptyList(), 0L))
        } else {
            // Files with errors first: that is what an agent is asking about when it asks for everything.
            store.all().sortedWith(compareBy({ file -> file.problems.none { it.isError() } }, { it.path }))
        }

        var total = 0
        var returned = 0
        val reports = mutableListOf<FileReport>()
        for (file in files) {
            // Hints are the unused-variable fades, which the server only produces for open files.
            val problems = file.problems
                .filter { it.isError() || (!errorsOnly && it.severity != DiagnosticSeverity.Hint) }
                .sortedWith(compareBy({ it.line }, { it.character }))
            total += problems.size
            val room = maxProblems - returned
            // An asked-for file is always listed, so that "no problems" carries its updatedAt.
            if (requested == null && (problems.isEmpty() || room == 0)) {
                continue
            }
            val kept = problems.take(room)
            returned += kept.size
            reports += FileReport(
                path = FileUtil.toSystemDependentName(AngelScriptDiagnosticsStore.relativeTo(project.basePath, file.path)),
                updatedAt = file.updatedAt.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() },
                problems = kept.map { it.toReport() },
            )
        }

        return gson.toJson(
            ProblemsReport(
                analysis = analysisReport(snapshot),
                totalProblems = total,
                truncated = total > returned,
                checkedAt = Instant.now().toString(),
                files = reports,
            )
        )
    }

    @McpTool(name = "angelscript_search_api")
    @McpDescription("""
        |Searches the API available to Unreal AngelScript: the classes, structs, enums, functions and
        |properties Unreal binds to script, and the functions, variables and mixins script declares,
        |under the names and call syntax script uses. These differ from the C++ declarations - static
        |functions live in namespaces like `System::`, and mixin functions are called as members of
        |their first argument's type - so use this instead of guessing from C++ headers whether and
        |how something can be called.
        |The query is one of:
        |- Words separated by spaces that must all occur in one name, case-insensitively: "Actor
        |  Location" finds AActor.GetActorLocation(). A word shorter than three letters must start the
        |  name unless a longer word came before it. A type or namespace matches by its own name,
        |  not by its members'.
        |- `Type.word`: only the members of types whose name contains Type. When Type is a type's
        |  whole name, such as "ACharacter.GetActor", what it inherits is searched too, and each such
        |  item names the supertype that declares it in `inheritedFrom`.
        |- `Namespace::word`: only that namespace's members, or a type's static members.
        |Nothing after the separator lists every member. A blueprint event is also found by its C++
        |name, such as ReceiveBeginPlay. Best matches come first: exact names, then names starting
        |with the query. Pass an item's `ref` to angelscript_get_api_details for its declaration and
        |documentation.
    """)
    suspend fun searchApi(
        @McpDescription("Words separated by spaces, \"Type.word\" or \"Namespace::word\", e.g. \"GetActorLocation\", \"Actor Location\" or \"ACharacter.Jump\".")
        query: String,
        @McpDescription("Maximum number of items to return.")
        limit: Int = 50,
    ): String {
        if (query.isBlank()) {
            mcpFail("Give at least one word to search for.")
        }
        val project = currentCoroutineContext().project
        val api = api(project)
        val result = withContext(Dispatchers.Default) {
            api.database.search(query, limit.coerceIn(1, 1_000), api.script)
        }
        val path = scriptPath(project)
        val items = result.hits.map { ApiItems.item(it, path) }
        return gson.toJson(
            SearchReport(
                query = query,
                total = result.total,
                returned = items.size,
                items = items,
                note = if (api.scriptListed) null else SCRIPT_MISSING,
            )
        )
    }

    @McpTool(name = "angelscript_get_api_details")
    @McpDescription("""
        |Returns the declaration and documentation of one item angelscript_search_api found, as
        |markdown. For a function, also whether it is a blueprint event, a script mixin, or known to
        |C++ by another name; for a class or struct, what it inherits from, how many members it
        |declares and inherits, and its constructors; for an enum, its values. Says which file
        |declares what script declares.
    """)
    suspend fun getApiDetails(
        @McpDescription("The item's `ref`, exactly as angelscript_search_api returned it.")
        ref: List<String>,
    ): String {
        if (ref.isEmpty()) {
            mcpFail("Pass the `ref` of an item returned by angelscript_search_api.")
        }
        val project = currentCoroutineContext().project
        val api = api(project)
        val symbol = ApiItems.resolve(ref, api.database, api.script)
            ?: mcpFail(
                "Nothing in the API is named by $ref. Refs come from angelscript_search_api; one kept from " +
                    "an earlier search may name something that has since been renamed or removed, so search again." +
                    if (api.scriptListed) "" else " $SCRIPT_MISSING"
            )
        return ApiDetailsMarkdown.of(symbol, api.database, api.script, scriptPath(project))
    }

    /** The server's view of its analysis state, or null when it is too busy to say. */
    private suspend fun querySnapshot(project: Project): AngelScriptUnrealStatusService.Snapshot? =
        withContext(Dispatchers.IO) {
            try {
                AngelScriptUnrealStatusService.getInstance(project).query()
            } catch (e: TimeoutException) {
                null
            } catch (e: ExecutionException) {
                null
            }
        }

    /**
     * Unreal's API and what script declares, as the API tool window has them: the type database the
     * server saved, read once and kept, and the server's list of script declarations, asked afresh
     * every time since script changes without the database doing so.
     */
    private class Api(val database: UnrealTypeDatabase, val script: ScriptDeclarations, val scriptListed: Boolean)

    private suspend fun api(project: Project): Api {
        awaitTypes(project)
        val database = typeDatabase(project)
        val reply = try {
            withTimeout(REQUEST_TIMEOUT_MS) { project.service<AngelScriptApiService>().scriptDeclarations().await() }
        } catch (e: TimeoutCancellationException) {
            null
        }
        val script = reply?.let { withContext(Dispatchers.Default) { ScriptDeclarations.parse(it, database) } }
        return Api(database, script ?: ScriptDeclarations.NONE, scriptListed = script != null)
    }

    /** The type database the server saved, once [UnrealTypeDatabaseService] has read it. */
    private suspend fun typeDatabase(project: Project): UnrealTypeDatabase {
        val service = UnrealTypeDatabaseService.getInstance(project)
        service.activate()
        // The service follows the server's status, which it polls every couple of seconds while
        // the server has no types: a server that has only just got them takes a poll to be noticed.
        val deadline = System.currentTimeMillis() + DATABASE_WAIT_MS
        while (true) {
            when (val state = service.state) {
                is UnrealTypeDatabaseService.State.Loaded -> return state.database
                is UnrealTypeDatabaseService.State.Failed ->
                    mcpFail("The type database the language server saved could not be read: ${state.reason}.")
                else -> if (System.currentTimeMillis() >= deadline) {
                    mcpFail("The type database the language server saved was not read within ${DATABASE_WAIT_MS / 1000}s. ${explain(querySnapshot(project))}")
                }
            }
            delay(TYPES_POLL_MS)
        }
    }

    /** Where script declares something, relative to the project when it is in it. */
    private fun scriptPath(project: Project): (ScriptOrigin) -> String = { origin ->
        origin.path?.let { FileUtil.toSystemDependentName(AngelScriptDiagnosticsStore.relativeTo(project.basePath, it)) } ?: origin.module
    }

    /**
     * Returns once the server has Unreal's types, which is when it saves them for the API.
     *
     * Starts the server if it is not running, as opening the API tool window does: a search is an
     * explicit request for something only Unreal's types can answer.
     */
    private suspend fun awaitTypes(project: Project) {
        val manager = LanguageServerManager.getInstance(project)
        val wasRunning = manager.getServerStatus(AngelScriptLanguageServerFactory.SERVER_ID) == ServerStatus.started

        val item = try {
            // thenApply hands the timeout a future of its own to cancel, not the shared startup one.
            withTimeout(SERVER_START_TIMEOUT_MS) {
                manager.getLanguageServer(AngelScriptLanguageServerFactory.SERVER_ID).thenApply { it }.await()
            }
        } catch (e: TimeoutCancellationException) {
            null
        }
        if (item?.server !is AngelScriptLanguageServer) {
            mcpFail("The AngelScript language server could not be started. See the AngelScript LSP console in Rider.")
        }

        val deadline = System.currentTimeMillis() + TYPES_WAIT_MS
        while (true) {
            val snapshot = querySnapshot(project)
            val status = snapshot?.status
            if (status != null && status.isAnalysisAvailable) {
                return
            }
            // A server that was already running and is not even connecting will not have types in
            // ten seconds either; one that has just started still needs to receive its configuration.
            val settled = wasRunning &&
                (status == AngelScriptUnrealStatus.DISCONNECTED || status == AngelScriptUnrealStatus.NOT_CONFIGURED)
            if (settled || System.currentTimeMillis() >= deadline) {
                mcpFail("No Unreal type database is loaded, so there is no API to search. ${explain(snapshot)}")
            }
            delay(TYPES_POLL_MS)
        }
    }

    /** The absolute path of the script file [filePath] names, or a failure the agent can act on. */
    private fun resolveScriptFile(project: Project, filePath: String): String {
        val resolved = try {
            val base = project.basePath
            (if (base != null) Path.of(base).resolve(filePath) else Path.of(filePath)).normalize()
        } catch (e: InvalidPathException) {
            mcpFail("Not a valid path: $filePath")
        }
        val extension = resolved.fileName?.toString()?.substringAfterLast('.', "")?.lowercase()
        if (extension != "as" && extension != "ash") {
            mcpFail("Not an AngelScript file: $filePath. Only .as and .ash files are analysed by the AngelScript language server.")
        }
        if (!resolved.toFile().isFile) {
            mcpFail("No such file: $resolved")
        }
        return resolved.toString().replace('\\', '/')
    }

    private fun analysisReport(snapshot: AngelScriptUnrealStatusService.Snapshot?) = AnalysisReport(
        state = snapshot?.status?.name ?: NOT_RESPONDING,
        available = snapshot?.status?.isAnalysisAvailable ?: false,
        explanation = explain(snapshot),
    )

    private fun explain(snapshot: AngelScriptUnrealStatusService.Snapshot?): String {
        val port = snapshot?.configuredPort
        return when (snapshot?.status) {
            null ->
                "The language server did not answer within a few seconds. It may be busy loading, so " +
                    "whatever it reported before may be out of date."
            AngelScriptUnrealStatus.READY ->
                "Connected to the Unreal editor on port $port with a live type database. Analysis is " +
                    "current, and Unreal's own compile errors are reported when it hot-reloads script."
            AngelScriptUnrealStatus.EDITOR_CLOSED ->
                "The Unreal editor has disconnected. Analysis continues on the type database it sent, " +
                    "but Unreal's own compile errors stop arriving until it reconnects."
            AngelScriptUnrealStatus.CACHED_TYPES ->
                "No Unreal editor is connected; analysis runs on a type database saved by an earlier " +
                    "session. Engine C++ changed since then is not reflected, and Unreal's own compile " +
                    "errors only arrive while the editor runs."
            AngelScriptUnrealStatus.LOADING_TYPES ->
                "Connected to the Unreal editor, still receiving its type database. Nothing is analysed " +
                    "until it has arrived, usually within seconds."
            AngelScriptUnrealStatus.DISCONNECTED ->
                "No Unreal editor is listening on port $port and no saved type database is in use, so " +
                    "nothing is analysed and an absence of problems means nothing. Start the Unreal editor."
            AngelScriptUnrealStatus.NOT_CONFIGURED ->
                "The language server has not received its configuration, so it is not connecting to " +
                    "Unreal and analyses nothing. Changing any setting under Settings | Languages & " +
                    "Frameworks | AngelScript resends it."
            AngelScriptUnrealStatus.SERVER_STOPPED ->
                "The AngelScript language server is not running, so nothing is analysed. Rider starts " +
                    "it when an .as file is opened in the editor."
        }
    }

    private fun AngelScriptDiagnosticsStore.Problem.isError() =
        severity == null || severity == DiagnosticSeverity.Error

    private fun AngelScriptDiagnosticsStore.Problem.toReport() = ProblemReport(
        severity = (severity ?: DiagnosticSeverity.Error).name.lowercase(),
        line = line + 1,
        column = character + 1,
        message = message,
        reportedBy = when (source) {
            AngelScriptDiagnosticsStore.UNREAL_COMPILE_SOURCE -> "unreal"
            AngelScriptDiagnosticsStore.ANALYSIS_SOURCE -> "languageServer"
            else -> source ?: "unknown"
        },
    )
}

/** Reported in place of a status when the server is running but did not answer the question. */
private const val NOT_RESPONDING = "NOT_RESPONDING"

private data class StatusReport(
    val state: String,
    val analysisAvailable: Boolean,
    val explanation: String,
    val languageServerRunning: Boolean,
    val unrealPort: Int?,
    val typesCapturedAt: String?,
)

private data class AnalysisReport(val state: String, val available: Boolean, val explanation: String)

private data class ProblemReport(
    val severity: String,
    val line: Int,
    val column: Int,
    val message: String,
    val reportedBy: String,
)

private data class FileReport(val path: String, val updatedAt: String?, val problems: List<ProblemReport>)

private data class ProblemsReport(
    val analysis: AnalysisReport,
    val totalProblems: Int,
    val truncated: Boolean,
    val checkedAt: String,
    val files: List<FileReport>,
)

private data class SearchReport(
    val query: String,
    val total: Int,
    val returned: Int,
    val items: List<ApiSearchItem>,
    /** Why the result may be incomplete; left out when it is not. */
    val note: String?,
)

/** Said when the server did not list what script declares, which the API is then without. */
private const val SCRIPT_MISSING =
    "The language server did not list what script declares, so only Unreal's API is searched; try again in a moment."
