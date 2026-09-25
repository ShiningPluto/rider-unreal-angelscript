package com.scriptacus.riderunrealangelscript.usages

import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector.Access
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.testFramework.ParsingTestCase
import com.intellij.usages.ShowUsageViewSettings
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageView
import com.intellij.usages.UsageViewPresentation
import com.intellij.usages.UsageViewSettings
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageTypeProvider
import com.intellij.usages.rules.UsageGroupingRule
import com.intellij.usages.rules.UsageGroupingRuleEx
import com.intellij.usages.rules.UsageGroupingRuleProvider
import com.intellij.usages.rules.UsageGroupingRuleProviderEx
import com.redhat.devtools.lsp4ij.usages.LSPUsagePsiElement
import com.scriptacus.riderunrealangelscript.lang.AngelScriptParserDefinition

/**
 * Each case marks the identifier a reference was reported at with `«...»`, the way the language
 * server reports a range covering exactly one identifier.
 */
class AngelScriptAccessTest : ParsingTestCase("", "as", AngelScriptParserDefinition()) {

    // The code is inline, but the base class resolves this path on construction
    override fun getTestDataPath(): String = "src/test/resources/testData"

    fun testAssignmentWrites() {
        assertAccess(Access.Write, "«Count» = 1;")
        assertAccess(Access.Write, "Actor.«Health» = 0;")
        assertAccess(Access.Write, "this.«Health» = 0;")
        assertAccess(Access.Write, "(«Count») = 1;")
    }

    fun testChainedAssignmentWritesEveryTarget() {
        assertAccess(Access.Write, "«A» = B = 0;")
        assertAccess(Access.Write, "A = «B» = 0;")
    }

    fun testCompoundAssignmentReadsAndWrites() {
        assertAccess(Access.ReadWrite, "«Count» += 1;")
        assertAccess(Access.ReadWrite, "«Flags» |= 4;")
        assertAccess(Access.ReadWrite, "«Bits» >>>= 1;")
    }

    fun testIncrementAndDecrementReadAndWrite() {
        assertAccess(Access.ReadWrite, "«Count»++;")
        assertAccess(Access.ReadWrite, "«Count»--;")
        assertAccess(Access.ReadWrite, "++«Count»;")
        assertAccess(Access.ReadWrite, "--Actor.«Lives»;")
    }

    fun testAssignedValueIsRead() {
        assertAccess(Access.Read, "Count = «Other»;")
        assertAccess(Access.Read, "Count += «Step» * 2;")
        assertAccess(Access.Read, "Count = -«Step»;")
        assertAccess(Access.Read, "if (!«bActive») return;")
    }

    fun testObjectOfAWriteIsRead() {
        assertAccess(Access.Read, "«Actor».Health = 0;")
        assertAccess(Access.Read, "Owner.«Actor».Health = 0;")
    }

    fun testWriteThroughSubscriptReadsTheContainer() {
        assertAccess(Access.Read, "«Items»[0] = 1;")
        assertAccess(Access.Read, "«Items»[0]++;")
        assertAccess(Access.Read, "Items[«Index»] = 1;")
    }

    fun testCallsAreNotValues() {
        assertAccess(null, "«Fire»();")
        assertAccess(null, "Actor.«Fire»(1);")
        assertAccess(null, "Math::«Abs»(Delta);")
        assertAccess(null, "Super::«BeginPlay»();")
        assertAccess(Access.Read, "Actor.Fire(«Delta»);")
        assertAccess(Access.Read, "«OnHit».Broadcast();")
    }

    fun testQualifiersAndTypesAreNotValues() {
        assertAccess(null, "Mode = «EMode»::Idle;")
        assertAccess(Access.Read, "Mode = EMode::«Idle»;")
        assertAccess(null, "«FVector» Location;")
        assertAccess(null, "Actor = Cast<«AActor»>(Other);")
        assertAccess(Access.Read, "Actor = Cast<AActor>(«Other»);")
    }

    fun testDeclarationWritesOnlyWhenItAssigns() {
        assertAccess(Access.Write, "int «Count» = 0;")
        assertAccess(Access.Write, "FVector «Location»(0, 0, 0);")
        assertAccess(null, "int «Count»;")
        assertAccess(Access.Write, "for (int «i» = 0; i < 3; i++) {}")
    }

    fun testFormatStringExpressionIsRead() {
        assertAccess(Access.Read, "Print(f\"{«Count»}\");")
    }

    fun testDefaultStatementWrites() {
        assertAccessIn(Access.Write, "class AMyActor\n{\n    int Value;\n    default «Value» = 42;\n}\n")
        assertAccessIn(Access.Write, "class AMyActor\n{\n    default Mesh.«RelativeScale3D» = FVector(2);\n}\n")
        assertAccessIn(Access.Write, "class AMyActor\n{\n    float «Speed» = 600.0;\n}\n")
    }

    fun testRangeMustBeExactlyOneIdentifier() {
        val (file, range) = parse("void Test()\n{\n    «Count» = 1;\n}\n")
        assertNull(AngelScriptAccess.at(file, TextRange(range.startOffset, range.endOffset - 1)))
        assertNull(AngelScriptAccess.at(file, TextRange(range.startOffset - 1, range.endOffset)))
    }

    fun testReferencesAreGroupedAsReadsAndWrites() {
        val provider = AngelScriptUsageTypeProvider()
        assertEquals(UsageType.WRITE, provider.getUsageType(usage("«Count» = 1;")))
        assertEquals(UsageType.WRITE, provider.getUsageType(usage("«Count»++;")))
        assertEquals(UsageType.READ, provider.getUsageType(usage("Other = «Count»;")))
        assertNull(provider.getUsageType(usage("«Fire»();")))
    }

    fun testOtherResultKindsAreLeftToLsp4ij() {
        val provider = AngelScriptUsageTypeProvider()
        for (kind in LSPUsagePsiElement.UsageKind.values().filter { it != LSPUsagePsiElement.UsageKind.references }) {
            assertNull(kind.name, provider.getUsageType(usage("«Count» = 1;", kind)))
        }
    }

    fun testGroupingAsksTheUsageTypeProviders() {
        // Registered under the platform's own constant, so a wrong name in the rule fails here
        registerExtensionPoint(UsageTypeProvider.EP_NAME, UsageTypeProvider::class.java)
        registerExtension(UsageTypeProvider.EP_NAME, AngelScriptUsageTypeProvider())
        val rule = AngelScriptUsageTypeGroupingRule()
        assertEquals(UsageType.WRITE, rule.usageTypeOf(usage("«Count» = 1;"), UsageTarget.EMPTY_ARRAY))
        assertEquals(UsageType.READ, rule.usageTypeOf(usage("Other = «Count»;"), UsageTarget.EMPTY_ARRAY))
        // lsp4ij's provider, not registered here, answers for this in the IDE
        assertEquals(UsageType.UNCLASSIFIED, rule.usageTypeOf(usage("«Fire»();"), UsageTarget.EMPTY_ARRAY))

        val (file, range) = parse(inFunction("«Count» = 1;"))
        assertNull(rule.usageTypeOf(file.findElementAt(range.startOffset)!!, UsageTarget.EMPTY_ARRAY))
    }

    fun testGroupingFollowsRidersUsageTypeGrouping() {
        registerExtensionPoint(UsageGroupingRuleProvider.EP_NAME, UsageGroupingRuleProvider::class.java)
        val rider = FakeRiderGrouping()
        registerExtension(UsageGroupingRuleProvider.EP_NAME, rider)
        val provider = AngelScriptUsageTypeGroupingRuleProvider()
        // Registered too, so asking itself would recurse
        registerExtension(UsageGroupingRuleProvider.EP_NAME, provider)

        rider.groupsByUsageType = true
        assertSize(1, provider.getActiveRules(project, UsageViewSettings(), null))
        rider.groupsByUsageType = false
        assertEmpty(provider.getActiveRules(project, UsageViewSettings(), null))
    }

    fun testWithoutRidersRuleGroupingFollowsThePlatformSetting() {
        registerExtensionPoint(UsageGroupingRuleProvider.EP_NAME, UsageGroupingRuleProvider::class.java)
        val provider = AngelScriptUsageTypeGroupingRuleProvider()
        assertSize(1, provider.getActiveRules(project, UsageViewSettings(), null))
        // The Show Usages popup
        assertEmpty(provider.getActiveRules(project, ShowUsageViewSettings(), null))
    }

    /** Rider's provider, reduced to its rule for grouping by usage type and whether it is on. */
    private class FakeRiderGrouping : UsageGroupingRuleProviderEx {
        var groupsByUsageType = false
        private val usageTypeRule = object : UsageGroupingRuleEx {
            override fun getId() = "OccurrenceType"
        }

        override fun getActiveRules(project: Project, settings: UsageViewSettings, presentation: UsageViewPresentation?) =
            if (groupsByUsageType) arrayOf<UsageGroupingRule>(usageTypeRule) else UsageGroupingRule.EMPTY_ARRAY

        override fun getAllRules(project: Project, usageView: UsageView?) = arrayOf<UsageGroupingRule>(usageTypeRule)
    }

    private fun usage(statement: String, kind: LSPUsagePsiElement.UsageKind = LSPUsagePsiElement.UsageKind.references) =
        parse(inFunction(statement)).let { (file, range) -> LSPUsagePsiElement(file, range).also { it.kind = kind } }

    private fun assertAccess(expected: Access?, statement: String) = assertAccessIn(expected, inFunction(statement))

    private fun assertAccessIn(expected: Access?, code: String) {
        val (file, range) = parse(code)
        assertEquals(code, expected, AngelScriptAccess.at(file, range))
    }

    private fun inFunction(statement: String) = "void Test()\n{\n    $statement\n}\n"

    private fun parse(marked: String): Pair<PsiFile, TextRange> {
        val start = marked.indexOf('«')
        val end = marked.indexOf('»', start) - 1
        assertTrue("no «...» in $marked", start >= 0 && end > start)
        val file = createPsiFile("test", marked.replace("«", "").replace("»", ""))
        ensureParsed(file)
        return file to TextRange(start, end)
    }
}
