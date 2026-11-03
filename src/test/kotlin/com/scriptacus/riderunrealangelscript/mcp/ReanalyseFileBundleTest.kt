package com.scriptacus.riderunrealangelscript.mcp

import com.intellij.testFramework.UsefulTestCase

/**
 * angelscript_get_problems has a file analysed again by sending the server a file event for it and
 * then a status request: the status comes back once the event was handled, and says whether the
 * server could analyse anything. Nothing reports that the event was handled otherwise - the server
 * only reports diagnostics that changed - so this rests on two things in the bundled server, which an
 * upstream change could take away without anything failing at build time.
 */
class ReanalyseFileBundleTest : UsefulTestCase() {

    private val bundle: String by lazy {
        val text = javaClass.getResourceAsStream("/js/angelscript-language-server.js")?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        assertNotNull("Bundled language server must be on the test classpath", text)
        text!!
    }

    /** The body of the handler registered with [registration], up to the end of its block. */
    private fun handler(registration: String): String {
        val start = bundle.indexOf(registration)
        assertTrue("The bundle has no $registration", start >= 0)
        val end = bundle.indexOf("\n});", start)
        assertTrue("The handler for $registration does not end", end > start)
        return bundle.substring(start, end)
    }

    fun testAFileEventHasTheFileReadAndAnalysedBeforeTheHandlerReturns() {
        val handler = handler("connection.onDidChangeWatchedFiles(")
        // A file no editor has open is read from disk again; an open one keeps the editor's text.
        assertTrue(handler, Regex("if \\(!\\w+\\.isOpened\\)\\s*UpdateModuleFromDisk\\(").containsMatchIn(handler))
        assertTrue(handler, handler.contains("ResolveModule(") && handler.contains("UpdateScriptModuleDiagnostics("))
        // All of it before returning, or the status request after it would not wait for it.
        for (deferral in listOf("setTimeout", "setImmediate", "await ", ".then(", "Promise")) {
            assertFalse("The file event handler defers work through $deferral:\n$handler", handler.contains(deferral))
        }
        // It analyses only with the queues empty, which is what pendingScripts counts.
        assertTrue(handler, handler.contains("ParseQueue.length == 0 && LoadQueue.length == 0"))
    }

    fun testTheStatusSaysHowManyScriptsAreStillQueued() {
        val status = handler("connection.onRequest(\"angelscript/getUnrealStatus\"")
        assertTrue(status, status.contains("pendingScripts: ParseQueue.length + LoadQueue.length"))
    }
}
