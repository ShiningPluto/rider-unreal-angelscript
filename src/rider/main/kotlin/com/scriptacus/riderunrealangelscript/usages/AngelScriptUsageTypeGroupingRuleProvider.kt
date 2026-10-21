package com.scriptacus.riderunrealangelscript.usages

import com.intellij.openapi.diagnostic.Logger
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
import com.intellij.usages.rules.UsageGroupingRuleProvider
import com.jetbrains.rd.ide.model.findUsagesHost
import com.jetbrains.rider.projectView.solution
import com.redhat.devtools.lsp4ij.usages.LSPUsagePsiElement
import com.scriptacus.riderunrealangelscript.lang.psi.AngelScriptFile
import java.util.concurrent.atomic.AtomicBoolean

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
open class AngelScriptUsageTypeGroupingRuleProvider : UsageGroupingRuleProvider {

    override fun getActiveRules(
        project: Project,
        usageViewSettings: UsageViewSettings,
        presentation: UsageViewPresentation?,
    ): Array<UsageGroupingRule> =
        if (groupsByUsageType(project, usageViewSettings)) arrayOf(AngelScriptUsageTypeGroupingRule())
        else UsageGroupingRule.EMPTY_ARRAY

    private fun groupsByUsageType(project: Project, settings: UsageViewSettings): Boolean {
        // Rider never groups by occurrence type in the Show Usages popup. Its settings class is
        // internal API, so it is recognised by name.
        if (settings.javaClass.name == SHOW_USAGES_SETTINGS_CLASS) return false
        // A Rider without the rule: fall back to the platform's setting rather than never grouping
        return ridersUsageTypeGrouping(project) ?: settings.isGroupByUsageType
    }

    /**
     * Whether Rider's own "Occurrence Type" grouping is on, or null when Rider has no such rule.
     *
     * Read from the rule Rider's backend describes, as Rider does for a rule with no frontend
     * toggle action of its own. Asking Rider's grouping rule provider for its active rules would be
     * simpler, but getActiveRules is override-only API, which the Marketplace verifier reports.
     */
    protected open fun ridersUsageTypeGrouping(project: Project): Boolean? {
        val rule = project.solution.findUsagesHost.groupingRules.valueOrNull?.items
            ?.firstOrNull { it.id == RIDER_USAGE_TYPE_RULE_ID }
            ?: return null
        if (loggedRule.compareAndSet(false, true)) {
            LOG.info("Rider's $RIDER_USAGE_TYPE_RULE_ID grouping rule: groupingActionId=${rule.groupingActionId}, enabled=${rule.enabled.valueOrNull}")
        }
        return rule.enabled.valueOrNull == true
    }

    private companion object {
        val LOG = Logger.getInstance(AngelScriptUsageTypeGroupingRuleProvider::class.java)
        val loggedRule = AtomicBoolean(false)

        // The id of Rider's RiderUsageGroupingRule for occurrence types; not API
        const val RIDER_USAGE_TYPE_RULE_ID = "OccurrenceType"

        const val SHOW_USAGES_SETTINGS_CLASS = "com.intellij.usages.ShowUsageViewSettings"
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
