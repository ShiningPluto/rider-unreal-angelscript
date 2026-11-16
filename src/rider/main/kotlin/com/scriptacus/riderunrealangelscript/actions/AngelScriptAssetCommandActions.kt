package com.scriptacus.riderunrealangelscript.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.commands.LSPCommand
import com.redhat.devtools.lsp4ij.commands.LSPCommandAction
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptLanguageServerFactory
import com.scriptacus.riderunrealangelscript.status.AngelScriptUnrealStatus
import com.scriptacus.riderunrealangelscript.status.AngelScriptUnrealStatusService
import org.eclipse.lsp4j.ExecuteCommandParams
import java.util.concurrent.TimeUnit

/**
 * Runs one of the commands the language server puts on its code lenses (`saveAndCreateBlueprint`,
 * `saveAndEditAsset`, `openAssets`).
 *
 * The server does the real work in `workspace/executeCommand`: it forwards `CreateBlueprint` /
 * `FindAssets` to the Unreal editor over its own socket, and shows an error when no editor is
 * connected. This action only has to save the script first, so that the editor sees the class the
 * command refers to, and then pass the command on.
 */
abstract class AngelScriptExecuteCommandAction(
    private val lspCommandId: String,
    private val saveScriptFirst: Boolean,
    private val notConnectedMessage: String,
) : LSPCommandAction() {

    override fun getCommandPerformedThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun commandPerformed(command: LSPCommand, e: AnActionEvent) {
        val project = e.project ?: return
        val arguments = command.arguments
        val payload = payloadOf(arguments, saveScriptFirst)
        if (payload == null) {
            LOG.warn("$lspCommandId command without a usable argument: $arguments")
            return
        }

        if (!isEditorOnline(project)) {
            notify(project, notConnectedMessage)
            return
        }

        if (saveScriptFirst) {
            saveScript(project, arguments.getOrNull(0) as? String)
        }

        // The VS Code extension waits briefly after saving so the editor can compile the script
        // before the command refers to the class. Saving is synchronous here, but Unreal compiles
        // on its own schedule, so keep the same short delay.
        val delayMs = if (saveScriptFirst) SAVE_SETTLE_DELAY_MS else 0
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            { execute(project, lspCommandId, payload) },
            delayMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun isEditorOnline(project: Project): Boolean = try {
        when (AngelScriptUnrealStatusService.getInstance(project).status) {
            AngelScriptUnrealStatus.READY,
            AngelScriptUnrealStatus.LOADING_TYPES -> true
            else -> false
        }
    } catch (e: Exception) {
        LOG.warn("Could not read the Unreal editor status", e)
        false
    }

    private fun saveScript(project: Project, uri: String?) {
        val file: VirtualFile = uri?.let { VirtualFileManager.getInstance().findFileByUrl(it) }
            ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            ?: return
        val documentManager = FileDocumentManager.getInstance()
        val document = documentManager.getDocument(file) ?: return
        if (documentManager.isDocumentUnsaved(document)) {
            documentManager.saveDocument(document)
        }
    }

    private fun execute(project: Project, commandId: String, argument: String) {
        if (project.isDisposed) return
        try {
            LanguageServerManager.getInstance(project)
                .getLanguageServer(AngelScriptLanguageServerFactory.SERVER_ID)
                .thenAccept { serverItem ->
                    val server = serverItem?.server ?: return@thenAccept
                    server.workspaceService
                        .executeCommand(ExecuteCommandParams(commandId, listOf(argument)))
                        .exceptionally { throwable ->
                            LOG.warn("Language server failed to run $commandId", throwable)
                            null
                        }
                }
                .exceptionally { throwable ->
                    LOG.warn("Could not reach the AngelScript language server for $commandId", throwable)
                    null
                }
        } catch (e: Exception) {
            LOG.warn("Could not run $commandId", e)
        }
    }

    private fun notify(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(message, NotificationType.WARNING)
            .notify(project)
    }

    companion object {
        private val LOG = Logger.getInstance(AngelScriptExecuteCommandAction::class.java)
        private const val NOTIFICATION_GROUP = "AngelScript"
        private const val SAVE_SETTLE_DELAY_MS = 300L

        /**
         * The argument the server command needs: the class name or asset path. The commands that
         * save first carry the document uri in slot 0 and the payload in slot 1.
         */
        fun payloadOf(arguments: List<Any?>, saveScriptFirst: Boolean): String? {
            val index = if (saveScriptFirst) 1 else 0
            val value = arguments.getOrNull(index) as? String ?: return null
            return value.ifEmpty { null }
        }
    }
}

/** `angelscript.saveAndCreateBlueprint`: save the script, then let the editor create the asset. */
class SaveAndCreateBlueprintCommandAction : AngelScriptExecuteCommandAction(
    lspCommandId = "angelscript.createBlueprint",
    saveScriptFirst = true,
    notConnectedMessage = "Cannot create the asset: the Unreal editor is not connected.",
)

/** `angelscript.saveAndEditAsset`: save the script, then ask the editor to open the asset. */
class SaveAndEditAssetCommandAction : AngelScriptExecuteCommandAction(
    lspCommandId = "angelscript.editAsset",
    saveScriptFirst = true,
    notConnectedMessage = "Cannot edit the asset: the Unreal editor is not connected.",
)

/** `angelscript.openAssets`: list the blueprints that implement a class. */
class OpenAssetsCommandAction : AngelScriptExecuteCommandAction(
    lspCommandId = "angelscript.openAssets",
    saveScriptFirst = false,
    notConnectedMessage = "Cannot open the implementing assets: the Unreal editor is not connected.",
)
