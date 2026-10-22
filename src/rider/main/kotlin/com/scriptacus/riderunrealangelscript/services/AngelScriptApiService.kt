package com.scriptacus.riderunrealangelscript.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptLanguageServer
import com.scriptacus.riderunrealangelscript.lsp.AngelScriptLanguageServerFactory
import java.util.concurrent.CompletableFuture

/**
 * Project-level service for the language server requests the API browser makes.
 *
 * The browser reads Unreal's API from the saved type database, and only asks the server what script
 * declares (angelscript/getScriptDeclarations, added by the bundle).
 */
@Service(Service.Level.PROJECT)
class AngelScriptApiService(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(AngelScriptApiService::class.java)
    }

    private val gson = Gson()

    /**
     * Get every function and variable script declares in a namespace, mixins included.
     *
     * @param callback Callback invoked with the `functions` and `properties` lists, or null on error
     */
    fun getScriptDeclarations(callback: (Map<String, Any>?) -> Unit) {
        sendRequest("angelscript/getScriptDeclarations", { it.getScriptDeclarations() }) { result ->
            callback(convertToMap(result))
        }
    }

    /**
     * Convert Gson's internal types (JsonObject, JsonArray, etc.) to standard Java types.
     */
    private fun convertToMap(obj: Any?): Map<String, Any>? {
        if (obj == null) return null

        return try {
            // Convert via JSON to handle Gson's internal types
            val json = gson.toJson(obj)
            gson.fromJson(json, object : TypeToken<Map<String, Any>>() {}.type)
        } catch (e: Exception) {
            LOG.warn("Failed to convert object to Map: ${obj.javaClass}", e)
            null
        }
    }

    /**
     * Send a custom LSP request to the AngelScript language server.
     *
     * @param method LSP method name, for the log
     * @param request Sends the request through the server's typed interface
     * @param callback Callback invoked with the result (or null on error)
     */
    private fun sendRequest(method: String, request: (AngelScriptLanguageServer) -> CompletableFuture<*>, callback: (Any?) -> Unit) {
        try {
            val manager = LanguageServerManager.getInstance(project)
            val serverItemFuture = manager.getLanguageServer(AngelScriptLanguageServerFactory.SERVER_ID)

            serverItemFuture.thenAccept { serverItem ->
                if (serverItem == null) {
                    LOG.warn("[$method] AngelScript LSP server not available")
                    callback(null)
                    return@thenAccept
                }

                try {
                    val server = serverItem.server
                    if (server is AngelScriptLanguageServer) {
                        request(server)
                            .thenAccept { result ->
                                callback(result)
                            }
                            .exceptionally { throwable ->
                                LOG.error("LSP request failed: $method - ${throwable.message}", throwable)
                                callback(null)
                                null
                            }
                    } else {
                        LOG.error("Server does not implement AngelScriptLanguageServer interface: ${server.javaClass.name}")
                        callback(null)
                    }
                } catch (e: Exception) {
                    LOG.error("Failed to send LSP request: $method", e)
                    callback(null)
                }
            }.exceptionally { throwable ->
                LOG.error("Failed to get language server for: $method", throwable)
                callback(null)
                null
            }
        } catch (e: Exception) {
            LOG.error("Failed to access language server manager: $method", e)
            callback(null)
        }
    }
}
