package com.scriptacus.riderunrealangelscript.actions

import com.intellij.testFramework.UsefulTestCase

/**
 * Covers how the code lens command actions pick their payload argument. The commands that save the
 * script first carry `[uri, name]`; `openAssets` carries `[name]`.
 */
class AngelScriptAssetCommandActionsTest : UsefulTestCase() {

    fun testSaveAndCreateBlueprintTakesTheSecondArgument() {
        assertEquals(
            "AActor",
            AngelScriptExecuteCommandAction.payloadOf(listOf("file:///C:/Script/Foo.as", "AActor"), true)
        )
    }

    fun testSaveAndEditAssetTakesTheAssetPath() {
        assertEquals(
            "/Script/AngelscriptAssets.MyDataAsset",
            AngelScriptExecuteCommandAction.payloadOf(
                listOf("file:///C:/Script/Foo.as", "/Script/AngelscriptAssets.MyDataAsset"),
                true
            )
        )
    }

    fun testOpenAssetsTakesTheFirstArgument() {
        assertEquals("AActor", AngelScriptExecuteCommandAction.payloadOf(listOf("AActor"), false))
    }

    fun testMissingArgumentGivesNull() {
        assertNull(AngelScriptExecuteCommandAction.payloadOf(emptyList(), true))
        assertNull(AngelScriptExecuteCommandAction.payloadOf(listOf("file:///C:/Script/Foo.as"), true))
        assertNull(AngelScriptExecuteCommandAction.payloadOf(emptyList(), false))
    }

    fun testEmptyArgumentGivesNull() {
        assertNull(AngelScriptExecuteCommandAction.payloadOf(listOf("file:///C:/Script/Foo.as", ""), true))
        assertNull(AngelScriptExecuteCommandAction.payloadOf(listOf(""), false))
    }

    fun testNonStringArgumentGivesNull() {
        assertNull(AngelScriptExecuteCommandAction.payloadOf(listOf(42), false))
    }
}
