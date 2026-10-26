package com.scriptacus.riderunrealangelscript.navigation

import com.intellij.testFramework.UsefulTestCase

/**
 * Guards the bundler patch that C++ navigation is built on.
 *
 * The handlers are injected as text into the bundled language server, so they can reference a name
 * that no longer exists there and still look perfectly applied. That is what happened in 0.9.10:
 * the esbuild layout hoisted upstream's functions to the top level, the injected code kept calling
 * `scriptsymbols.GetCppSymbol`, and both navigation requests failed at runtime with
 * "scriptsymbols is not defined" - silently, because a failed request means "no C++ symbol here"
 * to the client, which then simply does nothing.
 */
class CppNavigationBundleTest : UsefulTestCase() {

    private val bundle: String by lazy {
        val text = javaClass.getResourceAsStream("/js/angelscript-language-server.js")?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        assertNotNull("Bundled language server must be on the test classpath", text)
        text!!
    }

    fun testNavigationRequestsAreInTheBundle() {
        assertTrue(
            "bundle-lsp.js did not add the C++ navigation handlers",
            bundle.contains("connection.onRequest(\"angelscript/getCppSymbol\"") &&
                bundle.contains("connection.onRequest(\"angelscript/navigateToCpp\"")
        )
    }

    /** Every name the handlers call has to be defined in the same bundle. */
    fun testTheHandlersCallNamesTheBundleDefines() {
        for (name in listOf("GetCppSymbol", "GetDefinition", "GetAndParseModule", "buildGoTo")) {
            assertTrue(
                "The bundle calls $name but does not define it",
                Regex("(?:^|\\n)\\s*function $name\\b").containsMatchIn(bundle)
            )
        }
        assertTrue("The handlers must call the bundled GetCppSymbol", bundle.contains("GetCppSymbol(asmodule, position)"))
        assertTrue(
            "A symbol declared in script is not a C++ symbol: the handlers must ask GetDefinition first",
            bundle.contains("GetDefinition(asmodule, position)") &&
                bundle.contains("let cppSymbol = __asCppSymbolAt(asmodule, position)")
        )
        assertFalse(
            "The handlers reach for a module namespace the esbuild bundle does not have",
            bundle.contains("scriptsymbols.")
        )
    }
}
