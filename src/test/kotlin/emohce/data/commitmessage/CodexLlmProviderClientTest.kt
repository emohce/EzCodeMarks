package emohce.data.commitmessage

import com.intellij.openapi.progress.ProgressIndicator
import emohce.domain.commitmessage.LlmCompletion
import emohce.domain.commitmessage.LlmCompletionRequest
import emohce.domain.commitmessage.LlmProfile
import emohce.domain.commitmessage.LlmProviderType
import emohce.domain.commitmessage.LlmStructuredOutput
import emohce.domain.commitmessage.ProviderErrorKind
import emohce.domain.commitmessage.ProviderException
import emohce.domain.commitmessage.ProviderRequestBudget
import io.mockk.mockk
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodexLlmProviderClientTest {
    private val indicator = mockk<ProgressIndicator>(relaxed = true)

    @Test
    fun `completion uses managed account structured schema and no API key`() {
        val gateway = FakeGateway()
        val client = CodexLlmProviderClient(gateway)
        val budget = ProviderRequestBudget(3)
        val profile = codexProfile().copy(reasoningEffort = "low")

        val completion = client.complete(
            profile,
            apiKey = "",
            request = request(LlmStructuredOutput.COMMIT_DRAFT).copy(maxOutputTokens = 64),
            budget = budget,
            indicator = indicator,
        )

        assertEquals(LlmCompletion("{\"type\":\"feat\"}", "gpt-test"), completion)
        assertEquals(1, budget.usedRequests())
        val sent = requireNotNull(gateway.completionRequest)
        assertEquals("gpt-test", sent.model)
        assertEquals("low", sent.reasoningEffort)
        assertEquals("user body", sent.text)
        assertEquals("generation", sent.expectedAuthGeneration)
        assertEquals(64, sent.maxOutputTokens)
        assertTrue(sent.developerInstructions.contains("system rules"))
        assertTrue(sent.developerInstructions.contains("Do not inspect files"))
        val schema = requireNotNull(sent.outputSchema).jsonObject
        assertEquals("object", schema.getValue("type").jsonPrimitive.content)
        assertTrue(schema.getValue("properties").jsonObject.containsKey("subject"))
        assertFalse(schema.getValue("additionalProperties").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `each structured operation receives its own schema`() {
        val gateway = FakeGateway()
        val client = CodexLlmProviderClient(gateway)

        client.complete(
            codexProfile(),
            "",
            request(LlmStructuredOutput.STYLE_PROPOSAL),
            ProviderRequestBudget(),
            indicator,
        )
        assertEquals(
            setOf("prompt", "template", "explanation"),
            requireNotNull(gateway.completionRequest?.outputSchema).jsonObject
                .getValue("properties").jsonObject.keys,
        )

        client.complete(
            codexProfile(),
            "",
            request(LlmStructuredOutput.PROMPT_OPTIMIZATION),
            ProviderRequestBudget(),
            indicator,
        )
        assertEquals(
            setOf("optimizedInstruction", "explanation"),
            requireNotNull(gateway.completionRequest?.outputSchema).jsonObject
                .getValue("properties").jsonObject.keys,
        )
    }

    @Test
    fun `model listing keeps text capable models and preserves provider order`() {
        val gateway = FakeGateway().apply {
            availableModels = listOf(
                CodexAppServerModel("text", "Text", true, null, emptyList(), listOf("text")),
                CodexAppServerModel("image", "Image", false, null, emptyList(), listOf("image")),
                CodexAppServerModel("legacy", "Legacy", false, null, emptyList(), emptyList()),
                CodexAppServerModel("text", "Duplicate", false, null, emptyList(), listOf("text")),
            )
        }

        val models = CodexLlmProviderClient(gateway).fetchModels(
            codexProfile(),
            "",
            ProviderRequestBudget(),
            indicator,
        )

        assertEquals(listOf("text", "legacy"), models)
    }

    @Test
    fun `missing managed login is an authentication failure`() {
        val gateway = FakeGateway().apply {
            currentAccount = CodexAppServerAccount(null, null, null, true)
        }

        val error = assertThrows(MissingChatGptLoginException::class.java) {
            CodexLlmProviderClient(gateway).fetchModels(
                codexProfile(),
                "",
                ProviderRequestBudget(),
                indicator,
            )
        }

        assertEquals(ProviderErrorKind.AUTHENTICATION, error.kind)
        assertEquals(0, gateway.modelCalls)
    }

    @Test
    fun `account generation change after consent prevents completion`() {
        val gateway = FakeGateway().apply {
            authGenerationValue = "generation-a"
            onAccountRead = { authGenerationValue = "generation-b" }
        }
        val consentedRequest = request(LlmStructuredOutput.COMMIT_DRAFT).copy(
            expectedAuthGeneration = "generation-a",
        )

        assertThrows(SourceContextConsentRequiredException::class.java) {
            CodexLlmProviderClient(gateway).complete(
                codexProfile(),
                "",
                consentedRequest,
                ProviderRequestBudget(),
                indicator,
            )
        }

        assertEquals(null, gateway.completionRequest)
    }

    @Test
    fun `tool and process failures map to safe provider kinds`() {
        val toolGateway = FakeGateway().apply {
            completionFailure = CodexAppServerException(
                CodexAppServerErrorKind.TOOL_USE,
                "Codex attempted a disallowed tool operation",
            )
        }
        val tool = assertThrows(ProviderException::class.java) {
            CodexLlmProviderClient(toolGateway).complete(
                codexProfile(),
                "",
                request(LlmStructuredOutput.COMMIT_DRAFT),
                ProviderRequestBudget(),
                indicator,
            )
        }
        assertEquals(ProviderErrorKind.INVALID_RESPONSE, tool.kind)

        val processGateway = FakeGateway().apply {
            completionFailure = CodexAppServerException(CodexAppServerErrorKind.PROCESS, "Unavailable")
        }
        val process = assertThrows(ProviderException::class.java) {
            CodexLlmProviderClient(processGateway).complete(
                codexProfile(),
                "",
                request(null),
                ProviderRequestBudget(),
                indicator,
            )
        }
        assertEquals(ProviderErrorKind.NETWORK, process.kind)
    }

    private fun codexProfile(): LlmProfile = LlmProfile(
        id = "codex",
        name = "ChatGPT",
        provider = LlmProviderType.CHATGPT_CODEX,
        baseUrl = "",
        model = "gpt-test",
    )

    private fun request(output: LlmStructuredOutput?): LlmCompletionRequest = LlmCompletionRequest(
        systemPrompt = "system rules",
        userPrompt = "user body",
        structured = output != null,
        streaming = true,
        reasoningCompatibility = false,
        structuredOutput = output,
    )

    private class FakeGateway : CodexProviderGateway {
        var currentAccount = CodexAppServerAccount("chatgpt", "person@example.test", "plus", false)
        var availableModels = listOf(
            CodexAppServerModel("gpt-test", "GPT Test", true, "medium", listOf("low", "medium"), listOf("text")),
        )
        var completionRequest: CodexAppServerCompletionRequest? = null
        var completionFailure: RuntimeException? = null
        var modelCalls = 0
        var authGenerationValue = "generation"
        var onAccountRead: () -> Unit = {}

        override fun account(indicator: ProgressIndicator?): CodexAppServerAccount {
            onAccountRead()
            return currentAccount
        }
        override fun models(indicator: ProgressIndicator?): List<CodexAppServerModel> {
            modelCalls++
            return availableModels
        }

        override fun complete(request: CodexAppServerCompletionRequest, indicator: ProgressIndicator): String {
            completionRequest = request
            completionFailure?.let { throw it }
            return "{\"type\":\"feat\"}"
        }

        override fun authGeneration(): String = authGenerationValue
    }
}
