package com.scriptacus.riderunrealangelscript.debug

import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptorFactory

/**
 * Factory for creating AngelScript Debug Adapter descriptors.
 * Registered in plugin.xml as a debugAdapterServer extension.
 */
class AngelScriptDebugAdapterDescriptorFactory : DebugAdapterDescriptorFactory() {

    companion object {
        /**
         * Id of the `com.redhat.devtools.lsp4ij.debugAdapterServer` extension declared in plugin.xml.
         *
         * A DAP run configuration names its adapter by this id, so it is also how an AngelScript debug
         * configuration is told apart from any other. An id that does not match the extension resolves
         * to no adapter at all, and the configuration then fails for want of a server command.
         */
        const val SERVER_ID = "angelscript-dap"
    }

    override fun createDebugAdapterDescriptor(
        options: RunConfigurationOptions,
        environment: ExecutionEnvironment
    ): DebugAdapterDescriptor {
        return AngelScriptDebugAdapterDescriptor(options, environment, serverDefinition)
    }

    /**
     * Debug only. lsp4ij accepts the Run executor too, and then skips sending breakpoints altogether:
     * the adapter still connects to Unreal and the session looks alive, but no breakpoint is ever hit.
     * There is nothing a script "run" could do that the editor is not already doing, so Run is only
     * ever that trap.
     */
    override fun canRun(executorId: String): Boolean = executorId == DefaultDebugExecutor.EXECUTOR_ID
}
