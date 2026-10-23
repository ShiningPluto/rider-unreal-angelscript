package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.testFramework.UsefulTestCase

/**
 * Guards the bundler patch that drops the compile errors of an editor session that has ended.
 *
 * A restarted Unreal editor never says that a file the previous one complained about now compiles,
 * so without the patch the error stays on a reference to a C++ symbol that exists by now - while
 * hover, which reads the new types, already finds it. The patch is a regex over the bundle, so an
 * upstream rewrite would turn it into a silent no-op and bring that back.
 */
class StaleCompileDiagnosticsBundleTest : UsefulTestCase() {

    private val bundle: String by lazy {
        val text = javaClass.getResourceAsStream("/js/angelscript-language-server.js")?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        assertNotNull("Bundled language server must be on the test classpath", text)
        text!!
    }

    fun testTheFinishedDatabaseDropsThePreviousConnectionsCompileDiagnostics() {
        assertTrue(
            "bundle-lsp.js did not add the stale compile diagnostics support",
            bundle.contains("function __asOnDatabaseFinished()")
        )
        assertTrue(
            "bundle-lsp.js did not hook the support into the DebugDatabaseFinished handler",
            Regex("""msg\.type == \d+ /\* DebugDatabaseFinished \*/\) \{\s*__asOnDatabaseFinished\(\);""")
                .containsMatchIn(bundle)
        )
    }

    /**
     * Declared at the start of a line, which is where esbuild puts module-level names. A name the
     * bundle does not define would only fail once an editor connects, and then only into a log
     * line, leaving the stale errors where they were.
     */
    fun testTheSupportCallsNamesDeclaredAtTheTopLevel() {
        for (name in listOf("CompileDiagnostics", "UpdateCompileDiagnostics", "GetModuleByUri", "unreal")) {
            assertTrue(
                "The bundle does not declare $name at the top level",
                Regex("(?:^|\\n)(?:function|var|let|const) $name\\b").containsMatchIn(bundle)
            )
        }
    }
}
