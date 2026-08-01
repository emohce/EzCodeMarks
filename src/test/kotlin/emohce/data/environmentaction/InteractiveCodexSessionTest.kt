package emohce.data.environmentaction

import emohce.data.commitmessage.CodexAppServerConnectionProcessFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class InteractiveCodexSessionTest {
    @TempDir
    lateinit var tempDir: Path

    private val sessions = CopyOnWriteArrayList<InteractiveCodexSession>()
    private val processes = CopyOnWriteArrayList<FakeProcess>()

    @AfterEach
    fun tearDown() {
        sessions.forEach { runCatching { it.close() } }
        processes.forEach(FakeProcess::forceStop)
    }

    @Test
    fun `one ephemeral thread carries multiple turns and inherits CLI policy`() {
        val turnCounter = AtomicInteger()
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "config/read" -> respondConfig(request)
                "thread/start" -> respond(request, threadStartResult())
                "turn/start" -> {
                    val turnId = "turn-${turnCounter.incrementAndGet()}"
                    respond(request, turnStartResult(turnId))
                    notify("turn/started", buildJsonObject { putJsonObject("turn") { put("id", turnId) } })
                    notify(
                        "item/agentMessage/delta",
                        buildJsonObject {
                            put("itemId", "message-$turnId")
                            put("delta", "answer-$turnId")
                        },
                    )
                    notify("turn/completed", turnCompleted(turnId, "completed"))
                }
            }
        }
        val events = CopyOnWriteArrayList<InteractiveCodexEvent>()
        val capturedEnvironment = AtomicReference<Map<String, String>>()
        val session = session(
            process,
            environment = mapOf("HOME" to "/wrong", "CODEX_HOME" to "/wrong-codex", "FEATURE" to "on"),
            capturedEnvironment = capturedEnvironment,
            events = events,
        )

        val permission = session.start()
        session.send("first")
        await { session.state() == InteractiveCodexSessionState.IDLE }
        session.send("second")
        await { process.requests().count { it.method() == "turn/start" } == 2 }
        await { session.state() == InteractiveCodexSessionState.IDLE }

        assertEquals("workspace", permission.profileId)
        assertEquals("workspaceWrite", permission.sandboxType)
        assertEquals("on-request", permission.approvalPolicy)
        assertFalse(permission.broad)
        assertEquals(1, permission.skillCount)
        assertEquals(1, permission.pluginCount)
        assertEquals(
            listOf("initialize", "initialized", "config/read", "skills/list", "plugin/installed", "thread/start"),
            process.methods().take(6),
        )
        val start = process.requests().first { it.method() == "thread/start" }.params()
        assertEquals(setOf("cwd", "ephemeral", "serviceName"), start.keys)
        assertFalse(start.containsKey("sandbox"))
        assertFalse(start.containsKey("approvalPolicy"))
        val turns = process.requests().filter { it.method() == "turn/start" }
        assertEquals(listOf("thread-1", "thread-1"), turns.map { it.params().string("threadId") })
        assertEquals(listOf("first", "second"), turns.map { it.params().prompt() })
        assertTrue(events.filterIsInstance<InteractiveCodexEvent.AssistantDelta>().size >= 2)
        assertEquals("on", capturedEnvironment.get()["FEATURE"])
        assertFalse(capturedEnvironment.get().keys.any { it.equals("HOME", true) || it.equals("CODEX_HOME", true) })
    }

    @Test
    fun `broad permission profile blocks the first turn until explicitly acknowledged`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "config/read" -> respondConfig(request)
                "thread/start" -> respond(
                    request,
                    threadStartResult(profile = "danger", sandbox = "dangerFullAccess", approval = "never"),
                )
                "turn/start" -> respond(request, turnStartResult("turn-danger"))
                "turn/interrupt" -> respond(request, buildJsonObject {})
            }
        }
        val session = session(process)

        val permission = session.start()

        assertTrue(permission.broad)
        assertThrows(IllegalStateException::class.java) { session.send("do work") }
        assertTrue(process.requests().none { it.method() == "turn/start" })
        session.acknowledgeBroadPermissions()
        session.send("do work")
        assertEquals(InteractiveCodexSessionState.RUNNING, session.state())
    }

    @Test
    fun `approval decisions are returned to the server and interrupt targets the active turn`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "config/read" -> respondConfig(request)
                "thread/start" -> respond(request, threadStartResult())
                "turn/start" -> respond(request, turnStartResult("turn-approval"))
                "turn/interrupt" -> {
                    respond(request, buildJsonObject {})
                    notify("turn/completed", turnCompleted("turn-approval", "interrupted"))
                }
            }
        }
        val events = CopyOnWriteArrayList<InteractiveCodexEvent>()
        val session = session(process, events = events)
        session.start()
        session.send("needs approval")

        process.serverRequest(
            "approval-allow",
            "item/commandExecution/requestApproval",
            buildJsonObject { put("command", "./gradlew test") },
        )
        val allow = awaitApproval(events, "item/commandExecution/requestApproval")
        assertTrue(session.respondToApproval(allow.token, InteractiveCodexApprovalDecision.ALLOW))
        val allowResponse = process.awaitMessage("approval-allow")
        assertEquals("accept", allowResponse.getValue("result").jsonObject.string("decision"))

        process.serverRequest(
            "approval-deny",
            "item/fileChange/requestApproval",
            buildJsonObject { put("reason", "edit build file") },
        )
        val deny = awaitApproval(events, "item/fileChange/requestApproval")
        assertTrue(session.respondToApproval(deny.token, InteractiveCodexApprovalDecision.DENY))
        val denyResponse = process.awaitMessage("approval-deny")
        assertEquals("decline", denyResponse.getValue("result").jsonObject.string("decision"))

        session.interrupt()
        val interrupt = process.awaitRequest("turn/interrupt")
        assertEquals("thread-1", interrupt.params().string("threadId"))
        assertEquals("turn-approval", interrupt.params().string("turnId"))
        await { session.state() == InteractiveCodexSessionState.IDLE }
    }

    @Test
    fun `missing effective permission provenance fails closed before a turn`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "config/read" -> respondConfig(request)
                "thread/start" -> respond(
                    request,
                    threadStartResult().let { JsonObject(it.filterKeys { key -> key != "activePermissionProfile" }) },
                )
            }
        }
        val events = CopyOnWriteArrayList<InteractiveCodexEvent>()
        val session = session(process, events = events)

        assertThrows(RuntimeException::class.java) { session.start() }

        assertEquals(InteractiveCodexSessionState.FAILED, session.state())
        assertTrue(events.filterIsInstance<InteractiveCodexEvent.Failure>().single().message.contains("permission profile"))
        assertTrue(process.requests().none { it.method() == "turn/start" })
    }

    @Test
    fun `unsupported server requests are rejected without becoming approvals`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "config/read" -> respondConfig(request)
                "thread/start" -> respond(request, threadStartResult())
            }
        }
        val events = CopyOnWriteArrayList<InteractiveCodexEvent>()
        val session = session(process, events = events)
        session.start()

        process.serverRequest("unsupported-1", "account/secret/request", buildJsonObject {})
        val response = process.awaitError("unsupported-1")
        await { events.filterIsInstance<InteractiveCodexEvent.ToolEvent>().any { it.type == "unsupported" } }

        assertTrue(response.containsKey("error"))
        assertTrue(events.filterIsInstance<InteractiveCodexEvent.ApprovalRequested>().isEmpty())
        assertTrue(events.filterIsInstance<InteractiveCodexEvent.ToolEvent>().any { it.type == "unsupported" })
    }

    @Test
    fun `App Server process death fails the temporary session`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "config/read" -> respondConfig(request)
                "thread/start" -> respond(request, threadStartResult())
            }
        }
        val session = session(process)
        session.start()

        process.forceStop()

        await { session.state() == InteractiveCodexSessionState.FAILED }
    }

    @Test
    fun `conflicting completed turn id fails closed`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "config/read" -> respondConfig(request)
                "thread/start" -> respond(request, threadStartResult())
                "turn/start" -> {
                    respond(request, turnStartResult("turn-expected"))
                    notify("turn/completed", turnCompleted("turn-other", "completed"))
                }
            }
        }
        val session = session(process)
        session.start()

        runCatching { session.send("check correlation") }

        await { session.state() == InteractiveCodexSessionState.FAILED }
    }

    private fun session(
        process: FakeProcess,
        environment: Map<String, String> = emptyMap(),
        capturedEnvironment: AtomicReference<Map<String, String>> = AtomicReference(),
        events: MutableList<InteractiveCodexEvent> = CopyOnWriteArrayList(),
    ): InteractiveCodexSession = InteractiveCodexSession(
        executable = "fake-codex",
        cwd = tempDir.toAbsolutePath(),
        environment = environment,
        clientVersion = "test-version",
        listener = InteractiveCodexSessionListener(events::add),
        processFactory = CodexAppServerConnectionProcessFactory { _, _, passedEnvironment ->
            capturedEnvironment.set(passedEnvironment.toMap())
            process
        },
    ).also(sessions::add)

    private fun fakeProcess(handler: FakeProcess.(JsonObject) -> Unit): FakeProcess = FakeProcess()
        .also {
            processes += it
            it.start { request ->
                when (request.method()) {
                    "skills/list" -> respond(
                        request,
                        buildJsonObject {
                            putJsonArray("data") {
                                add(buildJsonObject {
                                    put("cwd", tempDir.toString())
                                    putJsonArray("skills") { add(buildJsonObject { put("name", "test-skill") }) }
                                    putJsonArray("errors") {}
                                })
                            }
                        },
                    )
                    "plugin/installed" -> respond(
                        request,
                        buildJsonObject {
                            putJsonArray("marketplaces") {
                                add(buildJsonObject {
                                    put("name", "local")
                                    putJsonArray("plugins") { add(buildJsonObject { put("id", "test-plugin") }) }
                                })
                            }
                            putJsonArray("marketplaceLoadErrors") {}
                        },
                    )
                    else -> handler(request)
                }
            }
        }

    private fun FakeProcess.respondInitialize(request: JsonObject) {
        respond(
            request,
            buildJsonObject {
                put("codexHome", "/normal-user-codex-home")
                put("userAgent", "codex-test")
            },
        )
    }

    private fun FakeProcess.respondConfig(request: JsonObject) {
        respond(request, buildJsonObject { putJsonObject("config") { put("model", "gpt-test") } })
    }

    private fun threadStartResult(
        profile: String = "workspace",
        sandbox: String = "workspaceWrite",
        approval: String = "on-request",
    ): JsonObject = buildJsonObject {
        put("cwd", tempDir.toAbsolutePath().toString())
        put("approvalPolicy", approval)
        putJsonObject("activePermissionProfile") { put("id", profile) }
        putJsonObject("sandbox") {
            put("type", sandbox)
            put("networkAccess", "restricted")
        }
        putJsonObject("thread") {
            put("id", "thread-1")
            put("ephemeral", true)
            put("cwd", tempDir.toAbsolutePath().toString())
        }
        putJsonArray("instructionSources") {
            add(JsonPrimitive(tempDir.resolve("AGENTS.md").toString()))
        }
    }

    private fun turnStartResult(turnId: String): JsonObject = buildJsonObject {
        putJsonObject("turn") {
            put("id", turnId)
            put("status", "inProgress")
        }
    }

    private fun turnCompleted(turnId: String, status: String): JsonObject = buildJsonObject {
        put("threadId", "thread-1")
        putJsonObject("turn") {
            put("id", turnId)
            put("status", status)
            putJsonArray("items") {}
        }
    }

    private fun awaitApproval(
        events: List<InteractiveCodexEvent>,
        method: String,
    ): InteractiveCodexApprovalRequest {
        await { events.filterIsInstance<InteractiveCodexEvent.ApprovalRequested>().any { it.request.method == method } }
        return events.filterIsInstance<InteractiveCodexEvent.ApprovalRequested>()
            .first { it.request.method == method }
            .request
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for condition")
    }

    private fun JsonObject.method(): String? = string("method")
    private fun JsonObject.params(): JsonObject = getValue("params").jsonObject
    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.prompt(): String = getValue("input").let { input ->
        (input as kotlinx.serialization.json.JsonArray).first().jsonObject.string("text")!!
    }

    private inner class FakeProcess : Process() {
        private val clientOutput = PipedOutputStream()
        private val serverInput = PipedInputStream(clientOutput)
        private val serverOutput = PipedOutputStream()
        private val clientInput = PipedInputStream(serverOutput)
        private val observed = CopyOnWriteArrayList<JsonObject>()
        private val alive = AtomicBoolean(true)
        private val stopped = CountDownLatch(1)
        private val handlerFailure = AtomicReference<Throwable>()

        fun start(handler: FakeProcess.(JsonObject) -> Unit) {
            Thread(
                {
                    try {
                        serverInput.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                            lines.forEach { line ->
                                val message = Json.parseToJsonElement(line).jsonObject
                                observed += message
                                handler(message)
                            }
                        }
                    } catch (error: Throwable) {
                        if (alive.get()) handlerFailure.compareAndSet(null, error)
                    }
                },
                "Fake interactive Codex App Server",
            ).apply {
                isDaemon = true
                start()
            }
        }

        fun respond(request: JsonObject, result: JsonElement) {
            send(buildJsonObject {
                put("id", request.getValue("id"))
                put("result", result)
            })
        }

        fun notify(method: String, params: JsonObject) {
            send(buildJsonObject {
                put("method", method)
                put("params", params)
            })
        }

        fun serverRequest(id: String, method: String, params: JsonObject) {
            send(buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
            })
        }

        fun requests(): List<JsonObject> = observed.toList()
        fun methods(): List<String> = observed.mapNotNull { it.method() }

        fun awaitRequest(method: String): JsonObject {
            awaitObserved { it.method() == method }?.let { return it }
            throw AssertionError("Timed out waiting for $method")
        }

        fun awaitMessage(id: String): JsonObject {
            awaitObserved { it.string("id") == id && it.containsKey("result") }?.let { return it }
            throw AssertionError("Timed out waiting for response $id")
        }

        fun awaitError(id: String): JsonObject {
            awaitObserved { it.string("id") == id && it.containsKey("error") }?.let { return it }
            throw AssertionError("Timed out waiting for error response $id")
        }

        private fun awaitObserved(predicate: (JsonObject) -> Boolean): JsonObject? {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < deadline) {
                handlerFailure.get()?.let { throw AssertionError("Fake process failed", it) }
                observed.firstOrNull(predicate)?.let { return it }
                Thread.sleep(10)
            }
            return null
        }

        fun forceStop() {
            if (!alive.compareAndSet(true, false)) return
            stopped.countDown()
            runCatching { clientOutput.close() }
            runCatching { serverInput.close() }
            runCatching { serverOutput.close() }
            runCatching { clientInput.close() }
        }

        override fun getOutputStream(): OutputStream = clientOutput
        override fun getInputStream(): InputStream = clientInput
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            stopped.await()
            return 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = stopped.await(timeout, unit)
        override fun exitValue(): Int = if (alive.get()) throw IllegalThreadStateException() else 0
        override fun destroy() = forceStop()
        override fun destroyForcibly(): Process = apply { forceStop() }
        override fun isAlive(): Boolean = alive.get()

        @Synchronized
        private fun send(message: JsonObject) {
            if (!alive.get()) return
            serverOutput.write((message.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
            serverOutput.flush()
        }
    }
}
