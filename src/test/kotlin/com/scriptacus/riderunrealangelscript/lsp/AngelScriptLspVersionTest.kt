package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.testFramework.UsefulTestCase

/**
 * Guards the one thing that can silently rot here: the version is generated at bundle time, so a
 * broken or absent resource has to degrade to something readable rather than to a crash or a lie.
 */
class AngelScriptLspVersionTest : UsefulTestCase() {

    fun testFullRevisionIsReported() {
        assertEquals(
            "v1.9.2 (0b7b872, 2026-02-24)",
            AngelScriptLspVersion.format("1.9.2", "0b7b872", "2026-02-24")
        )
    }

    /** Builds from a source archive have no git to ask, but the release still identifies itself. */
    fun testVersionAloneIsEnough() {
        assertEquals("v1.9.2", AngelScriptLspVersion.format("1.9.2", null, null))
        assertEquals("v1.9.2 (0b7b872)", AngelScriptLspVersion.format("1.9.2", "0b7b872", null))
    }

    /** A missing resource must read as unknown rather than as some plausible-looking version. */
    fun testMissingVersionIsNotInvented() {
        assertEquals("unknown", AngelScriptLspVersion.format(null, "0b7b872", "2026-02-24"))
    }
}
