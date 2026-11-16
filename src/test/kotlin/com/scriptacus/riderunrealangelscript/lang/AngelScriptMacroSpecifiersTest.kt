package com.scriptacus.riderunrealangelscript.lang

import com.intellij.testFramework.UsefulTestCase

/**
 * Covers the specifier set used to colour macro arguments: it must contain the specifiers the
 * server documents, and must not turn ordinary identifiers into specifiers.
 */
class AngelScriptMacroSpecifiersTest : UsefulTestCase() {

    private val names = AngelScriptMacroSpecifiers.NAMES

    fun testCommonSpecifiersAreKnown() {
        assertTrue(names.contains("DefaultComponent"))
        assertTrue(names.contains("RootComponent"))
        assertTrue(names.contains("EditAnywhere"))
        assertTrue(names.contains("BlueprintCallable"))
        assertTrue(names.contains("Abstract"))
        assertTrue(names.contains("BindWidget"))
    }

    fun testOrdinaryIdentifiersAreNotSpecifiers() {
        assertFalse(names.contains("AActor"))
        assertFalse(names.contains("Print"))
        assertFalse(names.contains("Foo"))
    }

    fun testCaseVariantsFromTheServerAreKept() {
        // The server's tables list some specifiers in both spellings; both are accepted.
        assertTrue(names.contains("meta"))
        assertTrue(names.contains("Meta"))
        assertTrue(names.contains("replicationcondition"))
        assertTrue(names.contains("ReplicationCondition"))
    }
}
