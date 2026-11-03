package com.scriptacus.riderunrealangelscript.mcp

import com.intellij.testFramework.UsefulTestCase

/**
 * angelscript_get_type_hierarchy starts its walk from an item it makes out of a type's name, with
 * no position the server could prepare one from - a type C++ declares has none. That works because
 * the bundled server finds the type of an item by the name in its `data` and nothing else, and puts
 * that name there in the items it answers with. An upstream change to either would leave every
 * answer empty without failing anything at build time.
 */
class TypeHierarchyBundleTest : UsefulTestCase() {

    private val bundle: String by lazy {
        val text = javaClass.getResourceAsStream("/js/angelscript-language-server.js")?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        assertNotNull("Bundled language server must be on the test classpath", text)
        text!!
    }

    /** The body of the top-level function [name], up to the end of its block. */
    private fun function(name: String): String {
        val start = bundle.indexOf("\nfunction $name(")
        assertTrue("The bundle has no function $name", start >= 0)
        val end = bundle.indexOf("\n}", start)
        assertTrue("The function $name does not end", end > start)
        return bundle.substring(start, end)
    }

    fun testSupertypesAndSubtypesFindTheTypeByTheNameInTheItemsData() {
        for (name in listOf("GetTypeHierarchySupertypes", "GetTypeHierarchySubtypes")) {
            val body = function(name)
            assertTrue(body, Regex("""let dbtype = GetTypeByName\(item\.data\);""").containsMatchIn(body))
        }
        // One level each: the supertype, and the types whose supertype it is
        assertTrue(function("GetTypeHierarchySubtypes").contains("checkType.supertype == dbtype.name"))
    }

    fun testTheItemsTheServerAnswersWithCarryTheTypesName() {
        assertTrue(function("GetTypeHierarchyItem").contains("data: dbtype.name"))
    }
}
