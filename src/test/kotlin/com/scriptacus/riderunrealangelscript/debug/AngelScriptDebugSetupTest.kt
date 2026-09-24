package com.scriptacus.riderunrealangelscript.debug

import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.testFramework.UsefulTestCase
import com.scriptacus.riderunrealangelscript.status.AngelScriptDebugStatusBarWidget
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the one-step debugging setup. Every way it can break is silent: breakpoints in script files
 * are accepted either way, they just never hit.
 */
class AngelScriptDebugSetupTest : UsefulTestCase() {

    /** The configuration the user last chose is the one they mean. */
    fun testSelectedConfigurationWins() {
        val temporary = setOf("from context menu")
        assertEquals(
            "from context menu",
            AngelScriptDebugConfigurations.choose(listOf("AngelScript", "from context menu"), "from context menu") { it in temporary }
        )
    }

    /**
     * Temporary configurations are discarded once enough newer ones pile up, so relying on one is how a
     * working setup disappears without anyone deleting it.
     */
    fun testPermanentConfigurationIsPreferredOverTemporary() {
        val temporary = setOf("from context menu")
        assertEquals(
            "AngelScript",
            AngelScriptDebugConfigurations.choose(listOf("from context menu", "AngelScript"), "MyProject") { it in temporary }
        )
        assertEquals(
            "from context menu",
            AngelScriptDebugConfigurations.choose(listOf("from context menu"), null) { it in temporary }
        )
        assertNull(AngelScriptDebugConfigurations.choose(emptyList<String>(), "MyProject") { false })
    }

    /** Under Run, lsp4ij connects the adapter but never sends it a breakpoint. */
    fun testOnlyTheDebugExecutorCanStartTheAdapter() {
        val factory = AngelScriptDebugAdapterDescriptorFactory()
        assertTrue(factory.canRun(DefaultDebugExecutor.EXECUTOR_ID))
        assertFalse(factory.canRun(DefaultRunExecutor.EXECUTOR_ID))
    }

    /**
     * lsp4ij's configuration type is package-private, so its factory is found by id. If lsp4ij renames
     * it, creating the configuration fails with nothing but a log line.
     */
    fun testDapConfigurationTypeIdMatchesLsp4ij() {
        val field = Class.forName("com.redhat.devtools.lsp4ij.dap.configurations.DAPConfigurationType").getField("ID")
        field.isAccessible = true
        assertEquals(field.get(null), AngelScriptDebugConfigurations.DAP_CONFIGURATION_TYPE_ID)
    }

    /**
     * The ids the code looks things up by must match the ones plugin.xml declares. A mismatched adapter
     * id finds no configuration and creates ones that name no adapter; a mismatched language mapping
     * stops breakpoints being placeable in script files; a mismatched group leaves the debugger status
     * bar widget doing nothing when clicked, and a mismatched widget id leaves it never refreshed.
     */
    fun testPluginXmlDeclaresTheIdsTheCodeUses() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(PLUGIN_XML))
        fun elements(tag: String) = document.getElementsByTagName(tag).let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
        }

        val serverId = AngelScriptDebugAdapterDescriptorFactory.SERVER_ID
        assertContainsElements(elements("debugAdapterServer").map { it.getAttribute("id") }, serverId)
        assertTrue(
            "script files must be mapped to the debug adapter, or breakpoints cannot be placed in them",
            elements("languageMapping").any { it.getAttribute("language") == "angelscript" && it.getAttribute("serverId") == serverId }
        )
        assertContainsElements(elements("group").map { it.getAttribute("id") }, AngelScriptDebugStatusBarWidget.POPUP_GROUP_ID)
        assertContainsElements(
            elements("statusBarWidgetFactory").map { it.getAttribute("id") },
            AngelScriptDebugStatusBarWidget.WIDGET_ID
        )
    }

    private companion object {
        const val PLUGIN_XML = "src/rider/main/resources/META-INF/plugin.xml"
    }
}
