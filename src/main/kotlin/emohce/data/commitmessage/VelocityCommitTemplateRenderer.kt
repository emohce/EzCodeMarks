package emohce.data.commitmessage

import com.intellij.openapi.components.Service
import emohce.domain.commitmessage.CommitDraft
import emohce.domain.commitmessage.CommitTemplateDefinition
import emohce.domain.commitmessage.CommitTemplateRenderer
import emohce.domain.commitmessage.TemplateValidation
import emohce.presentation.commitmessage.CommitMessageBundle
import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import org.apache.velocity.util.introspection.SecureUberspector
import java.io.Writer

internal object CommitTemplateStringHelper {
    @JvmStatic fun trim(value: String?): String = value.orEmpty().trim()
    @JvmStatic fun lower(value: String?): String = value.orEmpty().lowercase()
    @JvmStatic fun truncate(value: String?, maximum: Int): String = value.orEmpty().take(maximum.coerceAtLeast(0))
}

@Service(Service.Level.APP)
class VelocityCommitTemplateRenderer : CommitTemplateRenderer {
    private val engine = VelocityEngine().apply {
        setProperty("runtime.strict_mode.enable", true)
        setProperty("introspector.uberspect.class", SecureUberspector::class.java.name)
        setProperty("resource.loaders", "classpath")
        setProperty(
            "resource.loader.classpath.class",
            "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader",
        )
        setProperty("velocimacro.permissions.allow.inline", false)
        setProperty("velocimacro.permissions.allow.inline.to.replace.global", false)
        setProperty("directive.parse.max_depth", 0)
        init()
    }

    override fun render(template: CommitTemplateDefinition, draft: CommitDraft): String {
        enforceTemplatePolicy(template.content)
        val normalized = draft.normalized()
        val context = VelocityContext(
            mapOf(
                "type" to normalized.type,
                "scope" to normalized.scope,
                "subject" to normalized.subject,
                "body" to normalized.body,
                "breakingChanges" to normalized.breakingChanges,
                "changes" to normalized.breakingChanges,
                "closes" to normalized.closes,
                "skipCi" to if (normalized.skipCi) "[skip ci]" else "",
                "skipCiEnabled" to normalized.skipCi,
                "newline" to "\n",
                "string" to CommitTemplateStringHelper,
            ),
        )
        val writer = LimitedStringWriter(MAX_RENDERED_LENGTH)
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

    private fun enforceTemplatePolicy(source: String) {
        require(source.length <= MAX_TEMPLATE_LENGTH) {
            CommitMessageBundle.message("error.template.tooLong", MAX_TEMPLATE_LENGTH)
        }
        DIRECTIVE_PATTERN.findAll(source).forEach { match ->
            val directive = (match.groups[1]?.value ?: match.groups[2]?.value).orEmpty().lowercase()
            require(directive in ALLOWED_DIRECTIVES) {
                CommitMessageBundle.message("error.template.directiveNotAllowed", directive)
            }
        }
        val assignedVariables = SET_VARIABLE_PATTERN.findAll(source).map { it.groupValues[1] }.toSet()
        REFERENCE_PATTERN.findAll(source).forEach { match ->
            val reference = match.groupValues[1]
            require(reference in EXPOSED_VARIABLES || reference in assignedVariables) {
                CommitMessageBundle.message("error.template.referenceNotAllowed", reference)
            }
        }
        MEMBER_PATTERN.findAll(source).forEach { match ->
            val root = match.groupValues[1]
            val member = match.groupValues[2]
            require(root == "string" && member in ALLOWED_STRING_HELPERS) {
                CommitMessageBundle.message("error.template.memberNotAllowed", "$root.$member")
            }
        }
        require(!INDEX_ACCESS_PATTERN.containsMatchIn(source) && !CHAINED_MEMBER_PATTERN.containsMatchIn(source)) {
            CommitMessageBundle.message("error.template.memberNotAllowed", "index/property")
        }
        require(!RANGE_PATTERN.containsMatchIn(source)) {
            CommitMessageBundle.message("error.template.directiveNotAllowed", "range")
        }
    }

    private class LimitedStringWriter(private val maximum: Int) : Writer() {
        private val content = StringBuilder()

        override fun write(buffer: CharArray, offset: Int, length: Int) {
            require(content.length + length <= maximum) {
                CommitMessageBundle.message("error.template.outputTooLong", maximum)
            }
            content.append(buffer, offset, length)
        }

        override fun flush() = Unit

        override fun close() = Unit

        override fun toString(): String = content.toString()
    }

    companion object {
        const val EMPTY_OUTPUT_ERROR: String = "empty-output"
        private const val MAX_TEMPLATE_LENGTH: Int = 16_000
        private const val MAX_RENDERED_LENGTH: Int = 64_000
        private val ALLOWED_DIRECTIVES = setOf("set", "if", "elseif", "else", "end")
        private val ALLOWED_STRING_HELPERS = setOf("trim", "lower", "truncate")
        private val EXPOSED_VARIABLES = setOf(
            "type",
            "scope",
            "subject",
            "body",
            "breakingChanges",
            "changes",
            "closes",
            "skipCi",
            "skipCiEnabled",
            "newline",
            "string",
        )
        private val DIRECTIVE_PATTERN = Regex("""#(?:\{([A-Za-z][A-Za-z0-9_]*)}|([A-Za-z][A-Za-z0-9_]*))""")
        private val SET_VARIABLE_PATTERN = Regex("""#set\s*\(\s*\$!?\{?([A-Za-z_][A-Za-z0-9_]*)""")
        private val REFERENCE_PATTERN = Regex("""\$!?\{?([A-Za-z_][A-Za-z0-9_]*)""")
        private val MEMBER_PATTERN = Regex(
            """\$!?\{?([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)""",
        )
        private val INDEX_ACCESS_PATTERN = Regex("""\$!?\{?[A-Za-z_][A-Za-z0-9_]*\s*\[""")
        private val CHAINED_MEMBER_PATTERN = Regex("""\)\s*\.\s*[A-Za-z_]""")
        private val RANGE_PATTERN = Regex("""\[\s*[-+]?\d+\s*\.\.\s*[-+]?\d+\s*]""")
    }
}
