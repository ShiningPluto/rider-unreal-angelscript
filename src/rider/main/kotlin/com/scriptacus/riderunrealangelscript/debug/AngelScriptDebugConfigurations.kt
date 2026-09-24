package com.scriptacus.riderunrealangelscript.debug

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.util.PathUtil
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.redhat.devtools.lsp4ij.dap.DAPDebugProcess
import com.redhat.devtools.lsp4ij.dap.DebugAdapterManager
import com.redhat.devtools.lsp4ij.dap.DebugMode
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration
import com.scriptacus.riderunrealangelscript.lang.AngelScriptFileType
import com.scriptacus.riderunrealangelscript.status.AngelScriptDebugStatusBarWidget

/**
 * Finds, creates and starts the run configuration that attaches the AngelScript debugger to Unreal.
 *
 * Breakpoints in script files can be placed without one - lsp4ij accepts them on the strength of the
 * adapter's language mapping alone - but nothing sends them to Unreal until a Debug Adapter Protocol
 * configuration naming this plugin's adapter is running under Debug. Debugging the editor's C++ does
 * not do it, and nothing in the IDE says so: the breakpoints simply never hit.
 */
object AngelScriptDebugConfigurations {

    private val LOG = Logger.getInstance(AngelScriptDebugConfigurations::class.java)

    /** Name given to a configuration created on the user's behalf. */
    const val DEFAULT_NAME = "AngelScript"

    /**
     * Id of lsp4ij's Debug Adapter Protocol configuration type. The type's class is package-private,
     * so the id is the only way to reach its factory.
     */
    internal const val DAP_CONFIGURATION_TYPE_ID = "DAPConfiguration"

    /**
     * Set on a session's process handler once Unreal has answered its handshake, so the mark goes away
     * with the session instead of needing to be cleared.
     */
    private val UNREAL_ANSWERED = Key.create<Boolean>("AngelScript.Debugger.UnrealAnswered")

    /** What stands between the user and a breakpoint being hit. */
    sealed interface State {
        data object NoConfiguration : State
        data class NotAttached(val configuration: RunnerAndConfigurationSettings) : State

        /** A session is running but Unreal has not answered it yet, so breakpoints are not live. */
        data class Connecting(val session: XDebugSession) : State
        data class Attached(val session: XDebugSession) : State
    }

    fun state(project: Project): State {
        runningSession(project)?.let { session ->
            val answered = session.debugProcess.processHandler.getUserData(UNREAL_ANSWERED) == true
            return if (answered) State.Attached(session) else State.Connecting(session)
        }
        find(project)?.let { return State.NotAttached(it) }
        return State.NoConfiguration
    }

    /**
     * Whether any enabled breakpoint is in a script file. Only then does it matter that the debugger
     * is not attached: in a project where nobody debugs scripts, that is always true and says nothing.
     */
    fun hasScriptBreakpoints(project: Project): Boolean =
        XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints.any { breakpoint ->
            breakpoint is XLineBreakpoint<*> && breakpoint.isEnabled &&
                FileTypeRegistry.getInstance().getFileTypeByFileName(PathUtil.getFileName(breakpoint.fileUrl)) is AngelScriptFileType
        }

    /**
     * Records that Unreal answered [debugProcess]'s handshake. The adapter cannot finish the handshake
     * until Unreal has replied to it, and it ends the session when it cannot reach Unreal, so this is
     * what separates a session that is attached from one still waiting on an editor that may never answer.
     */
    fun markAttached(debugProcess: DAPDebugProcess) {
        debugProcess.processHandler.putUserData(UNREAL_ANSWERED, true)
        AngelScriptDebugStatusBarWidget.update(debugProcess.project)
    }

    /** Whether [profile] attaches this plugin's adapter, as opposed to some other DAP server's. */
    fun isAngelScript(profile: RunProfile?): Boolean =
        (profile as? DAPRunConfiguration)?.serverId == AngelScriptDebugAdapterDescriptorFactory.SERVER_ID

    /** The configuration to start, if the project has any that attaches the AngelScript adapter. */
    fun find(project: Project): RunnerAndConfigurationSettings? {
        val runManager = RunManager.getInstance(project)
        val candidates = runManager.allSettings.filter { isAngelScript(it.configuration) }
        return choose(candidates, runManager.selectedConfiguration) { it.isTemporary }
    }

    /**
     * Picks which of several AngelScript configurations to use.
     *
     * The selected one wins, since that is the one the user last chose. Otherwise a permanent one is
     * preferred over a temporary one: the IDE creates those from the editor's context menu and
     * discards them once enough newer ones pile up, which is how a working setup quietly disappears.
     */
    internal fun <T : Any> choose(candidates: List<T>, selected: T?, isTemporary: (T) -> Boolean): T? {
        if (selected != null && selected in candidates) {
            return selected
        }
        return candidates.firstOrNull { !isTemporary(it) } ?: candidates.firstOrNull()
    }

    /** The AngelScript debug session, whether or not Unreal has answered it yet. */
    fun runningSession(project: Project): XDebugSession? =
        XDebuggerManager.getInstance(project).debugSessions.firstOrNull { !it.isStopped && isAngelScript(it.runProfile) }

    /**
     * Brings a running session to the front, or starts one - creating the configuration first if
     * the project has none. Must be called on the EDT.
     */
    fun startOrShow(project: Project) {
        val session = runningSession(project)
        if (session != null) {
            RunContentManager.getInstance(project)
                .toFrontRunContent(DefaultDebugExecutor.getDebugExecutorInstance(), session.runContentDescriptor)
            return
        }

        val runManager = RunManager.getInstance(project)
        val settings = find(project) ?: create(project)
        if (settings.isTemporary) {
            runManager.makeStable(settings)
        }
        // Deliberately not made the selected configuration: the one already selected is usually the
        // editor's C++ launch, and Shift+F9 should keep doing what the user expects.
        ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())
    }

    /**
     * Creates the configuration the manual setup produces: a DAP configuration naming this plugin's
     * adapter, and nothing else. The port and host come from the plugin settings when the adapter
     * starts, so there is nothing to fill in here that could go stale.
     */
    fun create(project: Project): RunnerAndConfigurationSettings {
        val runManager = RunManager.getInstance(project)
        val type = ConfigurationTypeUtil.findConfigurationType(DAP_CONFIGURATION_TYPE_ID)
            ?: error("lsp4ij's '$DAP_CONFIGURATION_TYPE_ID' run configuration type is not registered")
        val factory = type.configurationFactories.first()
        val settings = runManager.createConfiguration(DEFAULT_NAME, factory)
        (settings.configuration as DAPRunConfiguration).apply {
            serverId = AngelScriptDebugAdapterDescriptorFactory.SERVER_ID
            serverName = DebugAdapterManager.getInstance()
                .getDebugAdapterServerById(AngelScriptDebugAdapterDescriptorFactory.SERVER_ID)
                ?.name
            debugMode = DebugMode.LAUNCH
        }
        runManager.addConfiguration(settings)
        LOG.info("Created AngelScript debug configuration '${settings.name}'")
        return settings
    }
}
