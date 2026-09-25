package com.scriptacus.riderunrealangelscript.overrides

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.ProjectManager
import com.jetbrains.rd.ide.model.DefaultHighlighterModel
import com.jetbrains.rd.ide.model.HighlighterModel
import com.jetbrains.rdclient.daemon.FrontendHighlighterSuppressionHandler
import com.scriptacus.riderunrealangelscript.navigation.CppBlueprintEvent

/**
 * Drops Rider's "BlueprintImplementableEvent function is not implemented in any blueprint" warning
 * from an event that a script class implements.
 *
 * The warning comes from ReSharper C++ in Rider's backend, which decides it from the Unreal asset
 * cache alone and offers no way to feed it other implementations: the search behind it
 * (`UEAssetUsagesSearcher`) is a concrete component over `.uasset` files, and none of the C++
 * assemblies are in a published SDK. What a plugin can do is decline to show it: Rider asks this
 * extension point about every highlighter the backend sends, as it arrives, before adding it to the
 * editor. The inspection id identifies the warning, and its range is the event's name.
 *
 * The question is only asked once per highlighter, so the answer has to be ready then, which is why
 * it comes from [ScriptOverrideService.overrides] rather than from the language server. A warning
 * already on screen when an override is added or removed stays as it is until the backend sends it
 * again, which it does when the header is reopened or the declaration edited.
 */
class NotImplementedWarningSuppressor : FrontendHighlighterSuppressionHandler {

    companion object {
        private val LOG = Logger.getInstance(NotImplementedWarningSuppressor::class.java)

        /** ReSharper C++'s configurable severity id for the warning, which Rider sends as the inspection id. */
        const val INSPECTION_ID = "CppUEBlueprintImplementableEventNotImplemented"

        /**
         * The event whose name is exactly [start]..[end].
         *
         * Rider asks before it repairs a highlighter's range to the editor's latest revision, so
         * after an edit the range can be off. Nothing is guessed then: the warning stays, and the
         * next analysis sends it again with a range that fits.
         */
        fun eventAt(events: List<CppBlueprintEvent>, start: Int, end: Int): CppBlueprintEvent? =
            events.firstOrNull { it.nameOffset == start && it.nameOffset + it.name.length == end }
    }

    override fun shouldSuppress(highlighterModel: HighlighterModel, document: Document): Boolean {
        if (highlighterModel !is DefaultHighlighterModel || highlighterModel.info?.inspectionId != INSPECTION_ID) {
            return false
        }
        val event = eventAt(CppHeaderEvents.inDocument(document), highlighterModel.start, highlighterModel.end)
            ?: return false

        // No project to hand, so ask every open one; a class name only matches in the project whose
        // scripts subclass it.
        val implementations = ProjectManager.getInstance().openProjects.firstNotNullOfOrNull { project ->
            project.getServiceIfCreated(ScriptOverrideService::class.java)?.overrides?.of(event)?.takeIf { it.isNotEmpty() }
        } ?: return false

        if (LOG.isDebugEnabled) {
            LOG.debug("Hiding $INSPECTION_ID on ${event.owner}::${event.name}, implemented by ${implementations.joinToString { it.scriptClass }}")
        }
        return true
    }
}
