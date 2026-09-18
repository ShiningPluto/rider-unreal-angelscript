package com.scriptacus.riderunrealangelscript.status

import com.intellij.testFramework.UsefulTestCase

/**
 * Guards the reporting of why AngelScript analysis is unavailable - the state that is otherwise
 * completely silent, since the language server produces nothing and logs nothing when it has no
 * type database.
 */
class AngelScriptUnrealStatusTest : UsefulTestCase() {

    fun testTypesLoadedIsTheOnlyThingThatMakesAnalysisAvailable() {
        assertEquals(
            AngelScriptUnrealStatus.READY,
            AngelScriptUnrealStatusService.classify(port = 27099, socketState = "open", typesLoaded = true)
        )
        assertTrue(AngelScriptUnrealStatus.READY.isAnalysisAvailable)

        for (status in AngelScriptUnrealStatus.entries.filter { it != AngelScriptUnrealStatus.READY }) {
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
        for (identifier in listOf("HasTypesFromUnreal()", "var unreal;", "var port = -1;")) {
            assertTrue(
                "Identifier '$identifier' the status handler depends on is gone from the bundle",
                bundle.contains(identifier)
            )
        }
    }
}
