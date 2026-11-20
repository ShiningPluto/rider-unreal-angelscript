package com.scriptacus.riderunrealangelscript.usages

import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector.Access
import com.redhat.devtools.lsp4ij.client.features.LSPUsageFeature
import com.redhat.devtools.lsp4ij.usages.LSPUsagePsiElement
import com.scriptacus.riderunrealangelscript.lang.psi.AngelScriptFile

/**
 * Tells lsp4ij whether each AngelScript reference Find Usages lists reads or writes the symbol.
 *
 * `textDocument/references` reports locations only, so the answer comes from the parse tree
 * ([AngelScriptAccess]). lsp4ij makes a reference with a known access a `ReadWriteAccessUsage`, which
 * the read and write access filters of the Find tool window and the Show Usages popup act on, and
 * which has a read or write access icon. [AngelScriptUsageTypeGroupingRule] groups by it.
 *
 * lsp4ij asks only for references, never for declarations, definitions or implementations.
 */
class AngelScriptUsageFeature : LSPUsageFeature() {

    override fun getReadWriteAccess(reference: LSPUsagePsiElement): Access? {
        val file = reference.containingFile as? AngelScriptFile ?: return null
        return AngelScriptAccess.at(file, reference.textRange)
    }
}
