package com.scriptacus.riderunrealangelscript.typedb

/**
 * The functions and variables script declares in namespaces, mixins included, as the bundled
 * language server lists them in answer to `angelscript/getScriptDeclarations`.
 *
 * They are kept in the classes Unreal's API is kept in, with [UnrealMember.scriptOrigin] set, so the
 * browser lists and describes both the same way. The server sends each one whole: its own API
 * requests name a function by an id that the next parse of its file replaces.
 */
class ScriptDeclarations private constructor(
    /** What script declares outside any namespace. */
    val globals: UnrealNamespace,
    /** By name, what script declares in each namespace, Unreal's namespaces included. */
    private val membersByNamespace: Map<String, UnrealNamespace>,
    /** The namespaces only script declares, nested ones included, sorted by name. */
    private val scriptNamespaces: List<String>,
    private val mixinsByType: Map<String, List<UnrealMethod>>,
) {
    /** The namespaces only script declares that are not nested in another of them. */
    val topLevelNamespaces: List<UnrealNamespace> by lazy {
        val names = scriptNamespaces.toSet()
        scriptNamespaces.filter { it.substringBeforeLast("::", "") !in names }.map(::namespace)
    }

    /** What script declares in a namespace Unreal declares too; empty when nothing. */
    fun additionsTo(namespace: String): UnrealNamespace = namespace(namespace)

    /** The namespaces only script declares that are nested directly in [parent]. */
    fun namespacesIn(parent: String): List<UnrealNamespace> =
        scriptNamespaces.filter { it.substringBeforeLast("::", "") == parent }.map(::namespace)

    /** The mixins script declares on the type of this name, by name. */
    fun mixinsOn(type: String): List<UnrealMethod> = mixinsByType[type].orEmpty()

    /** What script declares in [name], which may be nothing but other namespaces. */
    fun namespace(name: String): UnrealNamespace =
        membersByNamespace[name] ?: UnrealNamespace(name, emptyList(), emptyList())

    /** As [UnrealTypeDatabase.search] searches Unreal's API; a mixin is a member of the type it extends. */
    fun search(query: String, limit: Int): UnrealSearchResult {
        val matches = UnrealSearchMatches.of(query) ?: return UnrealSearchResult(emptyList(), 0)
        for (name in scriptNamespaces) {
            matches.addName(namespace(name))
        }
        for (namespace in membersByNamespace.values) {
            matches.addMembers(namespace.name, namespace.properties)
            matches.addMembers(namespace.name, namespace.functions)
        }
        matches.addMembers("", globals.properties)
        matches.addMembers("", globals.functions)
        for ((type, mixins) in mixinsByType) {
            matches.addMembers(type, mixins)
        }
        return matches.best(limit)
    }

    companion object {
        val NONE = ScriptDeclarations(UnrealNamespace("", emptyList(), emptyList()), emptyMap(), emptyList(), emptyMap())

        /**
         * Reads the server's answer: `{functions: [...], properties: [...]}`, each with its name,
         * namespace (empty outside any), module and file, and its signature. [database] decides which
         * namespaces are script's own.
         */
        fun parse(reply: Map<*, *>?, database: UnrealTypeDatabase): ScriptDeclarations {
            val functions = mutableListOf<UnrealMethod>()
            val properties = mutableListOf<UnrealProperty>()
            val mixins = mutableListOf<UnrealMethod>()

            for (item in objects(reply?.get("functions"))) {
                val name = item["name"] as? String ?: continue
                val namespace = item["namespace"] as? String ?: ""
                val args = objects(item["args"]).mapNotNull { arg ->
                    UnrealArg(arg["name"] as? String, arg["type"] as? String ?: return@mapNotNull null, arg["default"] as? String)
                }
                val isMixin = item["isMixin"] == true
                if (isMixin && args.isEmpty()) {
                    continue
                }
                val method = UnrealMethod(
                    name = name,
                    owner = if (isMixin) mixinTarget(args.first().type) else namespace.ifEmpty { null },
                    ownerSeparator = if (isMixin) "." else "::",
                    returnType = item["returnType"] as? String ?: "void",
                    args = if (isMixin) args.drop(1) else args,
                    documentation = documentation(item),
                    isConst = item["isConst"] == true,
                    isProtected = false,
                    isConstructor = false,
                    isBlueprintEvent = false,
                    isCallable = true,
                    unrealName = null,
                    isMixin = isMixin,
                    scriptOrigin = origin(item),
                )
                if (isMixin) mixins += method else functions += method
            }
            for (item in objects(reply?.get("properties"))) {
                val name = item["name"] as? String ?: continue
                val namespace = item["namespace"] as? String ?: ""
                properties += UnrealProperty(
                    name = name,
                    owner = namespace.ifEmpty { null },
                    ownerSeparator = "::",
                    type = item["type"] as? String ?: continue,
                    documentation = documentation(item),
                    isProtected = false,
                    scriptOrigin = origin(item),
                )
            }

            val byName = compareBy<UnrealMember, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
            val propertiesByNamespace = properties.sortedWith(byName).groupBy { it.owner.orEmpty() }
            val functionsByNamespace = functions.sortedWith(byName).groupBy { it.owner.orEmpty() }
            val members = (propertiesByNamespace.keys + functionsByNamespace.keys).associateWith { name ->
                UnrealNamespace(name, propertiesByNamespace[name].orEmpty(), functionsByNamespace[name].orEmpty())
            }

            // A namespace holding nothing but another one is still script's, and lists it.
            val scriptNamespaces = members.keys
                .filter { it.isNotEmpty() }
                .flatMap { name ->
                    val parts = name.split("::")
                    parts.indices.map { parts.subList(0, it + 1).joinToString("::") }
                }
                .distinct()
                .filter { !database.declaresNamespace(it) }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)

            return ScriptDeclarations(
                globals = members[""] ?: NONE.globals,
                membersByNamespace = members - "",
                scriptNamespaces = scriptNamespaces,
                mixinsByType = mixins.sortedWith(byName).groupBy { it.owner.orEmpty() },
            )
        }

        /**
         * The type a mixin extends, from the declared type of its first argument: `const FVector&`
         * is FVector, and a template instantiation is filed under its template.
         */
        fun mixinTarget(typename: String): String =
            typename.trim().replace(LEADING_CONST, "").substringBefore('&').substringBefore('<').trim()

        private val LEADING_CONST = Regex("^const\\s+")

        private fun documentation(item: Map<*, *>): String? =
            (item["doc"] as? String)?.takeIf { it.isNotBlank() }?.let { UnrealTypeDatabase.formatDocumentation(it) }

        private fun origin(item: Map<*, *>): ScriptOrigin? {
            val module = item["module"] as? String ?: return null
            return ScriptOrigin(module, (item["file"] as? String)?.let(::pathOf))
        }

        /** A file URI as the server writes it, `file:///d:/Script/A.as`, as a path. */
        private fun pathOf(uri: String): String {
            val path = uri.removePrefix("file://")
            return if (WINDOWS_DRIVE.containsMatchIn(path)) path.substring(1) else path
        }

        private val WINDOWS_DRIVE = Regex("^/[A-Za-z]:")

        private fun objects(value: Any?): List<Map<*, *>> = (value as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
    }
}
