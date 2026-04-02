package com.scriptacus.riderunrealangelscript.lang

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AngelScriptFoldingTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/resources/testData/folding"

    fun testFolding() {
        myFixture.testFolding("$testDataPath/FoldingData.as")
    }
}
