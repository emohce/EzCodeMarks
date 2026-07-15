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
import emohce.presentation.commitmessage.CommitMessageBundle
import emohce.domain.commitmessage.CommitMessageDefaults
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent

class AiCommitMessagePreviewDialog(
    project: Project,
    private val preview: AiPreview,
) : DialogWrapper(project) {
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

    init {
        title = CommitMessageBundle.message("dialog.preview.title")
        setOKButtonText(CommitMessageBundle.message("dialog.preview.apply"))
        setCancelButtonText(CommitMessageBundle.message("dialog.preview.cancel"))
        originalField.setDisposedWith(disposable)
        resultField.setDisposedWith(disposable)
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

    override fun createActions(): Array<Action> = arrayOf(okAction, copyAction, cancelAction)

    override fun getPreferredFocusedComponent(): JComponent = resultField

    override fun doValidate(): ValidationInfo? = if (resultField.text.isBlank()) {
        ValidationInfo(CommitMessageBundle.message("error.output.invalid"), resultField)
    } else {
        null
    }
}
