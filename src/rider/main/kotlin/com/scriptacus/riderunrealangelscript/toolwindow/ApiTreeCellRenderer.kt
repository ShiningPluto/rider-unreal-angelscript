package com.scriptacus.riderunrealangelscript.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.scriptacus.riderunrealangelscript.AngelScriptIcons
import com.scriptacus.riderunrealangelscript.typedb.UnrealMember
import com.scriptacus.riderunrealangelscript.typedb.UnrealMethod
import com.scriptacus.riderunrealangelscript.typedb.UnrealProperty
import com.scriptacus.riderunrealangelscript.typedb.UnrealTypeKind
import javax.swing.Icon
import javax.swing.JTree

/**
 * Draws API browser rows the way Rider's structure view draws members: the name, then the details
 * that tell overloads and types apart in grey, and then, in italics so that it never reads as a
 * type, a row's note.
 */
class ApiTreeCellRenderer : ColoredTreeCellRenderer() {

    companion object {
        fun text(node: ApiNode): String = when (node) {
            is ApiNode.Group -> node.kind.title
            is ApiNode.Type -> node.type.displayName
            is ApiNode.Namespace -> node.namespace.name + "::"
            is ApiNode.Statics -> "Static members"
            is ApiNode.Inherited -> "Inherited from ${node.ancestor.name}"
            is ApiNode.ScriptMixins -> "Script mixins"
            is ApiNode.Member -> memberText(node.member, node.qualified)
            is ApiNode.ScriptNamespace -> node.namespace.name + "::"
            is ApiNode.Message -> node.text
        }

        private fun memberText(member: UnrealMember, qualified: Boolean): String {
            val name = if (qualified) member.qualifiedName else member.name
            return when (member) {
                is UnrealMethod -> member.args.joinToString(", ", "$name(", ")") { it.type }
                is UnrealProperty -> name
            }
        }

        /** The grey text after a row, with the space that separates it. */
        private fun details(node: ApiNode): String? = when (node) {
            is ApiNode.Group -> "  ${node.count}"
            is ApiNode.Type -> node.type.supertypeName?.let { " : $it" }
            is ApiNode.Statics -> "  ${node.namespace.name}::"
            is ApiNode.Inherited -> "  ${node.members.size}"
            is ApiNode.ScriptMixins -> "  ${node.mixins.size}"
            is ApiNode.Member -> when (val member = node.member) {
                is UnrealMethod -> if (member.isConstructor) null else ": " + member.returnType
                // An enum value's type is the enum it is listed under.
                is UnrealProperty -> if (member.type == member.owner) null else ": " + member.type
            }
            is ApiNode.Namespace, is ApiNode.ScriptNamespace, is ApiNode.Message -> null
        }

        private fun icon(node: ApiNode): Icon? = when (node) {
            is ApiNode.Group -> AllIcons.Nodes.Folder
            is ApiNode.Type -> when (node.type.kind) {
                UnrealTypeKind.CLASS -> AllIcons.Nodes.Class
                UnrealTypeKind.STRUCT -> AllIcons.Nodes.Record
                UnrealTypeKind.ENUM -> AllIcons.Nodes.Enum
                UnrealTypeKind.DELEGATE, UnrealTypeKind.EVENT -> AllIcons.Nodes.Lambda
            }
            is ApiNode.Namespace, is ApiNode.ScriptNamespace -> AllIcons.Nodes.Package
            is ApiNode.Statics -> AllIcons.Nodes.Static
            is ApiNode.Inherited -> AllIcons.Hierarchy.Supertypes
            is ApiNode.ScriptMixins -> AngelScriptIcons.FileIcon
            // m and p for what is reached through an object, f and v for what is not. Field's "f" read
            // as "function" next to Function's, so a type's properties use Property's "p" instead.
            is ApiNode.Member -> when (val member = node.member) {
                is UnrealMethod -> if (member.ownerSeparator == "." || member.isConstructor) AllIcons.Nodes.Method else AllIcons.Nodes.Function
                is UnrealProperty -> when {
                    member.ownerSeparator == "." -> AllIcons.Nodes.Property
                    member.type == member.owner -> AllIcons.Nodes.Constant
                    else -> AllIcons.Nodes.Variable
                }
            }
            is ApiNode.Message -> null
        }
    }

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        val node = (value as? ApiTreeNode)?.node ?: return
        icon = icon(node)
        if (node is ApiNode.Message) {
            append(node.text, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            return
        }
        append(text(node))
        details(node)?.let { append(it, SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        (node as? ApiNode.Member)?.note?.let { append("  $it", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
    }
}
