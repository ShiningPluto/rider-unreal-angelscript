package com.scriptacus.riderunrealangelscript.mcp

import com.intellij.testFramework.ParsingTestCase
import com.scriptacus.riderunrealangelscript.lang.AngelScriptParserDefinition
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/**
 * How angelscript_find_references reports the ranges the language server found: each with its line
 * of text, and whether it reads or writes, from the plugin's own parse tree.
 */
class ScriptReferencesTest : ParsingTestCase("", "as", AngelScriptParserDefinition()) {

    // The code is inline, but the base class resolves this path on construction
    override fun getTestDataPath(): String = "src/test/resources/testData"

    private val code = """
        |class AMyCharacter : ACharacter
        |{
        |    int Health = 100;
        |
        |    void TakeDamage(int Damage)
        |    {
        |        Health -= Damage;
        |        Print(f"{Health}");
        |        if (Health <= 0)
        |            TakeDamage(0);
        |    }
        |}
        |""".trimMargin()

    fun testEachReferenceHasItsLineAndWhetherItReadsOrWrites() {
        val file = createPsiFile("test", code)
        val references = ScriptReferences.inFile(code, file, rangesOf(code, "Health") + rangesOf(code, "TakeDamage"))

        assertEquals(
            listOf(
                ReferenceItem(3, 9, "int Health = 100;", "write"),
                ReferenceItem(7, 9, "Health -= Damage;", "readWrite"),
                ReferenceItem(8, 18, "Print(f\"{Health}\");", "read"),
                ReferenceItem(9, 13, "if (Health <= 0)", "read"),
                // A declaration and a call are not a value being read or written
                ReferenceItem(5, 10, "void TakeDamage(int Damage)", null),
                ReferenceItem(10, 13, "TakeDamage(0);", null),
            ),
            references,
        )
    }

    fun testWithoutAParseTreeThereIsNoAccess() {
        val references = ScriptReferences.inFile(code, null, rangesOf(code, "Health"))
        assertEquals(listOf(null), references.map { it.access }.distinct())
        assertEquals("Health -= Damage;", references[1].text)
    }

    fun testLinesEndAsTheServerCountsThem() {
        // The server counts a line ended by \r\n, \r or \n, and so must the text it is matched against.
        val text = "First\r\nSecond\rThird\nFourth"
        val references = ScriptReferences.inFile(text, null, (0..3).map { Range(Position(it, 1), Position(it, 3)) })
        assertEquals(listOf("First", "Second", "Third", "Fourth"), references.map { it.text })
        assertEquals("ec", ScriptReferences.textAt(text, Range(Position(1, 1), Position(1, 3))))
        // A position past the end of its line stops at the line's end
        assertEquals("ird", ScriptReferences.textAt(text, Range(Position(2, 2), Position(2, 40))))
        assertEquals("", ScriptReferences.textAt(text, Range(Position(9, 0), Position(9, 1))))
    }

    fun testALongLineIsCut() {
        val long = "x".repeat(500)
        val text = ScriptReferences.inFile(long, null, listOf(Range(Position(0, 0), Position(0, 1)))).single().text
        assertEquals(201, text.length)
        assertTrue(text.endsWith("…"))
    }

    /** Every whole-word occurrence of [name] in [text], as the server reports a range. */
    private fun rangesOf(text: String, name: String): List<Range> =
        Regex("\\b${Regex.escape(name)}\\b").findAll(text).map { match ->
            val line = text.substring(0, match.range.first).count { it == '\n' }
            val column = match.range.first - (text.lastIndexOf('\n', match.range.first - 1) + 1)
            Range(Position(line, column), Position(line, column + name.length))
        }.toList()
}
