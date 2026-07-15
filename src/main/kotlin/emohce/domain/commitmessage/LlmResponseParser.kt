package emohce.domain.commitmessage

import kotlinx.serialization.json.Json

object LlmResponseParser {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun parseDraftOrNull(content: String): CommitDraft? {
        val candidate = extractJsonObject(content) ?: return null
        return runCatching { json.decodeFromString<CommitDraft>(candidate).normalized() }
            .getOrNull()
            ?.takeIf { it.isValid() }
    }

    fun extractJsonObject(content: String): String? {
        val trimmed = content.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return trimmed.substring(start, end + 1)
    }
}

object ProviderErrorSanitizer {
    private val bearer = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*")
    private val credentialAssignment = Regex(
        "(?i)(api[_-]?key|x-api-key|access[_-]?token|refresh[_-]?token|client[_-]?secret|" +
            "aws[_-]?(?:secret[_-]?access[_-]?key|access[_-]?key[_-]?id|session[_-]?token)|" +
            "password|passwd|private[_-]?key|secret|token|authorization|auth)" +
            "(\\s*[:=]\\s*[\"']?)[^\\s,;\"'}]+",
    )
    private val querySecret = Regex(
        "(?i)([?&](?:key|api_key|token|access_token|password|secret|auth)=)[^&\\s]+",
    )
    private val knownToken = Regex(
        "(?:\\bAKIA[0-9A-Z]{16}\\b|\\bgh[pousr]_[A-Za-z0-9]{20,}\\b|" +
            "\\bglpat-[A-Za-z0-9_-]{20,}\\b|\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b)",
    )

    fun sanitize(message: String?, knownSecret: String? = null): String {
        var value = message.orEmpty()
        if (!knownSecret.isNullOrEmpty()) value = value.replace(knownSecret, "<redacted>")
        value = bearer.replace(value, "Bearer <redacted>")
        value = credentialAssignment.replace(value) { "${it.groupValues[1]}${it.groupValues[2]}<redacted>" }
        value = querySecret.replace(value) { "${it.groupValues[1]}<redacted>" }
        value = knownToken.replace(value, "<redacted>")
        return value.take(1_000)
    }
}

class SseDecoder {
    private val pending = StringBuilder()

    fun feed(chunk: String): List<String> {
        pending.append(chunk.replace("\r\n", "\n"))
        val events = mutableListOf<String>()
        while (true) {
            val boundary = pending.indexOf("\n\n")
            if (boundary < 0) break
            val block = pending.substring(0, boundary)
            pending.delete(0, boundary + 2)
            val data = block.lineSequence()
                .filter { it.startsWith("data:") }
                .joinToString("\n") { it.removePrefix("data:").trimStart() }
            if (data.isNotEmpty() && data != "[DONE]") events += data
        }
        return events
    }

    fun finish(): List<String> = if (pending.isEmpty()) {
        emptyList()
    } else {
        feed("\n\n").also { pending.clear() }
    }
}
