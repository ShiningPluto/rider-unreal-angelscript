package com.scriptacus.riderunrealangelscript.overrides

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionHost
import com.intellij.codeInsight.codeVision.CodeVisionProvider
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.CodeVisionState
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.scriptacus.riderunrealangelscript.AngelScriptIcons
import com.scriptacus.riderunrealangelscript.navigation.CppBlueprintEvent
import java.awt.event.MouseEvent
import javax.swing.JList

/**
 * Shows, on a C++ blueprint event, the script classes that implement it.
 *
 * Rider's own code vision on these declarations counts blueprint assets only, and says "not
 * implemented in blueprints" for an event every script subclass overrides. This provider runs on
 * Rider's frontend, where C++ has no PSI, so it reads the header text and takes the implementations
 * from [ScriptOverrideService]; clicking the entry opens the implementation, or a list when there
 * are several.
 */
class ScriptOverridesCodeVisionProvider : CodeVisionProvider<Unit> {

    companion object {
        const val ID = "angelscript.scriptOverrides"

        /** The most classes the tooltip names; beyond that it says how many are left. */
        private const val TOOLTIP_CLASSES = 5

        /** Recomputes the entries in every open editor, for when the overrides have changed. */
        fun refresh(project: Project) {
            ApplicationManager.getApplication().invokeLater({
                project.service<CodeVisionHost>().invalidateProvider(CodeVisionHost.LensInvalidateSignal(null, listOf(ID)))
            }, project.disposed)
        }

        /**
         * One class per line, and only the first few when there are many.
         *
         * An event every interaction class overrides has dozens of them, and as a sentence that ran
         * across the whole screen. The complete list is one click away - the same split as Rider's own
         * blueprint entry, whose tooltip only repeats its text - so the last line says what the click
         * does rather than promising a list the tooltip may already have shown. The platform shows
         * the tooltip in a plain `JLabel`, which is what makes HTML line breaks work here.
         */
        fun tooltip(implementations: List<ScriptOverride>): String {
            val count = implementations.size
            val shown = minOf(count, TOOLTIP_CLASSES)

            val html = HtmlBuilder()
            html.append(if (count == 1) "Overridden in AngelScript by" else "Overridden in AngelScript by $count classes")
            for (implementation in implementations.take(shown)) {
                html.br().append(HtmlChunk.nbsp(2)).append(implementation.scriptClass)
            }
            if (count > shown) {
                html.br().append(HtmlChunk.nbsp(2)).append("and ${count - shown} more")
            }
            html.br().append(HtmlChunk.text(if (count == 1) "Click to open it" else "Click to choose one to open").italic())
            return html.wrapWithHtmlBody().toString()
        }
    }

    override val id: String = ID
    override val name: String = "AngelScript implementations"
    override val relativeOrderings: List<CodeVisionRelativeOrdering> = emptyList()
    override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Default

    override fun precomputeOnUiThread(editor: Editor) = Unit

    override fun computeCodeVision(editor: Editor, uiData: Unit): CodeVisionState {
        val project = editor.project ?: return CodeVisionState.READY_EMPTY
        val events = CppHeaderEvents.inDocument(editor.document)
        if (events.isEmpty()) return CodeVisionState.READY_EMPTY

        // Ready and empty rather than not ready while the server has not answered: a provider that
        // is not ready holds back the other providers' entries for the editor too. The service
        // invalidates this provider once the answer is in.
        val overrides = ScriptOverrideService.getInstance(project).overrides
        if (overrides.isEmpty) return CodeVisionState.READY_EMPTY

        val entries = events.mapNotNull { event ->
            val implementations = overrides.of(event)
            if (implementations.isEmpty()) return@mapNotNull null
            TextRange.from(event.nameOffset, event.name.length) to entry(project, event, implementations)
        }
        return CodeVisionState.Ready(entries)
    }

    private fun entry(project: Project, event: CppBlueprintEvent, implementations: List<ScriptOverride>): CodeVisionEntry {
        val text = if (implementations.size == 1) "1 AngelScript implementation" else "${implementations.size} AngelScript implementations"
        return ClickableTextCodeVisionEntry(
            text,
            id,
            onClick = { mouseEvent, editor -> onClick(project, editor, mouseEvent, event, implementations) },
            icon = AngelScriptIcons.FileIcon,
            longPresentation = text,
            tooltip = tooltip(implementations),
        )
    }

    private fun onClick(
        project: Project,
        editor: Editor,
        mouseEvent: MouseEvent?,
        event: CppBlueprintEvent,
        implementations: List<ScriptOverride>,
    ) {
        if (implementations.size == 1) {
            navigate(project, implementations.single())
            return
        }

        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(implementations)
            .setTitle("AngelScript Implementations of ${event.name}")
            .setRenderer(object : ColoredListCellRenderer<ScriptOverride>() {
                override fun customizeCellRenderer(
                    list: JList<out ScriptOverride>,
                    value: ScriptOverride,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean
                ) {
                    icon = AngelScriptIcons.FileIcon
                    append(value.scriptClass)
                    append("  " + value.path.substringAfterLast('/'), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            })
            .setNamerForFiltering { it.scriptClass }
            .setItemChosenCallback { navigate(project, it) }
            .createPopup()

        if (mouseEvent != null) {
            popup.show(RelativePoint(mouseEvent))
        } else {
            popup.showInBestPositionFor(editor)
        }
    }

    private fun navigate(project: Project, implementation: ScriptOverride) {
        val file = LocalFileSystem.getInstance().findFileByPath(implementation.path) ?: return
        // Navigatable.navigate is override-only API; opening the editor directly does the same
        val descriptor = OpenFileDescriptor(project, file, implementation.line, implementation.character)
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    }
}
