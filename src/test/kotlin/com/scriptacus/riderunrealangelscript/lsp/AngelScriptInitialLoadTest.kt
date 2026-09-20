package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.testFramework.UsefulTestCase

/**
 * Guards the two bundler patches that decide when analysis becomes available after the server
 * starts. Both are regexes over the bundled output, so an upstream rewrite turns them into silent
 * no-ops - and the failure they prevent is silent too: symbols from other files simply do not
 * exist for the first minutes of a session, and go-to-definition keeps failing for the rest of it.
 */
class AngelScriptInitialLoadTest : UsefulTestCase() {

    private val bundle: String by lazy {
        val text = javaClass.getResourceAsStream("/js/angelscript-language-server.js")?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        assertNotNull("Bundled language server must be on the test classpath", text)
        text!!
    }

    /**
     * Upstream waits for every workspace root to be scanned before loading anything. lsp4ij hands
     * the server all of the solution's content roots, so on a real project that is minutes of the
     * server knowing only the files the editor has open.
     */
    fun testEachWorkspaceRootIsLoadedAsSoonAsItHasBeenScanned() {
        assertTrue(
            "bundle-lsp.js did not patch the workspace root scan",
            bundle.contains("__asAllRootsScanned = true;")
        )
        // Waiting for the last root is exactly what the patch removes; if this reappears, the
        // patched form was replaced by upstream's.
        assertFalse(
            "The bundle still holds loading back until the last root has been scanned",
            bundle.contains("if (GlobsRemaining <= 0)\n        TickQueues();")
        )
    }

    /**
     * Resolution is cached on the module and only invalidated by an edit, so a module resolved
     * before the project finished loading stays wrong for the whole session. Upstream re-resolves
     * only when a type database arrives, which is never for a project running off a saved one.
     */
    fun testModulesResolvedBeforeTheProjectLoadedAreReResolved() {
        assertTrue(
            "bundle-lsp.js did not inject the initial load settle hook",
            bundle.contains("function __asOnQueuesSettled()") && bundle.contains("__asOnQueuesSettled();")
        )
        assertTrue(
            "The settle hook must re-resolve rather than merely log",
            bundle.contains("__asInitialLoadReResolved = true;") && bundle.contains("ReResolveAllModules();")
        )
    }

    /** The extra roots the client names are only useful if the server still reads them. */
    fun testAdditionalScriptRootFoldersAreStillAccepted() {
        assertTrue(
            "The server no longer reads initializationOptions.additionalScriptRootFolders",
            bundle.contains("additionalScriptRootFolders")
        )
    }
}
