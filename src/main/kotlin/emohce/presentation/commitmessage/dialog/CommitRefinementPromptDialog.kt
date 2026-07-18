package emohce.presentation.commitmessage.dialog

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import emohce.data.commitmessage.MissingChatGptLoginException
import emohce.domain.commitmessage.CommitPromptOptimizationProposal
import emohce.presentation.commitmessage.CommitMessageBundle
import org.jetbrains.annotations.TestOnly
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.util.concurrent.atomic.AtomicReference
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

internal data class CommitRefinementDialogResult(
    val beforeCommit: String,
    val afterCommit: String,
    val rawPrompt: String,
    val aiOptimizedPrompt: String,
    val confirmedPrompt: String,
    val promptEnvelope: emohce.domain.commitmessage.LlmPromptEnvelope,
    val promptOptimizationEnvelope: emohce.domain.commitmessage.LlmPromptEnvelope? = null,
    val promptOptimizationExplanation: String = "",
)

internal interface CommitRefinementProgressRunner {
    fun <T : Any> run(
        project: Project,
        title: String,
        operation: (ProgressIndicator) -> T,
    ): Result<T>?
}

internal object IntelliJCommitRefinementProgressRunner : CommitRefinementProgressRunner {
    override fun <T : Any> run(
        project: Project,
        title: String,
        operation: (ProgressIndicator) -> T,
    ): Result<T>? {
        val value = AtomicReference<T?>()
        val failure = AtomicReference<Throwable?>()
        val localIndicator = EmptyProgressIndicator()
        val bridgedIndicator = object : ProgressIndicator by localIndicator {
            override fun checkCanceled() {
                localIndicator.checkCanceled()
                ProgressManager.checkCanceled()
            }
        }
        val completed = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                Runnable {
                    try {
                        value.set(operation(bridgedIndicator))
                    } catch (error: Exception) {
                        failure.set(error)
                    }
                },
                title,
                true,
                project,
            )
        } catch (_: ProcessCanceledException) {
            false
        }
        val error = failure.get()
        if (!completed || error is ProcessCanceledException) return null
        if (error != null) return Result.failure(error)
        return value.get()?.let(Result.Companion::success)
    }
}

internal class CommitRefinementPromptDialog(
    private val project: Project,
    private val currentCommit: String,
    private val refiner: CommitMessagePreviewRefiner,
    private val runner: CommitRefinementProgressRunner = IntelliJCommitRefinementProgressRunner,
    private val confirmEnvelope: (Project, String, emohce.domain.commitmessage.LlmPromptEnvelope) -> Boolean =
        { targetProject, titleKey, envelope ->
            AiPromptEnvelopeConfirmationDialog(targetProject, envelope, titleKey).showAndGet()
        },
) : DialogWrapper(project) {
    private val currentCommitField = EditorTextField(currentCommit, project, PlainTextFileType.INSTANCE).apply {
        setOneLineMode(false)
        setViewer(true)
        setDisposedWith(disposable)
    }
    private val rawPromptField = JBTextArea(5, 72).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val optimizedPromptField = JBTextArea(5, 72).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val explanationField = JBTextArea(3, 72).apply {
        lineWrap = true
        wrapStyleWord = true
        isEditable = false
        isOpaque = false
    }
    private val statusLabel = JBLabel()
    private var attempt = refiner.newAttempt()
    private var aiOptimizedPrompt = ""
    private var promptOptimizationEnvelope: emohce.domain.commitmessage.LlmPromptEnvelope? = null
    private var promptOptimizationExplanation = ""
    private var loadingPrompt = false
    private var acceptedResult: CommitRefinementDialogResult? = null
    private val optimizePromptAction = object : AbstractAction(
        CommitMessageBundle.message("dialog.refinement.optimizePrompt"),
    ) {
        override fun actionPerformed(event: ActionEvent?) = optimizePrompt()
    }

    init {
        title = CommitMessageBundle.message("dialog.refinement.title")
        setOKButtonText(CommitMessageBundle.message("dialog.refinement.confirmAndApply"))
        bindPromptRevision()
        init()
    }

    val result: CommitRefinementDialogResult
        get() = checkNotNull(acceptedResult)

    override fun createCenterPanel(): JComponent = panel {
        row(CommitMessageBundle.message("dialog.refinement.currentCommit")) {
            cell(currentCommitField).align(Align.FILL).resizableColumn()
        }.resizableRow()
        row(CommitMessageBundle.message("dialog.refinement.rawPrompt")) {
            scrollCell(rawPromptField).align(Align.FILL).resizableColumn()
        }.resizableRow()
        row(CommitMessageBundle.message("dialog.refinement.optimizedPrompt")) {
            scrollCell(optimizedPromptField).align(Align.FILL).resizableColumn()
        }.resizableRow()
        row(CommitMessageBundle.message("dialog.refinement.explanation")) {
            scrollCell(explanationField).align(Align.FILL).resizableColumn()
        }
        row { cell(statusLabel).align(Align.FILL).resizableColumn() }
    }.apply { preferredSize = Dimension(900, 700) }

    override fun createActions(): Array<Action> = arrayOf(optimizePromptAction, okAction, cancelAction)

    override fun getPreferredFocusedComponent(): JComponent = rawPromptField

    override fun doValidate(): ValidationInfo? {
        val rawPrompt = rawPromptField.text.trim()
        val confirmedPrompt = optimizedPromptField.text.trim().ifBlank { rawPrompt }
        return when {
            rawPrompt.isBlank() -> ValidationInfo(
                CommitMessageBundle.message("dialog.refinement.promptRequired"),
                rawPromptField,
            )
            rawPrompt.length > MAX_PROMPT_LENGTH || confirmedPrompt.length > MAX_PROMPT_LENGTH -> ValidationInfo(
                CommitMessageBundle.message("dialog.refinement.promptTooLong", MAX_PROMPT_LENGTH),
                if (rawPrompt.length > MAX_PROMPT_LENGTH) rawPromptField else optimizedPromptField,
            )
            else -> null
        }
    }

    override fun doOKAction() {
        val validation = doValidate()
        if (validation != null) {
            validation.component?.requestFocusInWindow()
            statusLabel.text = validation.message
            return
        }
        val rawPrompt = rawPromptField.text.trim()
        val confirmedPrompt = optimizedPromptField.text.trim().ifBlank { rawPrompt }
        val prepared = runCatching { attempt.prepareCommitRefinement(currentCommit, confirmedPrompt) }
            .getOrElse {
                showFailure(it)
                return
            }
        if (!confirmEnvelope(project, "dialog.promptEnvelope.commitTitle", prepared.envelope)) return
        val outcome = runner.run(project, CommitMessageBundle.message("progress.refineCommit")) { indicator ->
            attempt.refineCommit(prepared, indicator)
        } ?: run {
            statusLabel.text = CommitMessageBundle.message("dialog.refinement.cancelled")
            return
        }
        outcome.onSuccess { refinedCommit ->
            acceptedResult = CommitRefinementDialogResult(
                beforeCommit = currentCommit,
                afterCommit = refinedCommit,
                rawPrompt = rawPrompt,
                aiOptimizedPrompt = aiOptimizedPrompt,
                confirmedPrompt = confirmedPrompt,
                promptEnvelope = prepared.envelope,
                promptOptimizationEnvelope = promptOptimizationEnvelope,
                promptOptimizationExplanation = promptOptimizationExplanation,
            )
            super.doOKAction()
        }.onFailure {
            attempt = refiner.newAttempt()
            showFailure(it)
        }
    }

    @TestOnly
    internal fun componentForTest(): JComponent = createCenterPanel()

    @TestOnly
    internal fun optimizeActionForTest(): Action = optimizePromptAction

    @TestOnly
    internal fun setRawPromptForTest(value: String) {
        rawPromptField.text = value
    }

    @TestOnly
    internal fun optimizedPromptForTest(): String = optimizedPromptField.text

    @TestOnly
    internal fun confirmForTest() = doOKAction()

    @TestOnly
    internal fun acceptedResultForTest(): CommitRefinementDialogResult? = acceptedResult

    private fun optimizePrompt() {
        val validation = doValidate()?.takeIf { rawPromptField.text.trim().isBlank() || rawPromptField.text.trim().length > MAX_PROMPT_LENGTH }
        if (validation != null) {
            statusLabel.text = validation.message
            validation.component?.requestFocusInWindow()
            return
        }
        if (attempt.usedRequests() > 0) attempt = refiner.newAttempt()
        val rawPrompt = rawPromptField.text.trim()
        val prepared = runCatching { attempt.preparePromptOptimization(currentCommit, rawPrompt) }
            .getOrElse {
                showFailure(it)
                return
            }
        if (!confirmEnvelope(project, "dialog.promptEnvelope.optimizeTitle", prepared.envelope)) return
        val outcome = runner.run(project, CommitMessageBundle.message("progress.optimizePrompt")) { indicator ->
            attempt.optimizePrompt(prepared, indicator)
        } ?: run {
            statusLabel.text = CommitMessageBundle.message("dialog.refinement.cancelled")
            return
        }
        outcome.onSuccess { proposal -> acceptOptimizedPrompt(proposal, prepared.envelope) }.onFailure {
            attempt = refiner.newAttempt()
            showFailure(it)
        }
    }

    private fun acceptOptimizedPrompt(
        proposal: CommitPromptOptimizationProposal,
        envelope: emohce.domain.commitmessage.LlmPromptEnvelope,
    ) {
        aiOptimizedPrompt = proposal.optimizedInstruction
        promptOptimizationEnvelope = envelope
        promptOptimizationExplanation = proposal.explanation
        loadingPrompt = true
        try {
            optimizedPromptField.text = proposal.optimizedInstruction
            explanationField.text = proposal.explanation
        } finally {
            loadingPrompt = false
        }
        statusLabel.text = CommitMessageBundle.message("dialog.refinement.promptReady")
    }

    private fun bindPromptRevision() {
        rawPromptField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                if (loadingPrompt) return
                if (aiOptimizedPrompt.isNotEmpty() || optimizedPromptField.text.isNotEmpty()) {
                    loadingPrompt = true
                    try {
                        optimizedPromptField.text = ""
                        explanationField.text = ""
                        aiOptimizedPrompt = ""
                        promptOptimizationEnvelope = null
                        promptOptimizationExplanation = ""
                    } finally {
                        loadingPrompt = false
                    }
                }
                attempt = refiner.newAttempt()
                statusLabel.text = ""
            }
        })
    }

    private fun showFailure(error: Throwable) {
        if (error is ProcessCanceledException) {
            statusLabel.text = CommitMessageBundle.message("dialog.refinement.cancelled")
            return
        }
        val message = if (error is MissingChatGptLoginException) {
            CommitMessageBundle.message("error.chatgpt.loginRequired")
        } else {
            error.message?.take(400).orEmpty().ifBlank {
                CommitMessageBundle.message("error.provider.response")
            }
        }
        statusLabel.text = message
        Messages.showErrorDialog(project, message, CommitMessageBundle.message("dialog.refinement.title"))
    }

    private companion object {
        const val MAX_PROMPT_LENGTH: Int = 4_000
    }
}
