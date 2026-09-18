package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.testFramework.UsefulTestCase
import com.scriptacus.riderunrealangelscript.settings.AngelScriptLspConfiguration
import com.scriptacus.riderunrealangelscript.settings.AngelScriptLspSettings

/**
 * Guards the two failure modes behind "no highlighting and no diagnostics in Rider":
 * a configuration payload the language server cannot consume, and a server id that does not
 * resolve to the registered language server.
 */
class AngelScriptLspConfigurationTest : UsefulTestCase() {

    /**
     * The server keeps `port = -1` until a configuration push carries `unrealConnectionPort`,
     * and only then connects to the Unreal editor to fetch the C++ type database.
     */
    fun testConfigurationCarriesUnrealConnectionPort() {
        val state = AngelScriptLspSettings.State(unrealConnectionPort = 27099)

        val settings = AngelScriptLspConfiguration.build(state)
            .getAsJsonObject("UnrealAngelscript")

        assertNotNull("Configuration must be nested under the UnrealAngelscript section", settings)
        assertEquals(27099, settings.get("unrealConnectionPort").asInt)
    }

    /**
     * The server dereferences these sections unconditionally in its `onDidChangeConfiguration`
     * handler, so a missing one throws there and the whole push is lost - including the port.
     */
    fun testConfigurationProvidesEverySectionTheServerDereferences() {
        val settings = AngelScriptLspConfiguration.build(AngelScriptLspSettings.State())
            .getAsJsonObject("UnrealAngelscript")

        for (section in listOf("completion", "inlayHints", "inlineValues", "codeLenses", "projectCodeGeneration")) {
            assertTrue("Missing '$section' section required by the language server", settings.has(section))
        }
        assertTrue(settings.getAsJsonObject("completion").has("dependencyRestrictions"))
        assertTrue(settings.getAsJsonObject("projectCodeGeneration").has("generators"))
    }

    /**
     * `LanguageServerManager` silently reports a `null` status for an unknown id rather than
     * failing, so a drifted constant is invisible at runtime.
     */
    fun testServerIdMatchesPluginXmlRegistration() {
        val pluginXml = javaClass.getResourceAsStream("/META-INF/plugin.xml")?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        assertNotNull("plugin.xml must be on the test classpath", pluginXml)

        val registeredId = Regex("<server\\s+id=\"([^\"]+)\"").find(pluginXml!!)?.groupValues?.get(1)

        assertEquals(
            "lsp4ij server id in plugin.xml and AngelScriptLanguageServerFactory.SERVER_ID must match",
            registeredId,
            AngelScriptLanguageServerFactory.SERVER_ID
        )
    }
}
