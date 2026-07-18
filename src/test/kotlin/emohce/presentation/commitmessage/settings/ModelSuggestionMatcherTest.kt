package emohce.presentation.commitmessage.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelSuggestionMatcherTest {
    @Test
    fun `ranking prefers exact prefix substring then ordered subsequence`() {
        val models = listOf(
            "openai/gpt-5-mini",
            "openai/gpt5",
            "gpt5-large",
            "gpt5",
        )

        assertEquals(
            listOf("gpt5", "gpt5-large", "openai/gpt5", "openai/gpt-5-mini"),
            ModelSuggestionMatcher.rank(models, "GPT5"),
        )
    }

    @Test
    fun `ordered subsequence supports provider model shorthand and keeps stable order`() {
        val models = listOf(
            "anthropic/claude-3-5-sonnet",
            "anthropic/claude-3-7-sonnet",
            "openai/gpt-5",
        )

        val result = ModelSuggestionMatcher.rank(models, "acson")

        assertEquals(models.take(2), result)
        assertTrue(ModelSuggestionMatcher.rank(models, "not-present").isEmpty())
        assertEquals(models, ModelSuggestionMatcher.rank(models, ""))
    }
}
