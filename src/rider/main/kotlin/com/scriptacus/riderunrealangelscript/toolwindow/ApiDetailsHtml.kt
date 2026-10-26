package com.scriptacus.riderunrealangelscript.toolwindow

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.scriptacus.riderunrealangelscript.typedb.ScriptDeclarations
import com.scriptacus.riderunrealangelscript.typedb.UnrealArg
import com.scriptacus.riderunrealangelscript.typedb.UnrealMember
import com.scriptacus.riderunrealangelscript.typedb.UnrealMethod
import com.scriptacus.riderunrealangelscript.typedb.UnrealNamespace
import com.scriptacus.riderunrealangelscript.typedb.UnrealProperty
import com.scriptacus.riderunrealangelscript.typedb.UnrealType
import com.scriptacus.riderunrealangelscript.typedb.UnrealTypeDatabase
import com.scriptacus.riderunrealangelscript.typedb.UnrealTypeKind

/**
 * The details pane's contents, as the body of an HTML document, laid out the way Rider's Quick
 * Documentation is: the declaration, its documentation, and then a table of the rest - parameters,
 * what it inherits, where script declares it.
 *
 * Built here from the type database and what the server lists script declaring, rather than asked
 * from the language server: its getAPIDetails knows a class's members only by name, and a script
 * function only by an id the next parse of its file replaces, and for either it then describes an
 * arbitrary overload. Everything is escaped - template types such as `TArray<AActor>` are in every
 * other signature.
 */
object ApiDetailsHtml {

    // The classes [css] styles.
    private const val DEFINITION = "definition"
    private const val SECTIONS = "sections"
    private const val SECTION = "section"
    internal const val PARAMETERS = "parameters"
    internal const val PARAMETER = "parameter"
    const val GREYED = "greyed"

    fun of(node: ApiNode, database: UnrealTypeDatabase, script: ScriptDeclarations = ScriptDeclarations.NONE): String? = when (node) {
        is ApiNode.Group -> group(node)
        is ApiNode.Type -> type(node.type, database, script)
        is ApiNode.Inherited -> type(node.ancestor, database, script)
        is ApiNode.Namespace -> namespace(node.namespace, script, note = null)
        is ApiNode.Statics -> namespace(
            node.namespace,
            script,
            note = "The static members of ${node.namespace.name}, called as ${node.namespace.name}::Name.",
        )
        is ApiNode.ScriptNamespace -> scriptNamespace(node.namespace, script)
        is ApiNode.Member -> member(node.member)
        is ApiNode.Message -> null
    }

    fun member(member: UnrealMember): String = when (member) {
        is UnrealMethod -> method(member)
        is UnrealProperty -> property(member)
    }

    /** As upstream's getAPIDetails lays it out, one argument per line, unless there is only one. */
    fun signature(method: UnrealMethod): String = signatureParts(method).joinToString("")

    /**
     * The style sheet the classes used here need, in the colours of the current theme. Margins are
     * spelled out because the IDE's HTML kit leaves paragraphs no space between them.
     */
    fun css(): String {
        val grey = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground())
        val line = ColorUtil.toHtmlColor(JBColor.border())
        fun px(n: Int) = "${JBUI.scale(n)}px"
        return """
            p { margin-top: 0; margin-bottom: ${px(6)}; }
            ul { margin-top: 0; margin-bottom: ${px(6)}; margin-left-ltr: ${px(16)}; margin-right-rtl: ${px(16)}; }
            li { margin-bottom: ${px(3)}; }
            pre { margin-top: 0; margin-bottom: 0; }
            .$DEFINITION { padding-bottom: ${px(5)}; margin-bottom: ${px(7)}; border-bottom: 1px solid $line; }
            .$SECTIONS { margin-top: ${px(3)}; }
            .$SECTIONS td p { margin-bottom: ${px(3)}; }
            .$SECTION { color: $grey; padding-right: ${px(12)}; white-space: nowrap; }
            .$PARAMETER { padding-right: ${px(10)}; white-space: nowrap; }
            .$GREYED { color: $grey; }
        """.trimIndent()
    }

    private fun group(group: ApiNode.Group): String {
        val (description, counted) = when (group.kind) {
            GroupKind.CLASSES -> "The classes Unreal binds to script, each with what it declares and what it inherits." to
                count(group.count, "class", "classes")
            GroupKind.STRUCTS -> "The structs Unreal binds to script." to count(group.count, "struct")
            GroupKind.ENUMS -> "The enums Unreal binds to script." to count(group.count, "enum")
            GroupKind.DELEGATES -> "Delegate and event types: a delegate calls one bound function, an event every bound one." to
                count(group.count, "type")
            GroupKind.NAMESPACES -> "Functions and variables called as Namespace::Name. A class's static members are listed with the class." to
                count(group.count, "namespace")
            GroupKind.GLOBALS -> "Functions and variables outside any namespace: Unreal's, then the ones script declares." to
                count(group.count, "member")
            GroupKind.SCRIPT_NAMESPACES -> "Namespaces only script declares." to count(group.count, "namespace")
            GroupKind.SCRIPT_RESULTS -> "What script declares that matches the search." to count(group.count, "match", "matches")
        }
        return "<p>${escape(description)}</p><p class='$GREYED'>${escape(counted)}</p>"
    }

    private fun type(type: UnrealType, database: UnrealTypeDatabase, script: ScriptDeclarations): String = buildString {
        val signature = type.signature
        definition(
            if (signature != null) {
                escape("${type.kind.keyword} ${signature.returnType} ") + bold(type.name) + escape(argumentList(signature.args))
            } else {
                escape("${type.kind.keyword} ") + bold(type.displayName) + escape(type.supertypeName?.let { " : $it" }.orEmpty())
            }
        )
        val doc = DocComment.parse(type.documentation)
        append(doc.content)

        val rows = doc.rows(DocTag.DEPRECATED, DocTag.NOTE, DocTag.WARNING).toMutableList()
        val supertypes = database.supertypes(type)
        if (supertypes.isNotEmpty()) {
            rows += "Inherits" to listOf(supertypes.joinToString(" → ") { escape(it.name) })
        }
        when (type.kind) {
            UnrealTypeKind.ENUM -> rows += "Values" to listOf(type.properties.size.toString())
            // Their members are the same few on every one: binding, calling, unbinding.
            UnrealTypeKind.DELEGATE, UnrealTypeKind.EVENT -> {}
            UnrealTypeKind.CLASS, UnrealTypeKind.STRUCT -> {
                counts(type.methods.size, type.properties.size)?.let { rows += "Declares" to listOf(it) }
                val inherited = supertypes.map { database.membersInheritedFrom(type, it) }
                counts(inherited.sumOf { it.methods.size }, inherited.sumOf { it.properties.size })?.let { rows += "Inherited" to listOf(it) }
                database.staticsOf(type)?.let { statics ->
                    counts(statics.functions.size, statics.properties.size)?.let { rows += "Static members" to listOf(it) }
                }
                script.mixinsOn(type.name).takeIf { it.isNotEmpty() }?.let { rows += "Script mixins" to listOf(escape(count(it.size, "function"))) }
            }
        }
        rows += doc.rows(DocTag.SEE, DocTag.TODO)
        sections(rows)
    }

    private fun namespace(namespace: UnrealNamespace, script: ScriptDeclarations, note: String?): String = buildString {
        definition(escape("namespace ") + bold(namespace.name))
        note?.let { append("<p>").append(escape(it)).append("</p>") }
        val rows = mutableListOf<Pair<String, List<String>>>()
        counts(namespace.functions.size, namespace.properties.size)?.let { rows += "Declares" to listOf(it) }
        val additions = script.additionsTo(namespace.name)
        counts(additions.functions.size, additions.properties.size)?.let { rows += "Script adds" to listOf(it) }
        declaredIn(additions)?.let { rows += it }
        sections(rows)
    }

    private fun scriptNamespace(namespace: UnrealNamespace, script: ScriptDeclarations): String = buildString {
        definition(escape("namespace ") + bold(namespace.name))
        append("<p>A namespace only script declares.</p>")
        val rows = mutableListOf<Pair<String, List<String>>>()
        counts(namespace.functions.size, namespace.properties.size)?.let { rows += "Declares" to listOf(it) }
        script.namespacesIn(namespace.name).takeIf { it.isNotEmpty() }?.let { nested ->
            rows += "Namespaces" to listOf(nested.joinToString(", ") { code(it.name.substringAfterLast("::")) })
        }
        declaredIn(namespace)?.let { rows += it }
        sections(rows)
    }

    /** The files script declares a namespace's members in, the first few of them. */
    private fun declaredIn(namespace: UnrealNamespace): Pair<String, List<String>>? {
        val files = (namespace.functions + namespace.properties)
            .mapNotNull { member -> member.scriptOrigin?.let { it.path ?: it.module } }
            .distinct()
            .sorted()
        if (files.isEmpty()) {
            return null
        }
        val shown = files.take(DECLARED_IN_LIMIT).map(::code)
        val more = files.size - shown.size
        return "Declared in" to shown + if (more > 0) listOf(escape("and ${count(more, "more file")}")) else emptyList()
    }

    private fun method(method: UnrealMethod): String = buildString {
        val (before, name, after) = signatureParts(method)
        definition(escape(before) + bold(name) + escape(after))
        val doc = DocComment.parse(method.documentation)
        append(doc.content)

        val rows = doc.rows(DocTag.DEPRECATED, DocTag.PARAM, DocTag.RETURN, DocTag.NOTE, DocTag.WARNING).toMutableList()
        if (method.isBlueprintEvent) {
            rows += "Blueprint event" to listOf(
                if (method.isCallable) "Script can override it and call it." else "Script can override it, but not call it."
            )
        }
        if (method.isMixin) {
            rows += "Script mixin" to listOf(escape("Called on ${method.owner} as one of its methods."))
        }
        method.unrealName?.takeIf { it != method.name }?.let { rows += "C++ name" to listOf(code(it)) }
        method.scriptOrigin?.let { rows += "Declared in" to listOf(code(it.path ?: it.module)) }
        rows += doc.rows(DocTag.SEE, DocTag.TODO)
        sections(rows)
    }

    private fun property(property: UnrealProperty): String = buildString {
        // An enum value's type is its enum, which its qualified name already says.
        val isEnumValue = property.ownerSeparator == "::" && property.type == property.owner
        val before = buildString {
            if (property.isProtected) append("protected ")
            if (!isEnumValue) append(property.type).append(' ')
            property.owner?.let { append(it).append(property.ownerSeparator) }
        }
        definition(escape(before) + bold(property.name))
        val doc = DocComment.parse(property.documentation)
        append(doc.content)

        val rows = doc.rows(DocTag.DEPRECATED, DocTag.NOTE, DocTag.WARNING).toMutableList()
        property.scriptOrigin?.let { rows += "Declared in" to listOf(code(it.path ?: it.module)) }
        rows += doc.rows(DocTag.SEE, DocTag.TODO)
        sections(rows)
    }

    /** What comes before the name, the name, and what comes after it. */
    private fun signatureParts(method: UnrealMethod): List<String> {
        val before = buildString {
            if (method.isProtected) append("protected ")
            if (!method.isConstructor) append(method.returnType).append(' ')
            method.owner?.let { append(it).append(method.ownerSeparator) }
        }
        return listOf(before, method.name, argumentList(method.args) + if (method.isConst) " const" else "")
    }

    private fun argumentList(args: List<UnrealArg>): String = when (args.size) {
        0 -> "()"
        1 -> "(${args.single().format()})"
        else -> args.joinToString(",\n", "(\n", "\n)") { "    " + it.format() }
    }

    private fun StringBuilder.definition(html: String) {
        append("<div class='$DEFINITION'><pre>").append(html).append("</pre></div>")
    }

    /** A label and its values, one paragraph each, in a table like Quick Documentation's. */
    private fun StringBuilder.sections(rows: List<Pair<String, List<String>>>) {
        if (rows.isEmpty()) {
            return
        }
        // No cellpadding: Swing lets it override the padding the style sheet gives the labels.
        append("<table class='$SECTIONS' cellspacing='0'>")
        for ((label, values) in rows) {
            append("<tr><td valign='top' class='$SECTION'><p>").append(escape(label)).append(":</p></td><td valign='top'>")
            values.forEach { value ->
                // A table, such as the parameters', is a block of its own, which a paragraph cannot hold.
                if (value.startsWith("<table")) append(value) else append("<p>").append(value).append("</p>")
            }
            append("</td></tr>")
        }
        append("</table>")
    }

    /** "2 functions, 1 property", leaving out what there is none of; null when there is nothing. */
    private fun counts(functions: Int, properties: Int): String? = listOfNotNull(
        functions.takeIf { it > 0 }?.let { count(it, "function") },
        properties.takeIf { it > 0 }?.let { count(it, "property", "properties") },
    ).takeIf { it.isNotEmpty() }?.joinToString(", ")?.let(::escape)

    private fun count(n: Int, singular: String, plural: String = singular + "s") = "$n ${if (n == 1) singular else plural}"

    private fun bold(text: String) = "<b>${escape(text)}</b>"

    private fun code(text: String) = "<code>${escape(text)}</code>"

    internal fun escape(text: String): String = StringUtil.escapeXmlEntities(text)

    private const val DECLARED_IN_LIMIT = 8
}

/** The `@` tags of a doc comment that get a row of their own, in the order the rows are shown. */
internal enum class DocTag(val label: String) {
    DEPRECATED("Deprecated"),
    PARAM("Parameters"),
    RETURN("Returns"),
    NOTE("Note"),
    WARNING("Warning"),
    SEE("See also"),
    TODO("To do"),
}

/**
 * A C++ doc comment as HTML: its text as paragraphs and lists, and its tags apart from it.
 *
 * `@param`, `@return`, `@note` and `@see` are read the way upstream's FormatFunctionDocumentation
 * reads them, and an indented line continues the tag above it, which is how Unreal wraps a long
 * parameter description. The text in between is wrapped by hand at about a hundred columns, which in
 * a pane narrower than that breaks every line twice, so lines are joined back into paragraphs:
 * unless a line ends with a colon, is more indented than its paragraph (an example, a table), or
 * starts a numbered step. A line starting with `- ` is a list item, continued by the more indented
 * lines below it.
 */
internal class DocComment private constructor(
    val content: String,
    private val tags: Map<DocTag, List<String>>,
    /** Each `@param`'s name and description, as HTML. */
    val params: List<Pair<String, String>>,
) {
    /** The rows of these tags, each with every value the comment gives it, as HTML. */
    fun rows(vararg kinds: DocTag): List<Pair<String, List<String>>> = kinds.mapNotNull { kind ->
        if (kind == DocTag.PARAM) {
            params.takeIf { it.isNotEmpty() }?.let { kind.label to listOf(parameterTable(it)) }
        } else {
            tags[kind]?.let { kind.label to it }
        }
    }

    /**
     * The lines of one paragraph, list item or tag. [keepsLayout] for a paragraph, whose further
     * indented lines are laid out by hand; a list item's or tag's are indented only to line up.
     */
    private class Lines(private val indent: Int, private val keepsLayout: Boolean) {
        private val html = StringBuilder()
        private var last: String? = null
        private var lastIndent = 0

        fun add(text: String, indent: Int): Lines {
            val previous = last
            if (previous != null) {
                val extra = indent - this.indent
                when {
                    keepsLayout && extra > 0 -> html.append("<br>").append("&nbsp;".repeat(extra))
                    // The text after an example starts a line of its own as well.
                    keepsLayout && lastIndent > this.indent -> html.append("<br>")
                    previous.endsWith(':') || NUMBERED.containsMatchIn(text) || BULLET.matches(text) -> html.append("<br>")
                    else -> html.append(' ')
                }
            }
            html.append(inline(text))
            last = text
            lastIndent = indent
            return this
        }

        fun html(): String = html.toString()
    }

    companion object {
        val EMPTY = DocComment("", emptyMap(), emptyList())

        fun parse(doc: String?): DocComment {
            if (doc.isNullOrBlank()) {
                return EMPTY
            }

            val content = StringBuilder()
            val tags = LinkedHashMap<DocTag, MutableList<() -> String>>()
            val params = mutableListOf<Pair<String, Lines>>()
            var paragraph: Lines? = null
            var items: MutableList<Lines>? = null
            var item: Lines? = null
            var itemIndent = 0
            var tag: Lines? = null

            fun endParagraph() {
                paragraph?.let { content.append("<p>").append(it.html()).append("</p>") }
                paragraph = null
            }

            fun endList() {
                items?.let { list ->
                    content.append("<ul>")
                    list.forEach { content.append("<li>").append(term(it.html())).append("</li>") }
                    content.append("</ul>")
                }
                items = null
                item = null
            }

            fun startLines(text: String, indent: Int): Lines {
                endParagraph()
                endList()
                return Lines(indent, keepsLayout = false).add(text, indent).also { tag = it }
            }

            fun startTag(kind: DocTag, render: (String) -> String, text: String, indent: Int) {
                val lines = startLines(text, indent)
                tags.getOrPut(kind) { mutableListOf() } += { render(lines.html()) }
            }

            for (rawLine in doc.lines()) {
                val line = rawLine.trimEnd()
                val text = line.trimStart()
                val indent = line.length - text.length
                val param = PARAM.matchEntire(text)
                val tagged = TAG.matchEntire(text)
                val bullet = BULLET.matchEntire(text)
                when {
                    text.isEmpty() -> {
                        endParagraph()
                        endList()
                        tag = null
                    }
                    param != null -> params += param.groupValues[1] to startLines(param.groupValues[2], indent)
                    tagged != null -> {
                        val body = tagged.groupValues[2]
                        when (val kind = TAGS[tagged.groupValues[1].lowercase()]) {
                            DocTag.SEE -> startTag(kind, ::reference, body, indent)
                            null -> {
                                // @brief: the summary, which is just text here.
                                endList()
                                tag = null
                                (paragraph ?: Lines(indent, keepsLayout = true).also { paragraph = it }).add(body, indent)
                            }
                            else -> startTag(kind, { it }, body, indent)
                        }
                    }
                    tag != null && indent > 0 -> tag?.add(text, indent)
                    bullet != null -> {
                        endParagraph()
                        tag = null
                        val list = items ?: mutableListOf<Lines>().also { items = it }
                        item = Lines(indent, keepsLayout = false).add(bullet.groupValues[1], indent).also { list += it }
                        itemIndent = indent
                    }
                    item != null && indent > itemIndent -> item?.add(text, indent)
                    else -> {
                        endList()
                        tag = null
                        (paragraph ?: Lines(indent, keepsLayout = true).also { paragraph = it }).add(text, indent)
                    }
                }
            }
            endParagraph()
            endList()
            return DocComment(
                content.toString(),
                tags.mapValues { (_, values) -> values.map { it() } },
                params.map { (name, lines) -> name to lines.html() },
            )
        }

        /**
         * A row per parameter, its name in a column of its own, so that every description starts in
         * the same place rather than somewhere after a dash.
         */
        private fun parameterTable(params: List<Pair<String, String>>): String = buildString {
            append("<table class='${ApiDetailsHtml.PARAMETERS}' cellspacing='0'>")
            for ((name, description) in params) {
                append("<tr><td valign='top' class='${ApiDetailsHtml.PARAMETER}'><p><code>").append(ApiDetailsHtml.escape(name))
                append("</code></p></td><td valign='top'><p>").append(description).append("</p></td></tr>")
            }
            append("</table>")
        }

        /** Text with its links made links and its `quoted code` code, and nothing else let through as markup. */
        private fun inline(text: String): String = buildString {
            var start = 0
            for (match in INLINE.findAll(text)) {
                append(ApiDetailsHtml.escape(text.substring(start, match.range.first)))
                val code = match.groups[1]
                if (code != null) {
                    append("<code>").append(ApiDetailsHtml.escape(code.value)).append("</code>")
                    start = match.range.last + 1
                } else {
                    val url = match.value.trimEnd('.', ',', ';', ':', ')')
                    append("<a href=\"").append(ApiDetailsHtml.escape(url)).append("\">").append(ApiDetailsHtml.escape(url)).append("</a>")
                    start = match.range.first + url.length
                }
            }
            append(ApiDetailsHtml.escape(text.substring(start)))
        }

        /** A list item that starts by naming what it describes - `- UObject::PostLoad: For...` - names it in bold. */
        private fun term(html: String): String =
            TERM.find(html)?.let { "<b>${it.groupValues[1]}</b>" + html.substring(it.groupValues[1].length) } ?: html

        /** What `@see` points at: a link, names, or a sentence. */
        private fun reference(html: String): String {
            val names = html.split(", ")
            return if (names.all(IDENTIFIER::matches)) names.joinToString(", ") { "<code>$it</code>" } else html
        }

        // Unreal writes "@note: ..." about as often as "@note ...", and "@param Name - ..." now and then.
        // Character.h has "@PARAM", others "@Param" or "@params", as for the other tags.
        private val PARAM = Regex("@params?(?:\\[[a-z, ]*])?\\s+([A-Za-z0-9_]+)\\s*(?::|-(?=\\s))?\\s*(.*)", RegexOption.IGNORE_CASE)
        private val TAG = Regex("@(returns?|note|see|warning|deprecated|todo|brief)\\b:?\\s*(.*)", RegexOption.IGNORE_CASE)
        private val TAGS = mapOf(
            "return" to DocTag.RETURN,
            "returns" to DocTag.RETURN,
            "note" to DocTag.NOTE,
            "see" to DocTag.SEE,
            "warning" to DocTag.WARNING,
            "deprecated" to DocTag.DEPRECATED,
            "todo" to DocTag.TODO,
        )
        private val BULLET = Regex("[-•+]\\s+(\\S.*)")
        private val NUMBERED = Regex("^\\d{1,2}[.)]\\s")
        private val TERM = Regex("^((?:[A-Za-z0-9_.~]|::|\\(\\))+):\\s")
        private val IDENTIFIER = Regex("(?:[A-Za-z0-9_~]|::|\\.|\\(\\))+")
        private val INLINE = Regex("`([^`]+)`|https?://[^\\s<>\"`]+")
    }
}
