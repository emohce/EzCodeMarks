package emohce.data.commitmessage

import emohce.domain.commitmessage.CommitDraft
import emohce.domain.commitmessage.CommitTemplateDefinition
import emohce.domain.commitmessage.LlmProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommitMessageAiContractTest {
    @Test
    fun `configured types are part of the prompt and reject out of list results`() {
        val allowedTypes = listOf("feature", "bug")
        val prompt = structuredCommitSystemPrompt(
            LlmProfile(responseLanguage = "Simplified Chinese"),
            allowedTypes,
        )

        assertTrue(prompt.contains("feature, bug"))
        assertNull(parseAllowedCommitDraft(draftJson("feat"), allowedTypes))
        assertEquals("feature", parseAllowedCommitDraft(draftJson("FEATURE"), allowedTypes)?.type)
    }

    @Test
    fun `format result is rendered through the selected velocity template`() {
        val template = CommitTemplateDefinition(
            id = "team-template",
            name = "Team template",
            content = "[${'$'}type] ${'$'}subject#if(${'$'}body)${'$'}newline${'$'}body#end",
        )
        val result = renderValidatedTemplate(
            renderer = VelocityCommitTemplateRenderer(),
            template = template,
            draft = CommitDraft(type = "fix", subject = "preserve custom format", body = "details"),
        )

        assertEquals("[fix] preserve custom format\ndetails", result)
    }

    private fun draftJson(type: String): String =
        """{"type":"$type","scope":"","subject":"keep configured type","body":"","breakingChanges":"","closes":"","skipCi":false}"""
}
