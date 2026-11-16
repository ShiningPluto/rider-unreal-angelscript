package com.scriptacus.riderunrealangelscript.actions

import com.intellij.testFramework.UsefulTestCase
import com.scriptacus.riderunrealangelscript.actions.AngelScriptSnippets.Context
import com.scriptacus.riderunrealangelscript.actions.AngelScriptSnippets.expand

/**
 * Covers the VS Code snippet subset the AngelScript language server sends for its file-template
 * code lenses (`editor.action.insertSnippet`).
 */
class AngelScriptSnippetsTest : UsefulTestCase() {

    private val context = Context(
        fileName = "MyActor.as",
        filePath = "C:\\Project\\Script\\MyActor.as",
        clipboard = "clip",
    )

    fun testActorTemplateExpandsNameAndCaret() {
        val snippet = """
            class U${'$'}{TM_FILENAME_BASE} : AActor
            {
            ${'$'}0
            	UFUNCTION(BlueprintOverride)
            	void BeginPlay()
            	{
            	}
            };
        """.trimIndent()

        val expanded = expand(snippet, context)

        assertTrue(expanded.text.startsWith("class UMyActor : AActor"))
        assertFalse(expanded.text.contains("TM_FILENAME_BASE"))
        assertFalse(expanded.text.contains("\$0"))
        // The caret sits where $0 was, on the empty line inside the class body.
        val caretLine = expanded.text.substring(0, expanded.caretOffset).count { it == '\n' }
        assertEquals(2, caretLine)
    }

    fun testFileVariablesAreResolved() {
        val expanded = expand(
            "${'$'}{TM_FILENAME}|${'$'}{TM_FILENAME_BASE}|${'$'}{TM_DIRECTORY}|${'$'}{TM_DIRECTORY_BASE}|${'$'}{CLIPBOARD}",
            context
        )
        assertEquals("MyActor.as|MyActor|C:/Project/Script|Script|clip", expanded.text)
    }

    fun testPlaceholderDefaultValueIsInserted() {
        val expanded = expand("void ${'$'}{1:DoThing}(${'$'}0)", context)
        assertEquals("void DoThing()", expanded.text)
        assertEquals(expanded.text.length - 1, expanded.caretOffset)
    }

    fun testFirstPlaceholderBecomesCaretWhenNoDollarZero() {
        val expanded = expand("class ${'$'}{1:Name} { }", context)
        assertEquals("class Name { }", expanded.text)
        assertEquals("class ".length, expanded.caretOffset)
    }

    fun testEscapesAreResolved() {
        val expanded = expand("cost \\${'$'}5 and \\\\ backslash", context)
        assertEquals("cost \$5 and \\ backslash", expanded.text)
    }

    fun testUnknownVariableFallsBackToItsDefault() {
        val expanded = expand("${'$'}{UNKNOWN_VARIABLE:fallback}", context)
        assertEquals("fallback", expanded.text)
    }

    fun testAnUnclosedBraceStaysLiteral() {
        val expanded = expand("if (a ${'$'}{ b)", context)
        assertEquals("if (a \${ b)", expanded.text)
    }

    fun testNoCaretMarkerPutsTheCaretAtTheEnd() {
        val expanded = expand("hello", context)
        assertEquals("hello", expanded.text)
        assertEquals(5, expanded.caretOffset)
    }
}
