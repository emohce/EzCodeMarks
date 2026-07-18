package emohce.data.commitmessage

import emohce.domain.commitmessage.CommitDraft
import emohce.domain.commitmessage.CommitTemplateDefinition
import emohce.domain.commitmessage.LlmProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `configured commit style is appended to the structured prompt`() {
        val prompt = structuredCommitSystemPrompt(
            LlmProfile(responseLanguage = "English"),
            listOf("feat", "fix"),
            "Prefer one line and never repeat the subject.",
        )

        assertTrue(prompt.contains("Follow this configured commit style"))
        assertTrue(prompt.contains("Prefer one line"))
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

    @Test
    fun `format prompt contains one-off instructions and only the current commit text`() {
        val prompt = buildFormatCommitPrompt(
            currentCommitMessage = "fix(parser): preserve quoted values",
            templateContent = "${'$'}{type}(${'$'}{scope}): ${'$'}{subject}",
            additionalInstructions = "Use one concise line and remove repetition.",
        )

        assertTrue(prompt.contains("fix(parser): preserve quoted values"))
        assertTrue(prompt.contains("Use one concise line and remove repetition."))
        assertTrue(prompt.contains("## One-off optimization instructions"))
        listOf("## Status", "## Diff", "## Unversioned files", "## Recent commits", "## Historical revision")
            .forEach { assertFalse(prompt.contains(it, ignoreCase = true), it) }
    }

    @Test
    fun `format prompt omits the one-off section when instructions are blank and enforces caps`() {
        val prompt = buildFormatCommitPrompt(
            currentCommitMessage = "m".repeat(20_000),
            templateContent = "t".repeat(10_000),
            additionalInstructions = "i".repeat(5_000),
        )
        val blankPrompt = buildFormatCommitPrompt("fix: keep facts", "${'$'}{subject}", "   ")

        assertEquals(16_000, prompt.substringAfter("## Current commit message\n").substringBefore("\n\n##").length)
        assertEquals(8_000, prompt.substringAfter("## Configured Velocity template\n").substringBefore("\n\n##").length)
        assertEquals(4_000, prompt.substringAfter("## One-off optimization instructions\n").length)
        assertFalse(blankPrompt.contains("## One-off optimization instructions"))
    }

    private fun draftJson(type: String): String =
        """{"type":"$type","scope":"","subject":"keep configured type","body":"","breakingChanges":"","closes":"","skipCi":false}"""
}
