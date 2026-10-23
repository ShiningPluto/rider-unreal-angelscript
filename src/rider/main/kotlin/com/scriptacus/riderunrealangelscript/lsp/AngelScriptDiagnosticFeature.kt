package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.openapi.editor.Document
import com.redhat.devtools.lsp4ij.client.features.LSPDiagnosticFeature
import com.scriptacus.riderunrealangelscript.recompile.RecompileInUnrealFix
import com.scriptacus.riderunrealangelscript.recompile.UnrealRecompile
import org.eclipse.lsp4j.Diagnostic

/**
 * Offers to have Unreal compile a file again on the compile errors it reported, which stay until it
 * does even once their cause is gone.
 */
class AngelScriptDiagnosticFeature : LSPDiagnosticFeature() {

    override fun createAnnotation(
        diagnostic: Diagnostic,
        document: Document,
        fixes: List<IntentionAction>,
        holder: AnnotationHolder,
    ) {
        val withRecompile =
            if (UnrealRecompile.isUnrealCompileError(diagnostic.source, diagnostic.severity)) fixes + RecompileInUnrealFix()
            else fixes
        super.createAnnotation(diagnostic, document, withRecompile, holder)
    }
}
