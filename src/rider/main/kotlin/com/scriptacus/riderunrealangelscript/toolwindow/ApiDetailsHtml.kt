package com.scriptacus.riderunrealangelscript.toolwindow

import com.intellij.openapi.util.text.StringUtil
import com.scriptacus.riderunrealangelscript.typedb.ScriptOrigin
import com.scriptacus.riderunrealangelscript.typedb.UnrealMember
import com.scriptacus.riderunrealangelscript.typedb.UnrealMethod
import com.scriptacus.riderunrealangelscript.typedb.UnrealNamespace
import com.scriptacus.riderunrealangelscript.typedb.UnrealProperty
import com.scriptacus.riderunrealangelscript.typedb.UnrealType
import com.scriptacus.riderunrealangelscript.typedb.UnrealTypeDatabase
import com.scriptacus.riderunrealangelscript.typedb.UnrealTypeKind

/**
 * The details pane's contents, as the body of an HTML document.
 *
 * Built here from the type database and what the server lists script declaring, rather than asked
 * from the language server: its getAPIDetails knows a class's members only by name, and a script
 * function only by an id the next parse of its file replaces, and for either it then describes an
 * arbitrary overload. Everything is escaped - template types such as `TArray<AActor>` are in every
 * other signature.
 */
object ApiDetailsHtml {

    fun of(node: ApiNode, database: UnrealTypeDatabase): String? = when (node) {
        is ApiNode.Type -> type(node.type, database)
        is ApiNode.Inherited -> type(node.ancestor, database)
        is ApiNode.Namespace -> namespace(node.namespace, statics = false)
        is ApiNode.Statics -> namespace(node.namespace, statics = true)
        is ApiNode.ScriptNamespace -> namespace(node.namespace, statics = false) + "<p>Declared in script.</p>"
        is ApiNode.Member -> member(node.member)
        is ApiNode.Group, is ApiNode.Message -> null
    }

    fun member(member: UnrealMember): String = when (member) {
        is UnrealMethod -> method(member)
        is UnrealProperty -> property(member)
    }

    /** As upstream's getAPIDetails lays it out: one argument per line. */
    fun signature(method: UnrealMethod): String = buildString {
        if (method.isProtected) append("protected ")
        if (!method.isConstructor) append(method.returnType).append(' ')
        append(method.qualifiedName)
        if (method.args.isEmpty()) {
            append("()")
        } else {
            method.args.joinTo(this, ",\n", "(\n", "\n)") { "    " + it.format() }
        }
        if (method.isConst) append(" const")
    }

    private fun method(method: UnrealMethod): String = buildString {
        code(signature(method))
        if (method.isBlueprintEvent) {
            paragraph(if (method.isCallable) "Blueprint event." else "Blueprint event: script can override it, not call it.")
        }
        if (method.isMixin) {
            paragraph("Script mixin: called on ${method.owner} as one of its methods.")
        }
        method.unrealName?.takeIf { it != method.name }?.let {
            append("<p>C++ name: <code>").append(escape(it)).append("</code></p>")
        }
        method.scriptOrigin?.let { origin(it) }
        append(documentation(method.documentation))
    }

    private fun property(property: UnrealProperty): String = buildString {
        // An enum value's type is its enum, which its qualified name already says.
        val isEnumValue = property.ownerSeparator == "::" && property.type == property.owner
        code(buildString {
            if (property.isProtected) append("protected ")
            if (!isEnumValue) append(property.type).append(' ')
            append(property.qualifiedName)
        })
        property.scriptOrigin?.let { origin(it) }
        append(documentation(property.documentation))
    }

    private fun StringBuilder.origin(origin: ScriptOrigin) {
        append("<p>Declared in <code>").append(escape(origin.path ?: origin.module)).append("</code></p>")
    }

    private fun type(type: UnrealType, database: UnrealTypeDatabase): String = buildString {
        val signature = type.signature
        code(
            if (signature != null) {
                "${type.kind.keyword} ${signature.returnType} ${type.name}(${signature.args.joinToString(", ") { it.format() }})"
            } else {
                "${type.kind.keyword} ${type.displayName}" + (type.supertypeName?.let { " : $it" } ?: "")
            }
        )
        append(documentation(type.documentation))

        val supertypes = database.supertypes(type)
        if (supertypes.isNotEmpty()) {
            append("<p><b>Inherits:</b> ")
            append(supertypes.joinToString(" → ") { escape(it.name) })
            append("</p>")
        }

        if (type.kind == UnrealTypeKind.ENUM) {
            paragraph(count(type.properties.size, "value"))
            return@buildString
        }
        val counts = mutableListOf("Declares ${count(type.methods.size, "function")} and ${count(type.properties.size, "property", "properties")}")
        if (supertypes.isNotEmpty()) {
            val inherited = supertypes.map { database.membersInheritedFrom(type, it) }
            counts += "inherits ${count(inherited.sumOf { it.methods.size }, "function")} and " +
                count(inherited.sumOf { it.properties.size }, "property", "properties")
        }
        database.staticsOf(type)?.let { statics ->
            counts += "has ${count(statics.functions.size + statics.properties.size, "static member")}"
        }
        paragraph(counts.joinToString("; ") + ".")
    }

    private fun namespace(namespace: UnrealNamespace, statics: Boolean): String = buildString {
        code("namespace ${namespace.name}")
        if (statics) {
            paragraph("The static members of ${namespace.name}, used as ${namespace.name}::Name.")
        }
        paragraph("${count(namespace.functions.size, "function")} and ${count(namespace.properties.size, "property", "properties")}.")
    }

    /**
     * A C++ doc comment, read the way upstream's FormatFunctionDocumentation reads it: `@param`,
     * `@return`, `@note` and `@see` lines are set apart, and an indented line continues the tag above
     * it, which is how Unreal wraps a long parameter description.
     */
    fun documentation(doc: String?): String {
        if (doc.isNullOrBlank()) {
            return ""
        }

        val html = StringBuilder()
        val text = mutableListOf<String>()
        val params = mutableListOf<Pair<String, StringBuilder>>()
        var lastTag: StringBuilder? = null

        fun flushText() {
            if (text.isNotEmpty()) {
                html.append("<p>").append(text.joinToString("<br>") { escape(it) }).append("</p>")
                text.clear()
            }
        }

        fun flushParams() {
            if (params.isNotEmpty()) {
                html.append("<ul>")
                for ((name, description) in params) {
                    html.append("<li><code>").append(escape(name)).append("</code> - ")
                        .append(escape(description.toString())).append("</li>")
                }
                html.append("</ul>")
                params.clear()
            }
        }

        val tags = mutableListOf<Pair<String, StringBuilder>>()
        fun flushTags() {
            for ((label, body) in tags) {
                html.append("<p><b>").append(label).append(":</b> ").append(escape(body.toString())).append("</p>")
            }
            tags.clear()
        }

        for (rawLine in doc.lines()) {
            val line = rawLine.trimEnd()
            val trimmed = line.trimStart()
            val param = PARAM.matchEntire(trimmed)
            val tag = TAG.matchEntire(trimmed)
            when {
                param != null -> {
                    flushText()
                    flushTags()
                    val description = StringBuilder(param.groupValues[2])
                    params += param.groupValues[1] to description
                    lastTag = description
                }
                tag != null -> {
                    flushText()
                    flushParams()
                    val body = StringBuilder(tag.groupValues[2])
                    tags += TAG_LABELS.getValue(tag.groupValues[1].lowercase()) to body
                    lastTag = body
                }
                trimmed.isEmpty() -> {
                    flushText()
                    lastTag = null
                }
                lastTag != null && trimmed.length < line.length -> lastTag?.append(' ')?.append(trimmed)
                else -> {
                    flushParams()
                    flushTags()
                    lastTag = null
                    text += trimmed
                }
            }
        }
        flushText()
        flushParams()
        flushTags()
        return html.toString()
    }

    private fun StringBuilder.code(text: String) {
        append("<pre>").append(escape(text)).append("</pre>")
    }

    private fun StringBuilder.paragraph(text: String) {
        append("<p>").append(escape(text)).append("</p>")
    }

    private fun count(n: Int, singular: String, plural: String = singular + "s") = "$n ${if (n == 1) singular else plural}"

    private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)

    private val PARAM = Regex("@param\\s+([A-Za-z0-9_]+)\\s*(.*)")
    private val TAG = Regex("@(returns?|note|see|warning)\\b\\s*(.*)", RegexOption.IGNORE_CASE)
    private val TAG_LABELS = mapOf(
        "return" to "Returns",
        "returns" to "Returns",
        "note" to "Note",
        "see" to "See",
        "warning" to "Warning",
    )
}
