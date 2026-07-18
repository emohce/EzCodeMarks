package emohce.presentation.commitmessage.dialog

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.EditorTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import emohce.domain.commitmessage.AiPreview
import emohce.domain.commitmessage.CommitRefinementSession
import emohce.domain.commitmessage.CommitRefinementSessionSnapshot
import emohce.presentation.commitmessage.CommitMessageBundle
import emohce.domain.commitmessage.CommitMessageDefaults
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent

internal fun interface CommitRefinementInteraction {
    fun refine(
        project: Project,
        currentCommit: String,
        refiner: CommitMessagePreviewRefiner,
    ): CommitRefinementDialogResult?
}

internal class AiCommitMessagePreviewDialog(
    private val project: Project,
    private val preview: AiPreview,
    private val refiner: CommitMessagePreviewRefiner? = null,
    private val refinementInteraction: CommitRefinementInteraction = CommitRefinementInteraction {
            targetProject, currentCommit, targetRefiner ->
        val dialog = CommitRefinementPromptDialog(targetProject, currentCommit, targetRefiner)
        if (dialog.showAndGet()) dialog.result else null
    },
) : DialogWrapper(project) {
    private val session = CommitRefinementSession(preview.original, preview.result)
    private val originalField = EditorTextField(preview.original, project, PlainTextFileType.INSTANCE).apply {
        setOneLineMode(false)
        setViewer(true)
    }
    private val resultField = EditorTextField(preview.result, project, PlainTextFileType.INSTANCE).apply {
        setOneLineMode(false)
    }
    private val copyAction = object : AbstractAction(CommitMessageBundle.message("dialog.preview.copy")) {
        override fun actionPerformed(event: ActionEvent?) {
            CopyPasteManager.copyTextToClipboard(resultField.text)
        }
    }
    private val refineAction = object : AbstractAction(CommitMessageBundle.message("dialog.preview.refine")) {
        override fun actionPerformed(event: ActionEvent?) {
            val targetRefiner = refiner ?: return
            val currentCommit = resultField.text.trim()
            if (currentCommit.isBlank()) return
            val refinement = refinementInteraction.refine(project, currentCommit, targetRefiner) ?: return
            if (refinement.beforeCommit != currentCommit || refinement.afterCommit.isBlank()) return
            session.recordRefinement(
                beforeCommit = refinement.beforeCommit,
                afterCommit = refinement.afterCommit,
                rawPrompt = refinement.rawPrompt,
                aiOptimizedPrompt = refinement.aiOptimizedPrompt,
                confirmedPrompt = refinement.confirmedPrompt,
                promptEnvelope = refinement.promptEnvelope,
                promptOptimizationEnvelope = refinement.promptOptimizationEnvelope,
                promptOptimizationExplanation = refinement.promptOptimizationExplanation,
            )
            resultField.text = refinement.afterCommit
            updateHistoryActionName()
        }
    }
    private val historyAction = object : AbstractAction() {
        override fun actionPerformed(event: ActionEvent?) {
            CommitRefinementHistoryDialog(project, session.snapshot(resultField.text.trim())).show()
        }
    }

    init {
        title = CommitMessageBundle.message("dialog.preview.title")
        setOKButtonText(CommitMessageBundle.message("dialog.preview.apply"))
        setCancelButtonText(CommitMessageBundle.message("dialog.preview.cancel"))
        originalField.setDisposedWith(disposable)
        resultField.setDisposedWith(disposable)
        updateHistoryActionName()
        init()
    }

    val result: String
        get() = resultField.text.trim()

    override fun createCenterPanel(): JComponent = panel {
        row {
            label(CommitMessageBundle.message("dialog.preview.provider", preview.provider))
            label(CommitMessageBundle.message("dialog.preview.endpoint", preview.endpoint))
        }
        row {
            val templateName = if (preview.templateId == CommitMessageDefaults.DEFAULT_TEMPLATE_ID) {
                CommitMessageBundle.message("template.default.name")
            } else {
                preview.template
            }
            label(CommitMessageBundle.message("dialog.preview.template", templateName))
            label(
                CommitMessageBundle.message(
                    "dialog.preview.filtered",
                    preview.filteredFiles.joinToString().ifBlank { "-" },
                ),
            )
        }
        row {
            label(CommitMessageBundle.message("dialog.preview.original"))
            label(CommitMessageBundle.message("dialog.preview.result"))
        }
        row {
            cell(originalField).align(Align.FILL)
            cell(resultField).align(Align.FILL)
        }.resizableRow()
    }.apply { preferredSize = Dimension(1_000, 600) }

    override fun createActions(): Array<Action> = if (refiner == null) {
        arrayOf(okAction, copyAction, cancelAction)
    } else {
        arrayOf(refineAction, historyAction, okAction, copyAction, cancelAction)
    }

    override fun getPreferredFocusedComponent(): JComponent = resultField

    override fun doValidate(): ValidationInfo? = if (resultField.text.isBlank()) {
        ValidationInfo(CommitMessageBundle.message("error.output.invalid"), resultField)
    } else {
        null
    }

    @org.jetbrains.annotations.TestOnly
    internal fun componentForTest(): JComponent = createCenterPanel()

    @org.jetbrains.annotations.TestOnly
    internal fun actionsForTest(): List<Action> = createActions().toList()

    @org.jetbrains.annotations.TestOnly
    internal fun sessionForTest(): CommitRefinementSessionSnapshot = session.snapshot(resultField.text.trim())

    private fun updateHistoryActionName() {
        historyAction.putValue(
            Action.NAME,
            CommitMessageBundle.message("dialog.preview.history", session.operations.size),
        )
    }
}
