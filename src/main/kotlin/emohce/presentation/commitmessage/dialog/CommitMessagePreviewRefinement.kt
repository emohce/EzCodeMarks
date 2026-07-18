package emohce.presentation.commitmessage.dialog

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import emohce.data.commitmessage.CommitMessageAiService
import emohce.data.commitmessage.CommitMessageCoordinatorService
import emohce.data.commitmessage.CommitOperationHandle
import emohce.domain.commitmessage.CommitPromptOptimizationProposal
import emohce.domain.commitmessage.CommitTemplateSnapshot
import emohce.domain.commitmessage.LlmProfile
import emohce.domain.commitmessage.PreparedCommitRefinement
import emohce.domain.commitmessage.PreparedPromptOptimization
import emohce.domain.commitmessage.ProviderRequestBudget

internal interface CommitMessagePreviewRefiner {
    fun newAttempt(): CommitMessageRefinementAttempt
}

internal interface CommitMessageRefinementAttempt {
    fun preparePromptOptimization(currentCommit: String, rawPrompt: String): PreparedPromptOptimization
    fun optimizePrompt(
        prepared: PreparedPromptOptimization,
        indicator: ProgressIndicator,
    ): CommitPromptOptimizationProposal

    fun prepareCommitRefinement(currentCommit: String, confirmedPrompt: String): PreparedCommitRefinement
    fun refineCommit(prepared: PreparedCommitRefinement, indicator: ProgressIndicator): String
    fun usedRequests(): Int
}

internal class DefaultCommitMessagePreviewRefiner(
    private val project: Project,
    profileSnapshot: LlmProfile,
    templateSnapshot: CommitTemplateSnapshot,
    private val operationHandle: CommitOperationHandle,
) : CommitMessagePreviewRefiner {
    private val profile = profileSnapshot.copy()
    private val template = templateSnapshot.copy(
        candidates = templateSnapshot.candidates.map { it.copy() },
        allowedTypes = templateSnapshot.allowedTypes.toList(),
        style = templateSnapshot.style.copy(),
    )

    override fun newAttempt(): CommitMessageRefinementAttempt = Attempt(ProviderRequestBudget(3))

    private inner class Attempt(
        private val budget: ProviderRequestBudget,
    ) : CommitMessageRefinementAttempt {
        private val service: CommitMessageAiService
            get() = CommitMessageAiService.getInstance(project)

        override fun preparePromptOptimization(
            currentCommit: String,
            rawPrompt: String,
        ): PreparedPromptOptimization {
            checkActive()
            return service.preparePromptOptimization(currentCommit, rawPrompt, profile)
        }

        override fun optimizePrompt(
            prepared: PreparedPromptOptimization,
            indicator: ProgressIndicator,
        ): CommitPromptOptimizationProposal {
            attachAndCheck(indicator)
            return service.optimizePreparedPrompt(prepared, profile, budget, indicator).also {
                checkActive(indicator)
            }
        }

        override fun prepareCommitRefinement(
            currentCommit: String,
            confirmedPrompt: String,
        ): PreparedCommitRefinement {
            checkActive()
            return service.prepareCommitRefinement(currentCommit, confirmedPrompt, profile, template)
        }

        override fun refineCommit(
            prepared: PreparedCommitRefinement,
            indicator: ProgressIndicator,
        ): String {
            attachAndCheck(indicator)
            return service.refinePreparedCommit(prepared, profile, budget, indicator).also {
                checkActive(indicator)
            }
        }

        override fun usedRequests(): Int = budget.usedRequests()

        private fun attachAndCheck(indicator: ProgressIndicator) {
            CommitMessageCoordinatorService.getInstance(project).attachIndicator(operationHandle, indicator)
            checkActive(indicator)
        }

        private fun checkActive(indicator: ProgressIndicator? = null) {
            if (!CommitMessageCoordinatorService.getInstance(project).isCurrent(operationHandle)) {
                indicator?.cancel()
                throw ProcessCanceledException()
            }
            indicator?.checkCanceled()
        }
    }
}
