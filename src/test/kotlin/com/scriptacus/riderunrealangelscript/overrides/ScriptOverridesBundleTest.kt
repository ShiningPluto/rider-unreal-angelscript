package com.scriptacus.riderunrealangelscript.overrides

import com.intellij.testFramework.UsefulTestCase

/**
 * Guards the bundler patch behind the script implementations shown on C++ blueprint events.
 *
 * The request is text injected into the bundled language server, so it can call a name the bundle
 * does not define and still look applied. A failed request looks like "no script overrides" to the
 * client, which then shows nothing and hides no warnings - nothing would say it is broken.
 */
class ScriptOverridesBundleTest : UsefulTestCase() {

    private val bundle: String by lazy {
        val text = javaClass.getResourceAsStream("/js/angelscript-language-server.js")?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        assertNotNull("Bundled language server must be on the test classpath", text)
        text!!
    }

    fun testTheRequestIsInTheBundle() {
        assertTrue(
            "bundle-lsp.js did not add the script overrides handler",
            bundle.contains("connection.onRequest(\"angelscript/getScriptOverrides\"")
        )
    }

    /**
     * Declared at the start of a line, which is where esbuild puts module-level names. An indented
     * declaration is local to a function: `GlobsRemaining` is one, and a handler calling it failed
     * with "GlobsRemaining is not defined".
     */
    fun testTheHandlerCallsNamesDeclaredAtTheTopLevel() {
        for (name in listOf("GetAllLoadedModules", "IsServicingQueues", "HasTypesFromUnreal")) {
            assertTrue(
                "The bundle does not declare $name at the top level",
                Regex("(?:^|\\n)(?:function|var|let|const) $name\\b").containsMatchIn(bundle)
            )
        }
    }
}
