package com.scriptacus.riderunrealangelscript.usages

import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector.Access
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.scriptacus.riderunrealangelscript.lang.psi.AngelScriptAssignExpr
import com.scriptacus.riderunrealangelscript.lang.psi.AngelScriptExpr
import com.scriptacus.riderunrealangelscript.lang.psi.AngelScriptIdentifierReference
import com.scriptacus.riderunrealangelscript.lang.psi.AngelScriptMemberAccessExpr
import com.scriptacus.riderunrealangelscript.lang.psi.AngelScriptPostfixExpr
import com.scriptacus.riderunrealangelscript.lang.psi.AngelScriptPrimaryExpr
import com.scriptacus.riderunrealangelscript.lang.psi.AngelScriptScopedIdentifier
import com.scriptacus.riderunrealangelscript.lang.psi.AngelScriptTypes
import com.scriptacus.riderunrealangelscript.lang.psi.AngelScriptUnaryExpr
import com.scriptacus.riderunrealangelscript.lang.psi.AngelScriptVariableDeclarator

/**
 * Tells whether the identifier the language server reported at a range is read or written there.
 *
 * `textDocument/references` carries locations only, so the answer has to come from the parse tree.
 * The server does know - `isWriteAccess` on its symbols, which it sends with document highlights - but
 * only for the file a highlight was asked for, and only as far as assignments go: it reads `X++` as a
 * read. Here an assignment, a compound assignment and an increment or decrement are writes, and so is
 * a declaration that gives the variable a value.
 *
 * Writes the syntax cannot show stay reads: passing a variable to an `&out` parameter, and writing
 * through a subscript, where `Items[i] = X` reads `Items`.
 */
object AngelScriptAccess {

    private val INCREMENTS = TokenSet.create(AngelScriptTypes.INC, AngelScriptTypes.DEC)

    /**
     * The access at [range] of [file], or null when the identifier there is not a value being used -
     * a function being called, a type, a namespace, or a declaration that assigns nothing - or when
     * the parse tree has no identifier exactly at [range].
     */
    fun at(file: PsiFile, range: TextRange): Access? {
        val identifier = file.findElementAt(range.startOffset) ?: return null
        if (identifier.elementType != AngelScriptTypes.IDENTIFIER || identifier.textRange != range) {
            return null
        }
        return when (val owner = identifier.parent) {
            is AngelScriptVariableDeclarator ->
                if (owner.variableInitializer != null || owner.variableConstructor != null) Access.Write else null
            is AngelScriptIdentifierReference -> valueExpression(owner)?.let(::accessOf)
            else -> null
        }
    }

    /** The expression whose value [reference] stands for, or null when it names no value. */
    private fun valueExpression(reference: AngelScriptIdentifierReference): AngelScriptExpr? {
        val parent = reference.parent
        if (parent is AngelScriptMemberAccessExpr) {
            return parent
        }
        // `Namespace::`, `EType::` and `Super::` qualify what follows them
        if (PsiTreeUtil.nextCodeLeaf(reference).elementType == AngelScriptTypes.SCOPE) {
            return null
        }
        // Anywhere but directly in an expression the name is a type or a function being called
        val scoped = PsiTreeUtil.getParentOfType(reference, AngelScriptScopedIdentifier::class.java)
        return scoped?.parent as? AngelScriptPrimaryExpr
    }

    private fun accessOf(value: AngelScriptExpr): Access? {
        var operand: PsiElement = value
        while (true) {
            val parent = operand.parent
            if (parent is AngelScriptPrimaryExpr && parent.expr == operand) {
                operand = parent
            } else {
                break
            }
        }
        val next = PsiTreeUtil.nextCodeLeaf(operand).elementType
        return when (val parent = operand.parent) {
            is AngelScriptAssignExpr -> when {
                parent.exprList.firstOrNull() != operand -> Access.Read
                next == AngelScriptTypes.ASSIGNMENT -> Access.Write
                else -> Access.ReadWrite
            }
            is AngelScriptUnaryExpr ->
                if (parent.firstChild.elementType in INCREMENTS) Access.ReadWrite else Access.Read
            is AngelScriptPostfixExpr -> when {
                parent.exprList.firstOrNull() != operand -> Access.Read
                next in INCREMENTS -> Access.ReadWrite
                next == AngelScriptTypes.OPEN_PARENTHESIS -> null
                else -> Access.Read
            }
            else -> Access.Read
        }
    }
}
