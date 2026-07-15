package emohce.data.commitmessage

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import emohce.domain.commitmessage.AiPreview
import emohce.domain.commitmessage.CommitDraft
import emohce.domain.commitmessage.CommitMessageDefaults
import emohce.domain.commitmessage.CommitTemplateDefinition
import emohce.domain.commitmessage.CommitTemplateRenderer
import emohce.domain.commitmessage.CommitTemplateSnapshot
import emohce.domain.commitmessage.LlmCompletionRequest
import emohce.domain.commitmessage.LlmProfile
import emohce.domain.commitmessage.LlmResponseParser
import emohce.domain.commitmessage.ProviderErrorKind
import emohce.domain.commitmessage.ProviderException
import emohce.domain.commitmessage.ProviderRequestBudget
import emohce.presentation.commitmessage.CommitActionSnapshot

@Service(Service.Level.PROJECT)
class CommitMessageAiService(private val project: Project) {
    private val settings: CommitMessageSettingsService
        get() = CommitMessageSettingsService.getInstance()
    private val contextRepository: GitContextRepository
        get() = project.getService(IntelliJGitContextRepository::class.java)
    private val renderer: VelocityCommitTemplateRenderer
        get() = ApplicationManager.getApplication().getService(VelocityCommitTemplateRenderer::class.java)
    private val providerClient: LlmProviderClient = HttpLlmProviderClient()
    private val secretStore = CommitMessageSecretStore()

    fun generate(
        snapshot: CommitActionSnapshot,
        additionalRequirements: String,
        profileSnapshot: LlmProfile,
        templateSnapshot: CommitTemplateSnapshot,
        indicator: ProgressIndicator,
    ): AiPreview {
        if (!SourceContextConsent.isGranted(profileSnapshot)) throw SourceContextConsentRequiredException()
        val (profile, apiKey) = credentials(profileSnapshot)
        val budget = ProviderRequestBudget(3)
        val template = validTemplate(templateSnapshot)
        val allowedTypes = normalizedAllowedTypes(templateSnapshot.allowedTypes)
        val context = contextRepository.collect(
            GitContextRequest(snapshot.changes, snapshot.unversionedFiles, snapshot.revision),
            indicator,
        )
        val completion = providerClient.complete(
            profile = profile,
            apiKey = apiKey,
            request = LlmCompletionRequest(
                systemPrompt = structuredCommitSystemPrompt(profile, allowedTypes),
                userPrompt = buildString {
                    append("Create a precise commit message from this filtered Git context.\n\n")
                    append(context.asPrompt())
                    append("\n\n## Configured Velocity template\n").append(template.content.take(8_000))
                    if (additionalRequirements.isNotBlank()) {
                        append("\n\n## Additional requirements\n").append(additionalRequirements.take(4_000))
                    }
                },
                structured = true,
                streaming = profile.streaming,
                reasoningCompatibility = profile.reasoningCompatibility,
            ),
            budget = budget,
            indicator = indicator,
        )
        val draft = parseOrRepair(profile, apiKey, completion.content, allowedTypes, budget, indicator)
        val rendered = renderer.render(template, draft)
        if (rendered.isBlank()) throw InvalidAiOutputException()
        return AiPreview(
            original = snapshot.currentText,
            result = rendered,
            provider = profile.name,
            endpoint = profile.baseUrl,
            template = template.name,
            templateId = template.id,
            filteredFiles = context.filteredFiles,
        )
    }

    fun format(
        snapshot: CommitActionSnapshot,
        profileSnapshot: LlmProfile,
        templateSnapshot: CommitTemplateSnapshot,
        indicator: ProgressIndicator,
    ): AiPreview {
        val (profile, apiKey) = credentials(profileSnapshot)
        val budget = ProviderRequestBudget(3)
        val template = validTemplate(templateSnapshot)
        val allowedTypes = normalizedAllowedTypes(templateSnapshot.allowedTypes)
        val completion = providerClient.complete(
            profile = profile,
            apiKey = apiKey,
            request = LlmCompletionRequest(
                systemPrompt = structuredCommitSystemPrompt(profile, allowedTypes),
                userPrompt = buildString {
                    append("Rewrite the current Git commit message into the configured structured fields. ")
                    append("Preserve factual meaning and do not invent repository facts.\n\n")
                    append("## Configured Velocity template\n").append(template.content.take(8_000))
                    append("\n\n## Current commit message\n").append(snapshot.currentText.take(16_000))
                },
                structured = true,
                streaming = profile.streaming,
                reasoningCompatibility = profile.reasoningCompatibility,
            ),
            budget = budget,
            indicator = indicator,
        )
        val draft = parseOrRepair(profile, apiKey, completion.content, allowedTypes, budget, indicator)
        val result = renderValidatedTemplate(renderer, template, draft)
        if (result.isBlank()) throw InvalidAiOutputException()
        return AiPreview(
            original = snapshot.currentText,
            result = result,
            provider = profile.name,
            endpoint = profile.baseUrl,
            template = template.name,
            templateId = template.id,
            filteredFiles = emptyList(),
        )
    }

    fun smartEcho(
        draft: CommitDraft,
        profileSnapshot: LlmProfile,
        allowedTypeSnapshot: List<String>,
        indicator: ProgressIndicator,
    ): CommitDraft {
        val (profile, apiKey) = credentials(profileSnapshot)
        val budget = ProviderRequestBudget(3)
        val allowedTypes = normalizedAllowedTypes(allowedTypeSnapshot)
        val completion = providerClient.complete(
            profile = profile,
            apiKey = apiKey,
            request = LlmCompletionRequest(
                systemPrompt = structuredCommitSystemPrompt(profile, allowedTypes),
                userPrompt = "Improve this parsed draft without inventing repository facts:\n$draft",
                structured = true,
                streaming = profile.streaming,
                reasoningCompatibility = profile.reasoningCompatibility,
            ),
            budget = budget,
            indicator = indicator,
        )
        return parseOrRepair(profile, apiKey, completion.content, allowedTypes, budget, indicator)
    }

    fun fetchModels(profile: LlmProfile, indicator: ProgressIndicator): List<String> {
        val apiKey = secretStore.getApiKey(profile.id).orEmpty()
        if (apiKey.isBlank()) throw MissingApiKeyException()
        return providerClient.fetchModels(profile, apiKey, ProviderRequestBudget(3), indicator)
    }

    private fun credentials(profileSnapshot: LlmProfile): Pair<LlmProfile, String> {
        val profile = profileSnapshot.copy()
        if (profile.baseUrl.isBlank() || profile.model.isBlank()) throw MissingActiveProfileException()
        return CommitMessageCredentialAccess.read {
            val current = settings.profile(profile.id) ?: throw StaleProviderProfileException()
            if (current.provider != profile.provider ||
                current.baseUrl.trim().trimEnd('/') != profile.baseUrl.trim().trimEnd('/')
            ) {
                throw StaleProviderProfileException()
            }
            val apiKey = secretStore.getApiKey(profile.id).orEmpty()
            if (apiKey.isBlank()) throw MissingApiKeyException()
            profile to apiKey
        }
    }

    private fun parseOrRepair(
        profile: LlmProfile,
        apiKey: String,
        content: String,
        allowedTypes: List<String>,
        budget: ProviderRequestBudget,
        indicator: ProgressIndicator,
    ): CommitDraft {
        parseAllowedCommitDraft(content, allowedTypes)?.let { return it }
        if (budget.remainingRequests() <= 0) throw InvalidAiOutputException()
        val repaired = providerClient.complete(
            profile = profile,
            apiKey = apiKey,
            request = LlmCompletionRequest(
                systemPrompt = structuredCommitSystemPrompt(profile, allowedTypes),
                userPrompt = "Repair this invalid response into the required JSON object without changing its meaning:\n" +
                    content.take(8_000),
                structured = true,
                streaming = false,
                reasoningCompatibility = false,
            ),
            budget = budget,
            indicator = indicator,
        )
        return parseAllowedCommitDraft(repaired.content, allowedTypes) ?: throw InvalidAiOutputException()
    }

    private fun normalizedAllowedTypes(snapshot: List<String>): List<String> = snapshot.asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy(String::lowercase)
        .take(MAX_ALLOWED_TYPES)
        .toList()
        .ifEmpty { CommitMessageDefaults.types().map { it.id } }

    private fun validTemplate(snapshot: CommitTemplateSnapshot): CommitTemplateDefinition {
        val selected = snapshot.selected
        if (renderer.validate(selected).valid) return selected
        val fallback = snapshot.fallback
        return if (renderer.validate(fallback).valid) fallback else CommitMessageDefaults.templates().single()
    }

    companion object {
        private const val MAX_ALLOWED_TYPES: Int = 100

        fun getInstance(project: Project): CommitMessageAiService =
            project.getService(CommitMessageAiService::class.java)
    }
}

internal fun renderValidatedTemplate(
    renderer: CommitTemplateRenderer,
    template: CommitTemplateDefinition,
    draft: CommitDraft,
): String = renderer.render(template, draft).trim()

internal fun structuredCommitSystemPrompt(profile: LlmProfile, allowedTypes: List<String>): String = """
    You create Git commit messages in ${profile.responseLanguage}. Return exactly one JSON object with these fields:
    type, scope, subject, body, breakingChanges, closes, skipCi. All text fields are strings and skipCi is boolean.
    The type field must be exactly one of: ${allowedTypes.joinToString(", ")}.
    Subject is required, concise, imperative, and has no trailing period. Do not include Markdown fences.
""".trimIndent()

internal fun parseAllowedCommitDraft(content: String, allowedTypes: List<String>): CommitDraft? {
    val draft = LlmResponseParser.parseDraftOrNull(content) ?: return null
    val canonicalType = allowedTypes.firstOrNull { it.equals(draft.type, ignoreCase = true) } ?: return null
    return draft.copy(type = canonicalType)
}

class MissingActiveProfileException : ProviderException(
    ProviderErrorKind.INVALID_RESPONSE,
    "No active AI provider profile",
)

class MissingApiKeyException : ProviderException(
    ProviderErrorKind.AUTHENTICATION,
    "No API key is stored for the active profile",
)

class InvalidAiOutputException : ProviderException(
    ProviderErrorKind.INVALID_RESPONSE,
    "AI response validation failed",
)

class SourceContextConsentRequiredException : ProviderException(
    ProviderErrorKind.INVALID_RESPONSE,
    "Source context consent is no longer valid",
)

class StaleProviderProfileException : ProviderException(
    ProviderErrorKind.INVALID_RESPONSE,
    "The provider profile changed before credentials were read",
)
