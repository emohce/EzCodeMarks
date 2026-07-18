package emohce.data.commitmessage

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import emohce.domain.commitmessage.LlmCompletionRequest
import emohce.domain.commitmessage.LlmProfile
import emohce.domain.commitmessage.LlmProviderType
import emohce.domain.commitmessage.ProviderErrorKind
import emohce.domain.commitmessage.ProviderException
import emohce.domain.commitmessage.ProviderRequestBudget
import io.mockk.mockk
import io.mockk.every
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

class HttpLlmProviderClientTest {
    private var server: HttpServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun `openai compatible response and authorization are parsed`() {
        val seenAuthorization = arrayOfNulls<String>(1)
        val seenRequestBody = arrayOfNulls<String>(1)
        val local = startServer("/v1/chat/completions") { exchange ->
            seenAuthorization[0] = exchange.requestHeaders.getFirst("Authorization")
            seenRequestBody[0] = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            exchange.respond(200, "{\"choices\":[{\"message\":{\"content\":\"feat: add preview\"}}]}")
        }
        val result = HttpLlmProviderClient().complete(
            profile = profile(local, streaming = false),
            apiKey = "test-key",
            request = request(streaming = false),
            budget = ProviderRequestBudget(3),
            indicator = indicator(),
        )

        assertEquals("feat: add preview", result.content)
        assertEquals("Bearer test-key", seenAuthorization[0])
        assertEquals(false, seenRequestBody[0].orEmpty().contains("response_format"))
    }

    @Test
    fun `connection test token cap is sent by openai compatible providers`() {
        val requestBody = arrayOfNulls<String>(1)
        val local = startServer("/v1/chat/completions") { exchange ->
            requestBody[0] = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            exchange.respond(200, "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}")
        }

        HttpLlmProviderClient().complete(
            profile(local, streaming = false),
            "test-key",
            request(streaming = false).copy(maxOutputTokens = 8),
            ProviderRequestBudget(3),
            indicator(),
        )

        val body = Json.parseToJsonElement(requestBody[0].orEmpty()).jsonObject
        assertEquals(8, body.getValue("max_tokens").jsonPrimitive.int)
    }

    @Test
    fun `connection test token cap overrides anthropic default`() {
        val requestBody = arrayOfNulls<String>(1)
        val local = startServer("/v1/messages") { exchange ->
            requestBody[0] = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            exchange.respond(200, "{\"content\":[{\"type\":\"text\",\"text\":\"OK\"}]}")
        }

        HttpLlmProviderClient().complete(
            profile(local, LlmProviderType.ANTHROPIC, streaming = false),
            "test-key",
            request(streaming = false).copy(maxOutputTokens = 8),
            ProviderRequestBudget(3),
            indicator(),
        )

        val body = Json.parseToJsonElement(requestBody[0].orEmpty()).jsonObject
        assertEquals(8, body.getValue("max_tokens").jsonPrimitive.int)
    }

    @Test
    fun `full provider endpoints are reused and counterpart endpoints are derived`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            LlmEndpointResolver.openAiCompletion("https://api.openai.com/v1/chat/completions/"),
        )
        assertEquals(
            "https://api.openai.com/v1/models",
            LlmEndpointResolver.openAiModels("https://api.openai.com/v1/chat/completions"),
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            LlmEndpointResolver.openAiCompletion("https://api.openai.com/v1/models"),
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            LlmEndpointResolver.anthropicMessages("https://api.anthropic.com/v1/messages"),
        )
        assertEquals(
            "https://api.anthropic.com/v1/models",
            LlmEndpointResolver.anthropicModels("https://api.anthropic.com/v1/messages"),
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            LlmEndpointResolver.anthropicMessages("https://api.anthropic.com/v1/models"),
        )
    }

    @Test
    fun `reasoning compatibility reduces or disables reasoning only for recognized providers`() {
        val openAi = profile("https://api.openai.com/v1", streaming = false).copy(model = "gpt-5-mini")
        val qwen = profile("https://dashscope.aliyuncs.com/compatible-mode/v1", streaming = false)
            .copy(model = "qwen-plus")
        val officialAnthropic = profile(
            "https://api.anthropic.com",
            provider = LlmProviderType.ANTHROPIC,
            streaming = false,
        ).copy(model = "claude-sonnet-4")
        val gateway = profile(
            "https://api.moonshot.example/v1",
            provider = LlmProviderType.ANTHROPIC,
            streaming = false,
        )

        assertEquals("low", ReasoningCompatibilityPolicy.parameters(openAi)["reasoning_effort"].toString().trim('"'))
        assertEquals("false", ReasoningCompatibilityPolicy.parameters(qwen)["enable_thinking"].toString())
        assertTrue(ReasoningCompatibilityPolicy.parameters(officialAnthropic).isEmpty())
        assertEquals(
            "disabled",
            ReasoningCompatibilityPolicy.parameters(gateway)["thinking"]
                ?.jsonObject?.get("type")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `anthropic sse chunks are joined`() {
        val local = startServer("/v1/messages") { exchange ->
            exchange.respond(
                200,
                """
                    data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"fix"}}

                    data: {"type":"content_block_delta","delta":{"type":"text_delta","text":": preserve draft"}}

                    data: [DONE]

                """.trimIndent(),
                "text/event-stream",
            )
        }
        val result = HttpLlmProviderClient().complete(
            profile = profile(local, LlmProviderType.ANTHROPIC, streaming = true),
            apiKey = "test-key",
            request = request(streaming = true),
            budget = ProviderRequestBudget(3),
            indicator = indicator(),
        )

        assertEquals("fix: preserve draft", result.content)
    }

    @Test
    fun `unsupported reasoning makes one controlled fallback`() {
        val calls = AtomicInteger()
        val requestBodies = mutableListOf<String>()
        val local = startServer("/v1/chat/completions") { exchange ->
            requestBodies += exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            if (calls.incrementAndGet() == 1) {
                exchange.respond(400, "unsupported reasoning parameter")
            } else {
                exchange.respond(200, "{\"choices\":[{\"message\":{\"content\":\"fix: fallback\"}}]}")
            }
        }
        val result = HttpLlmProviderClient().complete(
            profile = profile(local, streaming = false).copy(
                model = "gpt-5-mini",
                reasoningCompatibility = true,
            ),
            apiKey = "test-key",
            request = request(streaming = false).copy(reasoningCompatibility = true),
            budget = ProviderRequestBudget(3),
            indicator = indicator(),
        )

        assertEquals("fix: fallback", result.content)
        assertEquals(2, calls.get())
        assertTrue(requestBodies[0].contains("\"reasoning_effort\":\"low\""))
        assertEquals(false, requestBodies[1].contains("reasoning_effort"))
    }

    @Test
    fun `ambiguous unsupported model error is not retried`() {
        val calls = AtomicInteger()
        val local = startServer("/v1/chat/completions") { exchange ->
            calls.incrementAndGet()
            exchange.respond(400, "unsupported model")
        }

        assertThrows(ProviderException::class.java) {
            HttpLlmProviderClient().complete(
                profile(local, streaming = true).copy(reasoningCompatibility = true),
                "test-key",
                request(streaming = true).copy(reasoningCompatibility = true),
                ProviderRequestBudget(3),
                indicator(),
            )
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun `response headers may take longer than cancellation poll interval`() {
        val local = startServer("/v1/chat/completions") { exchange ->
            Thread.sleep(1_250)
            exchange.respond(200, "{\"choices\":[{\"message\":{\"content\":\"fix: delayed\"}}]}")
        }

        val result = HttpLlmProviderClient().complete(
            profile(local, streaming = false),
            "test-key",
            request(streaming = false),
            ProviderRequestBudget(3),
            indicator(),
        )

        assertEquals("fix: delayed", result.content)
    }

    @Test
    fun `utf8 response remains intact across byte chunks`() {
        val expected = "修复提交信息"
        val body = "{\"choices\":[{\"message\":{\"content\":\"$expected\"}}]}"
            .toByteArray(StandardCharsets.UTF_8)
        val firstCjk = body.indexOfFirst { it.toInt() and 0x80 != 0 }
        val local = startServer("/v1/chat/completions") { exchange ->
            exchange.respondInChunks(200, body, firstCjk + 1)
        }

        val result = HttpLlmProviderClient().complete(
            profile(local, streaming = false),
            "test-key",
            request(streaming = false),
            ProviderRequestBudget(3),
            indicator(),
        )

        assertEquals(expected, result.content)
    }

    @Test
    fun `authentication failure is not retried`() {
        val calls = AtomicInteger()
        val local = startServer("/v1/chat/completions") { exchange ->
            calls.incrementAndGet()
            exchange.respond(401, "invalid api key test-key")
        }

        val error = assertThrows(ProviderException::class.java) {
            HttpLlmProviderClient().complete(
                profile(local, streaming = false),
                "test-key",
                request(streaming = false),
                ProviderRequestBudget(3),
                indicator(),
            )
        }
        assertEquals(ProviderErrorKind.AUTHENTICATION, error.kind)
        assertEquals(1, calls.get())
        assertEquals(false, error.message.orEmpty().contains("test-key"))
    }

    @Test
    fun `cancellation before connect performs no provider request`() {
        val calls = AtomicInteger()
        val local = startServer("/v1/chat/completions") { exchange ->
            calls.incrementAndGet()
            exchange.respond(200, "{}")
        }
        val cancelled = mockk<ProgressIndicator>(relaxed = true)
        every { cancelled.checkCanceled() } throws ProcessCanceledException()

        assertThrows(ProcessCanceledException::class.java) {
            HttpLlmProviderClient().complete(
                profile(local, streaming = false),
                "test-key",
                request(streaming = false),
                ProviderRequestBudget(3),
                cancelled,
            )
        }
        assertEquals(0, calls.get())
    }

    private fun profile(
        baseUrl: String,
        provider: LlmProviderType = LlmProviderType.OPENAI_COMPATIBLE,
        streaming: Boolean,
    ) = LlmProfile(
        provider = provider,
        baseUrl = baseUrl,
        model = "test-model",
        streaming = streaming,
    )

    private fun request(streaming: Boolean) = LlmCompletionRequest(
        systemPrompt = "system",
        userPrompt = "user",
        structured = false,
        streaming = streaming,
        reasoningCompatibility = false,
    )

    private fun indicator(): ProgressIndicator = mockk(relaxed = true)

    private fun startServer(path: String, handler: (HttpExchange) -> Unit): String {
        val local = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        local.createContext(path, handler)
        local.start()
        server = local
        return "http://127.0.0.1:${local.address.port}${if (path.startsWith("/v1/")) "/v1" else ""}"
    }

    private fun HttpExchange.respond(status: Int, body: String, contentType: String = "application/json") {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.set("Content-Type", contentType)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.respondInChunks(status: Int, body: ByteArray, splitAt: Int) {
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { output ->
            output.write(body, 0, splitAt)
            output.flush()
            Thread.sleep(25)
            output.write(body, splitAt, body.size - splitAt)
        }
    }
}
