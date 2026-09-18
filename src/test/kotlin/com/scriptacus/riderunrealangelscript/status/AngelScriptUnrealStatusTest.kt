package com.scriptacus.riderunrealangelscript.status

import com.intellij.testFramework.UsefulTestCase

/**
 * Guards the reporting of why AngelScript analysis is unavailable - the state that is otherwise
 * completely silent, since the language server produces nothing and logs nothing when it has no
 * type database.
 */
class AngelScriptUnrealStatusTest : UsefulTestCase() {

    /** Only a loaded type database - live or cached - makes analysis work. */
    fun testAnalysisIsOnlyAvailableWithATypeDatabase() {
        assertEquals(
            AngelScriptUnrealStatus.READY,
            AngelScriptUnrealStatusService.classify(port = 27099, socketState = "open", typesLoaded = true)
        )
        assertTrue(AngelScriptUnrealStatus.READY.isAnalysisAvailable)

        val working = setOf(AngelScriptUnrealStatus.READY, AngelScriptUnrealStatus.CACHED_TYPES)
        for (status in AngelScriptUnrealStatus.entries.filter { it !in working }) {
            assertFalse("$status must not claim analysis works", status.isAnalysisAvailable)
        }
    }

    /** An unconfigured port is the signature of a configuration push that never arrived. */
    fun testUnconfiguredPortIsReportedSeparatelyFromDisconnection() {
        assertEquals(
            AngelScriptUnrealStatus.NOT_CONFIGURED,
            AngelScriptUnrealStatusService.classify(port = -1, socketState = "disconnected", typesLoaded = false)
        )
        assertEquals(
            AngelScriptUnrealStatus.NOT_CONFIGURED,
            AngelScriptUnrealStatusService.classify(port = null, socketState = null, typesLoaded = false)
        )
    }

    /**
     * Cached types make analysis work, but they describe the engine as of the last session, so they
     * must never be presented as a live connection.
     */
    fun testCachedTypesAreUsableButReportedSeparatelyFromLiveTypes() {
        assertEquals(
            AngelScriptUnrealStatus.CACHED_TYPES,
            AngelScriptUnrealStatusService.classify(
                port = 27099, socketState = "disconnected", typesLoaded = true, usingCachedTypes = true
            )
        )
        assertTrue(AngelScriptUnrealStatus.CACHED_TYPES.isAnalysisAvailable)

        // A live editor always wins: same reply but not from cache must read as READY.
        assertEquals(
            AngelScriptUnrealStatus.READY,
            AngelScriptUnrealStatusService.classify(
                port = 27099, socketState = "open", typesLoaded = true, usingCachedTypes = false
            )
        )
    }

    /**
     * HasTypesFromUnreal() never goes back to false, so a database outlives the editor that sent
     * it. Reporting a live connection on the strength of types alone claimed an editor that had
     * been closed.
     */
    fun testTypesOutlivingTheirEditorAreNotReportedAsALiveConnection() {
        assertEquals(
            AngelScriptUnrealStatus.CACHED_TYPES,
            AngelScriptUnrealStatusService.classify(
                port = 27099, socketState = "disconnected", typesLoaded = true, usingCachedTypes = false
            )
        )
    }

    /** A socket opening does not make cached types live; only a delivered database does. */
    fun testCachedTypesStayCachedUntilTheEditorDeliversADatabase() {
        assertEquals(
            AngelScriptUnrealStatus.CACHED_TYPES,
            AngelScriptUnrealStatusService.classify(
                port = 27099, socketState = "open", typesLoaded = true, usingCachedTypes = true
            )
        )
    }

    fun testOpenSocketWithoutTypesIsDistinctFromNoEditor() {
        assertEquals(
            AngelScriptUnrealStatus.LOADING_TYPES,
            AngelScriptUnrealStatusService.classify(port = 27099, socketState = "open", typesLoaded = false)
        )
        // A socket still dialling has not reached an editor yet, so it must not read as connected.
        assertEquals(
            AngelScriptUnrealStatus.DISCONNECTED,
            AngelScriptUnrealStatusService.classify(port = 27099, socketState = "connecting", typesLoaded = false)
        )
        assertEquals(
            AngelScriptUnrealStatus.DISCONNECTED,
            AngelScriptUnrealStatusService.classify(port = 27099, socketState = "disconnected", typesLoaded = false)
        )
    }

    /**
     * The bundler injects the status handler into the bundled server by regex. A rename upstream
     * makes the patch a silent no-op, which would leave the status permanently wrong rather than
     * failing the build.
     */
    fun testBundledServerExposesTheStatusHandler() {
        val bundle = javaClass.getResourceAsStream("/js/angelscript-language-server.js")?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        assertNotNull("Bundled language server must be on the test classpath", bundle)

        assertTrue(
            "bundle-lsp.js did not inject the angelscript/getUnrealStatus handler",
            bundle!!.contains("connection.onRequest(\"angelscript/getUnrealStatus\"")
        )
        assertTrue(
            "bundle-lsp.js did not inject the type database cache support",
            bundle.contains("function __asSaveTypeCache()") && bundle.contains("function __asLoadTypeCache()")
        )
        assertTrue(
            "bundle-lsp.js did not hook database chunks for caching",
            bundle.contains("__asTypeCache.chunks.push(dbStr)")
        )
        for (identifier in listOf("HasTypesFromUnreal()", "var unreal;", "var port = -1;")) {
            assertTrue(
                "Identifier '$identifier' the status handler depends on is gone from the bundle",
                bundle.contains(identifier)
            )
        }
    }
}
