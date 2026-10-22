package com.scriptacus.riderunrealangelscript.typedb

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.scriptacus.riderunrealangelscript.status.AngelScriptUnrealStatus
import com.scriptacus.riderunrealangelscript.status.AngelScriptUnrealStatusService
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Unreal type database the language server is using, read from the copy it saves.
 *
 * It follows the server rather than the file: while the server has no types there is nothing to
 * show, even if a file from an earlier session exists, so the API browser never shows an API the
 * editor's analysis does not have. The file is read again whenever the server reports a database
 * captured at another time, which it saves before reporting.
 */
@Service(Service.Level.PROJECT)
class UnrealTypeDatabaseService(private val project: Project) : Disposable {

    sealed interface State {
        /** The server has no type database, for the reason [status] gives. */
        data class Unavailable(val status: AngelScriptUnrealStatus) : State

        data object Loading : State

        class Loaded(val database: UnrealTypeDatabase) : State

        /** The server has types, but the copy it saved could not be read. */
        data class Failed(val reason: String) : State
    }

    fun interface Listener {
        /** Called on whatever thread noticed the change. */
        fun stateChanged(state: State)
    }

    companion object {
        private val LOG = Logger.getInstance(UnrealTypeDatabaseService::class.java)

        @Topic.ProjectLevel
        val TOPIC: Topic<Listener> = Topic.create("Unreal type database", Listener::class.java)

        fun getInstance(project: Project): UnrealTypeDatabaseService = project.service()

        /**
         * Where the server keeps the last type database it received from Unreal.
         *
         * Per project, because the database describes that project's classes, and under the IDE
         * system directory rather than the workspace so a generated cache never shows up in version
         * control.
         */
        fun cacheFile(project: Project): Path =
            Paths.get(PathManager.getSystemPath(), "angelscript-lsp", project.locationHash, "unreal-type-database.json")
    }

    @Volatile
    var state: State = State.Unavailable(AngelScriptUnrealStatus.SERVER_STOPPED)
        private set

    private val activated = AtomicBoolean(false)

    // Guarded by this.
    private var loaded: UnrealTypeDatabase? = null
    private var loadedCapturedAt: Long? = null
    private var loading = false
    private var changedWhileLoading = false

    /**
     * Starts following the server. Nothing is read until something wants to show the database: it
     * takes tens of megabytes once loaded.
     */
    fun activate() {
        if (!activated.compareAndSet(false, true)) {
            return
        }
        project.messageBus.connect(this).subscribe(
            AngelScriptUnrealStatusService.TOPIC,
            AngelScriptUnrealStatusService.Listener { refresh(force = false) }
        )
        AngelScriptUnrealStatusService.getInstance(project).startPolling()
        refresh(force = false)
    }

    /** Reads the file again even if the server reports the same database. */
    fun reload() {
        if (activated.get()) {
            refresh(force = true)
        }
    }

    private fun refresh(force: Boolean) {
        val statusService = AngelScriptUnrealStatusService.getInstance(project)
        val status = statusService.status
        val capturedAt = statusService.typesCapturedAt

        val next: State = synchronized(this) {
            val current = loaded
            when {
                !status.isAnalysisAvailable -> State.Unavailable(status)
                loading -> {
                    changedWhileLoading = true
                    return
                }
                !force && current != null && capturedAt == loadedCapturedAt -> State.Loaded(current)
                else -> {
                    loading = true
                    State.Loading
                }
            }
        }
        publish(next)

        if (next == State.Loading) {
            ApplicationManager.getApplication().executeOnPooledThread { load(capturedAt) }
        }
    }

    private fun load(capturedAt: Long?) {
        val file = cacheFile(project)
        val result = try {
            val started = System.currentTimeMillis()
            val database = UnrealTypeDatabase.read(file)
            LOG.info("Read ${database.types.size} Unreal types from $file in ${System.currentTimeMillis() - started} ms")
            State.Loaded(database)
        } catch (e: NoSuchFileException) {
            State.Failed("The language server has not saved its type database")
        } catch (e: Exception) {
            LOG.warn("Failed to read the saved Unreal type database at $file", e)
            State.Failed(e.message ?: e.javaClass.simpleName)
        }

        val again = synchronized(this) {
            loading = false
            if (result is State.Loaded) {
                loaded = result.database
                loadedCapturedAt = capturedAt
            }
            changedWhileLoading.also { changedWhileLoading = false }
        }

        // The server moved on while the file was being read; what to show is decided afresh.
        if (again) {
            refresh(force = false)
        } else {
            publish(result)
        }
    }

    private fun publish(next: State) {
        state = next
        if (!project.isDisposed) {
            project.messageBus.syncPublisher(TOPIC).stateChanged(next)
        }
    }

    override fun dispose() {
    }
}
