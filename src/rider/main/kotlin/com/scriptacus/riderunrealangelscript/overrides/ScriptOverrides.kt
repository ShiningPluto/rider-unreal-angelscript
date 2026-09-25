package com.scriptacus.riderunrealangelscript.overrides

import com.scriptacus.riderunrealangelscript.lsp.AngelScriptDiagnosticsStore
import com.scriptacus.riderunrealangelscript.navigation.CppBlueprintEvent

/** A script method overriding a blueprint event that C++ declares. */
data class ScriptOverride(
    val scriptClass: String,
    /** The script file, with `/` separators. */
    val path: String,
    /** 0-based, as the language server counts, pointing at the method name. */
    val line: Int,
    val character: Int,
)

/**
 * Which script classes override which C++ blueprint events, as the language server last reported.
 *
 * An event is identified by the C++ class that declares it and the name script knows it by, which
 * is how the server reports it: the script classes and the type database meet there, and nowhere
 * else. Getting from a header's declaration to that name is [CppBlueprintEvent.scriptNames].
 */
class ScriptOverrides private constructor(private val byEvent: Map<Key, List<ScriptOverride>>) {

    private data class Key(val cppClass: String, val scriptName: String)

    /** Whether the server has reported anything at all. */
    val isEmpty: Boolean get() = byEvent.isEmpty()

    /** The script classes overriding [event], by class name; empty when there are none. */
    fun of(event: CppBlueprintEvent): List<ScriptOverride> {
        val owner = event.owner ?: return emptyList()
        return event.scriptNames()
            .flatMap { byEvent[Key(owner, it)].orEmpty() }
            .distinct()
            .sortedBy { it.scriptClass }
    }

    override fun equals(other: Any?): Boolean = other is ScriptOverrides && other.byEvent == byEvent

    override fun hashCode(): Int = byEvent.hashCode()

    override fun toString(): String = "ScriptOverrides(${byEvent.values.sumOf { it.size }} overrides of ${byEvent.size} events)"

    companion object {
        val EMPTY = ScriptOverrides(emptyMap())

        /**
         * Reads the `overrides` list of an `angelscript/getScriptOverrides` reply, as lsp4j hands it
         * over: maps of strings and doubles. An entry missing a field is skipped rather than failing
         * the rest.
         */
        fun fromReply(overrides: List<*>): ScriptOverrides {
            val byEvent = HashMap<Key, MutableList<ScriptOverride>>()
            for (entry in overrides) {
                val fields = entry as? Map<*, *> ?: continue
                val cppClass = fields["cppClass"] as? String ?: continue
                val name = fields["name"] as? String ?: continue
                val scriptClass = fields["scriptClass"] as? String ?: continue
                val path = (fields["uri"] as? String)?.let { AngelScriptDiagnosticsStore.pathFromUri(it) } ?: continue
                val line = (fields["line"] as? Number)?.toInt() ?: continue
                val character = (fields["character"] as? Number)?.toInt() ?: continue

                byEvent.getOrPut(Key(cppClass, name)) { ArrayList() }
                    .add(ScriptOverride(scriptClass, path, line, character))
            }
            return ScriptOverrides(byEvent)
        }
    }
}
