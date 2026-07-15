package emohce.domain.commitmessage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LlmResponseAndContextPolicyTest {
    @Test
    fun `structured response parser accepts fenced json and requires subject`() {
        val draft = LlmResponseParser.parseDraftOrNull(
            """```json
                {"type":"feat","scope":"ai","subject":"add preview","skipCi":false}
                ```
            """.trimIndent(),
        )

        assertEquals("add preview", draft?.subject)
        assertEquals(null, LlmResponseParser.parseDraftOrNull("{\"type\":\"fix\"}"))
    }

    @Test
    fun `sse decoder supports split chunks and multiple data lines`() {
        val decoder = SseDecoder()

        assertTrue(decoder.feed("data: {\"a\":").isEmpty())
        assertEquals(listOf("{\"a\":1}"), decoder.feed("1}\n\n"))
        assertEquals(listOf("first\nsecond"), decoder.feed("data: first\ndata: second\n\n"))
        assertTrue(decoder.feed("data: [DONE]\n\n").isEmpty())
    }

    @Test
    fun `provider errors remove bearer query and known secrets`() {
        val sanitized = ProviderErrorSanitizer.sanitize(
            "Bearer secret-token api_key=secret-token https://x.test?a=1&token=secret-token",
            "secret-token",
        )

        assertFalse(sanitized.contains("secret-token"))
        assertTrue(sanitized.contains("<redacted>"))
    }

    @Test
    fun `provider errors redact cloud tokens passwords and secret query values`() {
        val sanitized = ProviderErrorSanitizer.sanitize(
            "AWS_SECRET_ACCESS_KEY=abcDEF1234567890 password=hunter1234 " +
                "ghp_123456789012345678901234567890123456 https://x.test?secret=query-secret",
        )

        assertFalse(sanitized.contains("abcDEF1234567890"))
        assertFalse(sanitized.contains("hunter1234"))
        assertFalse(sanitized.contains("ghp_"))
        assertFalse(sanitized.contains("query-secret"))
    }

    @Test
    fun `request budget never permits a fourth provider call`() {
        val budget = ProviderRequestBudget(3)
        repeat(3) { budget.acquire() }

        assertEquals(3, budget.usedRequests())
        assertThrows(ProviderRequestBudgetExceededException::class.java) { budget.acquire() }
    }

    @Test
    fun `context policy filters secrets generated files binary and sensitive content`() {
        assertTrue(GitContextPolicy.shouldExclude(".env.local"))
        assertTrue(GitContextPolicy.shouldExclude("config/service-account.json"))
        assertTrue(GitContextPolicy.shouldExclude(".git-credentials"))
        assertTrue(GitContextPolicy.shouldExclude(".docker/config"))
        assertTrue(GitContextPolicy.shouldExclude("build/generated.js"))
        assertTrue(GitContextPolicy.isProbablyBinary(byteArrayOf(1, 0, 2)))
        assertTrue(GitContextPolicy.containsLikelySecret("api_key=abcdefgh12345678"))
        assertTrue(GitContextPolicy.containsLikelySecret("AWS_SECRET_ACCESS_KEY=abcDEF1234567890/secret"))
        assertTrue(GitContextPolicy.containsLikelySecret("token=ghp_123456789012345678901234567890123456"))
        assertTrue(GitContextPolicy.containsLikelySecret("\"auth\": \"YWxpY2U6c2VjcmV0LXBhc3N3b3Jk\""))
        assertFalse(GitContextPolicy.shouldExclude("src/main/App.kt"))
    }

    @Test
    fun `context truncation respects exact budget`() {
        val (value, truncated) = GitContextPolicy.truncate("x".repeat(100), 32)

        assertTrue(truncated)
        assertEquals(32, value.length)
        assertTrue(value.endsWith("…[truncated]"))
    }
}
