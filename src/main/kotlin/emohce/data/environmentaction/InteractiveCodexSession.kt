package emohce.data.environmentaction

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import emohce.data.commitmessage.CodexAppServerConnection
import emohce.data.commitmessage.CodexAppServerConnectionListener
import emohce.data.commitmessage.CodexAppServerConnectionProcessFactory
import emohce.data.commitmessage.CodexAppServerClientIdentity
import emohce.data.commitmessage.CodexAppServerErrorKind
import emohce.data.commitmessage.CodexAppServerException
import emohce.data.commitmessage.CodexServerRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class InteractiveCodexSessionState {
    IDLE,
    STARTING,
    RUNNING,
    AWAITING_APPROVAL,
    STOPPING,
    FAILED,
    CLOSED,
}

data class InteractiveCodexPermissionSummary(
    val profileId: String,
    val sandboxType: String,
    val approvalPolicy: String,
    val networkAccess: String,
    val instructionSources: List<String>,
    val codexHome: String?,
    val skillCount: Int?,
    val skillErrorCount: Int,
    val pluginCount: Int?,
    val pluginErrorCount: Int,
    val broad: Boolean,
)

data class InteractiveCodexApprovalRequest(
    val token: String,
    val method: String,
    val summary: String,
    val availableDecisions: List<String>,
)

enum class InteractiveCodexApprovalDecision {
    ALLOW,
    DENY,
}

sealed interface InteractiveCodexEvent {
    data class StateChanged(val state: InteractiveCodexSessionState) : InteractiveCodexEvent
    data class SessionStarted(val permission: InteractiveCodexPermissionSummary) : InteractiveCodexEvent
    data class AssistantDelta(val itemId: String, val text: String) : InteractiveCodexEvent
    data class AssistantMessage(val itemId: String, val text: String) : InteractiveCodexEvent
    data class ToolEvent(val method: String, val type: String?, val summary: String?) : InteractiveCodexEvent
    data class ApprovalRequested(val request: InteractiveCodexApprovalRequest) : InteractiveCodexEvent
    data class TurnCompleted(val status: String) : InteractiveCodexEvent
    data class Failure(val message: String) : InteractiveCodexEvent
}

fun interface InteractiveCodexSessionListener {
    fun onEvent(event: InteractiveCodexEvent)
}

internal class InteractiveCodexSession internal constructor(
    private val executable: String,
    private val cwd: Path,
    environment: Map<String, String>,
    private val clientVersion: String,
    private val listener: InteractiveCodexSessionListener,
    private val processFactory: CodexAppServerConnectionProcessFactory = CodexAppServerConnectionProcessFactory.SYSTEM,
) : AutoCloseable, CodexAppServerConnectionListener {
    private val environment = environment.filterKeys { key ->
        PROTECTED_IDENTITY_VARIABLES.none { it.equals(key, ignoreCase = true) }
    }
    private val state = AtomicReference(InteractiveCodexSessionState.IDLE)
    private val closed = AtomicBoolean(false)
    private val broadPermissionsAcknowledged = AtomicBoolean(false)
    private val activeTurn = AtomicReference<ActiveTurn?>()
    private val approvals = ConcurrentHashMap<String, PendingApproval>()
    private val deltaItems: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var connection: CodexAppServerConnection? = null
    @Volatile
    private var threadId: String? = null
    @Volatile
    private var permissionSummary: InteractiveCodexPermissionSummary? = null

    init {
        require(executable.isNotBlank()) { "Codex executable is required" }
        require(cwd.isAbsolute && Files.isDirectory(cwd)) { "Codex Chat working directory must exist" }
    }

    fun start(): InteractiveCodexPermissionSummary {
        check(!closed.get()) { "Codex Chat session is closed" }
        transition(InteractiveCodexSessionState.STARTING)
        try {
            val (startedConnection, initializeResult) = CodexAppServerConnection.start(
                executable = executable,
                cwd = cwd,
                environment = environment,
                identity = CodexAppServerClientIdentity(
                    name = "ezcodemark_jetbrains_chat",
                    title = "EzCodeMark JetBrains Codex Chat",
                    version = clientVersion,
                ),
                listener = this,
                processFactory = processFactory,
            )
            connection = startedConnection
            val config = startedConnection.request(
                "config/read",
                buildJsonObject {
                    put("cwd", cwd.toString())
                    put("includeLayers", true)
                },
            ) as? JsonObject ?: protocolFailure("Codex config/read returned an invalid response")
            if (config["config"] !is JsonObject) protocolFailure("Codex config/read did not return effective config")
            val skills = runCatching {
                startedConnection.request(
                    "skills/list",
                    buildJsonObject {
                        putJsonArray("cwds") { add(JsonPrimitive(cwd.toString())) }
                        put("forceReload", false)
                    },
                ) as? JsonObject
            }.getOrNull()
            val installedPlugins = runCatching {
                startedConnection.request(
                    "plugin/installed",
                    buildJsonObject {
                        putJsonArray("cwds") { add(JsonPrimitive(cwd.toString())) }
                    },
                ) as? JsonObject
            }.getOrNull()

            val threadResult = startedConnection.request(
                "thread/start",
                buildJsonObject {
                    put("cwd", cwd.toString())
                    put("ephemeral", true)
                    put("serviceName", "ezcodemark_jetbrains_chat")
                },
            ) as? JsonObject ?: protocolFailure("Codex thread/start returned an invalid response")
            val thread = threadResult["thread"] as? JsonObject
                ?: protocolFailure("Codex thread/start omitted the thread")
            val id = thread.string("id")?.takeIf(String::isNotBlank)
                ?: protocolFailure("Codex thread/start omitted the thread id")
            if (thread.boolean("ephemeral") != true) protocolFailure("Codex Chat requires an ephemeral thread")
            val effectiveCwd = threadResult.string("cwd") ?: thread.string("cwd")
            if (effectiveCwd != cwd.toString()) protocolFailure("Codex Chat did not honor the selected working directory")

            val activeProfile = threadResult["activePermissionProfile"] as? JsonObject
                ?: protocolFailure("Codex did not report an active permission profile")
            val profileId = activeProfile.string("id")?.takeIf(String::isNotBlank)
                ?: protocolFailure("Codex reported an invalid permission profile")
            val sandbox = threadResult["sandbox"] as? JsonObject
                ?: protocolFailure("Codex did not report an effective sandbox")
            val sandboxType = sandbox.string("type")?.takeIf(String::isNotBlank)
                ?: protocolFailure("Codex reported an invalid sandbox")
            val approvalPolicy = threadResult["approvalPolicy"].displayValue()
                ?: protocolFailure("Codex did not report an approval policy")
            val networkAccess = sandbox["networkAccess"].displayValue() ?: "default"
            val instructionSources = (threadResult["instructionSources"] as? JsonArray)
                .orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val broad = sandboxType == "dangerFullAccess" ||
                (sandboxType != "readOnly" && approvalPolicy.contains("never", ignoreCase = true))
            val summary = InteractiveCodexPermissionSummary(
                profileId = profileId,
                sandboxType = sandboxType,
                approvalPolicy = approvalPolicy,
                networkAccess = networkAccess,
                instructionSources = instructionSources,
                codexHome = initializeResult.string("codexHome"),
                skillCount = skills?.nestedArrayCount("data", "skills"),
                skillErrorCount = skills?.nestedArrayCount("data", "errors") ?: 0,
                pluginCount = installedPlugins?.nestedArrayCount("marketplaces", "plugins"),
                pluginErrorCount = (installedPlugins?.get("marketplaceLoadErrors") as? JsonArray)?.size ?: 0,
                broad = broad,
            )
            threadId = id
            permissionSummary = summary
            transition(InteractiveCodexSessionState.IDLE)
            listener.onEvent(InteractiveCodexEvent.SessionStarted(summary))
            return summary
        } catch (error: RuntimeException) {
            fail(error.message.orEmpty().ifBlank { "Codex Chat could not be started" })
            closeConnection()
            throw error
        }
    }

    fun acknowledgeBroadPermissions() {
        broadPermissionsAcknowledged.set(true)
    }

    fun send(prompt: String) {
        val normalized = prompt.trim()
        require(normalized.isNotBlank()) { "Codex Chat prompt is required" }
        check(!closed.get()) { "Codex Chat session is closed" }
        val permission = permissionSummary ?: error("Codex Chat session has not started")
        check(!permission.broad || broadPermissionsAcknowledged.get()) {
            "Broad Codex permissions require acknowledgement"
        }
        val id = threadId ?: error("Codex Chat thread is unavailable")
        val turn = ActiveTurn(id)
        check(activeTurn.compareAndSet(null, turn)) { "Codex Chat already has an active turn" }
        transition(InteractiveCodexSessionState.RUNNING)
        try {
            val result = requireConnection().request(
                "turn/start",
                buildJsonObject {
                    put("threadId", id)
                    putJsonArray("input") {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", normalized)
                            },
                        )
                    }
                    put("cwd", cwd.toString())
                },
            ) as? JsonObject ?: protocolFailure("Codex turn/start returned an invalid response")
            val turnId = (result["turn"] as? JsonObject)?.string("id")
                ?: protocolFailure("Codex turn/start omitted the turn id")
            if (!turn.bind(turnId)) protocolFailure("Codex returned conflicting turn ids")
        } catch (error: RuntimeException) {
            activeTurn.compareAndSet(turn, null)
            fail(error.message.orEmpty().ifBlank { "Codex Chat turn failed to start" })
            throw error
        }
    }

    fun respondToApproval(token: String, decision: InteractiveCodexApprovalDecision): Boolean {
        val pending = approvals.remove(token) ?: return false
        when (pending.request.method) {
            "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> {
                pending.request.success(
                    buildJsonObject {
                        put("decision", if (decision == InteractiveCodexApprovalDecision.ALLOW) "accept" else "decline")
                    },
                )
            }
            "item/permissions/requestApproval" -> {
                pending.request.success(
                    buildJsonObject {
                        put(
                            "permissions",
                            if (decision == InteractiveCodexApprovalDecision.ALLOW) {
                                pending.request.params["permissions"] ?: JsonObject(emptyMap())
                            } else {
                                JsonObject(emptyMap())
                            },
                        )
                        put("scope", "turn")
                    },
                )
            }
            else -> pending.request.error()
        }
        transition(if (approvals.isEmpty()) InteractiveCodexSessionState.RUNNING else InteractiveCodexSessionState.AWAITING_APPROVAL)
        return true
    }

    fun interrupt() {
        val turn = activeTurn.get()
        if (turn == null) {
            if (state.get() == InteractiveCodexSessionState.STARTING) {
                transition(InteractiveCodexSessionState.STOPPING)
                close()
            }
            return
        }
        transition(InteractiveCodexSessionState.STOPPING)
        val turnId = turn.id()
        if (turnId == null) {
            closeConnection()
            activeTurn.compareAndSet(turn, null)
            transition(InteractiveCodexSessionState.FAILED)
            return
        }
        runCatching {
            requireConnection().request(
                "turn/interrupt",
                buildJsonObject {
                    put("threadId", turn.threadId)
                    put("turnId", turnId)
                },
            )
        }.onFailure { closeConnection() }
    }

    fun state(): InteractiveCodexSessionState = state.get()

    fun permission(): InteractiveCodexPermissionSummary? = permissionSummary

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val turn = activeTurn.get()
        if (turn?.id() != null) {
            runCatching {
                requireConnection().request(
                    "turn/interrupt",
                    buildJsonObject {
                        put("threadId", turn.threadId)
                        put("turnId", turn.id()!!)
                    },
                    timeoutMillis = CLOSE_INTERRUPT_TIMEOUT_MILLIS,
                )
            }
        }
        approvals.values.forEach { it.request.error(message = "Codex Chat was closed") }
        approvals.clear()
        activeTurn.set(null)
        closeConnection()
        transition(InteractiveCodexSessionState.CLOSED)
    }

    override fun onNotification(method: String, params: JsonObject) {
        when (method) {
            "turn/started" -> {
                val turnId = (params["turn"] as? JsonObject)?.string("id") ?: params.string("turnId")
                val current = activeTurn.get()
                if (current != null && (turnId == null || !current.bind(turnId))) {
                    protocolFailure("Codex reported a conflicting active turn")
                }
            }
            "item/agentMessage/delta" -> {
                val itemId = params.string("itemId").orEmpty()
                val delta = params.string("delta").orEmpty()
                if (delta.isNotEmpty()) {
                    deltaItems += itemId
                    listener.onEvent(InteractiveCodexEvent.AssistantDelta(itemId, delta))
                }
            }
            "item/started", "item/completed" -> {
                val item = params["item"] as? JsonObject
                val itemId = item.string("id").orEmpty()
                val type = item.string("type")
                val text = item.string("text")
                if (method == "item/completed" && type == "agentMessage" && itemId !in deltaItems && !text.isNullOrBlank()) {
                    listener.onEvent(InteractiveCodexEvent.AssistantMessage(itemId, text))
                } else if (type != "agentMessage" && type != "userMessage") {
                    listener.onEvent(InteractiveCodexEvent.ToolEvent(method, type, item?.summary()))
                }
            }
            "turn/completed" -> completeTurn(params)
            "thread/settings/updated" -> listener.onEvent(
                InteractiveCodexEvent.ToolEvent(method, "settings", null),
            )
            "turn/diff/updated", "turn/plan/updated", "item/reasoning/summaryTextDelta",
            "item/reasoning/textDelta", "item/reasoning/summaryPartAdded" -> listener.onEvent(
                InteractiveCodexEvent.ToolEvent(method, null, null),
            )
            "error" -> fail(params.string("message") ?: "Codex App Server reported an error")
            else -> Unit
        }
    }

    override fun onServerRequest(request: CodexServerRequest) {
        if (request.method !in APPROVAL_METHODS) {
            request.error()
            listener.onEvent(InteractiveCodexEvent.ToolEvent(request.method, "unsupported", null))
            return
        }
        val current = activeTurn.get()
        val requestThreadId = request.params.string("threadId")
        val requestTurnId = request.params.string("turnId")
        if (
            current == null ||
            requestThreadId != null && requestThreadId != current.threadId ||
            requestTurnId != null && current.id() != null && requestTurnId != current.id()
        ) {
            request.error(message = "Approval does not belong to the active Codex turn")
            listener.onEvent(InteractiveCodexEvent.ToolEvent(request.method, "stale-approval", null))
            return
        }
        val token = UUID.randomUUID().toString()
        approvals[token] = PendingApproval(request)
        transition(InteractiveCodexSessionState.AWAITING_APPROVAL)
        listener.onEvent(
            InteractiveCodexEvent.ApprovalRequested(
                InteractiveCodexApprovalRequest(
                    token = token,
                    method = request.method,
                    summary = request.params.approvalSummary(),
                    availableDecisions = (request.params["availableDecisions"] as? JsonArray)
                        .orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                ),
            ),
        )
    }

    override fun onClosed(error: CodexAppServerException) {
        if (!closed.get()) fail(error.message.orEmpty())
    }

    private fun completeTurn(params: JsonObject) {
        val turn = params["turn"] as? JsonObject
        val status = turn.string("status") ?: "failed"
        val current = activeTurn.get()
        val completedId = turn.string("id")
        val completedThreadId = params.string("threadId")
        if (current != null) {
            if (
                completedId == null ||
                !current.bind(completedId) ||
                completedThreadId != null && completedThreadId != current.threadId
            ) {
                protocolFailure("Codex completed a different turn")
            }
            activeTurn.compareAndSet(current, null)
        }
        approvals.values.forEach { it.request.error(message = "Codex turn completed before approval") }
        approvals.clear()
        deltaItems.clear()
        transition(if (status == "completed" || status == "interrupted") InteractiveCodexSessionState.IDLE else InteractiveCodexSessionState.FAILED)
        listener.onEvent(InteractiveCodexEvent.TurnCompleted(status))
    }

    private fun transition(next: InteractiveCodexSessionState) {
        state.set(next)
        listener.onEvent(InteractiveCodexEvent.StateChanged(next))
    }

    private fun fail(message: String) {
        transition(InteractiveCodexSessionState.FAILED)
        listener.onEvent(InteractiveCodexEvent.Failure(message.take(MAX_ERROR_CHARS)))
    }

    private fun closeConnection() {
        connection?.close()
        connection = null
    }

    private fun requireConnection(): CodexAppServerConnection = connection
        ?: throw CodexAppServerException(CodexAppServerErrorKind.CLOSED, "Codex Chat connection is unavailable")

    private fun protocolFailure(message: String): Nothing = throw CodexAppServerException(
        CodexAppServerErrorKind.PROTOCOL,
        message,
    )

    private class ActiveTurn(val threadId: String) {
        private val turnId = AtomicReference<String?>()
        fun bind(value: String): Boolean {
            val current = turnId.get()
            return current == value || current == null && turnId.compareAndSet(null, value)
        }
        fun id(): String? = turnId.get()
    }

    private data class PendingApproval(val request: CodexServerRequest)

    private fun JsonObject?.string(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.boolean(key: String): Boolean? {
        val primitive = this?.get(key) as? JsonPrimitive ?: return null
        return primitive.takeUnless(JsonPrimitive::isString)?.booleanOrNull
    }

    private fun JsonElement?.displayValue(): String? = when (this) {
        is JsonPrimitive -> contentOrNull
        is JsonObject -> toString()
        else -> null
    }

    private fun JsonObject.summary(): String? = string("name")
        ?: string("command")
        ?: string("reason")
        ?: toString().take(MAX_SUMMARY_CHARS)

    private fun JsonObject.approvalSummary(): String = listOfNotNull(
        string("command"),
        string("reason"),
        string("cwd"),
    ).joinToString("\n").ifBlank { toString().take(MAX_SUMMARY_CHARS) }.take(MAX_SUMMARY_CHARS)

    private fun JsonObject.nestedArrayCount(parentKey: String, childKey: String): Int =
        (this[parentKey] as? JsonArray).orEmpty().sumOf { entry ->
            (((entry as? JsonObject)?.get(childKey)) as? JsonArray)?.size ?: 0
        }

    companion object {
        private val APPROVAL_METHODS = setOf(
            "item/commandExecution/requestApproval",
            "item/fileChange/requestApproval",
            "item/permissions/requestApproval",
        )
        private const val CLOSE_INTERRUPT_TIMEOUT_MILLIS = 2_000L
        private const val MAX_SUMMARY_CHARS = 2_000
        private const val MAX_ERROR_CHARS = 500
        private val PROTECTED_IDENTITY_VARIABLES = setOf("HOME", "CODEX_HOME")
    }
}

@Service(Service.Level.PROJECT)
internal class InteractiveCodexSessionFactory(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    fun create(
        executable: String,
        cwd: Path,
        environment: Map<String, String>,
        listener: InteractiveCodexSessionListener,
    ): InteractiveCodexSession = InteractiveCodexSession(
        executable = executable,
        cwd = cwd,
        environment = environment,
        clientVersion = InteractiveCodexSessionFactory::class.java.`package`.implementationVersion
            ?.takeIf(String::isNotBlank)
            ?: "development",
        listener = listener,
    )

    fun launchIo(action: () -> Unit): Job = coroutineScope.launch(Dispatchers.IO) { action() }

    companion object {
        fun getInstance(project: Project): InteractiveCodexSessionFactory =
            project.getService(InteractiveCodexSessionFactory::class.java)
    }
}
