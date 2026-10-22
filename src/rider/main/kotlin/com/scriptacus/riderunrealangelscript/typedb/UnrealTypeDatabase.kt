package com.scriptacus.riderunrealangelscript.typedb

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.IOException
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path

/** Anything the API browser lists: a type, a namespace, or one of their members. */
sealed interface UnrealSymbol {
    val name: String
}

class UnrealArg(val name: String?, val type: String, val defaultValue: String?) {
    /** As upstream's DBArg.format writes it: `Type Name = Default`. */
    fun format(): String = buildString {
        append(type)
        name?.let { append(' ').append(it) }
        defaultValue?.let { append(" = ").append(it) }
    }
}

/** The script file that declares a function or variable. */
class ScriptOrigin(
    /** The module name: the file's path under its script root, with dots for slashes. */
    val module: String,
    /** The file's path, when the language server said. */
    val path: String?,
) {
    val fileName: String
        get() = path?.substringAfterLast('/') ?: (module.substringAfterLast('.') + ".as")
}

/**
 * A function or property, and where it lives.
 *
 * [owner] is the type or namespace it is declared in, or null for a global. [ownerSeparator] is how
 * script refers to it through that owner: `.` for a member of a type, `::` for a namespace member,
 * which is also how enum values and a class's static functions are named.
 *
 * What script declares in a namespace uses the same classes, with [scriptOrigin] set.
 */
sealed class UnrealMember : UnrealSymbol {
    abstract val owner: String?
    abstract val ownerSeparator: String
    abstract val documentation: String?
    abstract val isProtected: Boolean

    /** Where script declares it, or null for what Unreal declares. */
    abstract val scriptOrigin: ScriptOrigin?

    val qualifiedName: String
        get() = owner?.let { it + ownerSeparator + name } ?: name
}

class UnrealMethod(
    override val name: String,
    override val owner: String?,
    override val ownerSeparator: String,
    val returnType: String,
    val args: List<UnrealArg>,
    override val documentation: String?,
    val isConst: Boolean,
    override val isProtected: Boolean,
    val isConstructor: Boolean,
    val isBlueprintEvent: Boolean,
    /** False for events script can only override; completion leaves those out. */
    val isCallable: Boolean,
    /** The C++ name, when script knows the function by another one. */
    val unrealName: String?,
    /** A script mixin, called on its [owner] type: its first argument is left out of [args]. */
    val isMixin: Boolean = false,
    override val scriptOrigin: ScriptOrigin? = null,
) : UnrealMember() {
    /** Overloads share a name, so a method is only the same as another when its arguments are too. */
    val signatureKey: String
        get() = args.joinToString(",", "$name(", ")") { it.type }
}

class UnrealProperty(
    override val name: String,
    override val owner: String?,
    override val ownerSeparator: String,
    val type: String,
    override val documentation: String?,
    override val isProtected: Boolean,
    override val scriptOrigin: ScriptOrigin? = null,
) : UnrealMember()

enum class UnrealTypeKind(val keyword: String) {
    CLASS("class"),
    STRUCT("struct"),
    ENUM("enum"),
    DELEGATE("delegate"),
    EVENT("event"),
}

class UnrealType(
    override val name: String,
    val kind: UnrealTypeKind,
    /** The type it inherits from, by name; the database may not contain it. */
    val supertypeName: String?,
    val templateParameters: List<String>,
    val documentation: String?,
    val properties: List<UnrealProperty>,
    val methods: List<UnrealMethod>,
) : UnrealSymbol {
    val displayName: String
        get() = if (templateParameters.isEmpty()) name else templateParameters.joinToString(", ", "$name<", ">")

    /** What a delegate or event is called with: the signature of its own ExecuteIfBound or Broadcast. */
    val signature: UnrealMethod?
        get() = when (kind) {
            UnrealTypeKind.DELEGATE -> methods.firstOrNull { it.name == "ExecuteIfBound" }
            UnrealTypeKind.EVENT -> methods.firstOrNull { it.name == "Broadcast" }
            else -> null
        }
}

class UnrealNamespace(
    /** Empty for the global namespace. */
    override val name: String,
    val properties: List<UnrealProperty>,
    val functions: List<UnrealMethod>,
) : UnrealSymbol {
    val isEmpty: Boolean
        get() = properties.isEmpty() && functions.isEmpty()
}

/** Members of a type, split the way the browser lists them. */
class UnrealMembers(val properties: List<UnrealProperty>, val methods: List<UnrealMethod>) {
    val size: Int
        get() = properties.size + methods.size
}

class UnrealSearchResult(
    /** The best matches, best first, at most as many as were asked for. */
    val hits: List<UnrealSymbol>,
    /** How many matched in all. */
    val total: Int,
)

/**
 * The C++ API Unreal binds to script, as described by the type database the editor sends the
 * language server.
 *
 * Read from the copy the bundled server saves (see `scripts/bundle-lsp.js`) rather than asked from
 * the server: upstream's API requests list a namespace's functions but never a class's members,
 * while the file has every type's own members and the name of the type it inherits from.
 *
 * The format is the editor's. Each chunk maps a name to an object with `properties` (a name mapped
 * to `[type, flags or doc...]`) and `methods` (an array of function objects). A name starting with
 * `__` is a namespace rather than a type - `__APawn` holds APawn's static functions, `__` the global
 * ones - except for enums, which are sent that way too. That is how upstream's AddTypesFromUnreal
 * reads it, and later entries replace earlier ones of the same name there as well.
 */
class UnrealTypeDatabase private constructor(
    /** When the server saved this database, if it said. */
    val savedAt: Long?,
    private val typesByName: Map<String, UnrealType>,
    private val namespacesByName: Map<String, UnrealNamespace>,
    /** Global functions and properties, apart from the constructors listed with their types. */
    val globals: UnrealNamespace,
    private val constructorsByType: Map<String, List<UnrealMethod>>,
) {
    val types: Collection<UnrealType>
        get() = typesByName.values

    fun type(name: String): UnrealType? = typesByName[name]

    fun namespace(name: String): UnrealNamespace? = namespacesByName[name]

    private val typesByKind: Map<UnrealTypeKind, List<UnrealType>> by lazy {
        typesByName.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).groupBy { it.kind }
    }

    fun typesOfKind(kind: UnrealTypeKind): List<UnrealType> = typesByKind[kind].orEmpty()

    /**
     * Namespaces that stand on their own. One named after a type holds that type's static members,
     * and is listed with the type instead.
     */
    val standaloneNamespaces: List<UnrealNamespace> by lazy {
        namespacesByName.values
            .filter { !it.isEmpty && it.name !in typesByName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private val namespacePaths: Set<String> by lazy {
        namespacesByName.keys.flatMapTo(hashSetOf()) { name ->
            val parts = name.split("::")
            parts.indices.map { parts.subList(0, it + 1).joinToString("::") }
        }
    }

    /**
     * Whether Unreal declares a namespace of this name, or one nested in it. The language server also
     * knows the namespaces script declares, and this is what tells them apart.
     */
    fun declaresNamespace(name: String): Boolean = name in namespacePaths

    /** What script calls as `Type::Name`: the namespace of the type's own name. */
    fun staticsOf(type: UnrealType): UnrealNamespace? = namespacesByName[type.name]?.takeUnless { it.isEmpty }

    fun constructorsOf(type: UnrealType): List<UnrealMethod> = constructorsByType[type.name].orEmpty()

    /** The types [type] inherits from, nearest first, as far as the database knows them. */
    fun supertypes(type: UnrealType): List<UnrealType> {
        val chain = mutableListOf<UnrealType>()
        val seen = hashSetOf(type.name)
        var next = type.supertypeName?.let(typesByName::get)
        // The editor never sends a cycle, but a hand-edited or truncated file should not hang the IDE.
        while (next != null && seen.add(next.name)) {
            chain += next
            next = next.supertypeName?.let(typesByName::get)
        }
        return chain
    }

    /**
     * The members [type] inherits from [ancestor]: those [ancestor] declares and nothing between the
     * two declares again. The editor lists a member on the class that declares it, so a type's own
     * list is never repeated further down; this only drops what a subclass redeclares.
     */
    fun membersInheritedFrom(type: UnrealType, ancestor: UnrealType): UnrealMembers {
        val hiddenProperties = hashSetOf<String>()
        val hiddenMethods = hashSetOf<String>()
        for (closer in listOf(type) + supertypes(type).takeWhile { it !== ancestor }) {
            closer.properties.mapTo(hiddenProperties) { it.name }
            closer.methods.mapTo(hiddenMethods) { it.signatureKey }
        }
        return UnrealMembers(
            ancestor.properties.filter { it.name !in hiddenProperties },
            ancestor.methods.filter { it.signatureKey !in hiddenMethods },
        )
    }

    /**
     * Types, namespaces and members whose name matches [query], best first.
     *
     * Words separated by spaces must all match, as in upstream's own API search: a word shorter than
     * three letters has to start the name, unless a longer word came before it, and any other word
     * may appear anywhere in it. `Owner.member` or `Owner::member` looks only at the members of types
     * and namespaces whose name contains `Owner`.
     */
    fun search(query: String, limit: Int): UnrealSearchResult {
        val matches = UnrealSearchMatches.of(query) ?: return UnrealSearchResult(emptyList(), 0)
        for (type in typesByName.values) {
            matches.addName(type)
            matches.addMembers(type.name, type.properties)
            matches.addMembers(type.name, type.methods)
        }
        for (namespace in namespacesByName.values) {
            if (namespace.name !in typesByName) {
                matches.addName(namespace)
            }
            matches.addMembers(namespace.name, namespace.properties)
            matches.addMembers(namespace.name, namespace.functions)
        }
        matches.addMembers("", globals.properties)
        matches.addMembers("", globals.functions)
        return matches.best(limit)
    }

    companion object {
        private const val SUPPORTED_VERSION = 1

        /** The protected bit of a property's flags; 0x1 and 0x2 are NoEdit and EditOnly. */
        private const val PROPERTY_PROTECTED = 0x4

        fun read(file: Path): UnrealTypeDatabase =
            // Through NIO, which opens the file with FILE_SHARE_DELETE on Windows. The server replaces
            // it by renaming a new one over it, and a java.io reader holding it would make that fail.
            Files.newBufferedReader(file, Charsets.UTF_8).use { parse(it) }

        /** Parses the server's saved file: `{"version":1,"savedAt":...,"chunks":[...]}`. */
        fun parse(reader: Reader): UnrealTypeDatabase {
            val builder = Builder()
            // Streamed a type at a time: the file is tens of megabytes, and a whole tree of it would
            // be several times that again.
            val json = JsonReader(reader)
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "version" -> {
                        val version = json.nextInt()
                        if (version != SUPPORTED_VERSION) {
                            throw IOException("Unsupported type database version $version")
                        }
                    }
                    "savedAt" -> builder.savedAt = json.nextLong()
                    "chunks" -> {
                        json.beginArray()
                        while (json.hasNext()) {
                            json.beginObject()
                            while (json.hasNext()) {
                                val name = json.nextName()
                                val entry = JsonParser.parseReader(json)
                                if (entry is JsonObject) {
                                    builder.add(name, entry)
                                }
                            }
                            json.endObject()
                        }
                        json.endArray()
                    }
                    else -> json.skipValue()
                }
            }
            json.endObject()
            return builder.build()
        }

        /** Upstream's FormatDocumentationComment: strips the `*` a C++ comment carries on each line. */
        internal fun formatDocumentation(doc: String): String =
            doc.replace(COMMENT_STAR_END, "\n").replace(COMMENT_STAR_START, " ").trim()

        private val COMMENT_STAR_START = Regex("^[ \\t]*\\*+([ \\t]|\\r?\\n)")
        private val COMMENT_STAR_END = Regex("[\\r\\n]+[ \\t]*\\*+[ \\t]*")
    }

    private class Builder {
        var savedAt: Long? = null
        private val types = LinkedHashMap<String, UnrealType>()
        private val namespaces = LinkedHashMap<String, UnrealNamespace>()

        fun add(name: String, entry: JsonObject) {
            if (!name.startsWith("__")) {
                types[name] = readType(name, entry)
                return
            }
            val bare = name.substring(2)
            if (entry.flag("isEnum")) {
                types[bare] = readType(bare, entry)
            } else {
                namespaces[bare] = UnrealNamespace(
                    bare,
                    readProperties(entry, bare.ifEmpty { null }, "::"),
                    readMethods(entry, bare.ifEmpty { null }, "::"),
                )
            }
        }

        fun build(): UnrealTypeDatabase {
            // Constructors arrive as global functions named after their type. They belong with it,
            // and would otherwise make up nearly all of the globals.
            val global = namespaces.remove("") ?: UnrealNamespace("", emptyList(), emptyList())
            val (constructors, functions) = global.functions.partition { it.isConstructor && it.name in types }
            return UnrealTypeDatabase(
                savedAt,
                types,
                namespaces,
                UnrealNamespace("", global.properties, functions),
                constructors.groupBy { it.name },
            )
        }

        private fun readType(name: String, entry: JsonObject): UnrealType {
            val isEnum = entry.flag("isEnum")
            val properties = readProperties(entry, name, if (isEnum) "::" else ".")
            val methods = readMethods(entry, name, ".")
            // As upstream's DBType.fromJSON decides it when the editor leaves the flags out.
            val isEvent = entry.optionalFlag("isEvent") ?: methods.any { it.name == "Broadcast" }
            val isDelegate = entry.optionalFlag("isDelegate") ?: (!isEvent && methods.any { it.name == "ExecuteIfBound" })
            val kind = when {
                isEnum -> UnrealTypeKind.ENUM
                isEvent -> UnrealTypeKind.EVENT
                isDelegate -> UnrealTypeKind.DELEGATE
                entry.flag("isStruct") -> UnrealTypeKind.STRUCT
                else -> UnrealTypeKind.CLASS
            }
            return UnrealType(
                name = name,
                kind = kind,
                supertypeName = entry.string("inherits"),
                templateParameters = (entry.get("subtypes") as? JsonArray)?.mapNotNull { it.stringOrNull() }.orEmpty(),
                documentation = entry.string("doc")?.let(::formatDocumentation),
                properties = properties,
                methods = methods,
            )
        }

        private fun readProperties(entry: JsonObject, owner: String?, separator: String): List<UnrealProperty> {
            val properties = entry.get("properties") as? JsonObject ?: return emptyList()
            return properties.entrySet().mapNotNull { (name, value) ->
                val spec = value as? JsonArray ?: return@mapNotNull null
                val type = spec.firstOrNull()?.stringOrNull() ?: return@mapNotNull null
                var flags = 0
                var doc: String? = null
                // After the type come flags as a number, or as "NoEdit"/"EditOnly", and the doc.
                for (i in 1 until spec.size()) {
                    val item = spec[i]
                    if (!item.isJsonPrimitive) continue
                    val primitive = item.asJsonPrimitive
                    when {
                        primitive.isNumber -> flags = primitive.asInt
                        primitive.asString == "NoEdit" -> flags = flags or 0x1
                        primitive.asString == "EditOnly" -> flags = flags or 0x2
                        else -> doc = formatDocumentation(primitive.asString)
                    }
                }
                UnrealProperty(name, owner, separator, type, doc, flags and PROPERTY_PROTECTED != 0)
            }
        }

        private fun readMethods(entry: JsonObject, owner: String?, separator: String): List<UnrealMethod> {
            val elements = when (val methods = entry.get("methods")) {
                is JsonArray -> methods.toList()
                is JsonObject -> methods.entrySet().map { it.value }
                else -> return emptyList()
            }
            return elements.mapNotNull { element ->
                val method = element as? JsonObject ?: return@mapNotNull null
                val name = method.string("name") ?: return@mapNotNull null
                UnrealMethod(
                    name = name,
                    owner = owner,
                    ownerSeparator = separator,
                    returnType = method.string("return") ?: "void",
                    args = (method.get("args") as? JsonArray)?.mapNotNull { arg ->
                        val spec = arg as? JsonObject ?: return@mapNotNull null
                        UnrealArg(spec.string("name"), spec.string("type") ?: return@mapNotNull null, spec.string("default"))
                    }.orEmpty(),
                    documentation = method.string("doc")?.let(::formatDocumentation),
                    isConst = method.flag("const"),
                    isProtected = method.flag("protected"),
                    isConstructor = method.flag("isConstructor"),
                    isBlueprintEvent = method.flag("event"),
                    isCallable = method.optionalFlag("callable") ?: true,
                    // Upstream keeps it only with "ufunction", which events never carry, so it loses
                    // ReceiveBeginPlay behind BeginPlay - the very name a C++ reader knows it by.
                    unrealName = method.string("unrealname"),
                )
            }
        }

        private fun JsonElement.stringOrNull(): String? =
            if (isJsonPrimitive && asJsonPrimitive.isString) asString else null

        private fun JsonObject.string(key: String): String? = get(key)?.stringOrNull()

        private fun JsonObject.optionalFlag(key: String): Boolean? {
            val value = get(key)
            return if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) value.asBoolean else null
        }

        private fun JsonObject.flag(key: String): Boolean = optionalFlag(key) ?: false
    }
}

/** What one search query matches, ranked as [UnrealTypeDatabase.search] describes. */
internal class UnrealSearchMatches private constructor(private val query: UnrealSearchQuery) {
    private val matches = mutableListOf<Match>()

    fun addName(symbol: UnrealSymbol) {
        query.rankName(symbol.name)?.let { matches += Match(symbol, it, isMember = false) }
    }

    /** [owner] is what the `Owner` of an `Owner.member` query is matched against. */
    fun addMembers(owner: String, members: List<UnrealMember>) {
        for (member in members) {
            if (member is UnrealMethod && member.isConstructor) {
                continue
            }
            val rank = query.rankMember(owner, member.name)
                ?: (member as? UnrealMethod)?.unrealName?.let { query.rankMember(owner, it) }
                ?: continue
            matches += Match(member, rank, isMember = true)
        }
    }

    fun best(limit: Int): UnrealSearchResult {
        val best = matches.sortedWith(
            compareBy<Match> { it.rank }
                .thenBy { it.isMember }
                .thenBy { it.symbol.name.length }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.symbol.name }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { (it.symbol as? UnrealMember)?.owner.orEmpty() }
        )
        return UnrealSearchResult(best.take(limit).map { it.symbol }, matches.size)
    }

    private class Match(val symbol: UnrealSymbol, val rank: Int, val isMember: Boolean)

    companion object {
        /** Null for a query with nothing to match. */
        fun of(query: String): UnrealSearchMatches? = UnrealSearchQuery.parse(query)?.let(::UnrealSearchMatches)
    }
}

/** One search query, parsed once and matched against every name. */
internal class UnrealSearchQuery private constructor(
    /** Only members of types and namespaces whose name contains this, when the query named one. */
    private val ownerFilter: String?,
    private val phrases: List<String>,
    private val text: String,
) {
    /** Lower is better: an exact name, then one the query starts, then any other match. */
    fun rankName(name: String): Int? = if (ownerFilter != null) null else rank(name)

    fun rankMember(owner: String, name: String): Int? {
        if (ownerFilter == null) {
            return rank(name)
        }
        if (!owner.contains(ownerFilter, ignoreCase = true)) {
            return null
        }
        val nameRank = if (phrases.isEmpty()) 1 else rank(name) ?: return null
        // The owner the query names exactly comes before every owner that merely contains it.
        return if (owner.equals(ownerFilter, ignoreCase = true)) nameRank else nameRank + 3
    }

    private fun rank(name: String): Int? = when {
        !matches(name) -> null
        name.equals(text, ignoreCase = true) -> 0
        name.startsWith(phrases.first(), ignoreCase = true) -> 1
        else -> 2
    }

    /** Upstream's canComplete in GetAPISearch. */
    private fun matches(name: String): Boolean {
        var hadLongMatch = false
        for (phrase in phrases) {
            if (phrase.length < 3 && !hadLongMatch) {
                if (!name.startsWith(phrase, ignoreCase = true)) return false
            } else {
                hadLongMatch = true
                if (!name.contains(phrase, ignoreCase = true)) return false
            }
        }
        return true
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        fun parse(query: String): UnrealSearchQuery? {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                return null
            }
            if (!trimmed.contains(' ')) {
                val separator = maxOf(trimmed.lastIndexOf("::"), trimmed.lastIndexOf('.'))
                if (separator > 0) {
                    val length = if (trimmed.startsWith("::", separator)) 2 else 1
                    val member = trimmed.substring(separator + length)
                    return UnrealSearchQuery(trimmed.substring(0, separator).removeSuffix(":"), phrasesOf(member), member)
                }
            }
            return UnrealSearchQuery(null, phrasesOf(trimmed), trimmed)
        }

        private fun phrasesOf(text: String): List<String> = text.split(WHITESPACE).filter { it.isNotEmpty() }
    }
}
