package com.scriptacus.riderunrealangelscript.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementVisitor
import com.scriptacus.riderunrealangelscript.lang.psi.*

/**
 * Provides code folding support for AngelScript files.
 * Supports folding for braced blocks (classes, functions, namespaces, etc.) and comment blocks.
 */
class AngelScriptFoldingBuilder : FoldingBuilderEx(), DumbAware {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val descriptors = mutableListOf<FoldingDescriptor>()
        val comments = mutableListOf<PsiElement>()

        root.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (isFoldableBlock(element)) {
                    val node = element.node
                    val range = element.textRange
                    if (node != null && range.length > 2 && 
                        document.getLineNumber(range.startOffset) != document.getLineNumber(range.endOffset)) {
                        descriptors.add(FoldingDescriptor(node, range))
                    }
                }
                
                if (element.node?.elementType == AngelScriptTypes.COMMENT) {
                    comments.add(element)
                }
                
                super.visitElement(element)
            }
        })

        // Support for comments
        var i = 0
        while (i < comments.size) {
            val comment = comments[i]
            val text = comment.text

            if (text.startsWith("/*")) {
                // Block comment
                val range = comment.textRange
                if (document.getLineNumber(range.startOffset) != document.getLineNumber(range.endOffset)) {
                    descriptors.add(FoldingDescriptor(comment.node ?: continue, range))
                }
                i++
            } else if (text.startsWith("//")) {
                // Sequence of line comments
                var j = i + 1
                var lastComment = comment
                while (j < comments.size) {
                    val nextComment = comments[j]
                    if (nextComment.text.startsWith("//") && 
                        isAdjacent(lastComment, nextComment, document)) {
                        lastComment = nextComment
                        j++
                    } else {
                        break
                    }
                }

                if (j > i + 1) {
                    val range = TextRange(comment.textRange.startOffset, lastComment.textRange.endOffset)
                    val commentNode = comment.node
                    if (commentNode != null) {
                        descriptors.add(FoldingDescriptor(commentNode, range))
                    }
                    i = j
                } else {
                    i++
                }
            } else {
                i++
            }
        }

        return descriptors.toTypedArray()
    }

    private fun isFoldableBlock(element: PsiElement): Boolean {
        return element is AngelScriptClassBody ||
                element is AngelScriptStructBody ||
                element is AngelScriptFunctionBody ||
                element is AngelScriptStatementBlock ||
                element is AngelScriptNamespaceBody ||
                element is AngelScriptEnumBody ||
                element is AngelScriptAssetBody
    }

    private fun isAdjacent(c1: PsiElement, c2: PsiElement, document: Document): Boolean {
        val line1 = document.getLineNumber(c1.textRange.endOffset)
        val line2 = document.getLineNumber(c2.textRange.startOffset)
        if (line2 != line1 + 1) return false
        
        val betweenRange = TextRange(c1.textRange.endOffset, c2.textRange.startOffset)
        val betweenText = document.getText(betweenRange)
        return betweenText.trim().isEmpty()
    }

    override fun getPlaceholderText(node: ASTNode): String? {
        val type = node.elementType
        if (type == AngelScriptTypes.COMMENT) {
            val text = node.text
            if (text.startsWith("/*")) return "/*...*/"
            if (text.startsWith("//")) return "//..."
        }
        
        return "{...}"
    }

    override fun isCollapsedByDefault(node: ASTNode): Boolean {
        return false
    }
}
