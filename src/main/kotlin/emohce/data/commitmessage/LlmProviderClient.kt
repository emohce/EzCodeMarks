package emohce.data.commitmessage

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.util.net.HttpConnectionUtils
import com.intellij.util.concurrency.AppExecutorUtil
import emohce.domain.commitmessage.LlmCompletion
import emohce.domain.commitmessage.LlmCompletionRequest
import emohce.domain.commitmessage.LlmProfile
import emohce.domain.commitmessage.LlmProviderType
import emohce.domain.commitmessage.ProviderErrorKind
import emohce.domain.commitmessage.ProviderErrorSanitizer
import emohce.domain.commitmessage.ProviderException
import emohce.domain.commitmessage.ProviderRequestBudget
import emohce.domain.commitmessage.SseDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

interface LlmProviderClient {
    fun complete(
        profile: LlmProfile,
        apiKey: String,
        request: LlmCompletionRequest,
        budget: ProviderRequestBudget,
        indicator: ProgressIndicator,
    ): LlmCompletion

    fun fetchModels(
        profile: LlmProfile,
        apiKey: String,
        budget: ProviderRequestBudget,
        indicator: ProgressIndicator,
    ): List<String>
}

class HttpLlmProviderClient : LlmProviderClient {
    private val json = Json { ignoreUnknownKeys = true }

    override fun complete(
        profile: LlmProfile,
        apiKey: String,
        request: LlmCompletionRequest,
        budget: ProviderRequestBudget,
        indicator: ProgressIndicator,
    ): LlmCompletion {
        var useStreaming = request.streaming
        var useReasoning = request.reasoningCompatibility

        while (true) {
            val response = execute(
                endpoint = completionEndpoint(profile),
                headers = headers(profile, apiKey, useStreaming),
                body = requestBody(profile, request, useStreaming, useReasoning).toString(),
                budget = budget,
                indicator = indicator,
            )
            if (response.statusCode in 200..299) {
                val content = parseCompletion(profile.provider, response.body, useStreaming)
                if (content.isBlank()) throw ProviderException(
                    ProviderErrorKind.INVALID_RESPONSE,
                    "Provider response did not contain message content",
                )
                return LlmCompletion(content = content, model = profile.model)
            }

            val lower = response.body.lowercase()
            val incompatibleParameter = listOf(
                "unsupported",
                "not supported",
                "unknown parameter",
                "unrecognized parameter",
                "not allowed",
                "invalid parameter",
            ).any(lower::contains)
            val reasoningIncompatible = response.statusCode == 400 && useReasoning && incompatibleParameter &&
                ReasoningCompatibilityPolicy.hasParameters(profile) &&
                listOf("reasoning", "reasoning_effort", "thinking").any(lower::contains)
            val streamingIncompatible = response.statusCode == 400 && useStreaming && incompatibleParameter &&
                listOf("stream", "streaming", "sse").any(lower::contains)
            when {
                reasoningIncompatible && budget.remainingRequests() > 0 -> useReasoning = false
                streamingIncompatible && budget.remainingRequests() > 0 -> useStreaming = false
                else -> throw statusException(response, apiKey)
            }
        }
    }

    override fun fetchModels(
        profile: LlmProfile,
        apiKey: String,
        budget: ProviderRequestBudget,
        indicator: ProgressIndicator,
    ): List<String> {
        val response = execute(
            endpoint = modelsEndpoint(profile),
            headers = headers(profile, apiKey, false),
            body = null,
            budget = budget,
            indicator = indicator,
            method = "GET",
        )
        if (response.statusCode !in 200..299) throw statusException(response, apiKey)
        val root = parseObject(response.body)
        return root["data"]?.asArrayOrEmpty()
            ?.mapNotNull { it.asObjectOrNull()?.get("id")?.asStringOrNull() }
            ?.distinct()
            ?.sorted()
            .orEmpty()
    }

    private fun requestBody(
        profile: LlmProfile,
        request: LlmCompletionRequest,
        streaming: Boolean,
        reasoning: Boolean,
    ): JsonObject = when (profile.provider) {
        LlmProviderType.OPENAI_COMPATIBLE -> buildJsonObject {
            put("model", profile.model)
            put("temperature", profile.temperature)
            put("stream", streaming)
            request.maxOutputTokens?.let { put("max_tokens", it.coerceAtLeast(1)) }
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", request.systemPrompt)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", request.userPrompt)
                })
            }
            if (reasoning) {
                ReasoningCompatibilityPolicy.parameters(profile).forEach { (key, value) -> put(key, value) }
            }
        }

        LlmProviderType.ANTHROPIC -> buildJsonObject {
            put("model", profile.model)
            put("system", request.systemPrompt)
            put("max_tokens", request.maxOutputTokens?.coerceAtLeast(1) ?: 2_048)
            put("temperature", profile.temperature)
            put("stream", streaming)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", request.userPrompt)
                })
            }
            if (reasoning) {
                ReasoningCompatibilityPolicy.parameters(profile).forEach { (key, value) -> put(key, value) }
            }
        }

        LlmProviderType.CHATGPT_CODEX -> throw ProviderException(
            ProviderErrorKind.INVALID_RESPONSE,
            "ChatGPT Codex requests require the Codex App Server client",
        )
    }

    private fun headers(profile: LlmProfile, apiKey: String, streaming: Boolean): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("Accept", if (streaming) "text/event-stream, application/json" else "application/json")
        put("User-Agent", "EzCodeMarks/commit-message")
        when (profile.provider) {
            LlmProviderType.OPENAI_COMPATIBLE -> put("Authorization", "Bearer $apiKey")
            LlmProviderType.ANTHROPIC -> {
                put("x-api-key", apiKey)
                put("anthropic-version", "2023-06-01")
            }
            LlmProviderType.CHATGPT_CODEX -> throw ProviderException(
                ProviderErrorKind.INVALID_RESPONSE,
                "ChatGPT Codex requests require the Codex App Server client",
            )
        }
    }

    private fun completionEndpoint(profile: LlmProfile): String = when (profile.provider) {
        LlmProviderType.OPENAI_COMPATIBLE -> LlmEndpointResolver.openAiCompletion(profile.baseUrl)
        LlmProviderType.ANTHROPIC -> LlmEndpointResolver.anthropicMessages(profile.baseUrl)
        LlmProviderType.CHATGPT_CODEX -> throw ProviderException(
            ProviderErrorKind.INVALID_RESPONSE,
            "ChatGPT Codex requests require the Codex App Server client",
        )
    }

    private fun modelsEndpoint(profile: LlmProfile): String = when (profile.provider) {
        LlmProviderType.OPENAI_COMPATIBLE -> LlmEndpointResolver.openAiModels(profile.baseUrl)
        LlmProviderType.ANTHROPIC -> LlmEndpointResolver.anthropicModels(profile.baseUrl)
        LlmProviderType.CHATGPT_CODEX -> throw ProviderException(
            ProviderErrorKind.INVALID_RESPONSE,
            "ChatGPT Codex requests require the Codex App Server client",
        )
    }

    private fun execute(
        endpoint: String,
        headers: Map<String, String>,
        body: String?,
        budget: ProviderRequestBudget,
        indicator: ProgressIndicator,
        method: String = "POST",
    ): RawHttpResponse {
        budget.acquire()
        indicator.checkCanceled()
        val connection = try {
            HttpConnectionUtils.openHttpConnection(endpoint)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            throw ProviderException(
                ProviderErrorKind.NETWORK,
                ProviderErrorSanitizer.sanitize(e.message),
                e,
            )
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                indicator.checkCanceled()
                connection.outputStream.use { it.write(bytes) }
            }

            indicator.checkCanceled()
            val readDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READ_TIMEOUT_MS.toLong())
            val status = awaitResponseCode(connection, indicator, readDeadline)
            connection.readTimeout = READ_POLL_TIMEOUT_MS
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val responseBody = readLimited(stream, indicator, readDeadline)
            return RawHttpResponse(status, responseBody)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ProviderException(ProviderErrorKind.TIMEOUT, "Provider request timed out", e)
        } catch (e: ProviderException) {
            throw e
        } catch (e: Exception) {
            throw ProviderException(
                ProviderErrorKind.NETWORK,
                ProviderErrorSanitizer.sanitize(e.message),
                e,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun awaitResponseCode(
        connection: HttpURLConnection,
        indicator: ProgressIndicator,
        deadline: Long,
    ): Int {
        val future = AppExecutorUtil.getAppExecutorService().submit<Int> { connection.responseCode }
        try {
            while (true) {
                indicator.checkCanceled()
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) {
                    connection.disconnect()
                    future.cancel(true)
                    throw ProviderException(ProviderErrorKind.TIMEOUT, "Provider request timed out")
                }
                val pollMillis = minOf(
                    READ_POLL_TIMEOUT_MS.toLong(),
                    TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L),
                )
                try {
                    return future.get(pollMillis, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // Keep polling so the caller's ProgressIndicator remains observable.
                } catch (error: ExecutionException) {
                    throw error.cause ?: error
                }
            }
        } catch (error: ProcessCanceledException) {
            connection.disconnect()
            future.cancel(true)
            throw error
        }
    }

    private fun readLimited(stream: InputStream?, indicator: ProgressIndicator, deadline: Long): String {
        if (stream == null) return ""
        val output = StringBuilder()
        val buffer = CharArray(4_096)
        InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
            while (true) {
                indicator.checkCanceled()
                if (System.nanoTime() >= deadline) {
                    throw ProviderException(ProviderErrorKind.TIMEOUT, "Provider request timed out")
                }
                val read = try {
                    reader.read(buffer)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (read < 0) break
                output.append(buffer, 0, read)
                if (output.length > MAX_RESPONSE_CHARS) {
                    throw ProviderException(ProviderErrorKind.INVALID_RESPONSE, "Provider response exceeded size limit")
                }
            }
        }
        return output.toString()
    }

    private fun parseCompletion(provider: LlmProviderType, body: String, streaming: Boolean): String =
        if (streaming || body.lineSequence().any { it.startsWith("data:") }) {
            parseStreaming(provider, body)
        } else {
            val root = parseObject(body)
            when (provider) {
                LlmProviderType.OPENAI_COMPATIBLE -> {
                    val content = root["choices"]?.asArrayOrEmpty()?.firstOrNull()
                        ?.asObjectOrNull()?.get("message")?.asObjectOrNull()?.get("content")
                    content.asMessageText()
                }
                LlmProviderType.ANTHROPIC -> root["content"]?.asArrayOrEmpty()
                    ?.joinToString("") { it.asObjectOrNull()?.get("text")?.asStringOrNull().orEmpty() }
                    .orEmpty()
                LlmProviderType.CHATGPT_CODEX -> throw ProviderException(
                    ProviderErrorKind.INVALID_RESPONSE,
                    "ChatGPT Codex responses require the Codex App Server client",
                )
            }
        }

    private fun parseStreaming(provider: LlmProviderType, body: String): String {
        val decoder = SseDecoder()
        val events = decoder.feed(body) + decoder.finish()
        return events.joinToString("") { event ->
            val root = runCatching { json.parseToJsonElement(event).jsonObject }.getOrNull()
                ?: return@joinToString ""
            when (provider) {
                LlmProviderType.OPENAI_COMPATIBLE -> root["choices"]?.asArrayOrEmpty()?.firstOrNull()
                    ?.asObjectOrNull()?.get("delta")?.asObjectOrNull()?.get("content").asMessageText()
                LlmProviderType.ANTHROPIC -> root["delta"]?.asObjectOrNull()?.get("text")?.asStringOrNull().orEmpty()
                LlmProviderType.CHATGPT_CODEX -> throw ProviderException(
                    ProviderErrorKind.INVALID_RESPONSE,
                    "ChatGPT Codex responses require the Codex App Server client",
                )
            }
        }
    }

    private fun parseObject(body: String): JsonObject = try {
        json.parseToJsonElement(body).jsonObject
    } catch (e: Exception) {
        throw ProviderException(
            ProviderErrorKind.INVALID_RESPONSE,
            "Provider response was not valid JSON",
            e,
        )
    }

    private fun statusException(response: RawHttpResponse, secret: String): ProviderException {
        val kind = when (response.statusCode) {
            401, 403 -> ProviderErrorKind.AUTHENTICATION
            429 -> ProviderErrorKind.RATE_LIMIT
            in 500..599 -> ProviderErrorKind.SERVER
            else -> ProviderErrorKind.INVALID_RESPONSE
        }
        val detail = ProviderErrorSanitizer.sanitize(response.body, secret)
        return ProviderException(kind, "Provider HTTP ${response.statusCode}: $detail")
    }

    private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement?.asArrayOrEmpty(): JsonArray? = this as? JsonArray
    private fun JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.asMessageText(): String = when (this) {
        is JsonPrimitive -> contentOrNull.orEmpty()
        is JsonArray -> joinToString("") { element ->
            element.asObjectOrNull()?.get("text")?.asStringOrNull().orEmpty()
        }
        else -> ""
    }

    private data class RawHttpResponse(val statusCode: Int, val body: String)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val READ_POLL_TIMEOUT_MS = 1_000
        private const val MAX_RESPONSE_CHARS = 2_000_000
    }
}

internal object LlmEndpointResolver {
    private const val OPENAI_COMPLETIONS = "/chat/completions"
    private const val OPENAI_MODELS = "/models"
    private const val ANTHROPIC_MESSAGES = "/v1/messages"
    private const val ANTHROPIC_MODELS = "/v1/models"

    fun openAiCompletion(baseUrl: String): String = replaceOrAppend(
        baseUrl = baseUrl,
        targetPath = OPENAI_COMPLETIONS,
        interchangeablePath = OPENAI_MODELS,
    )

    fun openAiModels(baseUrl: String): String = replaceOrAppend(
        baseUrl = baseUrl,
        targetPath = OPENAI_MODELS,
        interchangeablePath = OPENAI_COMPLETIONS,
    )

    fun anthropicMessages(baseUrl: String): String = replaceOrAppend(
        baseUrl = baseUrl,
        targetPath = ANTHROPIC_MESSAGES,
        interchangeablePath = ANTHROPIC_MODELS,
        versionRoot = "/v1",
    )

    fun anthropicModels(baseUrl: String): String = replaceOrAppend(
        baseUrl = baseUrl,
        targetPath = ANTHROPIC_MODELS,
        interchangeablePath = ANTHROPIC_MESSAGES,
        versionRoot = "/v1",
    )

    private fun replaceOrAppend(
        baseUrl: String,
        targetPath: String,
        interchangeablePath: String,
        versionRoot: String? = null,
    ): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.endsWith(targetPath) -> base
            base.endsWith(interchangeablePath) -> base.dropLast(interchangeablePath.length) + targetPath
            versionRoot != null && base.endsWith(versionRoot) -> {
                base.dropLast(versionRoot.length) + targetPath
            }
            else -> base + targetPath
        }
    }
}

internal object ReasoningCompatibilityPolicy {
    fun hasParameters(profile: LlmProfile): Boolean = parameters(profile).isNotEmpty()

    fun parameters(profile: LlmProfile): JsonObject {
        val profileText = "${profile.baseUrl} ${profile.model}".lowercase(Locale.ROOT)
        return buildJsonObject {
            when {
                isQwenCompatible(profileText) -> put("enable_thinking", false)
                profile.provider == LlmProviderType.OPENAI_COMPATIBLE && isOpenAiReasoningModel(profile.model) -> {
                    put("reasoning_effort", "low")
                }
                isThinkingObjectCompatible(profile, profileText) -> {
                    putJsonObject("thinking") { put("type", "disabled") }
                }
            }
        }
    }

    private fun isOpenAiReasoningModel(model: String): Boolean {
        val normalized = model.trim().lowercase(Locale.ROOT)
        return listOf("o1", "o3", "o4", "o5", "gpt-5").any(normalized::startsWith)
    }

    private fun isQwenCompatible(profileText: String): Boolean =
        listOf("qwen", "dashscope", "aliyuncs", "alibabacloud").any(profileText::contains)

    private fun isThinkingObjectCompatible(profile: LlmProfile, profileText: String): Boolean {
        if (profile.provider == LlmProviderType.ANTHROPIC && profileText.contains("api.anthropic.com")) return false
        return listOf(
            "mimo",
            "xiaomimimo",
            "token-plan",
            "zhipu",
            "bigmodel",
            "glm",
            "moonshot",
            "kimi",
        ).any(profileText::contains)
    }
}
