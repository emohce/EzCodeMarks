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

@Serializable
data class CommitTypeDefinition(
    var id: String = "",
    var description: String = "",
)

@Serializable
data class CommitTemplateDefinition(
    var id: String = "",
    var name: String = "",
    var content: String = "",
    var builtIn: Boolean = false,
)

data class CommitTemplateSnapshot(
    val candidates: List<CommitTemplateDefinition>,
    val allowedTypes: List<String> = CommitMessageDefaults.types().map { it.id },
    val style: CommitStyleDefinition = CommitMessageDefaults.standardStyle(),
    val persistentInstructions: String = "",
) {
    val selected: CommitTemplateDefinition
        get() = candidates.first()

    val fallback: CommitTemplateDefinition
        get() = candidates.last()
}

@Serializable
data class CommitStyleDefinition(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "New style",
    var description: String = "",
    var prompt: String = "",
    var templateContent: String = "",
    var builtIn: Boolean = false,
)

fun CommitStyleDefinition.withEffectivePrompt(): CommitStyleDefinition = copy(
    prompt = prompt.trim().ifBlank { if (builtIn) "" else description.trim() },
)

@Serializable
data class CommitStyleProposal(
    val prompt: String = "",
    val template: String = "",
    val explanation: String = "",
)

@Serializable
enum class TypeDisplayMode {
    COMBO,
    RADIO,
    MIXED,
}

@Serializable
enum class LlmProviderType {
    OPENAI_COMPATIBLE,
    ANTHROPIC,
    CHATGPT_CODEX,
}

@Serializable
data class LlmProfile(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "New profile",
    var provider: LlmProviderType = LlmProviderType.OPENAI_COMPATIBLE,
    var baseUrl: String = "https://api.openai.com/v1",
    var model: String = "",
    var temperature: Double = 0.5,
    var responseLanguage: String = "English",
    var streaming: Boolean = true,
    var reasoningCompatibility: Boolean = false,
    var reasoningEffort: String = "",
    @get:com.intellij.util.xmlb.annotations.Transient
    @kotlinx.serialization.Transient
    var sourceConsentFingerprint: String = "",
)

@Serializable
enum class LlmProfileScope {
    GLOBAL,
    PROJECT,
}

@Serializable
data class LlmProfileRef(
    var scope: LlmProfileScope = LlmProfileScope.GLOBAL,
    var id: String = "",
)

@Serializable
enum class ProjectInstructionMode {
    INHERIT,
    APPEND,
    REPLACE,
}

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
    val style: String = "",
    val filteredFiles: List<String>,
)

data class LlmPromptEnvelope(
    val systemPrompt: String,
    val userPrompt: String,
)

@Serializable
data class CommitPromptOptimizationProposal(
    val optimizedInstruction: String = "",
    val explanation: String = "",
)

@ConsistentCopyVisibility
data class PreparedPromptOptimization internal constructor(
    val envelope: LlmPromptEnvelope,
    internal val originalInstruction: String,
    internal val profileFingerprint: String,
)

@ConsistentCopyVisibility
data class PreparedCommitRefinement internal constructor(
    val envelope: LlmPromptEnvelope,
    internal val template: CommitTemplateDefinition,
    internal val allowedTypes: List<String>,
    internal val stylePrompt: String,
    internal val confirmedInstruction: String,
    internal val profileFingerprint: String,
)

data class CommitRefinementOperation(
    val sequence: Int,
    val beforeCommit: String,
    val afterCommit: String,
    val rawPrompt: String,
    val aiOptimizedPrompt: String,
    val confirmedPrompt: String,
    val promptEnvelope: LlmPromptEnvelope,
    val promptOptimizationEnvelope: LlmPromptEnvelope? = null,
    val promptOptimizationExplanation: String = "",
)

data class CommitRefinementSessionSnapshot(
    val originalCommit: String,
    val initialAiResult: String,
    val finalCommit: String,
    val operations: List<CommitRefinementOperation>,
)

class CommitRefinementSession(
    val originalCommit: String,
    val initialAiResult: String,
) {
    private val mutableOperations = mutableListOf<CommitRefinementOperation>()

    val operations: List<CommitRefinementOperation>
        get() = mutableOperations.toList()

    fun recordRefinement(
        beforeCommit: String,
        afterCommit: String,
        rawPrompt: String,
        aiOptimizedPrompt: String,
        confirmedPrompt: String,
        promptEnvelope: LlmPromptEnvelope,
        promptOptimizationEnvelope: LlmPromptEnvelope? = null,
        promptOptimizationExplanation: String = "",
    ): CommitRefinementOperation = CommitRefinementOperation(
        sequence = mutableOperations.size + 1,
        beforeCommit = beforeCommit,
        afterCommit = afterCommit,
        rawPrompt = rawPrompt,
        aiOptimizedPrompt = aiOptimizedPrompt,
        confirmedPrompt = confirmedPrompt,
        promptEnvelope = promptEnvelope,
        promptOptimizationEnvelope = promptOptimizationEnvelope,
        promptOptimizationExplanation = promptOptimizationExplanation,
    ).also(mutableOperations::add)

    fun snapshot(finalCommit: String): CommitRefinementSessionSnapshot = CommitRefinementSessionSnapshot(
        originalCommit = originalCommit,
        initialAiResult = initialAiResult,
        finalCommit = finalCommit,
        operations = operations,
    )
}

object CommitMessageDefaults {
    const val DEFAULT_TEMPLATE_ID: String = "ezcodemarks.conventional"
    const val DEFAULT_PROFILE_ID: String = "default"
    const val STANDARD_STYLE_ID: String = "ezcodemarks.style.standard"
    const val CONCISE_STYLE_ID: String = "ezcodemarks.style.concise"
    const val PRIVACY_POLICY_VERSION: String = "commit-context-v1"

    val defaultTemplateContent: String = """
        #set(${'$'}sep = "${'$'}{newline}${'$'}{newline}")
        #if(${'$'}type || ${'$'}scope || ${'$'}subject)#if(${'$'}type)${'$'}{type}#end#if(${'$'}scope)(${'$'}{scope})#end#if(${'$'}type || ${'$'}scope): #end#if(${'$'}subject)${'$'}{subject}#end#end#if(${'$'}body)${'$'}{sep}${'$'}{body}#end#if(${'$'}changes)${'$'}{sep}BREAKING CHANGE: ${'$'}{changes}#end#if(${'$'}closes)${'$'}{sep}Closes ${'$'}{closes}#end#if(${'$'}skipCi)${'$'}{sep}${'$'}{skipCi}#end
    """.trimIndent()

    /** The v1 EzCodeMarks default, retained only to migrate untouched built-in settings. */
    val legacyDefaultTemplateContent: String = """
        ${'$'}{type}#if(${'$'}scope)(${'$'}{scope})#end#if(${'$'}breakingChanges)!#end: ${'$'}{subject}#if(${'$'}body)${'$'}{newline}${'$'}{newline}${'$'}{body}#end#if(${'$'}breakingChanges)${'$'}{newline}${'$'}{newline}BREAKING CHANGE: ${'$'}{breakingChanges}#end#if(${'$'}closes)${'$'}{newline}${'$'}{newline}Closes: ${'$'}{closes}#end#if(${'$'}skipCi)${'$'}{newline}${'$'}{newline}[skip ci]#end
    """.trimIndent()

    fun templates(): MutableList<CommitTemplateDefinition> = mutableListOf(
        CommitTemplateDefinition(
            id = DEFAULT_TEMPLATE_ID,
            name = "Default",
            content = defaultTemplateContent,
            builtIn = true,
        ),
    )

    fun types(): MutableList<CommitTypeDefinition> = mutableListOf(
        CommitTypeDefinition("feat", "A new feature"),
        CommitTypeDefinition("fix", "A bug fix"),
        CommitTypeDefinition("docs", "Documentation only changes"),
        CommitTypeDefinition(
            "style",
            "Changes that do not affect the meaning of the code (white-space, formatting, missing semi-colons, etc)",
        ),
        CommitTypeDefinition("refactor", "A code change that neither fixes a bug nor adds a feature"),
        CommitTypeDefinition("perf", "A code change that improves performance"),
        CommitTypeDefinition("test", "Adding missing tests or correcting existing tests"),
        CommitTypeDefinition(
            "build",
            "Changes that affect the build system or external dependencies (example scopes: gulp, broccoli, npm)",
        ),
        CommitTypeDefinition(
            "ci",
            "Changes to our CI configuration files and scripts (example scopes: Travis, Circle, BrowserStack, SauceLabs)",
        ),
        CommitTypeDefinition("chore", "Other changes that don't modify src or test files"),
        CommitTypeDefinition("revert", "Reverts a previous commit"),
    )

    fun defaultProfile(): LlmProfile = LlmProfile(
        id = DEFAULT_PROFILE_ID,
        name = "Default",
        provider = LlmProviderType.OPENAI_COMPATIBLE,
        baseUrl = "https://api.openai.com/v1",
        temperature = 0.5,
        responseLanguage = "English",
        streaming = true,
        reasoningCompatibility = false,
    )

    fun standardStyle(): CommitStyleDefinition = CommitStyleDefinition(
        id = STANDARD_STYLE_ID,
        name = "Standard",
        description = "Original balanced commit-message behavior without an additional style override.",
        prompt = "",
        builtIn = true,
    )

    fun conciseStyle(): CommitStyleDefinition = CommitStyleDefinition(
        id = CONCISE_STYLE_ID,
        name = "Concise",
        description = "Short commit messages that omit routine detail and repetition.",
        prompt = "Be brief. Prefer a single imperative subject under 72 characters. Omit the body unless an essential consequence cannot fit in the subject. Never list files or repeat the subject.",
        builtIn = true,
    )

    fun styles(): MutableList<CommitStyleDefinition> = mutableListOf(standardStyle(), conciseStyle())
}
