package com.scriptacus.riderunrealangelscript.recompile

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/** Offered on a compile error Unreal reported: has the editor compile the file again. */
class RecompileInUnrealFix : IntentionAction {

    override fun getText(): String = "Recompile in the Unreal editor"

    override fun getFamilyName(): String = text

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        file?.virtualFile?.isInLocalFileSystem == true && UnrealRecompile.isEditorConnected(project)

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val path = file?.virtualFile?.path ?: return
        UnrealRecompile.recompile(project, listOf(path))
    }

    override fun startInWriteAction(): Boolean = false

    // Nothing in the file changes, so there is nothing to preview - and the preview must not touch it.
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY
}
