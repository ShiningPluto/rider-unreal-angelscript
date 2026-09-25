package com.scriptacus.riderunrealangelscript.usages

import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector.Access
import com.intellij.psi.PsiElement
import com.intellij.usages.UsageTarget
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageTypeProviderEx
import com.redhat.devtools.lsp4ij.usages.LSPUsagePsiElement
import com.scriptacus.riderunrealangelscript.lang.psi.AngelScriptFile

/**
 * Gives the references Find Usages lists for AngelScript the usage types "Value read" and
 * "Value write".
 *
 * lsp4ij types every `textDocument/references` result as "References". Registered ahead of its
 * provider, this one takes the references it can classify and leaves the rest - calls, types,
 * declarations that assign nothing, and every other kind of result - to lsp4ij. Rider groups by
 * none of these types itself; [AngelScriptUsageTypeGroupingRuleProvider] does.
 *
 * Only the grouping can be supplied this way. The read and write access filters and icons act on
 * usages created as `ReadWriteAccessUsage`, and lsp4ij creates plain ones; see
 * docs/plans/0005-read-write-usage-filters.md.
 */
class AngelScriptUsageTypeProvider : UsageTypeProviderEx {

    override fun getUsageType(element: PsiElement): UsageType? = getUsageType(element, UsageTarget.EMPTY_ARRAY)

    override fun getUsageType(element: PsiElement, targets: Array<out UsageTarget>): UsageType? {
        if (element !is LSPUsagePsiElement || element.kind != LSPUsagePsiElement.UsageKind.references) {
            return null
        }
        val file = element.containingFile as? AngelScriptFile ?: return null
        return when (AngelScriptAccess.at(file, element.textRange)) {
            Access.Read -> UsageType.READ
            Access.Write, Access.ReadWrite -> UsageType.WRITE
            null -> null
        }
    }
}
