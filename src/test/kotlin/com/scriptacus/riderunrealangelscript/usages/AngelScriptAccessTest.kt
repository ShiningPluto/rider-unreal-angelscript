package com.scriptacus.riderunrealangelscript.usages

import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector.Access
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.testFramework.ParsingTestCase
import com.intellij.usages.ShowUsageViewSettings
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageViewSettings
import com.intellij.usages.impl.rules.UsageTypeProvider
import com.redhat.devtools.lsp4ij.usages.LSPUsagePsiElement
import com.redhat.devtools.lsp4ij.usages.LSPUsageTypeProvider
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

    fun testUsageFeatureGivesLsp4ijTheAccess() {
        val feature = AngelScriptUsageFeature()
        assertEquals(Access.Write, feature.getReadWriteAccess(usage("«Count» = 1;")))
        assertEquals(Access.ReadWrite, feature.getReadWriteAccess(usage("«Count»++;")))
        assertEquals(Access.Read, feature.getReadWriteAccess(usage("Other = «Count»;")))
        assertNull(feature.getReadWriteAccess(usage("«Fire»();")))
    }

    fun testReferencesAreGroupedByTheirAccessAsRiderGroupsCpp() {
        assertEquals("Write access", groupOfReference("«Count» = 1;"))
        assertEquals("Read write access", groupOfReference("«Count» += 1;"))
        assertEquals("Read write access", groupOfReference("«Count»++;"))
        assertEquals("Read access", groupOfReference("Other = «Count»;"))
    }

    fun testOtherUsagesAreGroupedByTheirUsageType() {
        // Registered under the platform's own constant, so a wrong name in the rule fails here
        registerExtensionPoint(UsageTypeProvider.EP_NAME, UsageTypeProvider::class.java)
        registerExtension(UsageTypeProvider.EP_NAME, LSPUsageTypeProvider())
        assertEquals("References", groupOfReference("«Fire»();"))
        val declaration = usage("int «Count» = 0;", LSPUsagePsiElement.UsageKind.declarations)
        assertEquals("Declarations", AngelScriptUsageTypeGroupingRule().groupNameOf(declaration, null, UsageTarget.EMPTY_ARRAY))

        val (file, range) = parse(inFunction("«Count» = 1;"))
        val notAnLspUsage = file.findElementAt(range.startOffset)!!
        assertNull(AngelScriptUsageTypeGroupingRule().groupNameOf(notAnLspUsage, Access.Write, UsageTarget.EMPTY_ARRAY))
    }

    fun testGroupingFollowsRidersUsageTypeGrouping() {
        val provider = WithRidersRule(true)
        assertSize(1, provider.getActiveRules(project, UsageViewSettings(), null))
        provider.enabled = false
        assertEmpty(provider.getActiveRules(project, UsageViewSettings(), null))
        // Rider never groups by occurrence type in the Show Usages popup
        provider.enabled = true
        assertEmpty(provider.getActiveRules(project, ShowUsageViewSettings(), null))
    }

    fun testWithoutRidersRuleGroupingFollowsThePlatformSetting() {
        val provider = WithRidersRule(null)
        assertSize(1, provider.getActiveRules(project, UsageViewSettings(), null))
        // The Show Usages popup
        assertEmpty(provider.getActiveRules(project, ShowUsageViewSettings(), null))
    }

    /** The provider, with the state of Rider's "Occurrence Type" rule given rather than read from Rider. */
    private class WithRidersRule(var enabled: Boolean?) : AngelScriptUsageTypeGroupingRuleProvider() {
        override fun ridersUsageTypeGrouping(project: Project): Boolean? = enabled
    }

    private fun usage(statement: String, kind: LSPUsagePsiElement.UsageKind = LSPUsagePsiElement.UsageKind.references) =
        parse(inFunction(statement)).let { (file, range) -> LSPUsagePsiElement(file, range).also { it.kind = kind } }

    /** The group of a reference whose usage has the access lsp4ij gets from the plugin. */
    private fun groupOfReference(statement: String): String? {
        val reference = usage(statement)
        val access = AngelScriptUsageFeature().getReadWriteAccess(reference)
        return AngelScriptUsageTypeGroupingRule().groupNameOf(reference, access, UsageTarget.EMPTY_ARRAY)
    }

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
