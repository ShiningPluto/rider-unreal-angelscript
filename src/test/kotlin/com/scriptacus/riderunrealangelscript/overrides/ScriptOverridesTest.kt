package com.scriptacus.riderunrealangelscript.overrides

import com.intellij.testFramework.UsefulTestCase
import com.scriptacus.riderunrealangelscript.navigation.CppBlueprintEvent
import com.scriptacus.riderunrealangelscript.navigation.CppSource

/**
 * Matching what the language server reports against what a header declares.
 *
 * The two meet at the class declaring the event and the event's script name, so the cases that
 * matter are the ones where a header's view and the server's differ: renamed events, the same event
 * name in another class, and URIs spelled the way the server spells them.
 */
class ScriptOverridesTest : UsefulTestCase() {

    /** An entry as lsp4j hands it over: numbers arrive as doubles. */
    private fun entry(cppClass: String, name: String, scriptClass: String, uri: String, line: Int = 3) = mapOf(
        "cppClass" to cppClass,
        "name" to name,
        "scriptClass" to scriptClass,
        "uri" to uri,
        "line" to line.toDouble(),
        "character" to 6.0,
    )

    private fun event(owner: String?, name: String) =
        CppBlueprintEvent(owner, name, 0, implementable = true, scriptNameMeta = null)

    private val reply = ScriptOverrides.fromReply(listOf(
        entry("UF1InteractableLogicBase", "ExecuteLogic", "UF1HandCartInteraction_UprightCart",
            "file:///f%3A/Project/Script/HandCart/F1HandCartInteractionLogic.as", line = 180),
        entry("UF1InteractableLogicBase", "ExecuteLogic", "UF1ExtractionAreaInteractLogic",
            "file:///f:/Project/Plugins/Extraction/Script/F1ExtractionAreaInteractLogic.as"),
        entry("AActor", "BeginPlay", "AF1ExtractionAreaVolume", "file:///f:/Project/Script/Volume.as"),
        entry("UActorComponent", "BeginPlay", "UF1CallerComponent", "file:///f:/Project/Script/Caller.as"),
    ))

    fun testOverridesAreFoundForTheDeclaringClass() {
        val found = reply.of(event("UF1InteractableLogicBase", "ExecuteLogic"))

        // Sorted by class, so the list the user picks from is stable.
        assertEquals(listOf("UF1ExtractionAreaInteractLogic", "UF1HandCartInteraction_UprightCart"), found.map { it.scriptClass })
        val upright = found.last()
        assertEquals("f:/Project/Script/HandCart/F1HandCartInteractionLogic.as", upright.path)
        assertEquals(180, upright.line)
        assertEquals(6, upright.character)
    }

    fun testAnEventScriptRenamesIsFoundUnderItsCppName() {
        assertEquals(listOf("AF1ExtractionAreaVolume"), reply.of(event("AActor", "ReceiveBeginPlay")).map { it.scriptClass })
    }

    fun testTheSameNameInAnotherClassIsAnotherEvent() {
        assertEquals(listOf("UF1CallerComponent"), reply.of(event("UActorComponent", "ReceiveBeginPlay")).map { it.scriptClass })
        assertEmpty(reply.of(event("APawn", "ReceiveBeginPlay")))
        assertEmpty(reply.of(event("UF1InteractableLogicBase", "TickLogic")))
    }

    fun testAnEventOutsideAnyClassHasNoOverrides() {
        assertEmpty(reply.of(event(null, "ExecuteLogic")))
    }

    fun testIncompleteEntriesAreSkipped() {
        val overrides = ScriptOverrides.fromReply(listOf(
            mapOf("cppClass" to "AActor", "name" to "BeginPlay"),
            "not an entry",
            entry("AActor", "BeginPlay", "AGood", "file:///f:/Project/Script/Good.as"),
        ))

        assertEquals(listOf("AGood"), overrides.of(event("AActor", "ReceiveBeginPlay")).map { it.scriptClass })
    }

    fun testEqualRepliesAreEqual() {
        val again = ScriptOverrides.fromReply(listOf(
            entry("AActor", "BeginPlay", "AF1ExtractionAreaVolume", "file:///f:/Project/Script/Volume.as"),
        ))
        val same = ScriptOverrides.fromReply(listOf(
            entry("AActor", "BeginPlay", "AF1ExtractionAreaVolume", "file:///f:/Project/Script/Volume.as"),
        ))

        assertEquals(again, same)
        assertFalse(again == reply)
        assertTrue(ScriptOverrides.EMPTY.isEmpty)
    }

    private fun implementations(count: Int) =
        (1..count).map { ScriptOverride("UF1Logic$it", "f:/Project/Script/Logic$it.as", 0, 0) }

    /** Measured on a real project: 41 classes override one event, which as a sentence spanned the screen. */
    fun testTheTooltipNamesOnlyTheFirstFewOfALongList() {
        val tooltip = ScriptOverridesCodeVisionProvider.tooltip(implementations(41))

        assertTrue(tooltip, tooltip.contains("Overridden in AngelScript by 41 classes"))
        assertTrue(tooltip, tooltip.contains("UF1Logic5<br"))
        assertFalse(tooltip, tooltip.contains("UF1Logic6<"))
        assertTrue(tooltip, tooltip.contains("and 36 more"))
        assertTrue(tooltip, tooltip.contains("Click to choose one to open"))
    }

    fun testTheTooltipListsAShortListInFull() {
        val tooltip = ScriptOverridesCodeVisionProvider.tooltip(implementations(5))

        assertTrue(tooltip, tooltip.contains("UF1Logic5<br"))
        assertFalse(tooltip, tooltip.contains("more"))
        // The same hint whether or not every class is named: the click is for picking one.
        assertTrue(tooltip, tooltip.contains("Click to choose one to open"))
    }

    fun testTheTooltipOfOneImplementation() {
        val tooltip = ScriptOverridesCodeVisionProvider.tooltip(implementations(1))

        assertTrue(tooltip, tooltip.startsWith("<html>"))
        assertTrue(tooltip, tooltip.contains("Overridden in AngelScript by<br"))
        assertTrue(tooltip, tooltip.contains("Click to open it"))
    }

    fun testTheWarningRangeFindsItsEvent() {
        val text = """
            class UThing : public UObject
            {
                UFUNCTION(BlueprintImplementableEvent)
                void OnThing();

                UFUNCTION(BlueprintImplementableEvent)
                void OnOther();
            };
        """.trimIndent()
        val events = CppSource(text).findBlueprintEvents()
        val at = text.indexOf("OnOther")

        assertEquals("OnOther", NotImplementedWarningSuppressor.eventAt(events, at, at + "OnOther".length)?.name)
    }

    /** A range computed before the latest edit is not trusted to mean the event that is there now. */
    fun testAWarningRangeThatTrailsAnEditHidesNothing() {
        val before = "class UThing\n{\n    UFUNCTION(BlueprintImplementableEvent)\n    void OnThing();\n};\n"
        val after = "class UThing\n{\n    // A new comment line\n    UFUNCTION(BlueprintImplementableEvent)\n    void OnThing();\n};\n"
        val stale = before.indexOf("OnThing")

        assertNull(NotImplementedWarningSuppressor.eventAt(CppSource(after).findBlueprintEvents(), stale, stale + "OnThing".length))
    }
}
