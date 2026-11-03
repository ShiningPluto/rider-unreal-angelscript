package com.scriptacus.riderunrealangelscript.typedb

/**
 * How script declares an API item, as text split around its name so that a reader can set the name
 * apart: the API tool window shows it in bold, and the MCP tools as plain text.
 *
 * A function's arguments go one to a line, as upstream's getAPIDetails lays them out, unless there
 * is only one.
 */
class UnrealDeclaration private constructor(val before: String, val name: String, val after: String) {

    override fun toString(): String = before + name + after

    companion object {
        fun of(method: UnrealMethod): UnrealDeclaration {
            val before = buildString {
                if (method.isProtected) append("protected ")
                if (!method.isConstructor) append(method.returnType).append(' ')
                method.owner?.let { append(it).append(method.ownerSeparator) }
            }
            return UnrealDeclaration(before, method.name, argumentList(method.args) + if (method.isConst) " const" else "")
        }

        fun of(property: UnrealProperty): UnrealDeclaration {
            val before = buildString {
                if (property.isProtected) append("protected ")
                // An enum value's type is its enum, which its qualified name already says.
                if (!property.isEnumValue) append(property.type).append(' ')
                property.owner?.let { append(it).append(property.ownerSeparator) }
            }
            return UnrealDeclaration(before, property.name, "")
        }

        /** `class APawn : AActor`, or for a delegate or event, what it is called with. */
        fun of(type: UnrealType): UnrealDeclaration {
            val signature = type.signature
            return if (signature != null) {
                UnrealDeclaration("${type.kind.keyword} ${signature.returnType} ", type.name, argumentList(signature.args))
            } else {
                UnrealDeclaration("${type.kind.keyword} ", type.displayName, type.supertypeName?.let { " : $it" }.orEmpty())
            }
        }

        private fun argumentList(args: List<UnrealArg>): String = when (args.size) {
            0 -> "()"
            1 -> "(${args.single().format()})"
            else -> args.joinToString(",\n", "(\n", "\n)") { "    " + it.format() }
        }
    }
}
