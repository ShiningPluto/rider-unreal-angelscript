package com.scriptacus.riderunrealangelscript.mcp

import com.intellij.mcpserver.impl.util.asTools
import com.intellij.testFramework.UsefulTestCase

/**
 * The MCP server discovers tools by reflection when the IDE starts, and a signature it cannot turn
 * into a schema costs the whole toolset without any error an agent would see - the tools are simply
 * not there. This runs the same reflection the server does.
 */
class AngelScriptMcpToolsetTest : UsefulTestCase() {

    fun testEveryToolIsDiscoveredWithItsParameters() {
        val tools = AngelScriptMcpToolset().asTools().associate { it.descriptor.name to it.descriptor.inputSchema }

        assertEquals(
            setOf("angelscript_status", "angelscript_get_problems", "angelscript_search_api", "angelscript_get_api_details"),
            tools.keys
        )
        // The server adds a `projectPath` parameter of its own to every tool, so only ours are checked.
        assertContainsElements(tools.getValue("angelscript_get_problems").propertiesSchema.keys, "filePath", "errorsOnly", "limit")
        assertEmpty(tools.getValue("angelscript_get_problems").requiredProperties)
        assertEquals(setOf("query"), tools.getValue("angelscript_search_api").requiredProperties)
        assertEquals(setOf("ref"), tools.getValue("angelscript_get_api_details").requiredProperties)
    }
}
