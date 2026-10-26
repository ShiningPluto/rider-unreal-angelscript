package com.scriptacus.riderunrealangelscript.toolwindow

import com.scriptacus.riderunrealangelscript.typedb.ScriptDeclarations
import com.scriptacus.riderunrealangelscript.typedb.UnrealMember
import com.scriptacus.riderunrealangelscript.typedb.UnrealMembers
import com.scriptacus.riderunrealangelscript.typedb.UnrealMethod
import com.scriptacus.riderunrealangelscript.typedb.UnrealNamespace
import com.scriptacus.riderunrealangelscript.typedb.UnrealType
import com.scriptacus.riderunrealangelscript.typedb.UnrealTypeDatabase
import com.scriptacus.riderunrealangelscript.typedb.UnrealTypeKind

/** What a row of the API browser stands for. */
sealed class ApiNode {
    /** A top-level list. [count] is how many rows it holds. */
    class Group(val kind: GroupKind, val count: Int) : ApiNode()

    class Type(val type: UnrealType) : ApiNode()

    class Namespace(val namespace: UnrealNamespace) : ApiNode()

    /** A type's static members: the namespace of the type's own name. */
    class Statics(val namespace: UnrealNamespace) : ApiNode()

    /**
     * The members a type inherits from [ancestor] and does not declare again, and the script mixins
     * that extend [ancestor].
     */
    class Inherited(val ancestor: UnrealType, val members: UnrealMembers, val mixins: List<UnrealMethod>) : ApiNode() {
        val size: Int
            get() = members.size + mixins.size
    }

    /**
     * Unreal's or script's. [qualified] when listed away from its owner, as search results are.
     * [note] says what the row alone does not: which script file declares it, when listed among
     * Unreal's API.
     */
    class Member(val member: UnrealMember, val qualified: Boolean, val note: String? = null) : ApiNode()

    /** A namespace only script declares. */
    class ScriptNamespace(val namespace: UnrealNamespace) : ApiNode()

    class Message(val text: String) : ApiNode()
}

enum class GroupKind(val title: String) {
    CLASSES("Classes"),
    STRUCTS("Structs"),
    ENUMS("Enums"),
    DELEGATES("Delegates"),
    NAMESPACES("Namespaces"),
    /** Functions and variables outside any namespace, Unreal's and script's. */
    GLOBALS("Globals"),
    SCRIPT_NAMESPACES("Script namespaces"),
    /** Search results script declares. */
    SCRIPT_RESULTS("From scripts"),
}

/**
 * The browser's tree over the type database, a level at a time: rows are filled in when they are
 * first expanded, since the database holds tens of thousands of types and a class can see hundreds
 * of members. What script declares is added from [ScriptDeclarations] where it belongs.
 */
object ApiTreeStructure {

    private val BY_NAME = compareBy<UnrealMember, String>(String.CASE_INSENSITIVE_ORDER) { it.name }

    fun roots(database: UnrealTypeDatabase, script: ScriptDeclarations = ScriptDeclarations.NONE): List<ApiNode> = listOf(
        ApiNode.Group(GroupKind.CLASSES, database.typesOfKind(UnrealTypeKind.CLASS).size),
        ApiNode.Group(GroupKind.STRUCTS, database.typesOfKind(UnrealTypeKind.STRUCT).size),
        ApiNode.Group(GroupKind.ENUMS, database.typesOfKind(UnrealTypeKind.ENUM).size),
        ApiNode.Group(GroupKind.DELEGATES, delegates(database).size),
        ApiNode.Group(GroupKind.NAMESPACES, database.standaloneNamespaces.size),
        ApiNode.Group(GroupKind.GLOBALS, size(database.globals) + size(script.globals)),
        ApiNode.Group(GroupKind.SCRIPT_NAMESPACES, script.topLevelNamespaces.size),
    ).filter { it.count > 0 }

    /** Cheap enough to ask of every row in a list of thousands. */
    fun isExpandable(node: ApiNode, database: UnrealTypeDatabase, script: ScriptDeclarations = ScriptDeclarations.NONE): Boolean = when (node) {
        is ApiNode.Group -> node.count > 0
        is ApiNode.Type -> node.type.properties.isNotEmpty() ||
            node.type.methods.isNotEmpty() ||
            node.type.supertypeName?.let(database::type) != null ||
            database.staticsOf(node.type) != null ||
            database.constructorsOf(node.type).isNotEmpty() ||
            script.mixinsOn(node.type.name).isNotEmpty()
        is ApiNode.Namespace, is ApiNode.Statics, is ApiNode.Inherited, is ApiNode.ScriptNamespace -> true
        is ApiNode.Member, is ApiNode.Message -> false
    }

    fun children(node: ApiNode, database: UnrealTypeDatabase, script: ScriptDeclarations = ScriptDeclarations.NONE): List<ApiNode> = when (node) {
        is ApiNode.Group -> when (node.kind) {
            GroupKind.CLASSES -> database.typesOfKind(UnrealTypeKind.CLASS).map { ApiNode.Type(it) }
            GroupKind.STRUCTS -> database.typesOfKind(UnrealTypeKind.STRUCT).map { ApiNode.Type(it) }
            GroupKind.ENUMS -> database.typesOfKind(UnrealTypeKind.ENUM).map { ApiNode.Type(it) }
            GroupKind.DELEGATES -> delegates(database).map { ApiNode.Type(it) }
            GroupKind.NAMESPACES -> database.standaloneNamespaces.map { ApiNode.Namespace(it) }
            GroupKind.GLOBALS -> members(database.globals) + scriptAdditions(script.globals)
            GroupKind.SCRIPT_NAMESPACES -> script.topLevelNamespaces.map { ApiNode.ScriptNamespace(it) }
            GroupKind.SCRIPT_RESULTS -> emptyList()
        }
        is ApiNode.Type -> typeChildren(node.type, database, script)
        is ApiNode.Namespace -> members(node.namespace) + scriptAdditions(script.additionsTo(node.namespace.name))
        is ApiNode.Statics -> members(node.namespace) + scriptAdditions(script.additionsTo(node.namespace.name))
        is ApiNode.Inherited -> members(node.members.properties, node.members.methods) + scriptAdditions(node.mixins)
        is ApiNode.ScriptNamespace -> script.namespacesIn(node.namespace.name).map { ApiNode.ScriptNamespace(it) } + members(node.namespace)
        is ApiNode.Member, is ApiNode.Message -> emptyList()
    }

    /**
     * What a type's row holds: its static members, its constructors, what it declares and then the
     * script mixins that extend it, and then what it inherits, a group per ancestor, nearest first.
     * A mixin is listed only with the type it extends, so a supertype's are in that supertype's group.
     */
    private fun typeChildren(type: UnrealType, database: UnrealTypeDatabase, script: ScriptDeclarations): List<ApiNode> = buildList {
        database.staticsOf(type)?.let { add(ApiNode.Statics(it)) }
        database.constructorsOf(type).forEach { add(ApiNode.Member(it, qualified = false)) }
        if (type.kind == UnrealTypeKind.ENUM) {
            // In declaration order, which is the order of their values.
            type.properties.forEach { add(ApiNode.Member(it, qualified = false)) }
        } else {
            addAll(members(type.properties, type.methods))
        }
        addAll(scriptAdditions(script.mixinsOn(type.name)))
        for (ancestor in database.supertypes(type)) {
            val inherited = ApiNode.Inherited(ancestor, database.membersInheritedFrom(type, ancestor), script.mixinsOn(ancestor.name))
            if (inherited.size > 0) {
                add(inherited)
            }
        }
    }

    /**
     * Where a search result is listed in the whole tree: a test for the row at each level, from its
     * group down to its own. Null for what the tree does not list, such as a mixin on a script type.
     */
    fun pathTo(node: ApiNode, database: UnrealTypeDatabase): List<(ApiNode) -> Boolean>? =
        when (node) {
            is ApiNode.Type -> pathTo(node.type)
            is ApiNode.Namespace -> listOf(isGroup(GroupKind.NAMESPACES), { it is ApiNode.Namespace && it.namespace.name == node.namespace.name })
            is ApiNode.ScriptNamespace -> scriptNamespacePath(node.namespace.name, database)
            is ApiNode.Member -> ownerPath(node.member, database)?.plus { it: ApiNode -> it is ApiNode.Member && it.member === node.member }
            is ApiNode.Group, is ApiNode.Statics, is ApiNode.Inherited, is ApiNode.Message -> null
        }

    private fun pathTo(type: UnrealType): List<(ApiNode) -> Boolean> {
        val group = when (type.kind) {
            UnrealTypeKind.CLASS -> GroupKind.CLASSES
            UnrealTypeKind.STRUCT -> GroupKind.STRUCTS
            UnrealTypeKind.ENUM -> GroupKind.ENUMS
            UnrealTypeKind.DELEGATE, UnrealTypeKind.EVENT -> GroupKind.DELEGATES
        }
        return listOf(isGroup(group), { it is ApiNode.Type && it.type === type })
    }

    /** The rows a member is listed under, as [children] lists it. */
    private fun ownerPath(member: UnrealMember, database: UnrealTypeDatabase): List<(ApiNode) -> Boolean>? {
        val owner = member.owner ?: return listOf(isGroup(GroupKind.GLOBALS))
        database.type(owner)?.let { type ->
            // A type's own members and enum values are its children; its static members are a row down.
            return if (member.ownerSeparator == "." || type.kind == UnrealTypeKind.ENUM) {
                pathTo(type)
            } else {
                pathTo(type) + { it: ApiNode -> it is ApiNode.Statics }
            }
        }
        if (member.ownerSeparator == ".") {
            return null
        }
        return if (database.declaresNamespace(owner)) {
            listOf(isGroup(GroupKind.NAMESPACES), { it is ApiNode.Namespace && it.namespace.name == owner })
        } else {
            scriptNamespacePath(owner, database)
        }
    }

    /** A namespace only script declares is listed in the one it is nested in, if script declares that alone too. */
    private fun scriptNamespacePath(name: String, database: UnrealTypeDatabase): List<(ApiNode) -> Boolean> {
        val names = generateSequence(name) { it.substringBeforeLast("::", "").takeIf { parent -> parent.isNotEmpty() && !database.declaresNamespace(parent) } }
            .toList()
            .asReversed()
        return listOf(isGroup(GroupKind.SCRIPT_NAMESPACES)) + names.map { namespace -> { it: ApiNode -> it is ApiNode.ScriptNamespace && it.namespace.name == namespace } }
    }

    private fun isGroup(kind: GroupKind): (ApiNode) -> Boolean = { it is ApiNode.Group && it.kind == kind }

    private fun size(namespace: UnrealNamespace) = namespace.properties.size + namespace.functions.size

    private fun members(namespace: UnrealNamespace): List<ApiNode> = members(namespace.properties, namespace.functions)

    private fun members(properties: List<UnrealMember>, methods: List<UnrealMember>): List<ApiNode> =
        (properties.sortedWith(BY_NAME) + methods.sortedWith(BY_NAME)).map { ApiNode.Member(it, qualified = false) }

    private fun scriptAdditions(namespace: UnrealNamespace): List<ApiNode> = scriptAdditions(namespace.properties + namespace.functions)

    /** What script declares among Unreal's API: after it, each row naming the file it is in. */
    private fun scriptAdditions(members: List<UnrealMember>): List<ApiNode> =
        members.map { member ->
            ApiNode.Member(member, qualified = false, note = member.scriptOrigin?.let { "in ${it.fileName}" })
        }

    private fun delegates(database: UnrealTypeDatabase): List<UnrealType> =
        (database.typesOfKind(UnrealTypeKind.DELEGATE) + database.typesOfKind(UnrealTypeKind.EVENT))
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}
