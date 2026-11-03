package com.scriptacus.riderunrealangelscript.mcp

import com.google.gson.JsonParser
import com.intellij.testFramework.UsefulTestCase
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import org.eclipse.lsp4j.services.LanguageServer

/**
 * angelscript_search_symbols reads the server's `workspace/symbol` answer through lsp4j's typed
 * API, and sends each symbol back to `workspaceSymbol/resolve` for its range. The server answers
 * with a location that has no range, which lsp4j must read as a WorkspaceSymbol rather than a
 * SymbolInformation, and with `data` - a type's name, or an array naming a member - which must reach
 * the server again as it was sent, or the resolve finds nothing.
 */
class WorkspaceSymbolJsonTest : UsefulTestCase() {

    private val uri = "file:///d%3A/p/Script/MyCharacter.as"

    private fun handler(method: String) =
        MessageJsonHandler(ServiceEndpoints.getSupportedMethods(LanguageServer::class.java)).apply { setMethodProvider { method } }

    fun testTheServersSymbolsAreReadAsWorkspaceSymbolsWithoutARange() {
        val answer = """
            {"jsonrpc":"2.0","id":"1","result":[
              {"name":"AMyCharacter","location":{"uri":"$uri"},"kind":5,"data":"AMyCharacter"},
              {"name":"AMyCharacter.TakeDamage(…)","data":["AMyCharacter","TakeDamage",12],"location":{"uri":"$uri"},"kind":6,"containerName":"AMyCharacter"}
            ]}
        """
        @Suppress("UNCHECKED_CAST")
        val result = (handler("workspace/symbol").parseMessage(answer) as ResponseMessage).result as Either<*, List<WorkspaceSymbol>>
        assertTrue(result.isRight)
        val (type, member) = result.right
        assertEquals(uri, ScriptSymbolQuery.uriOf(type))
        assertNull(type.location.left?.range)
        assertEquals("AMyCharacter", member.containerName)

        // Sent back as the request's one parameter, an object, with data untouched
        val request = RequestMessage().apply { jsonrpc = "2.0"; id = "2"; method = "workspaceSymbol/resolve"; params = member }
        val params = JsonParser.parseString(handler("workspaceSymbol/resolve").serialize(request)).asJsonObject["params"]
        assertTrue(params.isJsonObject)
        assertEquals(JsonParser.parseString("""["AMyCharacter","TakeDamage",12]"""), params.asJsonObject["data"])
    }

    fun testAResolvedSymbolHasItsRange() {
        val answer = """
            {"jsonrpc":"2.0","id":"2","result":
              {"name":"AMyCharacter.TakeDamage(…)","location":{"uri":"$uri","range":{"start":{"line":4,"character":9},"end":{"line":10,"character":5}}},"kind":6}
            }
        """
        val symbol = (handler("workspaceSymbol/resolve").parseMessage(answer) as ResponseMessage).result as WorkspaceSymbol
        val range = symbol.location.left.range
        assertEquals(4, range.start.line)
        assertEquals(9, range.start.character)
        assertEquals(10, range.end.line)
    }
}
