package emohce.presentation.environmentaction

import com.intellij.openapi.project.Project
import emohce.data.environmentaction.EnvironmentActionDefinition
import emohce.data.environmentaction.EnvironmentActionExecutionHandle
import emohce.data.environmentaction.EnvironmentActionExecutionResult
import emohce.data.environmentaction.EnvironmentActionExecutionService
import emohce.data.environmentaction.EnvironmentActionType
import emohce.data.environmentaction.EnvironmentDefinition
import emohce.environmentaction.EnvironmentActionsBundle

internal object EnvironmentActionInvoker {
    fun invoke(
        project: Project,
        environment: EnvironmentDefinition,
        action: EnvironmentActionDefinition,
        codexExecutable: String = "codex",
        onOutput: (text: String, stderr: Boolean) -> Unit = { _, _ -> },
        onCompleted: (EnvironmentActionExecutionResult) -> Unit = {},
    ): EnvironmentActionExecutionHandle? {
        return when (action.type) {
            EnvironmentActionType.PREPARE_COMMIT -> {
                NativeCommitWorkflowLauncher.getInstance(project).prepare(action.command)
                onCompleted(
                    EnvironmentActionExecutionResult(
                        actionId = action.id,
                        exitCode = null,
                        stdout = "",
                        stderr = "",
                        timedOut = false,
                        cancelled = false,
                        failureMessage = null,
                    ),
                )
                null
            }
            @Suppress("DEPRECATION")
            EnvironmentActionType.GIT_COMMIT -> {
                onCompleted(
                    EnvironmentActionExecutionResult(
                        action.id,
                        null,
                        "",
                        "",
                        false,
                        false,
                        EnvironmentActionsBundle.message("action.legacyCommit"),
                    ),
                )
                null
            }
            else -> EnvironmentActionExecutionService.getInstance(project).execute(
                project,
                environment,
                action,
                codexExecutable = codexExecutable,
                onOutput = onOutput,
                onCompleted = onCompleted,
            )
        }
    }
}
