package com.scriptacus.riderunrealangelscript.lang.parser

import com.intellij.testFramework.ParsingTestCase
import com.scriptacus.riderunrealangelscript.lang.AngelScriptParserDefinition
import java.io.File

/**
 * Utility to validate grammar against real production AngelScript files.
 *
 * Tests parser against large production files to ensure grammar handles real-world code.
 * Usage: Run this as a JUnit test with working directory set to project root.
 */
class ValidateProductionFilesTest : ParsingTestCase("parser", "as", true, AngelScriptParserDefinition()) {
    override fun getTestDataPath(): String = "third-party/angelscript-reference/Script"

    private data class ParseResult(
        val file: File,
        val lines: Int,
        val success: Boolean,
        val errorMessage: String? = null
    )

    private fun parseFile(file: File): ParseResult {
        return try {
            val content = file.readText()
            val lines = content.lines().size

            myFile = createFile(file.name, content)
            ensureParsed(myFile)

            // Check for parse errors
            val hasErrors = toParseTreeText(myFile, false, false).contains("PsiErrorElement")

            ParseResult(file, lines, !hasErrors, if (hasErrors) "Contains parse errors" else null)
        } catch (e: Exception) {
            ParseResult(file, 0, false, e.message)
        }
    }

    // Some directories in the scripts are named like files, e.g. GoatLaserEyesCapability.as/
    private fun scriptFiles(scriptDir: File): List<File> =
        scriptDir.walkTopDown().filter { it.isFile && it.extension == "as" }.toList()

    fun testValidateLargestFiles() {
        val scriptDir = File(getTestDataPath())
        require(scriptDir.exists()) { "Script directory not found: ${scriptDir.absolutePath}" }

        // Find all .as files sorted by size (largest first)
        val files = scriptFiles(scriptDir)
            .sortedByDescending { it.length() }
            .take(30)

        println("\n=== Validating largest production files ===\n")

        var successCount = 0
        var failCount = 0
        val failures = mutableListOf<ParseResult>()

        files.forEach { file ->
            val result = parseFile(file)

            if (result.success) {
                successCount++
                println("✓ ${file.name} (${result.lines} lines)")
            } else {
                failCount++
                failures.add(result)
                println("✗ ${file.name} (${result.lines} lines) - ${result.errorMessage}")
            }
        }

        println("\n=== Summary ===")
        println("Success: $successCount")
        println("Failed: $failCount")
        println("Total tested: ${successCount + failCount}")
        if (successCount + failCount > 0) {
            println("Pass rate: ${(successCount * 100.0 / (successCount + failCount)).format(1)}%")
        }

        if (failures.isNotEmpty()) {
            println("\n=== Failures ===")
            failures.forEach {
                println("${it.file.relativeTo(scriptDir).path}: ${it.errorMessage}")
            }
        }

        // Require 100% pass rate on tested files
        assertTrue("$failCount files failed to parse", failCount == 0)
    }

    fun testValidateRandomSample() {
        val scriptDir = File(getTestDataPath())
        require(scriptDir.exists()) { "Script directory not found: ${scriptDir.absolutePath}" }

        // Get random sample of files weighted towards larger files
        val allFiles = scriptFiles(scriptDir)

        val sampleSize = minOf(50, allFiles.size)
        val sample = allFiles
            .sortedByDescending { it.length() }
            .take(sampleSize * 2) // Take top 100 by size
            .shuffled()
            .take(sampleSize) // Random 50 from those

        println("\n=== Validating random sample of $sampleSize files ===\n")

        var successCount = 0
        var failCount = 0
        val failures = mutableListOf<ParseResult>()

        sample.forEach { file ->
            val result = parseFile(file)

            if (result.success) {
                successCount++
            } else {
                failCount++
                failures.add(result)
                println("✗ ${file.name} (${result.lines} lines) - ${result.errorMessage}")
            }
        }

        println("\n=== Summary ===")
        println("Success: $successCount")
        println("Failed: $failCount")
        println("Total tested: ${successCount + failCount}")
        if (successCount + failCount > 0) {
            println("Pass rate: ${(successCount * 100.0 / (successCount + failCount)).format(1)}%")
        }

        if (failures.isNotEmpty()) {
            println("\n=== Failures ===")
            failures.forEach {
                println("${it.file.relativeTo(scriptDir).path}: ${it.errorMessage}")
            }
        }

        // Warn but don't fail if pass rate is high
        val totalTested = successCount + failCount
        if (totalTested > 0) {
            val passRate = successCount * 100.0 / totalTested
            assertTrue("Pass rate ${passRate.format(1)}% is below 95%", passRate >= 95.0)
        }
    }

    private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)
}
