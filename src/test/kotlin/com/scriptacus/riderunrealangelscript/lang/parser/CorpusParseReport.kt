package com.scriptacus.riderunrealangelscript.lang.parser

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import com.scriptacus.riderunrealangelscript.lang.AngelScriptParserDefinition
import java.io.File

/**
 * Parses every script in the reference corpus and writes the files that fail, one line each with
 * the first error, sorted so that the reports from before and after a grammar change can be diffed.
 * See docs/decisions/0008-take-the-grammar-from-the-engine-parser.md.
 *
 * Usage: ./gradlew test --tests "CorpusParseReport" -Dcorpus.report=build/corpus-before.txt -i
 * Without -Dcorpus.report it does nothing, so the full corpus is not parsed on every test run.
 */
class CorpusParseReport : ParsingTestCase("parser", "as", true, AngelScriptParserDefinition()) {
    override fun getTestDataPath(): String = "third-party/angelscript-reference/Script"

    fun testWriteReport() {
        val report = File(System.getProperty("corpus.report") ?: return)
        val scriptDir = File(getTestDataPath())
        require(scriptDir.exists()) { "Script directory not found: ${scriptDir.absolutePath}" }

        val files = scriptDir.walkTopDown().filter { it.isFile && it.extension.equals("as", ignoreCase = true) }.toList()
        val failures = files.mapNotNull { file ->
            val content = file.readText()
            val error = PsiTreeUtil.findChildrenOfType(createFile(file.name, content), PsiErrorElement::class.java)
                .minByOrNull { it.textRange.startOffset } ?: return@mapNotNull null
            val line = content.substring(0, error.textRange.startOffset).count { it == '\n' } + 1
            val source = content.lines()[line - 1].trim()
            "${file.relativeTo(scriptDir).invariantSeparatorsPath}:$line\t$source\t${error.errorDescription.take(160)}"
        }.sorted()

        val summary = "files=${files.size} failed=${failures.size}"
        report.parentFile?.mkdirs()
        report.writeText((listOf(summary) + failures).joinToString("\n", postfix = "\n"))
        println("$summary -> ${report.absolutePath}")
    }
}
