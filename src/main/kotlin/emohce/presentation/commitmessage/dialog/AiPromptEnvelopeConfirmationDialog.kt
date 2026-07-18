package emohce.presentation.commitmessage.dialog

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.EditorTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import emohce.domain.commitmessage.LlmPromptEnvelope
import emohce.presentation.commitmessage.CommitMessageBundle
import org.jetbrains.annotations.TestOnly
import java.awt.Dimension
import javax.swing.JComponent

internal class AiPromptEnvelopeConfirmationDialog(
    project: Project,
    private val envelope: LlmPromptEnvelope,
    titleKey: String,
) : DialogWrapper(project) {
    private val systemPrompt = EditorTextField(envelope.systemPrompt, project, PlainTextFileType.INSTANCE).apply {
        setOneLineMode(false)
        setViewer(true)
        setDisposedWith(disposable)
    }
    private val userPrompt = EditorTextField(envelope.userPrompt, project, PlainTextFileType.INSTANCE).apply {
        setOneLineMode(false)
        setViewer(true)
        setDisposedWith(disposable)
    }

    init {
        title = CommitMessageBundle.message(titleKey)
        setOKButtonText(CommitMessageBundle.message("dialog.promptEnvelope.confirm"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { comment(CommitMessageBundle.message("dialog.promptEnvelope.notice")) }
        row(CommitMessageBundle.message("dialog.promptEnvelope.system")) {
            cell(systemPrompt).align(Align.FILL).resizableColumn()
        }.resizableRow()
        row(CommitMessageBundle.message("dialog.promptEnvelope.user")) {
            cell(userPrompt).align(Align.FILL).resizableColumn()
        }.resizableRow()
    }.apply { preferredSize = Dimension(920, 620) }

    @TestOnly
    internal fun componentForTest(): JComponent = createCenterPanel()
}
