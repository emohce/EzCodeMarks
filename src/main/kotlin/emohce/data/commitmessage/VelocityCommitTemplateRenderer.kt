package emohce.data.commitmessage

import com.intellij.openapi.components.Service
import emohce.domain.commitmessage.CommitDraft
import emohce.domain.commitmessage.CommitTemplateDefinition
import emohce.domain.commitmessage.CommitTemplateRenderer
import emohce.domain.commitmessage.TemplateValidation
import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import org.apache.velocity.util.introspection.SecureUberspector
import java.io.StringWriter

@Service(Service.Level.APP)
class VelocityCommitTemplateRenderer : CommitTemplateRenderer {
    private val engine = VelocityEngine().apply {
        setProperty("runtime.strict_mode.enable", true)
        setProperty("introspector.uberspect.class", SecureUberspector::class.java.name)
        init()
    }

    override fun render(template: CommitTemplateDefinition, draft: CommitDraft): String {
        val normalized = draft.normalized()
        val context = VelocityContext(
            mapOf(
                "type" to normalized.type,
                "scope" to normalized.scope,
                "subject" to normalized.subject,
                "body" to normalized.body,
                "breakingChanges" to normalized.breakingChanges,
                "closes" to normalized.closes,
                "skipCi" to normalized.skipCi,
                "newline" to "\n",
                "string" to CommitStringHelper,
            ),
        )
        val writer = StringWriter()
        engine.evaluate(context, writer, "ezcodemarks-commit-template", template.content)
        return writer.toString().trim()
    }

    override fun validate(template: CommitTemplateDefinition): TemplateValidation = runCatching {
        render(
            template,
            CommitDraft(
                type = "feat",
                scope = "ui",
                subject = "preview subject",
                body = "preview body",
                breakingChanges = "preview breaking change",
                closes = "#123",
                skipCi = true,
            ),
        )
    }.fold(
        onSuccess = { rendered ->
            if (rendered.isBlank()) {
                TemplateValidation(valid = false, error = EMPTY_OUTPUT_ERROR)
            } else {
                TemplateValidation(valid = true)
            }
        },
        onFailure = { TemplateValidation(valid = false, error = it.message.orEmpty()) },
    )

    private object CommitStringHelper {
        @JvmStatic fun trim(value: String?): String = value.orEmpty().trim()
        @JvmStatic fun lower(value: String?): String = value.orEmpty().lowercase()
        @JvmStatic fun truncate(value: String?, maximum: Int): String = value.orEmpty().take(maximum.coerceAtLeast(0))
    }

    companion object {
        const val EMPTY_OUTPUT_ERROR: String = "empty-output"
    }
}
