package emohce.data.commitmessage

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import emohce.domain.commitmessage.LlmCompletion
import emohce.domain.commitmessage.LlmCompletionRequest
import emohce.domain.commitmessage.LlmProfile
import emohce.domain.commitmessage.LlmProviderType
import emohce.domain.commitmessage.LlmStructuredOutput
import emohce.domain.commitmessage.ProviderErrorKind
import emohce.domain.commitmessage.ProviderException
import emohce.domain.commitmessage.ProviderRequestBudget
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal class CodexLlmProviderClient(
    private val gateway: CodexProviderGateway = CodexAppServerService.getInstance(),
) : LlmProviderClient {
    override fun complete(
        profile: LlmProfile,
        apiKey: String,
        request: LlmCompletionRequest,
        budget: ProviderRequestBudget,
        indicator: ProgressIndicator,
    ): LlmCompletion {
        requireCodexProfile(profile)
        if (profile.model.isBlank()) throw MissingActiveProfileException()
        budget.acquire()
        indicator.checkCanceled()
        val expectedGeneration = request.expectedAuthGeneration ?: gateway.authGeneration()
        ensureSignedIn(indicator)
        if (gateway.authGeneration() != expectedGeneration) {
            if (request.expectedAuthGeneration != null) throw SourceContextConsentRequiredException()
            throw MissingChatGptLoginException()
        }
        val content = codexCall {
            gateway.complete(
                CodexAppServerCompletionRequest(
                    model = profile.model,
                    text = request.userPrompt,
                    developerInstructions = buildString {
                        append(request.systemPrompt)
                        append("\n\nOperate only as a text transformation service. ")
                        append("Do not inspect files, invoke tools, search the web, or request additional context.")
                    },
                    outputSchema = request.structuredOutput?.let(::schemaFor),
                    reasoningEffort = profile.reasoningEffort.takeIf(String::isNotBlank),
                    maxOutputTokens = request.maxOutputTokens,
                    expectedAuthGeneration = expectedGeneration,
                ),
                indicator,
            )
        }
        if (content.isBlank()) {
            throw ProviderException(ProviderErrorKind.INVALID_RESPONSE, "Codex returned an empty response")
        }
        return LlmCompletion(content, profile.model)
    }

    override fun fetchModels(
        profile: LlmProfile,
        apiKey: String,
        budget: ProviderRequestBudget,
        indicator: ProgressIndicator,
    ): List<String> {
        requireCodexProfile(profile)
        budget.acquire()
        indicator.checkCanceled()
        ensureSignedIn(indicator)
        return codexCall {
            gateway.models(indicator)
                .filter { it.inputModalities.isEmpty() || "text" in it.inputModalities }
                .map { it.id }
                .distinct()
        }
    }

    private fun ensureSignedIn(indicator: ProgressIndicator) {
        val account = codexCall { gateway.account(indicator) }
        if (account.type != "chatgpt" || account.requiresOpenAiAuth) throw MissingChatGptLoginException()
    }

    private fun requireCodexProfile(profile: LlmProfile) {
        if (profile.provider != LlmProviderType.CHATGPT_CODEX) {
            throw ProviderException(ProviderErrorKind.INVALID_RESPONSE, "Invalid Codex provider profile")
        }
    }

    private fun <T> codexCall(operation: () -> T): T = try {
        operation()
    } catch (error: ProcessCanceledException) {
        throw error
    } catch (error: MissingChatGptLoginException) {
        throw error
    } catch (error: CodexAppServerException) {
        val kind = when (error.kind) {
            CodexAppServerErrorKind.REQUEST -> ProviderErrorKind.TIMEOUT
            CodexAppServerErrorKind.TURN_INTERRUPTED -> ProviderErrorKind.CANCELLED
            CodexAppServerErrorKind.PROCESS, CodexAppServerErrorKind.CLOSED -> ProviderErrorKind.NETWORK
            CodexAppServerErrorKind.TURN_FAILED -> ProviderErrorKind.SERVER
            CodexAppServerErrorKind.PROTOCOL,
            CodexAppServerErrorKind.ISOLATION,
            CodexAppServerErrorKind.TOOL_USE,
            -> ProviderErrorKind.INVALID_RESPONSE
        }
        throw ProviderException(kind, error.message.orEmpty(), error)
    }

    private fun schemaFor(output: LlmStructuredOutput): JsonElement = when (output) {
        LlmStructuredOutput.COMMIT_DRAFT -> objectSchema(
            "type" to "string",
            "scope" to "string",
            "subject" to "string",
            "body" to "string",
            "breakingChanges" to "string",
            "closes" to "string",
            "skipCi" to "boolean",
        )
        LlmStructuredOutput.STYLE_PROPOSAL -> objectSchema(
            "prompt" to "string",
            "template" to "string",
            "explanation" to "string",
        )
        LlmStructuredOutput.PROMPT_OPTIMIZATION -> objectSchema(
            "optimizedInstruction" to "string",
            "explanation" to "string",
        )
    }

    private fun objectSchema(vararg properties: Pair<String, String>): JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            properties.forEach { (name, type) ->
                put(name, buildJsonObject { put("type", type) })
            }
        }
        put("required", kotlinx.serialization.json.buildJsonArray {
            properties.forEach { (name, _) -> add(JsonPrimitive(name)) }
        })
        put("additionalProperties", false)
    }
}

internal class DefaultLlmProviderClient(
    private val http: LlmProviderClient = HttpLlmProviderClient(),
    private val codex: LlmProviderClient = CodexLlmProviderClient(),
) : LlmProviderClient {
    override fun complete(
        profile: LlmProfile,
        apiKey: String,
        request: LlmCompletionRequest,
        budget: ProviderRequestBudget,
        indicator: ProgressIndicator,
    ): LlmCompletion = delegate(profile).complete(profile, apiKey, request, budget, indicator)

    override fun fetchModels(
        profile: LlmProfile,
        apiKey: String,
        budget: ProviderRequestBudget,
        indicator: ProgressIndicator,
    ): List<String> = delegate(profile).fetchModels(profile, apiKey, budget, indicator)

    private fun delegate(profile: LlmProfile): LlmProviderClient =
        if (profile.provider == LlmProviderType.CHATGPT_CODEX) codex else http
}

class MissingChatGptLoginException : ProviderException(
    ProviderErrorKind.AUTHENTICATION,
    "Sign in with ChatGPT before using this provider",
)
