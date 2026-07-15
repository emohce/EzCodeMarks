package emohce.domain.commitmessage

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class CommitDraft(
    var type: String = "feat",
    var scope: String = "",
    var subject: String = "",
    var body: String = "",
    var breakingChanges: String = "",
    var closes: String = "",
    var skipCi: Boolean = false,
) {
    fun normalized(): CommitDraft = copy(
        type = type.trim().lowercase(),
        scope = scope.trim(),
        subject = subject.trim(),
        body = body.trim(),
        breakingChanges = breakingChanges.trim(),
        closes = closes.trim(),
    )

    fun isValid(): Boolean = subject.isNotBlank()
}

data class CommitTypeDefinition(
    var id: String = "",
    var description: String = "",
)

data class CommitTemplateDefinition(
    var id: String = "",
    var name: String = "",
    var content: String = "",
    var builtIn: Boolean = false,
)

data class CommitTemplateSnapshot(
    val selected: CommitTemplateDefinition,
    val fallback: CommitTemplateDefinition,
    val allowedTypes: List<String> = CommitMessageDefaults.types().map { it.id },
)

enum class TypeDisplayMode {
    COMBO,
    RADIO,
    MIXED,
}

enum class LlmProviderType {
    OPENAI_COMPATIBLE,
    ANTHROPIC,
}

data class LlmProfile(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "New profile",
    var provider: LlmProviderType = LlmProviderType.OPENAI_COMPATIBLE,
    var baseUrl: String = "https://api.openai.com/v1",
    var model: String = "",
    var temperature: Double = 0.2,
    var responseLanguage: String = "English",
    var streaming: Boolean = true,
    var reasoningCompatibility: Boolean = false,
    var sourceConsentFingerprint: String = "",
)

enum class CommitActionKind {
    CREATE,
    GENERATE,
    GENERATE_WITH_CONTEXT,
    FORMAT,
}

data class GitContext(
    val status: String = "",
    val diff: String = "",
    val unversionedFiles: String = "",
    val recentCommits: String = "",
    val revision: String = "",
    val filteredFiles: List<String> = emptyList(),
    val truncated: Boolean = false,
) {
    fun asPrompt(): String = buildString {
        appendSection("Status", status)
        appendSection("Diff", diff)
        appendSection("Unversioned files", unversionedFiles)
        appendSection("Recent commits", recentCommits)
        appendSection("Historical revision", revision)
    }.trim()

    private fun StringBuilder.appendSection(title: String, value: String) {
        if (value.isBlank()) return
        if (isNotEmpty()) append("\n\n")
        append("## ").append(title).append('\n').append(value)
    }
}

data class AiPreview(
    val original: String,
    val result: String,
    val provider: String,
    val endpoint: String,
    val template: String,
    val templateId: String,
    val filteredFiles: List<String>,
)

object CommitMessageDefaults {
    const val DEFAULT_TEMPLATE_ID: String = "ezcodemarks.conventional"
    const val PRIVACY_POLICY_VERSION: String = "commit-context-v1"

    val defaultTemplateContent: String = """
        ${'$'}{type}#if(${'$'}scope)(${'$'}{scope})#end#if(${'$'}breakingChanges)!#end: ${'$'}{subject}#if(${'$'}body)${'$'}{newline}${'$'}{newline}${'$'}{body}#end#if(${'$'}breakingChanges)${'$'}{newline}${'$'}{newline}BREAKING CHANGE: ${'$'}{breakingChanges}#end#if(${'$'}closes)${'$'}{newline}${'$'}{newline}Closes: ${'$'}{closes}#end#if(${'$'}skipCi)${'$'}{newline}${'$'}{newline}[skip ci]#end
    """.trimIndent()

    fun templates(): MutableList<CommitTemplateDefinition> = mutableListOf(
        CommitTemplateDefinition(
            id = DEFAULT_TEMPLATE_ID,
            name = "Conventional Commit",
            content = defaultTemplateContent,
            builtIn = true,
        ),
    )

    fun types(): MutableList<CommitTypeDefinition> = mutableListOf(
        CommitTypeDefinition("feat", "Feature"),
        CommitTypeDefinition("fix", "Bug fix"),
        CommitTypeDefinition("docs", "Documentation"),
        CommitTypeDefinition("style", "Style"),
        CommitTypeDefinition("refactor", "Refactor"),
        CommitTypeDefinition("perf", "Performance"),
        CommitTypeDefinition("test", "Tests"),
        CommitTypeDefinition("build", "Build"),
        CommitTypeDefinition("ci", "Continuous integration"),
        CommitTypeDefinition("chore", "Chore"),
        CommitTypeDefinition("revert", "Revert"),
    )
}
