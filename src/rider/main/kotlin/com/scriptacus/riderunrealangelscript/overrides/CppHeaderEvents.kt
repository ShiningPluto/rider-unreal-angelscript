package com.scriptacus.riderunrealangelscript.overrides

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Key
import com.scriptacus.riderunrealangelscript.navigation.CppBlueprintEvent
import com.scriptacus.riderunrealangelscript.navigation.CppFileRanking
import com.scriptacus.riderunrealangelscript.navigation.CppSource

/**
 * The blueprint events a C++ document declares, read once per document revision.
 *
 * Rider's frontend has no C++ PSI, so this is the header text read by [CppSource]. Code vision asks
 * whenever it recomputes, and the warning suppressor for every highlighter the backend sends, often
 * several for one revision; the answer is kept on the document until it changes.
 */
object CppHeaderEvents {

    private class Cached(val stamp: Long, val events: List<CppBlueprintEvent>)

    private val CACHE = Key.create<Cached>("angelscript.cppBlueprintEvents")

    /** Headers this large are generated, and declare nothing script overrides. */
    private const val MAX_CHARS = 2_000_000

    fun inDocument(document: Document): List<CppBlueprintEvent> {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return emptyList()
        if (!CppFileRanking.isCppSource(file.name)) return emptyList()

        val stamp = document.modificationStamp
        document.getUserData(CACHE)?.takeIf { it.stamp == stamp }?.let { return it.events }

        val text = document.immutableCharSequence
        val events = if (text.length > MAX_CHARS || !mayDeclareEvents(text)) {
            emptyList()
        } else {
            CppSource(text.toString()).findBlueprintEvents()
        }
        document.putUserData(CACHE, Cached(stamp, events))
        return events
    }

    /** Most C++ files declare no events at all, and this is far cheaper than finding that out properly. */
    private fun mayDeclareEvents(text: CharSequence): Boolean =
        text.contains("UFUNCTION") &&
            (text.contains("BlueprintImplementableEvent", ignoreCase = true) ||
                text.contains("BlueprintNativeEvent", ignoreCase = true))
}
