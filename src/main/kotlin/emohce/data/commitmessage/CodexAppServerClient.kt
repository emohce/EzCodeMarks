package emohce.data.commitmessage

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.SystemInfoRt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class CodexAppServerAccount(
    val type: String?,
    val email: String?,
    val planType: String?,
    val requiresOpenAiAuth: Boolean,
)

internal data class CodexAppServerBrowserLogin(
    val loginId: String,
    val authUrl: String,
)

internal data class CodexAppServerDeviceLogin(
    val loginId: String,
    val verificationUrl: String,
    val userCode: String,
)

internal data class CodexAppServerLoginCompleted(
    val loginId: String?,
    val success: Boolean,
    val error: String?,
)

internal data class CodexAppServerModel(
    val id: String,
    val displayName: String,
    val isDefault: Boolean,
    val defaultReasoningEffort: String?,
    val reasoningEfforts: List<String>,
    val inputModalities: List<String>,
)

internal data class CodexAppServerCompletionRequest(
    val model: String,
    val text: String,
    val developerInstructions: String,
    val outputSchema: JsonElement? = null,
    val reasoningEffort: String? = null,
    val maxOutputTokens: Int? = null,
    val serviceName: String = "ezcodemark_jetbrains",
    val expectedAuthGeneration: String? = null,
)

internal enum class CodexAppServerErrorKind {
    CLOSED,
    PROCESS,
    REQUEST,
    PROTOCOL,
    ISOLATION,
    TOOL_USE,
    TURN_FAILED,
    TURN_INTERRUPTED,
}

internal class CodexAppServerException(
    val kind: CodexAppServerErrorKind,
    message: String,
) : RuntimeException(message)

internal fun interface CodexAppServerProcessFactory {
    fun start(executable: String, home: Path, cwd: Path): Process

    companion object {
        val SYSTEM: CodexAppServerProcessFactory = CodexAppServerProcessFactory { executable, home, cwd ->
            val processBuilder = configureIsolatedCodexProcess(
                ProcessBuilder(codexAppServerCommand(executable)),
                home,
                cwd,
            )
                .redirectError(ProcessBuilder.Redirect.PIPE)
            processBuilder.start()
        }
    }
}

internal fun codexAppServerCommand(executable: String): List<String> = codexPlatformCommand(executable, buildList {
    addAll(listOf("app-server", "--stdio", "--strict-config"))
    addAll(listOf("-c", "web_search=\"disabled\""))
    addAll(listOf("-c", "mcp_servers={}"))
    CODEX_DISABLED_TOOL_FEATURES.forEach { feature -> addAll(listOf("--disable", feature)) }
})

internal fun codexPlatformCommand(
    executable: String,
    arguments: List<String>,
    windows: Boolean = SystemInfoRt.isWindows,
): List<String> = if (
    windows && (executable.endsWith(".cmd", ignoreCase = true) || executable.endsWith(".bat", ignoreCase = true))
) {
    listOf("cmd.exe", "/d", "/s", "/c", executable) + arguments
} else {
    listOf(executable) + arguments
}

private fun prepareIsolatedDirectory(path: Path) {
    val normalized = path.toAbsolutePath().normalize()
    val parent = normalized.parent ?: throw IllegalArgumentException("Codex isolation path requires a parent")
    if (Files.isSymbolicLink(parent) || Files.isSymbolicLink(normalized)) {
        throw IllegalArgumentException("Codex isolation path cannot contain a symbolic link")
    }
    Files.createDirectories(normalized)
    if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized)) {
        throw IllegalArgumentException("Codex isolation path is not a safe directory")
    }
}

internal fun configureIsolatedCodexProcess(
    processBuilder: ProcessBuilder,
    home: Path,
    cwd: Path,
): ProcessBuilder {
    prepareIsolatedDirectory(home)
    prepareIsolatedDirectory(cwd)
    listOf("tmp", "config", "data", "cache", "state").forEach { child ->
        prepareIsolatedDirectory(home.resolve(child))
    }
    require(home.parent == cwd.parent) { "Codex home and workspace must share an isolated root" }
    processBuilder.directory(cwd.toFile())
    isolateCodexEnvironment(processBuilder.environment(), home)
    return processBuilder
}

internal fun prepareCodexIsolationRoot(trustedRoot: Path, isolationRoot: Path) {
    val trusted = trustedRoot.toAbsolutePath().normalize()
    val target = isolationRoot.toAbsolutePath().normalize()
    require(target.startsWith(trusted)) { "Codex isolation root must stay under common data" }
    fun rejectLinks() {
        require(!Files.isSymbolicLink(trusted)) { "Codex common-data root cannot be a symbolic link" }
        var candidate = trusted
        trusted.relativize(target).forEach { component ->
            candidate = candidate.resolve(component)
            require(!Files.isSymbolicLink(candidate)) { "Codex isolation root cannot contain a symbolic link" }
        }
    }
    rejectLinks()
    Files.createDirectories(target)
    rejectLinks()
    require(Files.isDirectory(target)) { "Codex isolation root must be a directory" }
}

internal fun isolateCodexEnvironment(environment: MutableMap<String, String>, home: Path) {
    val inherited = environment.toMap()
    environment.clear()
    inherited.forEach { (key, value) ->
        if (key.uppercase() in CODEX_ENV_ALLOWLIST) environment[key] = value
    }
    val isolatedHome = home.toAbsolutePath().normalize().toString()
    val isolatedTemp = home.resolve("tmp").toAbsolutePath().normalize().toString()
    environment["CODEX_HOME"] = isolatedHome
    environment["HOME"] = isolatedHome
    environment["USERPROFILE"] = isolatedHome
    environment["XDG_CONFIG_HOME"] = home.resolve("config").toString()
    environment["XDG_DATA_HOME"] = home.resolve("data").toString()
    environment["XDG_CACHE_HOME"] = home.resolve("cache").toString()
    environment["XDG_STATE_HOME"] = home.resolve("state").toString()
    environment["TMPDIR"] = isolatedTemp
    environment["TMP"] = isolatedTemp
    environment["TEMP"] = isolatedTemp
}

private val CODEX_DISABLED_TOOL_FEATURES = listOf(
    "apps",
    "artifact",
    "browser_use",
    "browser_use_external",
    "browser_use_full_cdp_access",
    "code_mode",
    "code_mode_host",
    "code_mode_only",
    "computer_use",
    "deferred_executor",
    "enable_fanout",
    "enable_mcp_apps",
    "hooks",
    "image_generation",
    "in_app_browser",
    "multi_agent",
    "multi_agent_v2",
    "plugins",
    "request_permissions_tool",
    "shell_snapshot",
    "shell_tool",
    "skill_mcp_dependency_install",
    "tool_call_mcp_elicitation",
    "tool_suggest",
    "unified_exec",
    "workspace_dependencies",
)

private val CODEX_ENV_ALLOWLIST = setOf(
    "PATH",
    "PATHEXT",
    "SYSTEMROOT",
    "WINDIR",
    "COMSPEC",
    "LANG",
    "LC_ALL",
    "LC_CTYPE",
)

internal class CodexAppServerClient(
    private val processFactory: CodexAppServerProcessFactory = CodexAppServerProcessFactory.SYSTEM,
    private val executable: String,
    home: Path,
    cwd: Path,
    private val version: String,
) : AutoCloseable {
    private val home = home.normalize()
    private val cwd = cwd.normalize()
    private val permissionProfileId = "$CODEX_PERMISSION_PROFILE_PREFIX-${UUID.randomUUID()}"
    private val closed = AtomicBoolean()
    private val lifecycleLock = Any()

    @Volatile
    private var session: Session? = null
    private var hasStartedProcess = false
    private var restartUsed = false

    init {
        require(executable.isNotBlank()) { "Codex executable is required" }
        require(home.isAbsolute) { "Codex home must be absolute" }
        require(cwd.isAbsolute) { "Codex working directory must be absolute" }
        require(version.isNotBlank()) { "Client version is required" }
    }

    fun accountRead(indicator: ProgressIndicator? = null): CodexAppServerAccount {
        val result = request(
            method = "account/read",
            params = buildJsonObject { put("refreshToken", false) },
            indicator = indicator,
        ).asObject() ?: protocolFailure()
        val account = result["account"].asObject()
        return CodexAppServerAccount(
            type = account.string("type"),
            email = account.string("email"),
            planType = account.string("planType"),
            requiresOpenAiAuth = result.boolean("requiresOpenaiAuth") ?: protocolFailure(),
        )
    }

    fun browserLoginStart(): CodexAppServerBrowserLogin {
        val activeSession = activeSession()
        val result = requestOnSession(
            activeSession,
            method = "account/login/start",
            params = buildJsonObject { put("type", "chatgpt") },
        ).asObject() ?: protocolFailure()
        if (result.string("type") != "chatgpt") protocolFailure()
        val login = CodexAppServerBrowserLogin(
            loginId = result.requiredString("loginId"),
            authUrl = result.requiredString("authUrl"),
        )
        activeSession.pendingLoginIds += login.loginId
        activeSession.loginResults.computeIfAbsent(login.loginId) { CompletableFuture() }
        return login
    }

    fun deviceLoginStart(): CodexAppServerDeviceLogin {
        val activeSession = activeSession()
        val result = requestOnSession(
            activeSession,
            method = "account/login/start",
            params = buildJsonObject { put("type", "chatgptDeviceCode") },
        ).asObject() ?: protocolFailure()
        if (result.string("type") != "chatgptDeviceCode") protocolFailure()
        val login = CodexAppServerDeviceLogin(
            loginId = result.requiredString("loginId"),
            verificationUrl = result.requiredString("verificationUrl"),
            userCode = result.requiredString("userCode"),
        )
        activeSession.pendingLoginIds += login.loginId
        activeSession.loginResults.computeIfAbsent(login.loginId) { CompletableFuture() }
        return login
    }

    fun awaitLogin(loginId: String, indicator: ProgressIndicator): CodexAppServerLoginCompleted {
        require(loginId.isNotBlank()) { "Login ID is required" }
        val activeSession = activeSession()
        val future = activeSession.loginResults.computeIfAbsent(loginId) { CompletableFuture() }
        var completed = false
        return try {
            awaitFuture(activeSession, future, indicator, LOGIN_TIMEOUT_MS).also { completed = true }
        } finally {
            if (!completed) abortLoginOnSession(activeSession, loginId)
            activeSession.loginResults.remove(loginId, future)
        }
    }

    fun loginCancel(loginId: String) {
        require(loginId.isNotBlank()) { "Login ID is required" }
        val activeSession = activeSession()
        requestOnSession(
            activeSession,
            method = "account/login/cancel",
            params = buildJsonObject { put("loginId", loginId) },
        )
    }

    fun logout() {
        val activeSession = activeSession()
        requestOnSession(activeSession, method = "account/logout", params = JsonNull)
        activeSession.pendingLoginIds.clear()
    }

    fun modelList(indicator: ProgressIndicator? = null): List<CodexAppServerModel> {
        val models = LinkedHashMap<String, CodexAppServerModel>()
        val seenCursors = mutableSetOf<String>()
        var parsedModels = 0
        var retainedChars = 0L
        var cursor: String? = null
        var pages = 0
        do {
            if (++pages > MAX_MODEL_PAGES) protocolFailure()
            val result = request(
                method = "model/list",
                params = buildJsonObject {
                    put("includeHidden", false)
                    cursor?.let { put("cursor", it) }
                },
                indicator = indicator,
            ).asObject() ?: protocolFailure()
            val data = result["data"].asArray() ?: protocolFailure()
            data.forEach { element ->
                parseModel(element.asObject())?.let { model ->
                    if (++parsedModels > MAX_MODELS) protocolFailure()
                    if (model.id !in models) {
                        retainedChars += model.retainedChars()
                        if (retainedChars > MAX_MODEL_RETAINED_CHARS) protocolFailure()
                        models[model.id] = model
                    }
                }
            }
            cursor = result.string("nextCursor")
            if (cursor != null) {
                if (cursor.length > MAX_MODEL_CURSOR_CHARS || !seenCursors.add(cursor)) protocolFailure()
            }
        } while (cursor != null)
        return models.values.toList()
    }

    fun complete(request: CodexAppServerCompletionRequest, indicator: ProgressIndicator): String {
        require(request.model.isNotBlank()) { "Codex model is required" }
        require(request.serviceName.isNotBlank()) { "Codex service name is required" }
        indicator.checkCanceled()

        val activeSession = activeSession()
        val threadResult = requestOnSession(
            activeSession,
            method = "thread/start",
            params = buildJsonObject {
                put("model", request.model)
                put("ephemeral", true)
                put("cwd", cwd.toString())
                put("approvalPolicy", "never")
                putJsonObject("config") {
                    put("project_doc_max_bytes", 0)
                    put("web_search", "disabled")
                    put("default_permissions", permissionProfileId)
                    put("include_environment_context", false)
                    request.reasoningEffort?.let { put("model_reasoning_effort", it) }
                    putJsonObject("history") { put("persistence", "none") }
                    putJsonObject("mcp_servers") {}
                    putJsonObject("permissions") {
                        putJsonObject(permissionProfileId) {
                            putJsonObject("filesystem") {
                                put(":root", "deny")
                                put(":minimal", "read")
                                put(cwd.toString(), "read")
                            }
                            putJsonObject("network") { put("enabled", false) }
                        }
                    }
                    putJsonObject("features") {
                        CODEX_DISABLED_TOOL_FEATURES.forEach { put(it, false) }
                    }
                }
                put("serviceName", request.serviceName)
                put("developerInstructions", request.developerInstructions)
            },
            indicator = indicator,
            onCanceled = {
                terminateForSecurity(
                    activeSession,
                    failure(CodexAppServerErrorKind.REQUEST, "Codex thread start was cancelled"),
                )
            },
        ).asObject() ?: protocolFailure()

        try {
            validateThreadIsolation(threadResult)
        } catch (error: CodexAppServerException) {
            terminateForSecurity(activeSession, error)
            throw error
        }
        val threadId = threadResult["thread"].asObject().requiredString("id")
        val turn = TurnState(threadId, outputCharacterLimit(request.maxOutputTokens))
        if (activeSession.turns.putIfAbsent(threadId, turn) != null) protocolFailure()

        try {
            indicator.checkCanceled()
            val turnResult = requestOnSession(
                activeSession,
                method = "turn/start",
                params = buildJsonObject {
                    put("threadId", threadId)
                    putJsonArray("input") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", request.text)
                        })
                    }
                    request.outputSchema?.let { put("outputSchema", it) }
                },
                indicator = indicator,
                onCanceled = { abortTurnBestEffort(activeSession, turn) },
            ).asObject() ?: protocolFailure()
            val turnId = turnResult["turn"].asObject().requiredString("id")
            if (!turn.bind(turnId)) protocolFailure()
            return awaitTurn(activeSession, turn, indicator)
        } catch (error: ProcessCanceledException) {
            abortTurnBestEffort(activeSession, turn)
            throw error
        } catch (error: CodexAppServerException) {
            if (!turn.result.isDone) abortTurnBestEffort(activeSession, turn)
            throw error
        } finally {
            activeSession.turns.remove(threadId, turn)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val activeSession = synchronized(lifecycleLock) {
            session.also { session = null }
        } ?: return

        if (!activeSession.terminated.get()) {
            activeSession.pendingLoginIds.toList().forEach { loginId ->
                sendRequestBestEffort(
                    activeSession,
                    method = "account/login/cancel",
                    params = buildJsonObject { put("loginId", loginId) },
                )
            }
            activeSession.turns.values.forEach { interruptBestEffort(activeSession, it) }
        }

        val closeError = failure(CodexAppServerErrorKind.CLOSED, "Codex App Server client is closed")
        markTerminated(activeSession, closeError)
        activeSession.connection.close(forcibly = false, failure = closeError)
    }

    private fun request(
        method: String,
        params: JsonElement,
        indicator: ProgressIndicator? = null,
    ): JsonElement = requestOnSession(activeSession(), method, params, indicator)

    private fun activeSession(): Session = synchronized(lifecycleLock) {
        if (closed.get()) throw failure(CodexAppServerErrorKind.CLOSED, "Codex App Server client is closed")
        session?.let { current ->
            if (!current.terminated.get() && current.connection.isAlive()) return@synchronized current
            terminateUnexpectedly(current)
        }

        if (hasStartedProcess) {
            if (restartUsed) {
                throw failure(CodexAppServerErrorKind.PROCESS, "Codex App Server is unavailable")
            }
            restartUsed = true
        }
        startSession()
    }

    private fun startSession(): Session {
        hasStartedProcess = true
        val sessionReference = java.util.concurrent.atomic.AtomicReference<Session?>()
        val listener = object : CodexAppServerConnectionListener {
            override fun onNotification(method: String, params: JsonObject) {
                sessionReference.get()?.let { routeNotification(it, method, params) }
            }

            override fun onServerRequest(request: CodexServerRequest) {
                sessionReference.get()?.let { rejectServerRequest(it, request) } ?: request.error()
            }

            override fun onClosed(error: CodexAppServerException) {
                sessionReference.get()?.let { connectionClosed(it, error) }
            }
        }
        val connection = CodexAppServerConnection.open(
            executable = executable,
            cwd = cwd,
            environment = emptyMap(),
            listener = listener,
            processFactory = CodexAppServerConnectionProcessFactory { requestedExecutable, _, _ ->
                processFactory.start(requestedExecutable, home, cwd)
            },
        )
        val startedSession = Session(connection)
        sessionReference.set(startedSession)
        session = startedSession

        try {
            connection.initialize(
                CodexAppServerClientIdentity(
                    name = "ezcodemark_jetbrains",
                    title = "EzCodeMark JetBrains Commit Message Provider",
                    version = version,
                ),
            ) { initializeResult ->
                val effectiveHome = initializeResult.string("codexHome")
                    ?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
                    ?: isolationFailure()
                if (effectiveHome != home.toAbsolutePath().normalize()) isolationFailure()
            }
            return startedSession
        } catch (error: RuntimeException) {
            terminateForSecurity(startedSession, error as? CodexAppServerException ?: failure(
                CodexAppServerErrorKind.PROTOCOL,
                "Codex App Server returned an invalid response",
            ))
            throw error
        }
    }

    private fun requestOnSession(
        activeSession: Session,
        method: String,
        params: JsonElement,
        indicator: ProgressIndicator? = null,
        onCanceled: () -> Unit = {},
    ): JsonElement {
        if (activeSession.terminated.get()) {
            throw failure(CodexAppServerErrorKind.PROCESS, "Codex App Server terminated unexpectedly")
        }
        return activeSession.connection.request(
            method = method,
            params = params,
            timeoutMillis = CONTROL_REQUEST_TIMEOUT_MS,
            indicator = indicator,
            onCanceled = onCanceled,
        )
    }

    private fun <T> awaitFuture(
        activeSession: Session,
        future: CompletableFuture<T>,
        indicator: ProgressIndicator,
        timeoutMillis: Long,
    ): T {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            indicator.checkCanceled()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                throw failure(CodexAppServerErrorKind.REQUEST, "Codex App Server request timed out")
            }
            try {
                return future.get(
                    minOf(RESPONSE_POLL_MS, TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)),
                    TimeUnit.MILLISECONDS,
                )
            } catch (_: TimeoutException) {
                if (activeSession.terminated.get()) {
                    throw failure(CodexAppServerErrorKind.PROCESS, "Codex App Server terminated unexpectedly")
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure(CodexAppServerErrorKind.REQUEST, "Codex App Server request was interrupted")
            } catch (error: ExecutionException) {
                throw unwrap(error)
            }
        }
    }

    private fun awaitTurn(
        activeSession: Session,
        turn: TurnState,
        indicator: ProgressIndicator,
    ): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(MAX_TURN_SECONDS)
        while (true) {
            try {
                indicator.checkCanceled()
                if (System.nanoTime() >= deadline) {
                    interruptBestEffort(activeSession, turn)
                    val error = failure(CodexAppServerErrorKind.REQUEST, "Codex turn timed out")
                    terminateForSecurity(activeSession, error)
                    throw error
                }
                return turn.result.get(RESPONSE_POLL_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                if (activeSession.terminated.get()) {
                    throw failure(CodexAppServerErrorKind.PROCESS, "Codex App Server terminated unexpectedly")
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                interruptBestEffort(activeSession, turn)
                val failure = failure(CodexAppServerErrorKind.REQUEST, "Codex App Server request was interrupted")
                terminateForSecurity(activeSession, failure)
                throw failure
            } catch (error: ExecutionException) {
                throw unwrap(error)
            } catch (error: ProcessCanceledException) {
                interruptBestEffort(activeSession, turn)
                terminateForSecurity(
                    activeSession,
                    failure(CodexAppServerErrorKind.REQUEST, "Codex turn was cancelled"),
                )
                throw error
            }
        }
    }

    private fun unwrap(error: ExecutionException): RuntimeException = when (val cause = error.cause) {
        is RuntimeException -> cause
        else -> failure(CodexAppServerErrorKind.PROTOCOL, "Codex App Server returned an invalid response")
    }

    private fun routeNotification(activeSession: Session, method: String, params: JsonObject) {
        if (activeSession.notificationCount.incrementAndGet() > MAX_SESSION_NOTIFICATIONS) {
            terminateForSecurity(
                activeSession,
                failure(CodexAppServerErrorKind.PROTOCOL, "Codex emitted too many events"),
            )
            return
        }
        when (method) {
            "account/login/completed" -> {
                val loginId = params.string("loginId")
                if (loginId == null) activeSession.pendingLoginIds.clear()
                else activeSession.pendingLoginIds -= loginId
                val completed = CodexAppServerLoginCompleted(
                    loginId = loginId,
                    success = params.boolean("success") ?: protocolFailure(activeSession),
                    error = params.string("error"),
                )
                if (loginId == null) {
                    activeSession.loginResults.values.forEach { it.complete(completed) }
                } else {
                    activeSession.loginResults.computeIfAbsent(loginId) { CompletableFuture() }.complete(completed)
                }
            }

            "turn/started" -> bindStartedTurn(activeSession, params)
            "item/agentMessage/delta" -> recordDelta(activeSession, params)
            "item/started", "item/completed" -> recordItem(activeSession, params, method == "item/completed")
            in SAFE_ITEM_NOTIFICATION_METHODS -> Unit
            "turn/completed" -> completeTurn(activeSession, params)
            "error" -> terminateForSecurity(
                activeSession,
                failure(CodexAppServerErrorKind.REQUEST, "Codex App Server reported an error"),
            )
            in SAFE_IGNORED_NOTIFICATION_METHODS -> Unit
            else -> {
                val kind = if (isDisallowedOperationNotification(method)) {
                    CodexAppServerErrorKind.TOOL_USE
                } else {
                    CodexAppServerErrorKind.PROTOCOL
                }
                terminateForSecurity(activeSession, failure(kind, "Codex returned an unsupported event"))
            }
        }
    }

    private fun bindStartedTurn(activeSession: Session, params: JsonObject) {
        val threadId = params.string("threadId") ?: return protocolFailure(activeSession)
        val turnId = params["turn"].asObject().string("id") ?: return protocolFailure(activeSession)
        val turn = activeSession.turns[threadId] ?: return protocolFailure(activeSession)
        if (!turn.bind(turnId)) protocolFailure(activeSession)
    }

    private fun recordDelta(activeSession: Session, params: JsonObject) {
        val turn = matchingTurn(activeSession, params)
        val itemId = params.string("itemId") ?: return protocolFailure(activeSession)
        val delta = params.string("delta") ?: return protocolFailure(activeSession)
        if (!turn.recordDelta(itemId, delta)) {
            terminateForSecurity(
                activeSession,
                failure(CodexAppServerErrorKind.PROTOCOL, "Codex returned an invalid or oversized item event"),
            )
        }
    }

    private fun recordItem(activeSession: Session, params: JsonObject, completed: Boolean) {
        val item = params["item"].asObject() ?: return terminateForSecurity(
            activeSession,
            failure(CodexAppServerErrorKind.PROTOCOL, "Codex returned an invalid item event"),
        )
        val type = item.string("type") ?: return terminateForSecurity(
            activeSession,
            failure(CodexAppServerErrorKind.PROTOCOL, "Codex returned an invalid item event"),
        )
        if (type !in SAFE_TURN_ITEM_TYPES) {
            terminateForSecurity(
                activeSession,
                failure(CodexAppServerErrorKind.TOOL_USE, "Codex attempted a disallowed tool operation"),
            )
            return
        }
        val itemId = item.string("id") ?: return terminateForSecurity(
            activeSession,
            failure(CodexAppServerErrorKind.PROTOCOL, "Codex returned an invalid item event"),
        )
        val turn = matchingTurn(activeSession, params)
        val accepted = if (completed) {
            turn.completeItem(
                itemId = itemId,
                type = type,
                text = item.string("text").orEmpty(),
                phase = item.string("phase"),
            )
        } else {
            turn.startItem(itemId, type)
        }
        if (!accepted) {
            terminateForSecurity(
                activeSession,
                failure(CodexAppServerErrorKind.PROTOCOL, "Codex returned an invalid or oversized item event"),
            )
        }
    }

    private fun completeTurn(activeSession: Session, params: JsonObject) {
        val threadId = params.string("threadId") ?: return protocolFailure(activeSession)
        val turnObject = params["turn"].asObject() ?: return protocolFailure(activeSession)
        val turnId = turnObject.string("id") ?: return protocolFailure(activeSession)
        val turn = activeSession.turns[threadId] ?: return protocolFailure(activeSession)
        if (!turn.bind(turnId)) protocolFailure(activeSession)

        val items = turnObject["items"].asArray() ?: return terminateForSecurity(
            activeSession,
            failure(CodexAppServerErrorKind.PROTOCOL, "Codex returned an invalid completed turn"),
        )
        if (items.size > MAX_TURN_ITEMS) {
            terminateForSecurity(
                activeSession,
                failure(CodexAppServerErrorKind.PROTOCOL, "Codex returned too many turn items"),
            )
            return
        }
        items.forEach { element ->
            val item = element.asObject() ?: return terminateForSecurity(
                activeSession,
                failure(CodexAppServerErrorKind.PROTOCOL, "Codex returned an invalid turn item"),
            )
            val type = item.string("type") ?: return terminateForSecurity(
                activeSession,
                failure(CodexAppServerErrorKind.PROTOCOL, "Codex returned an invalid turn item"),
            )
            if (type !in SAFE_TURN_ITEM_TYPES) {
                terminateForSecurity(
                    activeSession,
                    failure(CodexAppServerErrorKind.TOOL_USE, "Codex attempted a disallowed tool operation"),
                )
                return
            }
            if (type == "agentMessage") {
                if (!turn.recordFinalMessage(
                    text = item.string("text").orEmpty(),
                    phase = item.string("phase"),
                    )
                ) {
                    terminateForSecurity(
                        activeSession,
                        failure(CodexAppServerErrorKind.PROTOCOL, "Codex assistant output exceeded its limit"),
                    )
                    return
                }
            }
        }

        when (turnObject.string("status")) {
            "completed" -> {
                val text = turn.completedText()
                if (text.isNullOrBlank()) {
                    turn.fail(
                        failure(
                            CodexAppServerErrorKind.PROTOCOL,
                            "Codex completed without an assistant message",
                        ),
                    )
                } else {
                    turn.result.complete(text)
                }
            }

            "failed" -> turn.fail(
                failure(CodexAppServerErrorKind.TURN_FAILED, "Codex turn failed"),
            )

            "interrupted" -> turn.fail(
                failure(CodexAppServerErrorKind.TURN_INTERRUPTED, "Codex turn was interrupted"),
            )

            else -> turn.fail(
                failure(CodexAppServerErrorKind.PROTOCOL, "Codex returned an invalid turn status"),
            )
        }
    }

    private fun abortLoginOnSession(activeSession: Session, loginId: String) {
        if (activeSession.terminated.get()) return
        sendRequestBestEffort(
            activeSession,
            method = "account/login/cancel",
            params = buildJsonObject { put("loginId", loginId) },
        )
        terminateForSecurity(
            activeSession,
            failure(CodexAppServerErrorKind.REQUEST, "Codex login did not complete"),
        )
    }

    private fun matchingTurn(activeSession: Session, params: JsonObject): TurnState {
        val threadId = params.string("threadId") ?: protocolFailure(activeSession)
        val turnId = params.string("turnId") ?: protocolFailure(activeSession)
        val turn = activeSession.turns[threadId] ?: protocolFailure(activeSession)
        if (!turn.bind(turnId)) protocolFailure(activeSession)
        return turn
    }

    private fun interruptBestEffort(activeSession: Session, turn: TurnState) {
        val turnId = turn.turnId() ?: return
        if (!turn.interruptSent.compareAndSet(false, true)) return
        sendRequestBestEffort(
            activeSession,
            method = "turn/interrupt",
            params = buildJsonObject {
                put("threadId", turn.threadId)
                put("turnId", turnId)
            },
        )
    }

    private fun abortTurnBestEffort(activeSession: Session, turn: TurnState) {
        if (turn.turnId() == null) {
            terminateForSecurity(
                activeSession,
                failure(CodexAppServerErrorKind.REQUEST, "Codex turn start did not complete"),
            )
        } else {
            interruptBestEffort(activeSession, turn)
        }
    }

    private fun sendRequestBestEffort(activeSession: Session, method: String, params: JsonElement) {
        if (activeSession.terminated.get()) return
        activeSession.connection.requestBestEffort(method, params)
    }

    private fun terminateUnexpectedly(activeSession: Session) {
        val error = failure(CodexAppServerErrorKind.PROCESS, "Codex App Server terminated unexpectedly")
        markTerminated(activeSession, error)
        activeSession.connection.close(forcibly = false, failure = error)
        synchronized(lifecycleLock) {
            if (session === activeSession) session = null
        }
    }

    private fun connectionClosed(activeSession: Session, error: CodexAppServerException) {
        markTerminated(activeSession, error)
        synchronized(lifecycleLock) {
            if (session === activeSession) session = null
        }
    }

    private fun rejectServerRequest(activeSession: Session, request: CodexServerRequest) {
        request.error(message = "EzCodeMark does not permit server-initiated operations")
        val kind = if (request.method in DISALLOWED_SERVER_REQUESTS) {
            CodexAppServerErrorKind.TOOL_USE
        } else {
            CodexAppServerErrorKind.PROTOCOL
        }
        terminateForSecurity(activeSession, failure(kind, "Codex requested a disallowed client operation"))
    }

    private fun terminateForSecurity(activeSession: Session, error: CodexAppServerException) {
        markTerminated(activeSession, error)
        activeSession.connection.close(forcibly = true, failure = error)
        synchronized(lifecycleLock) {
            if (session === activeSession) session = null
        }
    }

    private fun markTerminated(activeSession: Session, error: CodexAppServerException) {
        if (!activeSession.terminated.compareAndSet(false, true)) return
        activeSession.turns.values.forEach { it.fail(error) }
        activeSession.loginResults.values.forEach { it.completeExceptionally(error) }
        activeSession.loginResults.clear()
        activeSession.pendingLoginIds.clear()
    }

    private fun parseModel(model: JsonObject?): CodexAppServerModel? {
        model ?: return null
        val id = model.string("id")?.takeIf(String::isNotBlank) ?: return null
        val efforts = model["supportedReasoningEfforts"].asArray().orEmpty().mapNotNull { effort ->
            effort.asObject().string("reasoningEffort") ?: (effort as? JsonPrimitive)?.contentOrNull
        }
        val modalities = model["inputModalities"].asArray().orEmpty().mapNotNull { modality ->
            (modality as? JsonPrimitive)?.contentOrNull
        }
        return CodexAppServerModel(
            id = id,
            displayName = model.string("displayName") ?: id,
            isDefault = model.boolean("isDefault") ?: false,
            defaultReasoningEffort = model.string("defaultReasoningEffort"),
            reasoningEfforts = efforts,
            inputModalities = modalities,
        )
    }

    private fun CodexAppServerModel.retainedChars(): Long =
        id.length.toLong() + displayName.length + defaultReasoningEffort.orEmpty().length +
            reasoningEfforts.sumOf(String::length) + inputModalities.sumOf(String::length)

    private fun protocolFailure(): Nothing = throw failure(
        CodexAppServerErrorKind.PROTOCOL,
        "Codex App Server returned an invalid response",
    )

    private fun protocolFailure(activeSession: Session): Nothing {
        val error = failure(
            CodexAppServerErrorKind.PROTOCOL,
            "Codex App Server returned an invalid response",
        )
        terminateForSecurity(activeSession, error)
        throw error
    }

    private fun isolationFailure(): Nothing = throw failure(
        CodexAppServerErrorKind.ISOLATION,
        "Codex did not honor the required isolation settings",
    )

    private fun validateThreadIsolation(result: JsonObject) {
        val instructionSources = result["instructionSources"]
        if (instructionSources !is JsonArray || instructionSources.isNotEmpty()) isolationFailure()
        if (result.string("cwd") != cwd.toString()) isolationFailure()
        if (result.string("approvalPolicy") != "never") isolationFailure()
        val activeProfile = result["activePermissionProfile"].asObject() ?: isolationFailure()
        if (activeProfile.string("id") != permissionProfileId) isolationFailure()
        val sandbox = result["sandbox"].asObject() ?: isolationFailure()
        if (sandbox.string("type") != "readOnly" || sandbox.boolean("networkAccess") != false) isolationFailure()
        val thread = result["thread"].asObject() ?: isolationFailure()
        if (thread.boolean("ephemeral") != true || thread.string("cwd") != cwd.toString()) isolationFailure()
    }

    private fun failure(kind: CodexAppServerErrorKind, message: String) =
        CodexAppServerException(kind, message)

    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
    private fun JsonElement?.asArray(): JsonArray? = this as? JsonArray
    private fun JsonObject?.string(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.boolean(key: String): Boolean? {
        val primitive = this?.get(key) as? JsonPrimitive ?: return null
        return primitive.takeUnless(JsonPrimitive::isString)?.booleanOrNull
    }

    private fun JsonObject?.requiredString(key: String): String =
        string(key)?.takeIf(String::isNotBlank) ?: protocolFailure()

    private class Session(val connection: CodexAppServerConnection) {
        val notificationCount = AtomicLong()
        val pendingLoginIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val loginResults = ConcurrentHashMap<String, CompletableFuture<CodexAppServerLoginCompleted>>()
        val turns = ConcurrentHashMap<String, TurnState>()
        val terminated = AtomicBoolean()
    }

    private class TurnState(val threadId: String, private val maxOutputChars: Int) {
        val result = CompletableFuture<String>()
        val interruptSent = AtomicBoolean()
        private val lock = Any()
        private var boundTurnId: String? = null
        private var finalCompletedText: String? = null
        private var latestCompletedText: String? = null
        private val deltas = LinkedHashMap<String, StringBuilder>()

        fun bind(turnId: String): Boolean = synchronized(lock) {
            val current = boundTurnId
            if (current == null) {
                boundTurnId = turnId
                true
            } else {
                current == turnId
            }
        }

        fun turnId(): String? = synchronized(lock) { boundTurnId }

        private var outputChars = 0
        private val itemTypes = LinkedHashMap<String, String>()

        fun startItem(itemId: String, type: String): Boolean = synchronized(lock) {
            if (itemTypes.size >= MAX_TURN_ITEMS || itemTypes.putIfAbsent(itemId, type) != null) {
                return@synchronized false
            }
            true
        }

        fun recordDelta(itemId: String, delta: String): Boolean = synchronized(lock) {
            if (itemTypes[itemId] != "agentMessage") return@synchronized false
            if (delta.length > maxOutputChars - outputChars) return@synchronized false
            deltas.getOrPut(itemId, ::StringBuilder).append(delta)
            outputChars += delta.length
            true
        }

        fun completeItem(itemId: String, type: String, text: String, phase: String?): Boolean = synchronized(lock) {
            if (itemTypes.remove(itemId) != type) return@synchronized false
            if (type != "agentMessage") return@synchronized true
            if (text.length > maxOutputChars) return@synchronized false
            latestCompletedText = text
            if (phase == "final_answer") finalCompletedText = text
            true
        }

        fun recordFinalMessage(text: String, phase: String?): Boolean = synchronized(lock) {
            if (text.length > maxOutputChars) return@synchronized false
            latestCompletedText = text
            if (phase == "final_answer") finalCompletedText = text
            true
        }

        fun completedText(): String? = synchronized(lock) {
            finalCompletedText?.takeIf(String::isNotBlank)
                ?: latestCompletedText?.takeIf(String::isNotBlank)
                ?: deltas.values.joinToString(separator = "") { it.toString() }
                    .takeIf(String::isNotBlank)
        }

        fun fail(error: CodexAppServerException): Boolean = result.completeExceptionally(error)
    }

    companion object {
        private val EMPTY_OBJECT = JsonObject(emptyMap())
        private const val CODEX_PERMISSION_PROFILE_PREFIX = "ezcodemark-isolated"
        private val SAFE_TURN_ITEM_TYPES = setOf(
            "userMessage",
            "agentMessage",
            "reasoning",
            "contextCompaction",
        )
        private val SAFE_ITEM_NOTIFICATION_METHODS = setOf(
            "item/reasoning/summaryTextDelta",
            "item/reasoning/summaryPartAdded",
            "item/reasoning/textDelta",
        )
        private val SAFE_IGNORED_NOTIFICATION_METHODS = setOf(
            "account/rateLimits/updated",
            "account/updated",
            "deprecationNotice",
            "model/rerouted",
            "model/safetyBuffering/updated",
            "model/verification",
            "thread/closed",
            "thread/compacted",
            "thread/started",
            "thread/status/changed",
            "thread/tokenUsage/updated",
            "turn/moderationMetadata",
            "windows/worldWritableWarning",
            "windowsSandbox/setupCompleted",
        )
        private val DISALLOWED_SERVER_REQUESTS = setOf(
            "item/commandExecution/requestApproval",
            "item/fileChange/requestApproval",
            "item/tool/requestUserInput",
            "mcpServer/elicitation/request",
            "item/permissions/requestApproval",
            "item/tool/call",
            "applyPatchApproval",
            "execCommandApproval",
        )
        private const val RESPONSE_POLL_MS = 50L
        private const val CONTROL_REQUEST_TIMEOUT_MS = 30_000L
        private const val LOGIN_TIMEOUT_MS = 5 * 60_000L
        private const val MAX_TURN_SECONDS = 120L
        private const val MAX_ASSISTANT_OUTPUT_CHARS = 1_000_000
        private const val MAX_CHARS_PER_TOKEN = 8
        private const val MAX_TURN_ITEMS = 256
        private const val MAX_SESSION_NOTIFICATIONS = 10_000L
        private const val MAX_MODEL_PAGES = 100
        private const val MAX_MODELS = 10_000
        private const val MAX_MODEL_CURSOR_CHARS = 4_096
        private const val MAX_MODEL_RETAINED_CHARS = 1_000_000L

        private fun outputCharacterLimit(maxOutputTokens: Int?): Int = maxOutputTokens
            ?.coerceAtLeast(1)
            ?.let { tokens ->
                if (tokens > MAX_ASSISTANT_OUTPUT_CHARS / MAX_CHARS_PER_TOKEN) MAX_ASSISTANT_OUTPUT_CHARS
                else tokens * MAX_CHARS_PER_TOKEN
            }
            ?: MAX_ASSISTANT_OUTPUT_CHARS

        private fun isDisallowedOperationNotification(method: String): Boolean =
            method == "turn/plan/updated" ||
                method == "turn/diff/updated" ||
                method == "thread/settings/updated" ||
                method.startsWith("item/") ||
                method.startsWith("hook/") ||
                method.startsWith("command/") ||
                method.startsWith("process/") ||
                method.startsWith("mcpServer/") ||
                method.startsWith("fs/")
    }
}
