package emohce.data.commitmessage

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CodexAppServerClientTest {
    @TempDir
    lateinit var tempDir: Path

    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val clients = CopyOnWriteArrayList<CodexAppServerClient>()
    private val processes = CopyOnWriteArrayList<FakeProcess>()

    @AfterEach
    fun tearDown() {
        clients.forEach { runCatching { it.close() } }
        processes.forEach(FakeProcess::forceStop)
        executor.shutdownNow()
    }

    @Test
    fun `initialize and initialized precede the first stable request`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "account/read" -> respond(
                    request,
                    buildJsonObject {
                        put("account", JsonPrimitive("ignored-shape"))
                        put("requiresOpenaiAuth", true)
                        put("unknown", "accepted")
                    },
                )
            }
        }
        val seenFactoryArguments = AtomicReference<List<String>>()
        val client = client(
            CodexAppServerProcessFactory { executable, home, cwd ->
                seenFactoryArguments.set(listOf(executable, home.toString(), cwd.toString()))
                process
            },
        )

        val account = client.accountRead()

        assertEquals(listOf("initialize", "initialized", "account/read"), process.methods())
        val initialize = process.requests()[0]
        assertEquals(1L, initialize.getValue("id").jsonPrimitive.long)
        assertEquals("test-version", initialize.params().getValue("clientInfo").jsonObject
            .getValue("version").jsonPrimitive.content)
        assertTrue(initialize.params().getValue("capabilities").jsonObject
            .getValue("experimentalApi").jsonPrimitive.boolean)
        assertFalse(initialize.containsKey("jsonrpc"))
        assertEquals(listOf("fake-codex", TEST_HOME.toString(), TEST_CWD.toString()), seenFactoryArguments.get())
        assertEquals(true, account.requiresOpenAiAuth)
        assertNull(account.type)
    }

    @Test
    fun `interleaved numeric ids route account and model responses correctly`() {
        val accountRequest = AtomicReference<JsonObject>()
        val modelRequest = AtomicReference<JsonObject>()
        val responsesSent = AtomicBoolean()
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "account/read" -> accountRequest.set(request)
                "model/list" -> modelRequest.set(request)
            }
            val account = accountRequest.get()
            val models = modelRequest.get()
            if (account != null && models != null && responsesSent.compareAndSet(false, true)) {
                notify("account/updated", buildJsonObject { put("authMode", "chatgpt") })
                respond(models, modelListResult())
                respond(account, accountResult())
            }
        }
        val client = client(process)

        val accountFuture = executor.submit<CodexAppServerAccount> { client.accountRead() }
        val modelsFuture = executor.submit<List<CodexAppServerModel>> { client.modelList() }
        val account = accountFuture.await()
        val models = modelsFuture.await()

        assertEquals("chatgpt", account.type)
        assertEquals("person@example.test", account.email)
        assertEquals("plus", account.planType)
        assertFalse(account.requiresOpenAiAuth)
        assertEquals(
            CodexAppServerModel(
                id = "gpt-test",
                displayName = "GPT Test",
                isDefault = true,
                defaultReasoningEffort = "medium",
                reasoningEfforts = listOf("low", "medium"),
                inputModalities = listOf("text", "image"),
            ),
            models.single(),
        )
        val accountId = accountRequest.get().getValue("id").jsonPrimitive.long
        val modelId = modelRequest.get().getValue("id").jsonPrimitive.long
        assertNotEquals(accountId, modelId)
        assertTrue(accountId > 0)
        assertTrue(modelId > 0)
    }

    @Test
    fun `model pagination deduplicates early and preserves first-seen order`() {
        val firstDisplay = "a".repeat(600_000)
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "model/list" -> when (request.params().string("cursor")) {
                    null -> respond(
                        request,
                        modelListResult(
                            models = listOf(modelResult("model-a", firstDisplay)),
                            nextCursor = "page-2",
                        ),
                    )
                    "page-2" -> respond(
                        request,
                        modelListResult(
                            models = listOf(
                                modelResult("model-a", "b".repeat(600_000)),
                                modelResult("model-b", "Model B"),
                            ),
                        ),
                    )
                }
            }
        }
        val client = client(process)

        val models = client.modelList()

        assertEquals(listOf("model-a", "model-b"), models.map(CodexAppServerModel::id))
        assertEquals(firstDisplay, models.first().displayName)
        assertEquals("Model B", models.last().displayName)
        assertEquals("page-2", process.requests().last { it.method() == "model/list" }.params().string("cursor"))
    }

    @Test
    fun `model pagination rejects oversized cursors`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "model/list" -> respond(
                    request,
                    modelListResult(emptyList(), nextCursor = "x".repeat(5_000)),
                )
            }
        }
        val client = client(process)

        val error = assertThrows(CodexAppServerException::class.java) { client.modelList() }

        assertEquals(CodexAppServerErrorKind.PROTOCOL, error.kind)
    }

    @Test
    fun `model pagination rejects an oversized retained aggregate`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "model/list" -> respond(
                    request,
                    modelListResult(listOf(modelResult("large-model", "x".repeat(1_100_000)))),
                )
            }
        }
        val client = client(process)

        val error = assertThrows(CodexAppServerException::class.java) { client.modelList() }

        assertEquals(CodexAppServerErrorKind.PROTOCOL, error.kind)
    }

    @Test
    fun `browser and device login support cancel and logout`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "account/login/start" -> when (request.params().string("type")) {
                    "chatgpt" -> respond(
                        request,
                        buildJsonObject {
                            put("type", "chatgpt")
                            put("loginId", "browser-login")
                            put("authUrl", "https://auth.example.test/browser")
                            put("unknown", 1)
                        },
                    )

                    "chatgptDeviceCode" -> respond(
                        request,
                        buildJsonObject {
                            put("type", "chatgptDeviceCode")
                            put("loginId", "device-login")
                            put("verificationUrl", "https://auth.example.test/device")
                            put("userCode", "ABCD-EFGH")
                        },
                    )
                }

                "account/login/cancel" -> respond(
                    request,
                    buildJsonObject { put("status", "canceled") },
                )

                "account/logout" -> respond(request, buildJsonObject {})
            }
        }
        val client = client(process)

        val browser = client.browserLoginStart()
        val device = client.deviceLoginStart()
        process.notify(
            "account/login/completed",
            buildJsonObject {
                put("loginId", browser.loginId)
                put("success", true)
                put("error", kotlinx.serialization.json.JsonNull)
            },
        )
        val completed = client.awaitLogin(browser.loginId, indicator())
        client.loginCancel(device.loginId)
        client.logout()

        assertEquals(
            CodexAppServerBrowserLogin(
                loginId = "browser-login",
                authUrl = "https://auth.example.test/browser",
            ),
            browser,
        )
        assertEquals(
            CodexAppServerDeviceLogin(
                loginId = "device-login",
                verificationUrl = "https://auth.example.test/device",
                userCode = "ABCD-EFGH",
            ),
            device,
        )
        assertEquals(CodexAppServerLoginCompleted(browser.loginId, true, null), completed)
        assertEquals(
            listOf(
                "initialize",
                "initialized",
                "account/login/start",
                "account/login/start",
                "account/login/cancel",
                "account/logout",
            ),
            process.methods(),
        )
        assertEquals("device-login", process.awaitRequest("account/login/cancel").params().string("loginId"))
        assertEquals(JsonNull, process.awaitRequest("account/logout")["params"])
    }

    @Test
    fun `incomplete login wait cancels the flow and terminates its process`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "account/login/start" -> respond(
                    request,
                    buildJsonObject {
                        put("type", "chatgpt")
                        put("loginId", "incomplete-login")
                        put("authUrl", "https://auth.example.test/incomplete")
                    },
                )
            }
        }
        val client = client(process)
        val login = client.browserLoginStart()

        assertThrows(ProcessCanceledException::class.java) {
            client.awaitLogin(login.loginId, indicator(AtomicBoolean(true)))
        }

        assertEquals("incomplete-login", process.awaitRequest("account/login/cancel").params().string("loginId"))
        process.awaitStopped()
        assertTrue(process.forceCalled.get())
    }

    @Test
    fun `delta alone does not finish and completed agent message is preferred at turn completion`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "thread/start" -> respond(request, threadStartResult(request, "thread-success"))
                "turn/start" -> {
                    respond(request, turnStartResult("turn-success"))
                    notify(
                        "item/started",
                        startedAgentParams("thread-success", "turn-success", "message"),
                    )
                    notify(
                        "item/agentMessage/delta",
                        deltaParams("thread-success", "turn-success", "message", "partial"),
                    )
                }
            }
        }
        val client = client(process)
        val result = executor.submit<String> {
            client.complete(completionRequest(), indicator())
        }

        process.awaitRequest("turn/start")
        Thread.sleep(100)
        assertFalse(result.isDone)
        process.notify(
            "item/completed",
            completedAgentParams(
                threadId = "thread-success",
                turnId = "turn-success",
                text = "final completed text",
            ),
        )
        process.notify(
            "turn/completed",
            turnCompletedParams("thread-success", "turn-success", "completed"),
        )

        assertEquals("final completed text", result.await())
    }

    @Test
    fun `completion sends fresh isolated thread and restrictive turn schema`() {
        val process = successfulCompletionProcess(
            threadId = "thread-isolated",
            turnId = "turn-isolated",
            text = "{\"type\":\"feat\"}",
        )
        val client = client(process)
        val outputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("type") { put("type", "string") }
            }
            putJsonArray("required") { add(JsonPrimitive("type")) }
        }

        val result = client.complete(
            completionRequest().copy(
                outputSchema = outputSchema,
                reasoningEffort = "low",
                serviceName = "EzCodeMark Test",
            ),
            indicator(),
        )

        assertEquals("{\"type\":\"feat\"}", result)
        val thread = process.awaitRequest("thread/start").params()
        assertEquals("gpt-test", thread.string("model"))
        assertTrue(thread.getValue("ephemeral").jsonPrimitive.boolean)
        assertEquals(TEST_CWD.toString(), thread.string("cwd"))
        assertTrue(Path.of(thread.string("cwd")!!).isAbsolute)
        assertEquals("never", thread.string("approvalPolicy"))
        assertFalse(thread.containsKey("sandbox"))
        val config = thread.getValue("config").jsonObject
        assertEquals(0, config.getValue("project_doc_max_bytes").jsonPrimitive.int)
        assertEquals("disabled", config.string("web_search"))
        val permissionProfileId = config.string("default_permissions")!!
        assertTrue(permissionProfileId.startsWith("ezcodemark-isolated-"))
        assertFalse(config.getValue("include_environment_context").jsonPrimitive.boolean)
        assertEquals("low", config.string("model_reasoning_effort"))
        assertEquals("none", config.getValue("history").jsonObject.string("persistence"))
        assertTrue(config.getValue("mcp_servers").jsonObject.isEmpty())
        val permissionProfile = config.getValue("permissions").jsonObject
            .getValue(permissionProfileId).jsonObject
        val filesystem = permissionProfile.getValue("filesystem").jsonObject
        assertEquals("deny", filesystem.string(":root"))
        assertEquals("read", filesystem.string(":minimal"))
        assertEquals("read", filesystem.string(TEST_CWD.toString()))
        assertFalse(permissionProfile.getValue("network").jsonObject.getValue("enabled").jsonPrimitive.boolean)
        assertFalse(
            config.getValue("features").jsonObject
                .getValue("shell_tool").jsonPrimitive.boolean,
        )
        assertEquals("EzCodeMark Test", thread.string("serviceName"))
        assertEquals("developer-only", thread.string("developerInstructions"))

        val turn = process.awaitRequest("turn/start").params()
        assertEquals("thread-isolated", turn.string("threadId"))
        assertFalse(turn.containsKey("model"))
        assertFalse(turn.containsKey("approvalPolicy"))
        assertFalse(turn.containsKey("cwd"))
        assertFalse(turn.containsKey("effort"))
        assertFalse(turn.containsKey("sandboxPolicy"))
        assertEquals(outputSchema, turn.getValue("outputSchema"))
        val input = turn.getValue("input").jsonArray
        assertEquals(1, input.size)
        assertEquals("text", input.single().jsonObject.string("type"))
        assertEquals("one text input", input.single().jsonObject.string("text"))
    }

    @Test
    fun `nonempty instruction sources are rejected before turn start without leaking paths`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "thread/start" -> respond(
                    request,
                    threadStartResult(request, "thread-rejected", listOf("/private/secret/instructions.md")),
                )
            }
        }
        val client = client(process)

        val error = assertThrows(CodexAppServerException::class.java) {
            client.complete(completionRequest(), indicator())
        }

        assertEquals(CodexAppServerErrorKind.ISOLATION, error.kind)
        assertFalse(error.message.orEmpty().contains("/private/secret"))
        assertFalse(process.methods().contains("turn/start"))
    }

    @Test
    fun `tool item start terminates the process and fails completion`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "thread/start" -> respond(request, threadStartResult(request, "thread-tool"))
                "turn/start" -> {
                    respond(request, turnStartResult("turn-tool"))
                    notify(
                        "item/started",
                        buildJsonObject {
                            put("threadId", "thread-tool")
                            put("turnId", "turn-tool")
                            put("startedAtMs", 1)
                            putJsonObject("item") {
                                put("type", "commandExecution")
                                put("id", "command-1")
                                put("command", "sensitive command text")
                            }
                        },
                    )
                }
            }
        }
        val client = client(process)

        val error = assertThrows(CodexAppServerException::class.java) {
            client.complete(completionRequest(), indicator())
        }

        assertEquals(CodexAppServerErrorKind.TOOL_USE, error.kind)
        assertFalse(error.message.orEmpty().contains("sensitive command"))
        process.awaitStopped()
        assertTrue(process.forceCalled.get())
        assertFalse(process.methods().contains("turn/interrupt"))
    }

    @Test
    fun `plan update terminates the process and fails completion`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "thread/start" -> respond(request, threadStartResult(request, "thread-plan"))
                "turn/start" -> {
                    respond(request, turnStartResult("turn-plan"))
                    notify(
                        "turn/plan/updated",
                        buildJsonObject {
                            put("threadId", "thread-plan")
                            put("turnId", "turn-plan")
                            putJsonArray("plan") {}
                        },
                    )
                }
            }
        }
        val client = client(process)

        val error = assertThrows(CodexAppServerException::class.java) {
            client.complete(completionRequest(), indicator())
        }

        assertEquals(CodexAppServerErrorKind.TOOL_USE, error.kind)
        process.awaitStopped()
        assertTrue(process.forceCalled.get())
    }

    @Test
    fun `unknown turn item fails closed as tool use`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "thread/start" -> respond(request, threadStartResult(request, "thread-future-tool"))
                "turn/start" -> {
                    respond(request, turnStartResult("turn-future-tool"))
                    notify(
                        "item/started",
                        buildJsonObject {
                            put("threadId", "thread-future-tool")
                            put("turnId", "turn-future-tool")
                            putJsonObject("item") {
                                put("type", "futureToolCall")
                                put("id", "future-tool")
                            }
                        },
                    )
                }
            }
        }
        val client = client(process)

        val error = assertThrows(CodexAppServerException::class.java) {
            client.complete(completionRequest(), indicator())
        }

        assertEquals(CodexAppServerErrorKind.TOOL_USE, error.kind)
        process.awaitStopped()
        assertTrue(process.forceCalled.get())
    }

    @Test
    fun `unknown notification fails closed as a protocol error`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "account/read" -> notify("future/notification", buildJsonObject { put("unknown", true) })
            }
        }
        val client = client(process)

        val error = assertThrows(CodexAppServerException::class.java) { client.accountRead() }

        assertEquals(CodexAppServerErrorKind.PROTOCOL, error.kind)
        process.awaitStopped()
        assertTrue(process.forceCalled.get())
    }

    @Test
    fun `assistant output above the request bound terminates the process`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "thread/start" -> respond(request, threadStartResult(request, "thread-output-limit"))
                "turn/start" -> {
                    respond(request, turnStartResult("turn-output-limit"))
                    notify(
                        "item/started",
                        startedAgentParams("thread-output-limit", "turn-output-limit", "message"),
                    )
                    notify(
                        "item/completed",
                        completedAgentParams("thread-output-limit", "turn-output-limit", "123456789"),
                    )
                }
            }
        }
        val client = client(process)

        val error = assertThrows(CodexAppServerException::class.java) {
            client.complete(completionRequest().copy(maxOutputTokens = 1), indicator())
        }

        assertEquals(CodexAppServerErrorKind.PROTOCOL, error.kind)
        process.awaitStopped()
        assertTrue(process.forceCalled.get())
    }

    @Test
    fun `isolated environment keeps only runtime essentials and isolated paths`() {
        val environment = linkedMapOf(
            "PATH" to "/usr/bin",
            "LANG" to "en_US.UTF-8",
            "DATABASE_URL" to "postgres://secret",
            "SSH_AUTH_SOCK" to "/private/agent.sock",
            "PWD" to "/private/project",
            "CUSTOM_SECRET_NAME" to "secret",
        )

        isolateCodexEnvironment(environment, TEST_HOME)

        assertEquals("/usr/bin", environment["PATH"])
        assertEquals("en_US.UTF-8", environment["LANG"])
        assertEquals(TEST_HOME.toString(), environment["CODEX_HOME"])
        assertEquals(TEST_HOME.resolve("tmp").toString(), environment["TMPDIR"])
        assertFalse(environment.containsKey("DATABASE_URL"))
        assertFalse(environment.containsKey("SSH_AUTH_SOCK"))
        assertFalse(environment.containsKey("PWD"))
        assertFalse(environment.containsKey("CUSTOM_SECRET_NAME"))
    }

    @Test
    fun `version probes use the same isolated process environment`() {
        val home = tempDir.resolve("home")
        val cwd = tempDir.resolve("workspace")
        val processBuilder = ProcessBuilder("codex", "--version").apply {
            environment()["PATH"] = "/usr/bin"
            environment()["DATABASE_URL"] = "postgres://secret"
            environment()["SSH_AUTH_SOCK"] = "/private/agent.sock"
        }
        val builder = codexVersionProcessBuilder(processBuilder, home, cwd)

        assertEquals(cwd.toFile(), builder.directory())
        assertEquals(home.toAbsolutePath().normalize().toString(), builder.environment()["CODEX_HOME"])
        assertEquals("/usr/bin", builder.environment()["PATH"])
        assertFalse(builder.environment().containsKey("DATABASE_URL"))
        assertFalse(builder.environment().containsKey("SSH_AUTH_SOCK"))
        assertTrue(Files.isDirectory(home.resolve("tmp")))
        assertTrue(Files.isDirectory(home.resolve("config")))
    }

    @Test
    fun `server initiated tool request is rejected and terminates the process`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "thread/start" -> respond(request, threadStartResult(request, "thread-request"))
                "turn/start" -> {
                    respond(request, turnStartResult("turn-request"))
                    serverRequest(
                        id = "server-tool-1",
                        method = "item/commandExecution/requestApproval",
                        params = buildJsonObject {
                            put("threadId", "thread-request")
                            put("turnId", "turn-request")
                        },
                    )
                }
            }
        }
        val client = client(process)

        val error = assertThrows(CodexAppServerException::class.java) {
            client.complete(completionRequest(), indicator())
        }

        assertEquals(CodexAppServerErrorKind.TOOL_USE, error.kind)
        process.awaitStopped()
        assertTrue(process.requests().any { request ->
            request["id"]?.jsonPrimitive?.content == "server-tool-1" && request.containsKey("error")
        })
    }

    @Test
    fun `thread response must confirm effective sandbox cwd approval and ephemeral state`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "thread/start" -> respond(
                    request,
                    threadStartResult(request, "thread-mismatch", cwd = TEST_ROOT.resolve("unexpected")),
                )
            }
        }
        val client = client(process)

        val error = assertThrows(CodexAppServerException::class.java) {
            client.complete(completionRequest(), indicator())
        }

        assertEquals(CodexAppServerErrorKind.ISOLATION, error.kind)
        assertFalse(process.methods().contains("turn/start"))
        process.awaitStopped()
    }

    @Test
    fun `thread response requires an explicit false network access value`() {
        val invalidValues = listOf<JsonElement?>(
            null,
            JsonNull,
            buildJsonObject {},
            JsonPrimitive("false"),
            JsonPrimitive(true),
        )

        invalidValues.forEachIndexed { index, networkAccess ->
            val process = fakeProcess { request ->
                when (request.method()) {
                    "initialize" -> respondInitialize(request)
                    "thread/start" -> respond(
                        request,
                        threadStartResult(
                            request = request,
                            threadId = "thread-network-$index",
                            networkAccess = networkAccess,
                        ),
                    )
                }
            }
            val client = client(process)

            val error = assertThrows(CodexAppServerException::class.java) {
                client.complete(completionRequest(), indicator())
            }

            assertEquals(CodexAppServerErrorKind.ISOLATION, error.kind)
            assertFalse(process.methods().contains("turn/start"))
            process.awaitStopped()
        }
    }

    @Test
    fun `thread response must confirm the exact isolated permission profile`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "thread/start" -> respond(
                    request,
                    threadStartResult(
                        request = request,
                        threadId = "thread-profile-mismatch",
                        permissionProfileId = "unexpected-profile",
                    ),
                )
            }
        }
        val client = client(process)

        val error = assertThrows(CodexAppServerException::class.java) {
            client.complete(completionRequest(), indicator())
        }

        assertEquals(CodexAppServerErrorKind.ISOLATION, error.kind)
        assertFalse(process.methods().contains("turn/start"))
        process.awaitStopped()
    }

    @Test
    fun `system command disables local web mcp shell browser and delegation tools`() {
        val command = codexAppServerCommand("codex")

        assertTrue(command.containsAll(listOf("--strict-config", "web_search=\"disabled\"", "mcp_servers={}")))
        listOf("shell_tool", "unified_exec", "browser_use", "computer_use", "multi_agent", "plugins", "hooks")
            .forEach { feature ->
                val index = command.indexOf(feature)
                assertTrue(index > 0 && command[index - 1] == "--disable", feature)
            }
    }

    @Test
    fun `indicator cancellation explicitly interrupts the active turn`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "thread/start" -> respond(request, threadStartResult(request, "thread-cancel"))
                "turn/start" -> respond(request, turnStartResult("turn-cancel"))
                "turn/interrupt" -> respond(request, buildJsonObject {})
            }
        }
        val client = client(process)
        val canceled = AtomicBoolean()
        val indicator = indicator(canceled)
        val result = executor.submit<String> { client.complete(completionRequest(), indicator) }
        process.awaitRequest("turn/start")
        Thread.sleep(100)

        canceled.set(true)

        val error = assertThrows(ExecutionException::class.java) { result.get(3, TimeUnit.SECONDS) }
        assertInstanceOf(ProcessCanceledException::class.java, error.cause)
        val interrupt = process.awaitRequest("turn/interrupt")
        assertEquals("thread-cancel", interrupt.params().string("threadId"))
        assertEquals("turn-cancel", interrupt.params().string("turnId"))
    }

    @Test
    fun `cancellation while thread start is unresolved terminates the process`() {
        val process = fakeProcess { request ->
            if (request.method() == "initialize") respondInitialize(request)
        }
        val client = client(process)
        val canceled = AtomicBoolean()
        val result = executor.submit<String> { client.complete(completionRequest(), indicator(canceled)) }
        process.awaitRequest("thread/start")

        canceled.set(true)

        val error = assertThrows(ExecutionException::class.java) { result.get(3, TimeUnit.SECONDS) }
        assertInstanceOf(ProcessCanceledException::class.java, error.cause)
        process.awaitStopped()
        assertTrue(process.forceCalled.get())
        assertFalse(process.methods().contains("turn/start"))
    }

    @Test
    fun `cancellation while turn start is unresolved terminates the process`() {
        val process = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "thread/start" -> respond(request, threadStartResult(request, "thread-start-cancel"))
            }
        }
        val client = client(process)
        val canceled = AtomicBoolean()
        val result = executor.submit<String> { client.complete(completionRequest(), indicator(canceled)) }
        process.awaitRequest("turn/start")

        canceled.set(true)

        val error = assertThrows(ExecutionException::class.java) { result.get(3, TimeUnit.SECONDS) }
        assertInstanceOf(ProcessCanceledException::class.java, error.cause)
        process.awaitStopped()
        assertTrue(process.forceCalled.get())
        assertFalse(process.methods().contains("turn/interrupt"))
    }

    @Test
    fun `failed and interrupted turns remain distinct`() {
        val failedProcess = terminalTurnProcess("failed")
        val failedClient = client(failedProcess)
        val failed = assertThrows(CodexAppServerException::class.java) {
            failedClient.complete(completionRequest(), indicator())
        }

        val interruptedProcess = terminalTurnProcess("interrupted")
        val interruptedClient = client(interruptedProcess)
        val interrupted = assertThrows(CodexAppServerException::class.java) {
            interruptedClient.complete(completionRequest(), indicator())
        }

        assertEquals(CodexAppServerErrorKind.TURN_FAILED, failed.kind)
        assertEquals(CodexAppServerErrorKind.TURN_INTERRUPTED, interrupted.kind)
        assertNotEquals(failed.message, interrupted.message)
    }

    @Test
    fun `process eof fails in flight and restarts once only for a new request`() {
        val first = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "account/read" -> closeStdout()
            }
        }
        val second = fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "account/read" -> respond(request, accountResult())
            }
        }
        val starts = AtomicInteger()
        val queue = ArrayDeque(listOf(first, second))
        val client = client(
            CodexAppServerProcessFactory { _, _, _ ->
                starts.incrementAndGet()
                synchronized(queue) { queue.removeFirst() }
            },
        )

        val eof = assertThrows(CodexAppServerException::class.java) { client.accountRead() }
        assertEquals(CodexAppServerErrorKind.PROCESS, eof.kind)
        assertEquals(1, starts.get())
        assertEquals(1, first.methods().count { it == "account/read" })

        val account = client.accountRead()
        assertEquals("chatgpt", account.type)
        assertEquals(2, starts.get())
        assertEquals(1, second.methods().count { it == "account/read" })

        second.closeStdout()
        second.awaitStopped()
        val unavailable = assertThrows(CodexAppServerException::class.java) { client.accountRead() }
        assertEquals(CodexAppServerErrorKind.PROCESS, unavailable.kind)
        assertEquals(2, starts.get())
    }

    @Test
    fun `close cancels login interrupts turn closes stdin and avoids force after graceful exit`() {
        val process = fakeProcess(exitOnStdinClose = true) { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "account/login/start" -> respond(
                    request,
                    buildJsonObject {
                        put("type", "chatgpt")
                        put("loginId", "pending-login")
                        put("authUrl", "https://auth.example.test")
                    },
                )

                "thread/start" -> respond(request, threadStartResult(request, "thread-close"))
                "turn/start" -> respond(request, turnStartResult("turn-close"))
            }
        }
        val client = client(process)
        client.browserLoginStart()
        val completion = executor.submit<String> {
            client.complete(completionRequest(), indicator())
        }
        process.awaitRequest("turn/start")
        Thread.sleep(100)

        client.close()

        process.awaitRequest("account/login/cancel")
        process.awaitRequest("turn/interrupt")
        process.awaitStopped()
        assertFalse(process.destroyCalled.get())
        assertFalse(process.forceCalled.get())
        val completionFailure = assertThrows(ExecutionException::class.java) {
            completion.get(3, TimeUnit.SECONDS)
        }
        assertEquals(
            CodexAppServerErrorKind.CLOSED,
            (completionFailure.cause as CodexAppServerException).kind,
        )
        assertThrows(CodexAppServerException::class.java) { client.accountRead() }
    }

    @Test
    fun `close destroys and only then forces a process that will not exit`() {
        val process = fakeProcess(
            exitOnStdinClose = false,
            destroyTerminates = false,
        ) { request ->
            if (request.method() == "initialize") respondInitialize(request)
            if (request.method() == "account/read") respond(request, accountResult())
        }
        val client = client(process)
        client.accountRead()

        client.close()

        assertTrue(process.destroyCalled.get())
        assertTrue(process.forceCalled.get())
        assertFalse(process.isAlive)
    }

    @Test
    fun `account mutation invalidates before mutation and aborts every exceptional exit`() {
        val events = mutableListOf<String>()
        val failure = assertThrows(IllegalStateException::class.java) {
            runCodexAccountMutation(
                invalidateGeneration = { events += "invalidate" },
                abort = { events += "abort" },
            ) {
                events += "mutate"
                throw IllegalStateException("failed mutation")
            }
        }

        assertEquals("failed mutation", failure.message)
        assertEquals(listOf("invalidate", "mutate", "abort"), events)

        events.clear()
        assertThrows(IllegalArgumentException::class.java) {
            runCodexAccountMutation(
                invalidateGeneration = {
                    events += "invalidate"
                    throw IllegalArgumentException("persistence failed")
                },
                abort = { events += "abort" },
            ) {
                events += "mutate"
            }
        }
        assertEquals(listOf("invalidate"), events)
    }

    private fun completionRequest() = CodexAppServerCompletionRequest(
        model = "gpt-test",
        text = "one text input",
        developerInstructions = "developer-only",
    )

    private fun indicator(canceled: AtomicBoolean = AtomicBoolean()): ProgressIndicator =
        mockk(relaxed = true) {
            every { checkCanceled() } answers {
                if (canceled.get()) throw ProcessCanceledException()
            }
        }

    private fun terminalTurnProcess(status: String): FakeProcess = fakeProcess { request ->
        when (request.method()) {
            "initialize" -> respondInitialize(request)
            "thread/start" -> respond(request, threadStartResult(request, "thread-$status"))
            "turn/start" -> {
                respond(request, turnStartResult("turn-$status"))
                notify(
                    "turn/completed",
                    turnCompletedParams("thread-$status", "turn-$status", status),
                )
            }
        }
    }

    private fun successfulCompletionProcess(threadId: String, turnId: String, text: String): FakeProcess =
        fakeProcess { request ->
            when (request.method()) {
                "initialize" -> respondInitialize(request)
                "thread/start" -> respond(request, threadStartResult(request, threadId))
                "turn/start" -> {
                    respond(request, turnStartResult(turnId))
                    notify("item/started", startedAgentParams(threadId, turnId, "message"))
                    notify("item/agentMessage/delta", deltaParams(threadId, turnId, "message", "ignored"))
                    notify("item/completed", completedAgentParams(threadId, turnId, text))
                    notify("turn/completed", turnCompletedParams(threadId, turnId, "completed"))
                }
            }
        }

    private fun client(process: FakeProcess): CodexAppServerClient = client(
        CodexAppServerProcessFactory { _, _, _ -> process },
    )

    private fun client(factory: CodexAppServerProcessFactory): CodexAppServerClient =
        CodexAppServerClient(
            processFactory = factory,
            executable = "fake-codex",
            home = TEST_HOME,
            cwd = TEST_CWD,
            version = "test-version",
        ).also(clients::add)

    private fun fakeProcess(
        exitOnStdinClose: Boolean = true,
        destroyTerminates: Boolean = true,
        handler: FakeProcess.(JsonObject) -> Unit,
    ): FakeProcess = FakeProcess(exitOnStdinClose, destroyTerminates)
        .also {
            processes += it
            it.start(handler)
        }

    private fun FakeProcess.respondInitialize(request: JsonObject) {
        respond(
            request,
            buildJsonObject {
                put("userAgent", "codex-test")
                put("codexHome", TEST_HOME.toString())
                put("platformFamily", "unix")
                put("platformOs", "macos")
                put("unknown", true)
            },
        )
    }

    private fun accountResult(): JsonObject = buildJsonObject {
        putJsonObject("account") {
            put("type", "chatgpt")
            put("email", "person@example.test")
            put("planType", "plus")
            put("futureField", "ignored")
        }
        put("requiresOpenaiAuth", false)
    }

    private fun modelListResult(): JsonObject = buildJsonObject {
        putJsonArray("data") {
            add(buildJsonObject {
                put("id", "gpt-test")
                put("model", "gpt-test")
                put("displayName", "GPT Test")
                put("description", "Test model")
                put("hidden", false)
                put("isDefault", true)
                put("defaultReasoningEffort", "medium")
                putJsonArray("supportedReasoningEfforts") {
                    add(buildJsonObject {
                        put("reasoningEffort", "low")
                        put("description", "Low")
                    })
                    add(buildJsonObject {
                        put("reasoningEffort", "medium")
                        put("description", "Medium")
                    })
                }
                putJsonArray("inputModalities") {
                    add(JsonPrimitive("text"))
                    add(JsonPrimitive("image"))
                }
                put("unknown", "accepted")
            })
        }
    }

    private fun modelListResult(models: List<JsonObject>, nextCursor: String? = null): JsonObject =
        buildJsonObject {
            putJsonArray("data") { models.forEach(::add) }
            nextCursor?.let { put("nextCursor", it) }
        }

    private fun modelResult(id: String, displayName: String): JsonObject = buildJsonObject {
        put("id", id)
        put("displayName", displayName)
        putJsonArray("supportedReasoningEfforts") {}
        putJsonArray("inputModalities") { add(JsonPrimitive("text")) }
    }

    private fun threadStartResult(
        request: JsonObject,
        threadId: String,
        instructionSources: List<String> = emptyList(),
        cwd: Path = TEST_CWD,
        permissionProfileId: String = request.params().getValue("config").jsonObject
            .string("default_permissions")!!,
        networkAccess: JsonElement? = JsonPrimitive(false),
    ): JsonObject =
        buildJsonObject {
            put("cwd", cwd.toString())
            put("approvalPolicy", "never")
            putJsonObject("activePermissionProfile") {
                put("id", permissionProfileId)
                put("extends", JsonNull)
            }
            putJsonObject("sandbox") {
                put("type", "readOnly")
                networkAccess?.let { put("networkAccess", it) }
            }
            putJsonObject("thread") {
                put("id", threadId)
                put("ephemeral", true)
                put("cwd", cwd.toString())
                put("unknown", 1)
            }
            putJsonArray("instructionSources") {
                instructionSources.forEach { add(JsonPrimitive(it)) }
            }
            put("unknown", true)
        }

    private fun turnStartResult(turnId: String): JsonObject = buildJsonObject {
        putJsonObject("turn") {
            put("id", turnId)
            put("status", "inProgress")
        }
    }

    private fun deltaParams(threadId: String, turnId: String, itemId: String, delta: String): JsonObject =
        buildJsonObject {
            put("threadId", threadId)
            put("turnId", turnId)
            put("itemId", itemId)
            put("delta", delta)
        }

    private fun startedAgentParams(threadId: String, turnId: String, itemId: String): JsonObject =
        buildJsonObject {
            put("threadId", threadId)
            put("turnId", turnId)
            putJsonObject("item") {
                put("type", "agentMessage")
                put("id", itemId)
                put("text", "")
                put("phase", "final_answer")
            }
        }

    private fun completedAgentParams(threadId: String, turnId: String, text: String): JsonObject =
        buildJsonObject {
            put("threadId", threadId)
            put("turnId", turnId)
            put("completedAtMs", 2)
            putJsonObject("item") {
                put("type", "agentMessage")
                put("id", "message")
                put("text", text)
                put("phase", "final_answer")
            }
        }

    private fun turnCompletedParams(threadId: String, turnId: String, status: String): JsonObject =
        buildJsonObject {
            put("threadId", threadId)
            putJsonObject("turn") {
                put("id", turnId)
                put("status", status)
                putJsonArray("items") {}
                if (status == "failed") {
                    putJsonObject("error") { put("message", "sensitive upstream detail") }
                }
            }
        }

    private fun <T> Future<T>.await(): T = get(3, TimeUnit.SECONDS)

    private fun JsonObject.method(): String? = (get("method") as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.params(): JsonObject = getValue("params").jsonObject
    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private inner class FakeProcess(
        private val exitOnStdinClose: Boolean,
        private val destroyTerminates: Boolean,
    ) : Process() {
        private val clientOutput = PipedOutputStream()
        private val serverInput = PipedInputStream(clientOutput)
        private val serverOutput = PipedOutputStream()
        private val clientInput = PipedInputStream(serverOutput)
        private val observed = CopyOnWriteArrayList<JsonObject>()
        private val alive = AtomicBoolean(true)
        private val stopped = java.util.concurrent.CountDownLatch(1)
        private val handlerFailure = AtomicReference<Throwable>()
        private lateinit var requestThread: Thread

        val destroyCalled = AtomicBoolean()
        val forceCalled = AtomicBoolean()

        fun start(handler: FakeProcess.(JsonObject) -> Unit) {
            requestThread = Thread(
                {
                    try {
                        serverInput.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                            for (line in lines) {
                                val request = Json.parseToJsonElement(line).jsonObject
                                observed += request
                                handler(request)
                            }
                        }
                    } catch (error: Throwable) {
                        if (alive.get()) handlerFailure.compareAndSet(null, error)
                    } finally {
                        if (exitOnStdinClose) terminate()
                    }
                },
                "Fake Codex App Server requests",
            ).apply {
                isDaemon = true
                start()
            }
        }

        fun respond(request: JsonObject, result: JsonElement) {
            send(buildJsonObject {
                put("id", request.getValue("id"))
                put("result", result)
                put("unknown", true)
            })
        }

        fun notify(method: String, params: JsonObject) {
            send(buildJsonObject {
                put("method", method)
                put("params", params)
                put("unknown", true)
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

        fun awaitRequest(method: String, timeoutSeconds: Long = 3): JsonObject {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (System.nanoTime() < deadline) {
                handlerFailure.get()?.let { throw AssertionError("Fake process handler failed", it) }
                observed.firstOrNull { it.method() == method }?.let { return it }
                Thread.sleep(10)
            }
            throw AssertionError("Timed out waiting for $method; observed ${methods()}")
        }

        fun closeStdout() {
            runCatching { serverOutput.close() }
            terminate()
        }

        fun awaitStopped() {
            assertTrue(stopped.await(3, TimeUnit.SECONDS), "Fake process did not stop")
            requestThread.join(1_000)
            handlerFailure.get()?.let { throw AssertionError("Fake process handler failed", it) }
        }

        fun forceStop() {
            terminate()
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

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
            if (exitOnStdinClose || !alive.get()) stopped.await(timeout, unit) else false

        override fun exitValue(): Int {
            if (alive.get()) throw IllegalThreadStateException()
            return 0
        }

        override fun destroy() {
            destroyCalled.set(true)
            if (destroyTerminates) terminate()
        }

        override fun destroyForcibly(): Process {
            forceCalled.set(true)
            terminate()
            return this
        }

        override fun isAlive(): Boolean = alive.get()

        @Synchronized
        private fun send(message: JsonObject) {
            if (!alive.get()) return
            val bytes = (message.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
            serverOutput.write(bytes)
            serverOutput.flush()
        }

        private fun terminate() {
            if (!alive.compareAndSet(true, false)) return
            stopped.countDown()
            runCatching { serverOutput.close() }
        }
    }

    companion object {
        private val TEST_ROOT: Path = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        private val TEST_HOME: Path = TEST_ROOT.resolve("ezcodemark-codex-home")
        private val TEST_CWD: Path = TEST_ROOT.resolve("ezcodemark-codex-neutral")
    }
}
