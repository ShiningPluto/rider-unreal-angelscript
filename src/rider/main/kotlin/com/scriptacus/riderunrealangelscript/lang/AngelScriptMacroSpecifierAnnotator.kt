package com.scriptacus.riderunrealangelscript.lang

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.scriptacus.riderunrealangelscript.lang.psi.AngelScriptMacroArguments
import com.scriptacus.riderunrealangelscript.lang.psi.AngelScriptTypes

/**
 * Colours the specifiers inside Unreal declaration macros, such as `DefaultComponent` in
 * `UPROPERTY(DefaultComponent, RootComponent)`.
 *
 * The lexer cannot tell a specifier from any other identifier - the same word is an ordinary
 * identifier everywhere else - so the decision is made on the parse tree: an identifier inside a
 * macro argument list whose name is one of the documented specifiers.
 */
class AngelScriptMacroSpecifierAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.node?.elementType != AngelScriptTypes.IDENTIFIER) return
        if (element.text !in AngelScriptMacroSpecifiers.NAMES) return
        if (PsiTreeUtil.getParentOfType(element, AngelScriptMacroArguments::class.java) == null) return

        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(element)
            .textAttributes(AngelScriptSyntaxHighlighter.MACRO_SPECIFIER)
            .create()
    }
}
