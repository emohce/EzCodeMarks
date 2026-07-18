package emohce.domain.commitmessage

import emohce.data.commitmessage.SourceContextConsent
import emohce.data.commitmessage.VelocityCommitTemplateRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommitMessageDomainTest {
    @Test
    fun `parser reads every structured field`() {
        val draft = CommitMessageParser.parse(
            """
                feat(ui)!: add commit preview

                Keep the original text until apply.

                BREAKING CHANGE: preview is now required
                Closes: #42
                [skip ci]
            """.trimIndent(),
        )

        assertEquals("feat", draft.type)
        assertEquals("ui", draft.scope)
        assertEquals("add commit preview", draft.subject)
        assertEquals("Keep the original text until apply.", draft.body)
        assertEquals("preview is now required", draft.breakingChanges)
        assertEquals("#42", draft.closes)
        assertTrue(draft.skipCi)
    }

    @Test
    fun `parser preserves a non conventional first line as subject`() {
        val draft = CommitMessageParser.parse("Plain subject\n\nBody")

        assertEquals("feat", draft.type)
        assertEquals("Plain subject", draft.subject)
        assertEquals("Body", draft.body)
    }

    @Test
    fun `default velocity template renders all enabled fields`() {
        val renderer = VelocityCommitTemplateRenderer()
        val message = renderer.render(
            CommitMessageDefaults.templates().single(),
            CommitDraft("fix", "vcs", "preserve draft", "Body", "API changed", "#7", true),
        )

        assertEquals(
            "fix(vcs): preserve draft\n\nBody\n\nBREAKING CHANGE: API changed\n\nCloses #7\n\n[skip ci]",
            message,
        )
    }

    @Test
    fun `velocity validation rejects malformed templates`() {
        val result = VelocityCommitTemplateRenderer().validate(
            CommitTemplateDefinition("bad", "Bad", "#if(${'$'}subject) missing end"),
        )

        assertFalse(result.valid)
        assertTrue(result.error.isNotBlank())
    }

    @Test
    fun `velocity policy rejects resource evaluation and iteration directives including escaped forms`() {
        val renderer = VelocityCommitTemplateRenderer()
        val forbidden = listOf(
            "#parse(\"classpath-resource.vm\")",
            "#include(\"classpath-resource.vm\")",
            "#evaluate(${'$'}subject)",
            "#foreach(${'$'}item in ${'$'}subject)${'$'}item#end",
            "\\\\#parse(\"classpath-resource.vm\")",
        )

        forbidden.forEachIndexed { index, content ->
            val result = renderer.validate(CommitTemplateDefinition("forbidden-$index", "Forbidden", content))

            assertFalse(result.valid, content)
            assertTrue(result.error.isNotBlank(), content)
        }
    }

    @Test
    fun `velocity policy rejects arbitrary members indexes and unknown references`() {
        val renderer = VelocityCommitTemplateRenderer()
        val forbidden = listOf(
            "${'$'}subject.length",
            "${'$'}subject[0]",
            "${'$'}string.trim(${'$'}subject).length",
            "${'$'}unknownReference",
        )

        forbidden.forEachIndexed { index, content ->
            val result = renderer.validate(CommitTemplateDefinition("reference-$index", "Reference", content))

            assertFalse(result.valid, content)
            assertTrue(result.error.isNotBlank(), content)
        }
    }

    @Test
    fun `velocity policy rejects rendered output beyond the bounded writer`() {
        val chunk = "x".repeat(15_000)
        val result = VelocityCommitTemplateRenderer().validate(
            CommitTemplateDefinition(
                "too-much-output",
                "Too much output",
                "#set(${'$'}chunk = \"$chunk\")${'$'}chunk${'$'}chunk${'$'}chunk${'$'}chunk${'$'}chunk",
            ),
        )

        assertFalse(result.valid)
        assertTrue(result.error.isNotBlank())
    }

    @Test
    fun `velocity default template and bounded string helpers remain available`() {
        val renderer = VelocityCommitTemplateRenderer()
        assertTrue(renderer.validate(CommitMessageDefaults.templates().single()).valid)

        val rendered = renderer.render(
            CommitTemplateDefinition(
                "helpers",
                "Helpers",
                "${'$'}string.lower(${'$'}string.trim(${'$'}subject))|${'$'}string.truncate(${'$'}body, 4)",
            ),
            CommitDraft(subject = "  Subject  ", body = "abcdef"),
        )

        assertEquals("subject|abcd", rendered)
    }

    @Test
    fun `velocity renderer exposes upstream and EzCodeMarks compatibility aliases`() {
        val message = VelocityCommitTemplateRenderer().render(
            CommitTemplateDefinition(
                "aliases",
                "Aliases",
                "${'$'}{changes}|${'$'}{breakingChanges}|${'$'}{skipCi}|${'$'}{skipCiEnabled}",
            ),
            CommitDraft(subject = "subject", breakingChanges = "API changed", skipCi = true),
        )

        assertEquals("API changed|API changed|[skip ci]|true", message)
    }

    @Test
    fun `velocity validation rejects an empty rendered message`() {
        val result = VelocityCommitTemplateRenderer().validate(
            CommitTemplateDefinition("empty", "Empty", "#if(false)never#end"),
        )

        assertFalse(result.valid)
        assertEquals(VelocityCommitTemplateRenderer.EMPTY_OUTPUT_ERROR, result.error)
    }

    @Test
    fun `source consent changes when endpoint or provider changes`() {
        val profile = LlmProfile(baseUrl = "https://example.test/v1")
        SourceContextConsent.grant(profile)
        assertTrue(SourceContextConsent.isGranted(profile))

        profile.baseUrl = "https://other.test/v1"
        assertFalse(SourceContextConsent.isGranted(profile))
    }

    @Test
    fun `source consent normalizes host case but preserves path case`() {
        val profile = LlmProfile(baseUrl = "HTTPS://API.EXAMPLE/TenantA/")
        SourceContextConsent.grant(profile)

        profile.baseUrl = "https://api.example/TenantA"
        assertTrue(SourceContextConsent.isGranted(profile))
        profile.baseUrl = "https://api.example/tenanta"
        assertFalse(SourceContextConsent.isGranted(profile))
    }

    @Test
    fun `refinement session keeps original initial final and ordered operation evidence separate`() {
        val session = CommitRefinementSession(
            originalCommit = "draft before generation",
            initialAiResult = "feat: initial AI result",
        )
        val firstEnvelope = LlmPromptEnvelope("system one", "user one")
        val secondEnvelope = LlmPromptEnvelope("system two", "user two")

        session.recordRefinement(
            beforeCommit = "feat: initial AI result",
            afterCommit = "feat: concise result",
            rawPrompt = "Make it shorter",
            aiOptimizedPrompt = "Use one concise imperative subject",
            confirmedPrompt = "Use one concise imperative subject under 50 characters",
            promptEnvelope = firstEnvelope,
            promptOptimizationEnvelope = LlmPromptEnvelope("optimizer system", "optimizer user"),
            promptOptimizationExplanation = "Makes the instruction precise",
        )
        session.recordRefinement(
            beforeCommit = "feat: concise result",
            afterCommit = "feat(ui): concise result",
            rawPrompt = "Add the scope",
            aiOptimizedPrompt = "",
            confirmedPrompt = "Add the ui scope without changing facts",
            promptEnvelope = secondEnvelope,
        )
        val snapshot = session.snapshot("feat(ui): final manual edit")

        assertEquals("draft before generation", snapshot.originalCommit)
        assertEquals("feat: initial AI result", snapshot.initialAiResult)
        assertEquals("feat(ui): final manual edit", snapshot.finalCommit)
        assertEquals(listOf(1, 2), snapshot.operations.map { it.sequence })
        assertEquals("feat: initial AI result", snapshot.operations.first().beforeCommit)
        assertEquals("feat: concise result", snapshot.operations.first().afterCommit)
        assertEquals("Make it shorter", snapshot.operations.first().rawPrompt)
        assertEquals("Use one concise imperative subject", snapshot.operations.first().aiOptimizedPrompt)
        assertEquals(firstEnvelope, snapshot.operations.first().promptEnvelope)
        assertEquals("optimizer system", snapshot.operations.first().promptOptimizationEnvelope?.systemPrompt)
        assertEquals("Makes the instruction precise", snapshot.operations.first().promptOptimizationExplanation)
        assertEquals(secondEnvelope, snapshot.operations.last().promptEnvelope)
    }
}
