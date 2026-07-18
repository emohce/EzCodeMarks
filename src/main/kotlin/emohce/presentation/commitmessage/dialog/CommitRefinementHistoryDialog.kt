package emohce.presentation.commitmessage.dialog

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.EditorTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import emohce.domain.commitmessage.CommitRefinementSessionSnapshot
import emohce.presentation.commitmessage.CommitMessageBundle
import org.jetbrains.annotations.TestOnly
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent

internal class CommitRefinementHistoryDialog(
    project: Project,
    private val snapshot: CommitRefinementSessionSnapshot,
) : DialogWrapper(project) {
    private val originalField = viewer(project, snapshot.originalCommit)
    private val initialResultField = viewer(project, snapshot.initialAiResult)
    private val finalField = viewer(project, snapshot.finalCommit)
    private val historyText = renderHistory(snapshot)
    private val copyText = renderCopyText(snapshot, historyText)
    private val historyField = viewer(project, historyText)
    private val copyAction = object : AbstractAction(CommitMessageBundle.message("dialog.refinementHistory.copy")) {
        override fun actionPerformed(event: ActionEvent?) {
            CopyPasteManager.copyTextToClipboard(copyText)
        }
    }

    init {
        title = CommitMessageBundle.message("dialog.refinementHistory.title", snapshot.operations.size)
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label(CommitMessageBundle.message("dialog.refinementHistory.original"))
            label(CommitMessageBundle.message("dialog.refinementHistory.initialResult"))
        }
        row {
            cell(originalField).align(Align.FILL).resizableColumn()
            cell(initialResultField).align(Align.FILL).resizableColumn()
        }.resizableRow()
        row(CommitMessageBundle.message("dialog.refinementHistory.finalResult")) {
            cell(finalField).align(Align.FILL).resizableColumn()
        }.resizableRow()
        row(CommitMessageBundle.message("dialog.refinementHistory.operations")) {
            cell(historyField).align(Align.FILL).resizableColumn()
        }.resizableRow()
    }.apply { preferredSize = Dimension(1_020, 720) }

    override fun createActions(): Array<Action> = arrayOf(copyAction, okAction)

    @TestOnly
    internal fun componentForTest(): JComponent = createCenterPanel()

    @TestOnly
    internal fun copyTextForTest(): String = copyText

    private fun viewer(project: Project, text: String): EditorTextField =
        EditorTextField(text, project, PlainTextFileType.INSTANCE).apply {
            setOneLineMode(false)
            setViewer(true)
            setDisposedWith(disposable)
        }

    private fun renderHistory(value: CommitRefinementSessionSnapshot): String = if (value.operations.isEmpty()) {
        CommitMessageBundle.message("dialog.refinementHistory.empty")
    } else {
        value.operations.joinToString("\n\n") { operation ->
            buildString {
                append(CommitMessageBundle.message("dialog.refinementHistory.step", operation.sequence)).append('\n')
                append(CommitMessageBundle.message("dialog.refinementHistory.rawPrompt")).append('\n')
                append(operation.rawPrompt).append("\n\n")
                append(CommitMessageBundle.message("dialog.refinementHistory.optimizedPrompt")).append('\n')
                append(operation.aiOptimizedPrompt.ifBlank { "-" }).append("\n\n")
                if (operation.promptOptimizationExplanation.isNotBlank()) {
                    append(CommitMessageBundle.message("dialog.refinementHistory.optimizationExplanation")).append('\n')
                    append(operation.promptOptimizationExplanation).append("\n\n")
                }
                operation.promptOptimizationEnvelope?.let { optimizerEnvelope ->
                    append(CommitMessageBundle.message("dialog.refinementHistory.optimizerSystem")).append('\n')
                    append(optimizerEnvelope.systemPrompt).append("\n\n")
                    append(CommitMessageBundle.message("dialog.refinementHistory.optimizerUser")).append('\n')
                    append(optimizerEnvelope.userPrompt).append("\n\n")
                }
                append(CommitMessageBundle.message("dialog.refinementHistory.confirmedPrompt")).append('\n')
                append(operation.confirmedPrompt).append("\n\n")
                append(CommitMessageBundle.message("dialog.refinementHistory.before")).append('\n')
                append(operation.beforeCommit).append("\n\n")
                append(CommitMessageBundle.message("dialog.refinementHistory.after")).append('\n')
                append(operation.afterCommit).append("\n\n")
                append(CommitMessageBundle.message("dialog.promptEnvelope.system")).append('\n')
                append(operation.promptEnvelope.systemPrompt).append("\n\n")
                append(CommitMessageBundle.message("dialog.promptEnvelope.user")).append('\n')
                append(operation.promptEnvelope.userPrompt)
            }
        }
    }

    private fun renderCopyText(value: CommitRefinementSessionSnapshot, operationsText: String): String = buildString {
        append(CommitMessageBundle.message("dialog.refinementHistory.original")).append('\n')
        append(value.originalCommit).append("\n\n")
        append(CommitMessageBundle.message("dialog.refinementHistory.initialResult")).append('\n')
        append(value.initialAiResult).append("\n\n")
        append(CommitMessageBundle.message("dialog.refinementHistory.finalResult")).append('\n')
        append(value.finalCommit).append("\n\n")
        append(CommitMessageBundle.message("dialog.refinementHistory.operations")).append('\n')
        append(operationsText)
    }
}
