package com.scriptacus.riderunrealangelscript.usages

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.usages.Usage
import com.intellij.usages.UsageGroup
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageViewPresentation
import com.intellij.usages.UsageViewSettings
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageTypeProvider
import com.intellij.usages.impl.rules.UsageTypeProviderEx
import com.intellij.usages.rules.PsiElementUsage
import com.intellij.usages.rules.SingleParentUsageGroupingRule
import com.intellij.usages.rules.UsageGroupingRule
import com.intellij.usages.rules.UsageGroupingRuleEx
import com.intellij.usages.rules.UsageGroupingRuleProvider
import com.intellij.usages.rules.UsageGroupingRuleProviderEx
import com.redhat.devtools.lsp4ij.usages.LSPUsagePsiElement
import com.scriptacus.riderunrealangelscript.lang.psi.AngelScriptFile

/**
 * Groups AngelScript usages in the Find tool window by their usage type, which Rider does not do.
 *
 * The platform groups by usage type through `UsageGroupingRuleProviderImpl`, and Rider unregisters
 * it at startup (`RiderExtensionsConfigurator`) in favour of rules that only group the usages its
 * own backend finds. The types lsp4ij and [AngelScriptUsageTypeProvider] give AngelScript usages
 * were never shown. This asks the same providers the platform rule asks, for AngelScript usages
 * only, so every other result keeps Rider's grouping.
 *
 * It is on when Rider groups its own results by usage type, which is the "Occurrence Type" item
 * under Group By; toggling that item makes every usage view ask again.
 */
class AngelScriptUsageTypeGroupingRuleProvider : UsageGroupingRuleProvider {

    override fun getActiveRules(
        project: Project,
        usageViewSettings: UsageViewSettings,
        presentation: UsageViewPresentation?,
    ): Array<UsageGroupingRule> =
        if (groupsByUsageType(project, usageViewSettings, presentation)) arrayOf(AngelScriptUsageTypeGroupingRule())
        else UsageGroupingRule.EMPTY_ARRAY

    private fun groupsByUsageType(project: Project, settings: UsageViewSettings, presentation: UsageViewPresentation?): Boolean {
        val others = GROUPING_RULE_PROVIDERS.extensionList.filter { it !is AngelScriptUsageTypeGroupingRuleProvider }
        if (others.any { provider -> provider.getActiveRules(project, settings, presentation).any(::isRidersUsageTypeRule) }) {
            return true
        }
        // A Rider without the rule: fall back to the platform's setting rather than never grouping
        val riderHasTheRule = others.any { provider ->
            provider is UsageGroupingRuleProviderEx && provider.getAllRules(project, null).any(::isRidersUsageTypeRule)
        }
        return !riderHasTheRule && settings.isGroupByUsageType
    }

    private companion object {
        // UsageGroupingRuleProvider.EP_NAME is internal API
        val GROUPING_RULE_PROVIDERS = ExtensionPointName.create<UsageGroupingRuleProvider>("com.intellij.usageGroupingRuleProvider")

        // RiderUsageGroupingRule.occurrenceType; not API
        const val RIDER_USAGE_TYPE_RULE_ID = "OccurrenceType"

        fun isRidersUsageTypeRule(rule: UsageGroupingRule) = (rule as? UsageGroupingRuleEx)?.id == RIDER_USAGE_TYPE_RULE_ID
    }
}

// Parses no more than the file the usage is in, so it keeps grouping while indexes are rebuilt
class AngelScriptUsageTypeGroupingRule : SingleParentUsageGroupingRule(), DumbAware {

    override fun getParentGroupFor(usage: Usage, targets: Array<out UsageTarget>): UsageGroup? {
        val element = (usage as? PsiElementUsage)?.element ?: return null
        return usageTypeOf(element, targets)?.let(::UsageTypeGroup)
    }

    // Outermost, as the platform places its usage type groups; Rider's own rules come from its backend
    override fun getRank(): Int = Int.MIN_VALUE

    internal fun usageTypeOf(element: PsiElement, targets: Array<out UsageTarget>): UsageType? {
        if (element !is LSPUsagePsiElement || element.containingFile !is AngelScriptFile) return null
        return USAGE_TYPE_PROVIDERS.extensionList.firstNotNullOfOrNull {
            if (it is UsageTypeProviderEx) it.getUsageType(element, targets) else it.getUsageType(element)
        } ?: UsageType.UNCLASSIFIED
    }

    private data class UsageTypeGroup(val type: UsageType) : UsageGroup {
        override fun getPresentableGroupText(): String = type.toString()
        override fun compareTo(other: UsageGroup): Int = presentableGroupText.compareTo(other.presentableGroupText)
    }

    private companion object {
        // UsageTypeProvider.EP_NAME is internal API
        val USAGE_TYPE_PROVIDERS = ExtensionPointName.create<UsageTypeProvider>("com.intellij.usageTypeProvider")
    }
}
