package emohce.presentation.commitmessage.dialog

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.EditorTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import emohce.domain.commitmessage.CommitStyleProposal
import emohce.presentation.commitmessage.CommitMessageBundle
import java.awt.Dimension
import javax.swing.JComponent

class CommitStyleProposalDialog(
    project: Project,
    proposal: CommitStyleProposal,
) : DialogWrapper(project) {
    private val promptEditor = EditorTextField(proposal.prompt, project, PlainTextFileType.INSTANCE).apply {
        setOneLineMode(false)
        setDisposedWith(disposable)
    }
    private val templateEditor = EditorTextField(proposal.template, project, PlainTextFileType.INSTANCE).apply {
        setOneLineMode(false)
        setDisposedWith(disposable)
    }
    private val explanation = proposal.explanation

    init {
        title = CommitMessageBundle.message("dialog.styleProposal.title")
        setOKButtonText(CommitMessageBundle.message("dialog.styleProposal.apply"))
        init()
    }

    val result: CommitStyleProposal
        get() = CommitStyleProposal(
            prompt = promptEditor.text.trim(),
            template = templateEditor.text.trim(),
            explanation = explanation,
        )

    override fun createCenterPanel(): JComponent = panel {
        row { comment(CommitMessageBundle.message("dialog.styleProposal.explanation", explanation.ifBlank { "-" })) }
        row(CommitMessageBundle.message("settings.styles.prompt")) {
            cell(promptEditor).align(Align.FILL).resizableColumn()
        }.resizableRow()
        row(CommitMessageBundle.message("settings.styles.template")) {
            cell(templateEditor).align(Align.FILL).resizableColumn()
        }.resizableRow()
    }.apply { preferredSize = Dimension(900, 560) }
}
