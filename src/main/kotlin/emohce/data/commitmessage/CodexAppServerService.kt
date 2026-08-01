package emohce.data.commitmessage

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.SystemInfoRt
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
internal data class CodexMachineSettings(
    val executablePath: String = "",
    val authGeneration: String = "",
)

internal data class CodexInstallationStatus(
    val available: Boolean,
    val executable: String,
    val version: String? = null,
    val message: String = "",
    val problem: CodexInstallationProblem? = null,
)

internal enum class CodexInstallationProblem {
    NOT_FOUND,
    VERSION_TIMEOUT,
    VERSION_UNKNOWN,
    VERSION_TOO_OLD,
    VERSION_CHECK_FAILED,
}

internal interface CodexProviderGateway {
    fun account(indicator: ProgressIndicator?): CodexAppServerAccount
    fun models(indicator: ProgressIndicator? = null): List<CodexAppServerModel>
    fun complete(request: CodexAppServerCompletionRequest, indicator: ProgressIndicator): String
    fun authGeneration(): String
}

internal interface CodexAccountSettingsGateway {
    fun executablePath(): String
    fun resolvedExecutablePath(): String
    fun setExecutablePath(value: String, expectedValue: String)
    fun installationStatus(): CodexInstallationStatus
    fun account(indicator: ProgressIndicator? = null): CodexAppServerAccount
    fun browserLogin(
        indicator: ProgressIndicator,
        started: (CodexAppServerBrowserLogin) -> Unit,
    ): CodexAppServerLoginCompleted
    fun deviceLogin(
        indicator: ProgressIndicator,
        started: (CodexAppServerDeviceLogin) -> Unit,
    ): CodexAppServerLoginCompleted
    fun logout()
}

@Service(Service.Level.APP)
internal class CodexAppServerService(
    private val commonDataRoot: Path = PathManager.getCommonDataPath(),
    private val machineStore: CommitMessagePortableStore? = defaultCodexStore(commonDataRoot, "machine-settings.json"),
    private val accountOperationStore: CommitMessagePortableStore? = defaultCodexStore(
        commonDataRoot,
        "account-operation.guard",
    ),
    private val executableCandidates: () -> List<String> = ::defaultCodexExecutableCandidates,
) : CodexProviderGateway, CodexAccountSettingsGateway, Disposable {
    private val lock = Any()
    private val root = codexProviderRoot(commonDataRoot)
    private var machineEnvelope: CommitMessagePortableEnvelope? = null
    private var machineSettings = CodexMachineSettings(authGeneration = UUID.randomUUID().toString())
    private var client: CodexAppServerClient? = null
    private var validatedExecutable: String? = null
    private var installationStatus: CodexInstallationStatus? = null

    init {
        loadMachineSettings()
    }

    override fun executablePath(): String = synchronized(lock) {
        refreshMachineSettingsLocked()
        machineSettings.executablePath
    }

    override fun resolvedExecutablePath(): String = synchronized(lock) {
        refreshMachineSettingsLocked()
        resolvedExecutableLocked()
    }

    override fun setExecutablePath(value: String, expectedValue: String) {
        synchronized(lock) {
            val normalized = value.trim()
            updateMachineSettingsLocked { current ->
                if (current.executablePath != expectedValue.trim()) {
                    throw CodexAppServerException(
                        CodexAppServerErrorKind.ISOLATION,
                        "Codex machine settings changed in another IDE",
                    )
                }
                current.copy(executablePath = normalized)
            }
        }
    }

    override fun installationStatus(): CodexInstallationStatus = synchronized(lock) {
        refreshMachineSettingsLocked()
        val executable = resolvedExecutableLocked()
        if (validatedExecutable == executable) return@synchronized checkNotNull(installationStatus)
        validateExecutable(executable).also {
            validatedExecutable = executable
            installationStatus = it
        }
    }

    override fun account(indicator: ProgressIndicator?): CodexAppServerAccount {
        val activeClient = client()
        return try {
            activeClient.accountRead(indicator)
        } catch (error: CodexAppServerException) {
            if (error.kind == CodexAppServerErrorKind.PROTOCOL || error.kind == CodexAppServerErrorKind.REQUEST) {
                discardClient(activeClient)
            }
            throw error
        }
    }

    override fun browserLogin(
        indicator: ProgressIndicator,
        started: (CodexAppServerBrowserLogin) -> Unit,
    ): CodexAppServerLoginCompleted = login(indicator, CodexAppServerClient::browserLoginStart, started)

    override fun deviceLogin(
        indicator: ProgressIndicator,
        started: (CodexAppServerDeviceLogin) -> Unit,
    ): CodexAppServerLoginCompleted = login(indicator, CodexAppServerClient::deviceLoginStart, started)

    override fun logout() {
        withAccountOperationLock(indicator = null) {
            var activeClient: CodexAppServerClient? = null
            runCodexAccountMutation(
                invalidateGeneration = ::rotateAuthGeneration,
                abort = { activeClient?.let(::discardClient) },
            ) {
                client().also { activeClient = it }.logout()
            }
        }
    }

    override fun models(indicator: ProgressIndicator?): List<CodexAppServerModel> {
        val activeClient = client()
        return try {
            activeClient.modelList(indicator)
        } catch (error: CodexAppServerException) {
            if (error.kind == CodexAppServerErrorKind.PROTOCOL || error.kind == CodexAppServerErrorKind.REQUEST) {
                discardClient(activeClient)
            }
            throw error
        }
    }

    override fun complete(request: CodexAppServerCompletionRequest, indicator: ProgressIndicator): String =
        withAccountOperationLock(indicator) {
            val expectedGeneration = request.expectedAuthGeneration
            val currentGeneration = authGeneration()
            if (expectedGeneration != null && expectedGeneration != currentGeneration) {
                throw CodexAppServerException(
                    CodexAppServerErrorKind.ISOLATION,
                    "ChatGPT account changed after source-context consent",
                )
            }
            val activeClient = client()
            try {
                activeClient.complete(request, indicator)
            } catch (error: ProcessCanceledException) {
                discardClient(activeClient)
                throw error
            } catch (error: CodexAppServerException) {
                if (error.kind in setOf(
                        CodexAppServerErrorKind.PROTOCOL,
                        CodexAppServerErrorKind.ISOLATION,
                        CodexAppServerErrorKind.TOOL_USE,
                        CodexAppServerErrorKind.REQUEST,
                    )
                ) {
                    discardClient(activeClient)
                }
                throw error
            }
        }

    override fun authGeneration(): String = synchronized(lock) {
        refreshMachineSettingsLocked()
        machineSettings.authGeneration
    }

    override fun dispose() {
        synchronized(lock) {
            client?.close()
            client = null
        }
    }

    private fun client(): CodexAppServerClient = synchronized(lock) {
        refreshMachineSettingsLocked()
        client?.let { return@synchronized it }
        val status = installationStatus()
        if (!status.available) {
            throw CodexAppServerException(
                CodexAppServerErrorKind.PROCESS,
                status.message.ifBlank { "A supported Codex CLI installation is required" },
            )
        }
        try {
            prepareCodexIsolationRoot(commonDataRoot, root)
        } catch (_: Exception) {
            throw CodexAppServerException(
                CodexAppServerErrorKind.ISOLATION,
                "Codex provider isolation directories are unsafe",
            )
        }
        CodexAppServerClient(
            executable = status.executable,
            home = root.resolve("home").toAbsolutePath().normalize(),
            cwd = root.resolve("workspace").toAbsolutePath().normalize(),
            version = pluginVersion(),
        ).also { client = it }
    }

    private fun rotateAuthGeneration() {
        synchronized(lock) {
            val generation = UUID.randomUUID().toString()
            updateMachineSettingsLocked { current -> current.copy(authGeneration = generation) }
        }
    }

    private fun <L> login(
        indicator: ProgressIndicator,
        start: (CodexAppServerClient) -> L,
        started: (L) -> Unit,
    ): CodexAppServerLoginCompleted = withAccountOperationLock(indicator) {
        var activeClient: CodexAppServerClient? = null
        runCodexAccountMutation(
            invalidateGeneration = ::rotateAuthGeneration,
            abort = { activeClient?.let(::discardClient) },
        ) {
            val client = client().also { activeClient = it }
            val login = start(client)
            val loginId = when (login) {
                is CodexAppServerBrowserLogin -> login.loginId
                is CodexAppServerDeviceLogin -> login.loginId
                else -> error("Unsupported Codex login response")
            }
            started(login)
            client.awaitLogin(loginId, indicator).also { completed ->
                if (!completed.success) {
                    throw CodexAppServerException(
                        CodexAppServerErrorKind.REQUEST,
                        completed.error.orEmpty().ifBlank { "Codex login failed" },
                    )
                }
            }
        }
    }

    private fun discardClient(activeClient: CodexAppServerClient) {
        synchronized(lock) {
            if (client === activeClient) client = null
            activeClient.close()
        }
    }

    private fun <T> withAccountOperationLock(indicator: ProgressIndicator?, action: () -> T): T = try {
        accountOperationStore?.withExclusiveLock(
            timeoutMillis = ACCOUNT_OPERATION_LOCK_TIMEOUT_MS,
            checkCancelled = { indicator?.checkCanceled() },
            action = action,
        ) ?: action()
    } catch (error: CommitMessagePortableStoreException) {
        throw CodexAppServerException(
            CodexAppServerErrorKind.ISOLATION,
            "Codex account operation lock is unavailable",
        )
    }

    private fun loadMachineSettings() {
        synchronized(lock) {
            refreshMachineSettingsLocked()
            if (machineEnvelope == null) {
                ensureMachineSettingsLocked()
            }
        }
    }

    private fun refreshMachineSettingsLocked() {
        val store = machineStore ?: return
        val found = try {
            store.read()
        } catch (_: CommitMessagePortableStoreException) {
            throw CodexAppServerException(
                CodexAppServerErrorKind.ISOLATION,
                "Codex machine settings are unavailable",
            )
        } as? CommitMessagePortableReadResult.Found ?: return
        if (found.envelope.revision == machineEnvelope?.revision) return
        acceptMachineEnvelopeLocked(found.envelope)
    }

    private fun ensureMachineSettingsLocked() {
        val store = machineStore ?: return
        repeat(MAX_CONDITIONAL_WRITE_ATTEMPTS) {
            val candidate = CommitMessagePortableEnvelope.create(JSON.encodeToString(machineSettings))
            when (val result = store.compareAndWrite(expectedRevision = null, candidate)) {
                is CommitMessagePortableConditionalWriteResult.Written -> {
                    machineEnvelope = candidate
                    return
                }
                is CommitMessagePortableConditionalWriteResult.RevisionMismatch -> {
                    val current = result.current ?: return@repeat
                    acceptMachineEnvelopeLocked(current)
                    return
                }
            }
        }
        throw CodexAppServerException(CodexAppServerErrorKind.PROCESS, "Codex machine settings changed repeatedly")
    }

    private fun updateMachineSettingsLocked(transform: (CodexMachineSettings) -> CodexMachineSettings) {
        val store = machineStore
        if (store == null) {
            acceptMachineSettingsLocked(transform(machineSettings))
            return
        }
        repeat(MAX_CONDITIONAL_WRITE_ATTEMPTS) {
            refreshMachineSettingsLocked()
            val updated = transform(machineSettings)
            if (updated == machineSettings) return
            val expectedRevision = machineEnvelope?.revision
            val candidate = machineEnvelope?.let {
                CommitMessagePortableEnvelope.successor(JSON.encodeToString(updated), it)
            } ?: CommitMessagePortableEnvelope.create(JSON.encodeToString(updated))
            when (val result = store.compareAndWrite(expectedRevision, candidate)) {
                is CommitMessagePortableConditionalWriteResult.Written -> {
                    machineEnvelope = candidate
                    acceptMachineSettingsLocked(updated)
                    return
                }
                is CommitMessagePortableConditionalWriteResult.RevisionMismatch -> {
                    result.current?.let(::acceptMachineEnvelopeLocked)
                }
            }
        }
        throw CodexAppServerException(CodexAppServerErrorKind.PROCESS, "Codex machine settings changed repeatedly")
    }

    private fun acceptMachineEnvelopeLocked(initialEnvelope: CommitMessagePortableEnvelope) {
        var envelope = initialEnvelope
        repeat(MAX_CONDITIONAL_WRITE_ATTEMPTS) {
            if (envelope.schemaVersion != CommitMessagePortableEnvelope.DEFAULT_SCHEMA_VERSION) {
                throw CodexAppServerException(CodexAppServerErrorKind.PROCESS, "Codex machine settings are invalid")
            }
            val rawPayload = runCatching { JSON.parseToJsonElement(envelope.payload).jsonObject }
                .getOrElse {
                    throw CodexAppServerException(CodexAppServerErrorKind.PROCESS, "Codex machine settings are invalid")
                }
            val restored = runCatching { JSON.decodeFromJsonElement<CodexMachineSettings>(rawPayload) }
                .getOrElse {
                    throw CodexAppServerException(CodexAppServerErrorKind.PROCESS, "Codex machine settings are invalid")
                }
            if (isUuid(restored.authGeneration) || machineStore == null) {
                machineEnvelope = envelope
                acceptMachineSettingsLocked(
                    restored.copy(
                        executablePath = restored.executablePath.trim(),
                        authGeneration = restored.authGeneration.takeIf(::isUuid) ?: UUID.randomUUID().toString(),
                    ),
                )
                return
            }

            val repaired = restored.copy(authGeneration = UUID.randomUUID().toString())
            val repairedPayload = JsonObject(rawPayload + ("authGeneration" to JsonPrimitive(repaired.authGeneration)))
            val candidate = CommitMessagePortableEnvelope.successor(repairedPayload.toString(), envelope)
            val result = try {
                machineStore.compareAndWrite(envelope.revision, candidate)
            } catch (_: CommitMessagePortableStoreException) {
                throw CodexAppServerException(
                    CodexAppServerErrorKind.ISOLATION,
                    "Codex machine settings are unavailable",
                )
            }
            when (result) {
                is CommitMessagePortableConditionalWriteResult.Written -> {
                    machineEnvelope = candidate
                    acceptMachineSettingsLocked(repaired.copy(executablePath = repaired.executablePath.trim()))
                    return
                }
                is CommitMessagePortableConditionalWriteResult.RevisionMismatch -> {
                    envelope = result.current ?: throw CodexAppServerException(
                        CodexAppServerErrorKind.PROCESS,
                        "Codex machine settings changed repeatedly",
                    )
                }
            }
        }
        throw CodexAppServerException(CodexAppServerErrorKind.PROCESS, "Codex machine settings changed repeatedly")
    }

    private fun acceptMachineSettingsLocked(settings: CodexMachineSettings) {
        val executableChanged = settings.executablePath != machineSettings.executablePath
        val accountChanged = settings.authGeneration != machineSettings.authGeneration
        machineSettings = settings
        if (executableChanged || accountChanged) {
            client?.close()
            client = null
        }
        if (executableChanged) {
            validatedExecutable = null
            installationStatus = null
        }
    }

    private fun resolvedExecutableLocked(): String {
        val configured = machineSettings.executablePath.trim()
        if (configured.isNotBlank()) return configured
        return executableCandidates().firstOrNull { it.isExistingExecutable() } ?: "codex"
    }

    private fun String.isExistingExecutable(): Boolean = runCatching {
        val path = Path.of(this)
        Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(path)
    }.getOrDefault(false)

    private fun validateExecutable(executable: String): CodexInstallationStatus {
        val processBuilder = try {
            prepareCodexIsolationRoot(commonDataRoot, root)
            codexVersionProcessBuilder(
                ProcessBuilder(codexPlatformCommand(executable, listOf("--version"))),
                root.resolve("home").toAbsolutePath().normalize(),
                root.resolve("workspace").toAbsolutePath().normalize(),
            )
        } catch (_: Exception) {
            return CodexInstallationStatus(
                false,
                executable,
                message = "Codex CLI version check failed",
                problem = CodexInstallationProblem.VERSION_CHECK_FAILED,
            )
        }
        val process = try {
            processBuilder.start()
        } catch (_: Exception) {
            return CodexInstallationStatus(
                false,
                executable,
                message = "Codex CLI was not found",
                problem = CodexInstallationProblem.NOT_FOUND,
            )
        }
        return try {
            val exited = process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                return CodexInstallationStatus(
                    false,
                    executable,
                    message = "Codex CLI version check timed out",
                    problem = CodexInstallationProblem.VERSION_TIMEOUT,
                )
            }
            val output = InputStreamReader(process.inputStream, StandardCharsets.UTF_8).use { reader ->
                val buffer = CharArray(MAX_VERSION_OUTPUT_CHARS)
                val count = reader.read(buffer)
                if (count <= 0) "" else String(buffer, 0, count)
            }
            val version = VERSION_PATTERN.find(output)?.groupValues?.getOrNull(1)
            if (process.exitValue() != 0 || version == null) {
                CodexInstallationStatus(
                    false,
                    executable,
                    message = "Codex CLI version could not be determined",
                    problem = CodexInstallationProblem.VERSION_UNKNOWN,
                )
            } else if (compareVersions(version, MINIMUM_CODEX_VERSION) < 0) {
                CodexInstallationStatus(
                    false,
                    executable,
                    version,
                    "Codex CLI $MINIMUM_CODEX_VERSION or newer is required",
                    CodexInstallationProblem.VERSION_TOO_OLD,
                )
            } else {
                CodexInstallationStatus(true, executable, version)
            }
        } catch (_: Exception) {
            CodexInstallationStatus(
                false,
                executable,
                message = "Codex CLI version check failed",
                problem = CodexInstallationProblem.VERSION_CHECK_FAILED,
            )
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun pluginVersion(): String = CodexAppServerService::class.java.`package`.implementationVersion
        ?.takeIf(String::isNotBlank)
        ?: "development"

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = left.split('.').map { it.toIntOrNull() ?: 0 }
        val rightParts = right.split('.').map { it.toIntOrNull() ?: 0 }
        return (0 until maxOf(leftParts.size, rightParts.size))
            .map { (leftParts.getOrElse(it) { 0 }).compareTo(rightParts.getOrElse(it) { 0 }) }
            .firstOrNull { it != 0 }
            ?: 0
    }

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

    companion object {
        const val MINIMUM_CODEX_VERSION: String = "0.144.5"
        private const val VERSION_TIMEOUT_SECONDS: Long = 5
        private const val MAX_VERSION_OUTPUT_CHARS: Int = 4_096
        private const val MAX_CONDITIONAL_WRITE_ATTEMPTS: Int = 4
        private const val ACCOUNT_OPERATION_LOCK_TIMEOUT_MS: Long = 30_000
        private val VERSION_PATTERN = Regex("(?:codex-cli\\s+)?(\\d+\\.\\d+\\.\\d+)")
        private val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        fun getInstance(): CodexAppServerService =
            ApplicationManager.getApplication().getService(CodexAppServerService::class.java)
    }
}

private fun codexProviderRoot(commonDataRoot: Path): Path =
    commonDataRoot.resolve("EzCodeMarks").resolve("codex-provider")

private fun defaultCodexStore(commonDataRoot: Path, fileName: String): CommitMessagePortableStore? =
    if (ApplicationManager.getApplication().isUnitTestMode) {
        null
    } else {
        CommitMessagePortableStore(
            codexProviderRoot(commonDataRoot).resolve(fileName),
            trustedRoot = commonDataRoot,
        )
    }

private fun defaultCodexExecutableCandidates(): List<String> {
    val userHome = runCatching { System.getProperty("user.home") }.getOrNull().orEmpty()
    val isWindows = SystemInfoRt.isWindows
    return buildList {
        PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("codex")
            ?.absolutePath
            ?.let(::add)
        if (isWindows) {
            add("$userHome\\.codex\\bin\\codex.exe")
            add("$userHome\\.codex\\bin\\codex.cmd")
            add("$userHome\\.npm-global\\codex.cmd")
            add("$userHome\\AppData\\Roaming\\npm\\codex.cmd")
            add("$userHome\\AppData\\Local\\Programs\\codex\\codex.exe")
            add("C:\\Program Files\\codex\\codex.exe")
            add("C:\\Program Files (x86)\\codex\\codex.exe")
        } else {
            add("$userHome/.codex/bin/codex")
            add("$userHome/.local/bin/codex")
            add("$userHome/.npm-global/bin/codex")
            add("/opt/homebrew/bin/codex")
            add("/usr/local/bin/codex")
            add("/usr/bin/codex")
        }
    }.distinct()
}

internal fun codexVersionProcessBuilder(processBuilder: ProcessBuilder, home: Path, cwd: Path): ProcessBuilder =
    configureIsolatedCodexProcess(processBuilder, home, cwd)
        .redirectErrorStream(true)

internal inline fun <T> runCodexAccountMutation(
    invalidateGeneration: () -> Unit,
    abort: () -> Unit,
    action: () -> T,
): T {
    invalidateGeneration()
    return try {
        action()
    } catch (error: Throwable) {
        runCatching(abort)
        throw error
    }
}
