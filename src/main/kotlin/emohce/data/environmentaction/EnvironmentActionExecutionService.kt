package emohce.data.environmentaction

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.OSProcessUtil
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.util.execution.ParametersListUtil
import emohce.environmentaction.EnvironmentActionsBundle
import emohce.data.commitmessage.codexPlatformCommand
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class EnvironmentActionExecutionResult(
    val actionId: String,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val cancelled: Boolean,
    val failureMessage: String? = null,
)

data class EnvironmentActionProcessSpec(
    val commandLine: GeneralCommandLine,
    val stdin: String? = null,
)

internal object EnvironmentActionCommandResolver {
    fun resolve(environment: EnvironmentDefinition, action: EnvironmentActionDefinition): GeneralCommandLine =
        resolveSpec(environment, action).commandLine

    fun resolveSpec(
        environment: EnvironmentDefinition,
        action: EnvironmentActionDefinition,
        codexExecutable: String = "codex",
    ): EnvironmentActionProcessSpec {
        val workingDirectory = environment.workingDirectory.takeIf(String::isNotBlank)?.let(Path::of)
        if (workingDirectory != null) {
            require(Files.isDirectory(workingDirectory)) { EnvironmentActionsBundle.message("action.error.workingDirectory") }
        }
        val spec = when (action.type) {
            EnvironmentActionType.SHELL -> EnvironmentActionProcessSpec(shellCommand(action.command))
            EnvironmentActionType.SCRIPT -> EnvironmentActionProcessSpec(
                scriptCommand(action.scriptPath, action.arguments, workingDirectory),
            )
            EnvironmentActionType.CODEX -> codexCommand(
                action.command,
                action.allowNonGitDirectory,
                workingDirectory,
                codexExecutable,
            )
            EnvironmentActionType.PREPARE_COMMIT -> throw IllegalArgumentException(
                EnvironmentActionsBundle.message("action.error.prepareCommitProcess"),
            )
            @Suppress("DEPRECATION")
            EnvironmentActionType.GIT_COMMIT -> throw IllegalArgumentException(
                EnvironmentActionsBundle.message("action.legacyCommit"),
            )
        }
        if (workingDirectory != null) spec.commandLine.withWorkDirectory(workingDirectory.toString())
        spec.commandLine.withEnvironment(environment.variables)
        return spec
    }

    private fun shellCommand(script: String): GeneralCommandLine {
        require(script.isNotBlank()) { EnvironmentActionsBundle.message("action.error.shellRequired") }
        return if (SystemInfoRt.isWindows) {
            GeneralCommandLine("cmd.exe", "/c", script)
        } else {
            GeneralCommandLine("/bin/sh", "-lc", script)
        }
    }

    private fun scriptCommand(scriptPath: String, arguments: String, workingDirectory: Path?): GeneralCommandLine {
        require(scriptPath.isNotBlank()) { EnvironmentActionsBundle.message("action.error.scriptRequired") }
        val configured = Path.of(scriptPath)
        val path = if (!configured.isAbsolute && workingDirectory != null) workingDirectory.resolve(configured).normalize() else configured
        require(Files.isRegularFile(path)) { EnvironmentActionsBundle.message("action.error.scriptMissing") }
        val interpreter = when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
            "py" -> listOf("python3")
            "js", "mjs", "cjs" -> listOf("node")
            "sh", "bash" -> listOf("/bin/sh")
            "ps1" -> listOf(
                if (SystemInfoRt.isWindows) "powershell.exe" else "pwsh",
                "-File",
            )
            "rb" -> listOf("ruby")
            "php" -> listOf("php")
            else -> emptyList()
        }
        val command = if (interpreter.isEmpty()) listOf(path.toString()) else interpreter + path.toString()
        return GeneralCommandLine(command + ParametersListUtil.parse(arguments))
    }

    private fun codexCommand(
        prompt: String,
        allowNonGitDirectory: Boolean,
        workingDirectory: Path?,
        executable: String,
    ): EnvironmentActionProcessSpec {
        require(prompt.isNotBlank()) { EnvironmentActionsBundle.message("action.error.codexPromptRequired") }
        val arguments = mutableListOf("exec", "--json", "--ephemeral")
        workingDirectory?.let { arguments += listOf("--cd", it.toString()) }
        if (allowNonGitDirectory) arguments += "--skip-git-repo-check"
        return EnvironmentActionProcessSpec(
            commandLine = GeneralCommandLine(codexPlatformCommand(executable, arguments)),
            stdin = "$prompt\n",
        )
    }
}

interface EnvironmentActionExecutionHandle {
    val isDone: Boolean
    fun cancel()
}

@Service(Service.Level.PROJECT)
class EnvironmentActionExecutionService : Disposable {
    private val active = ConcurrentHashMap<Int, ExecutionHandle>()

    fun execute(
        project: Project,
        environment: EnvironmentDefinition,
        action: EnvironmentActionDefinition,
        codexExecutable: String = "codex",
        onOutput: (text: String, stderr: Boolean) -> Unit = { _, _ -> },
        onCompleted: (EnvironmentActionExecutionResult) -> Unit = {},
    ): EnvironmentActionExecutionHandle {
        val handle = ExecutionHandle()
        handle.addCompletionListener(onCompleted)
        val existing = active.putIfAbsent(action.slot, handle)
        if (existing != null && !existing.isDone) {
            existing.addCompletionListener(onCompleted)
            return existing
        }
        if (existing != null) active[action.slot] = handle

        object : Task.Backgroundable(project, EnvironmentActionsBundle.message("action.task", action.name), true) {
            private var result: EnvironmentActionExecutionResult? = null

            override fun run(indicator: ProgressIndicator) {
                handle.indicator.set(indicator)
                result = runAction(environment, action, indicator, handle, codexExecutable, onOutput)
            }

            override fun onFinished() {
                val completed = result ?: EnvironmentActionExecutionResult(
                    action.id,
                    null,
                    "",
                    "",
                    timedOut = false,
                    cancelled = true,
                )
                active.remove(action.slot, handle)
                val type = if (completed.exitCode == 0 && !completed.timedOut && !completed.cancelled) {
                    NotificationType.INFORMATION
                } else {
                    NotificationType.ERROR
                }
                val message = when {
                    completed.cancelled -> EnvironmentActionsBundle.message("action.cancelled", action.name)
                    completed.timedOut -> EnvironmentActionsBundle.message("action.timedOut", action.name)
                    completed.failureMessage != null -> EnvironmentActionsBundle.message(
                        "action.failed",
                        action.name,
                        completed.failureMessage,
                    )
                    completed.exitCode == 0 -> EnvironmentActionsBundle.message("action.completed", action.name)
                    else -> EnvironmentActionsBundle.message("action.exited", action.name, completed.exitCode ?: -1)
                }
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("EzCodeMarks")
                    .createNotification(message, type)
                    .notify(project)
                handle.finish(completed)
            }
        }.queue()
        return handle
    }

    internal fun runAction(
        environment: EnvironmentDefinition,
        action: EnvironmentActionDefinition,
        indicator: ProgressIndicator? = null,
        externalHandle: ExecutionHandle? = null,
        codexExecutable: String = "codex",
        onOutput: (text: String, stderr: Boolean) -> Unit = { _, _ -> },
    ): EnvironmentActionExecutionResult {
        val handle = externalHandle ?: ExecutionHandle()
        return try {
            indicator?.checkCanceled()
            val spec = EnvironmentActionCommandResolver.resolveSpec(environment, action, codexExecutable)
            val stdout = TailBuffer(MAX_CAPTURED_OUTPUT_CHARS)
            val stderr = TailBuffer(MAX_CAPTURED_OUTPUT_CHARS)
            val processHandler = OSProcessHandler(spec.commandLine)
            handle.process.set(processHandler)
            processHandler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val isStderr = when (outputType) {
                        ProcessOutputTypes.STDERR -> true
                        ProcessOutputTypes.STDOUT -> false
                        else -> return
                    }
                    val safeText = EnvironmentActionSecurity.redact(event.text)
                    if (isStderr) stderr.append(safeText) else stdout.append(safeText)
                    onOutput(safeText, isStderr)
                }
            })
            processHandler.startNotify()
            spec.stdin?.let { input ->
                processHandler.processInput.use { stream ->
                    stream.write(input.toByteArray(StandardCharsets.UTF_8))
                    stream.flush()
                }
            }

            val deadline = System.nanoTime() + MAX_EXECUTION_MILLIS * 1_000_000L
            var timedOut = false
            var cancelled = false
            while (!processHandler.isProcessTerminated) {
                if (handle.cancelled.get() || indicator?.isCanceled == true) {
                    cancelled = true
                    terminate(processHandler)
                    break
                }
                if (System.nanoTime() >= deadline) {
                    timedOut = true
                    terminate(processHandler)
                    break
                }
                processHandler.waitFor(POLL_MILLIS)
            }
            if (handle.cancelled.get() || indicator?.isCanceled == true) cancelled = true
            if (!processHandler.isProcessTerminated) processHandler.waitFor(TERMINATION_GRACE_MILLIS)
            if (!processHandler.isProcessTerminated) terminate(processHandler)
            val capturedStdout = stdout.value()
            val capturedStderr = stderr.value()
            val approvalFailure = if (
                action.type == EnvironmentActionType.CODEX &&
                processHandler.exitCode != 0 &&
                (capturedStdout + capturedStderr).containsApprovalRequirement()
            ) {
                EnvironmentActionsBundle.message("action.error.codexApprovalRequired")
            } else {
                null
            }
            EnvironmentActionExecutionResult(
                actionId = action.id,
                exitCode = processHandler.exitCode,
                stdout = capturedStdout,
                stderr = capturedStderr,
                timedOut = timedOut,
                cancelled = cancelled,
                failureMessage = approvalFailure,
            )
        } catch (_: ProcessCanceledException) {
            handle.process.get()?.let(::terminate)
            EnvironmentActionExecutionResult(action.id, null, "", "", false, true)
        } catch (error: IllegalArgumentException) {
            EnvironmentActionExecutionResult(action.id, null, "", "", false, false, error.message)
        } catch (_: Exception) {
            EnvironmentActionExecutionResult(
                action.id,
                null,
                "",
                "",
                false,
                handle.cancelled.get(),
                EnvironmentActionsBundle.message("action.error.processStart"),
            )
        } finally {
            handle.process.set(null)
        }
    }

    override fun dispose() {
        active.values.forEach(EnvironmentActionExecutionHandle::cancel)
        active.clear()
    }

    private fun terminate(handler: OSProcessHandler) {
        runCatching {
            OSProcessUtil.killProcessTree(handler.process)
        }
    }

    internal class ExecutionHandle : EnvironmentActionExecutionHandle {
        val indicator = AtomicReference<ProgressIndicator?>()
        val process = AtomicReference<OSProcessHandler?>()
        val cancelled = AtomicBoolean(false)
        private val done = AtomicBoolean(false)
        private val completedResult = AtomicReference<EnvironmentActionExecutionResult?>()
        private val completionListeners = CopyOnWriteArrayList<(EnvironmentActionExecutionResult) -> Unit>()

        override val isDone: Boolean get() = done.get()

        override fun cancel() {
            cancelled.set(true)
            indicator.get()?.cancel()
            process.get()?.let { handler -> runCatching { OSProcessUtil.killProcessTree(handler.process) } }
        }

        fun addCompletionListener(listener: (EnvironmentActionExecutionResult) -> Unit) {
            completedResult.get()?.let {
                listener(it)
                return
            }
            completionListeners += listener
            completedResult.get()?.let { result ->
                if (completionListeners.remove(listener)) listener(result)
            }
        }

        fun finish(result: EnvironmentActionExecutionResult) {
            if (!completedResult.compareAndSet(null, result)) return
            done.set(true)
            indicator.set(null)
            process.set(null)
            completionListeners.forEach { listener -> runCatching { listener(result) } }
            completionListeners.clear()
        }
    }

    private class TailBuffer(private val limit: Int) {
        private val content = StringBuilder()

        @Synchronized
        fun append(value: String) {
            content.append(value)
            if (content.length > limit) content.delete(0, content.length - limit)
        }

        @Synchronized
        fun value(): String = content.toString()
    }

    companion object {
        private const val MAX_EXECUTION_MILLIS = 10 * 60 * 1_000L
        private const val MAX_CAPTURED_OUTPUT_CHARS = 64_000
        private const val POLL_MILLIS = 100L
        private const val TERMINATION_GRACE_MILLIS = 2_000L

        fun getInstance(project: Project): EnvironmentActionExecutionService =
            project.getService(EnvironmentActionExecutionService::class.java)
    }

    private fun String.containsApprovalRequirement(): Boolean {
        val normalized = lowercase()
        return "approval" in normalized || "permission" in normalized || "not a terminal" in normalized
    }
}
