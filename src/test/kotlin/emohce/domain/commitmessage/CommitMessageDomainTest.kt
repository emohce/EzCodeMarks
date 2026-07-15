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
            "fix(vcs)!: preserve draft\n\nBody\n\nBREAKING CHANGE: API changed\n\nCloses: #7\n\n[skip ci]",
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
}
