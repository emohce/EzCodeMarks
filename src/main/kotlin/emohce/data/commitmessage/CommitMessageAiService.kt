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
import emohce.domain.commitmessage.CommitStyleProposal
import emohce.domain.commitmessage.CommitPromptOptimizationProposal
import emohce.domain.commitmessage.LlmCompletionRequest
import emohce.domain.commitmessage.LlmPromptEnvelope
import emohce.domain.commitmessage.LlmProfile
import emohce.domain.commitmessage.LlmResponseParser
import emohce.domain.commitmessage.LlmStructuredOutput
import emohce.domain.commitmessage.PreparedCommitRefinement
import emohce.domain.commitmessage.PreparedPromptOptimization
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
    private val providerClient: LlmProviderClient = DefaultLlmProviderClient()
    private val secretStore = CommitMessageSecretStore()

    fun generate(
        snapshot: CommitActionSnapshot,
        additionalRequirements: String,
        profileSnapshot: LlmProfile,
        templateSnapshot: CommitTemplateSnapshot,
        indicator: ProgressIndicator,
    ): AiPreview {
        val credentials = credentials(profileSnapshot)
        val profile = credentials.profile
        val apiKey = credentials.apiKey
        val accountGeneration = credentials.generation
        if (!CommitProjectStateService.getInstance(project)
                .hasSourceContextConsent(profile, accountGeneration)
        ) {
            throw SourceContextConsentRequiredException()
        }
        val budget = ProviderRequestBudget(3)
        val template = validTemplate(templateSnapshot)
        val allowedTypes = normalizedAllowedTypes(templateSnapshot.allowedTypes)
        val systemPrompt = structuredCommitSystemPrompt(
            profile,
            allowedTypes,
            templateSnapshot.style.prompt,
            templateSnapshot.persistentInstructions,
        )
        val context = contextRepository.collect(
            GitContextRequest(snapshot.changes, snapshot.unversionedFiles, snapshot.revision),
            indicator,
        )
        val completion = providerClient.complete(
            profile = profile,
            apiKey = apiKey,
            request = LlmCompletionRequest(
                systemPrompt = systemPrompt,
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
                structuredOutput = LlmStructuredOutput.COMMIT_DRAFT,
                expectedAuthGeneration = accountGeneration.takeIf {
                    profile.provider == emohce.domain.commitmessage.LlmProviderType.CHATGPT_CODEX
                },
            ),
            budget = budget,
            indicator = indicator,
        )
        val draft = parseOrRepair(
            profile,
            apiKey,
            completion.content,
            allowedTypes,
            templateSnapshot.style.prompt,
            budget,
            indicator,
            additionalRequirements,
            systemPrompt,
            expectedAuthGeneration = accountGeneration.takeIf(String::isNotBlank),
        )
        val rendered = renderer.render(template, draft)
        if (rendered.isBlank()) throw InvalidAiOutputException()
        return AiPreview(
            original = snapshot.currentText,
            result = rendered,
            provider = profile.name,
            endpoint = profile.baseUrl,
            template = template.name,
            templateId = template.id,
            style = templateSnapshot.style.name,
            filteredFiles = context.filteredFiles,
        )
    }

    fun format(
        currentCommitMessage: String,
        additionalInstructions: String,
        profileSnapshot: LlmProfile,
        templateSnapshot: CommitTemplateSnapshot,
        indicator: ProgressIndicator,
    ): AiPreview {
        val prepared = prepareCommitRefinement(
            currentCommitMessage,
            additionalInstructions,
            profileSnapshot,
            templateSnapshot,
        )
        val result = refinePreparedCommit(
            prepared = prepared,
            profileSnapshot = profileSnapshot,
            budget = ProviderRequestBudget(3),
            indicator = indicator,
            streaming = profileSnapshot.streaming,
            reasoningCompatibility = profileSnapshot.reasoningCompatibility,
        )
        return AiPreview(
            original = currentCommitMessage,
            result = result,
            provider = profileSnapshot.name,
            endpoint = profileSnapshot.baseUrl,
            template = prepared.template.name,
            templateId = prepared.template.id,
            style = templateSnapshot.style.name,
            filteredFiles = emptyList(),
        )
    }

    fun preparePromptOptimization(
        currentCommitMessage: String,
        originalInstruction: String,
        profileSnapshot: LlmProfile,
    ): PreparedPromptOptimization {
        val instruction = originalInstruction.trim()
        if (instruction.isBlank() || instruction.length > MAX_REFINEMENT_INSTRUCTION_LENGTH) {
            throw InvalidAiOutputException()
        }
        return PreparedPromptOptimization(
            envelope = buildPromptOptimizationEnvelope(currentCommitMessage, instruction, profileSnapshot),
            originalInstruction = instruction,
            profileFingerprint = profileConfigurationFingerprint(profileSnapshot),
        )
    }

    fun optimizePreparedPrompt(
        prepared: PreparedPromptOptimization,
        profileSnapshot: LlmProfile,
        budget: ProviderRequestBudget,
        indicator: ProgressIndicator,
    ): CommitPromptOptimizationProposal {
        ensurePreparedProfile(prepared.profileFingerprint, profileSnapshot)
        val (profile, apiKey) = credentials(profileSnapshot)
        val completion = providerClient.complete(
            profile = profile,
            apiKey = apiKey,
            request = LlmCompletionRequest(
                systemPrompt = prepared.envelope.systemPrompt,
                userPrompt = prepared.envelope.userPrompt,
                structured = true,
                streaming = false,
                reasoningCompatibility = false,
                maxOutputTokens = 1_024,
                structuredOutput = LlmStructuredOutput.PROMPT_OPTIMIZATION,
            ),
            budget = budget,
            indicator = indicator,
        )
        return LlmResponseParser.parsePromptOptimizationOrNull(completion.content)
            ?: throw InvalidAiOutputException()
    }

    fun prepareCommitRefinement(
        currentCommitMessage: String,
        confirmedInstruction: String,
        profileSnapshot: LlmProfile,
        templateSnapshot: CommitTemplateSnapshot,
    ): PreparedCommitRefinement {
        val instruction = confirmedInstruction.trim()
        if (currentCommitMessage.isBlank() || instruction.length > MAX_REFINEMENT_INSTRUCTION_LENGTH) {
            throw InvalidAiOutputException()
        }
        val template = validTemplate(templateSnapshot)
        val allowedTypes = normalizedAllowedTypes(templateSnapshot.allowedTypes)
        val systemPrompt = commitRefinementSystemPrompt(
            profileSnapshot,
            allowedTypes,
            templateSnapshot.style.prompt,
            templateSnapshot.persistentInstructions,
        )
        return PreparedCommitRefinement(
            envelope = LlmPromptEnvelope(
                systemPrompt = systemPrompt,
                userPrompt = buildFormatCommitPrompt(currentCommitMessage, template.content, instruction),
            ),
            template = template.copy(),
            allowedTypes = allowedTypes.toList(),
            stylePrompt = templateSnapshot.style.prompt,
            confirmedInstruction = instruction,
            profileFingerprint = profileConfigurationFingerprint(profileSnapshot),
        )
    }

    fun refinePreparedCommit(
        prepared: PreparedCommitRefinement,
        profileSnapshot: LlmProfile,
        budget: ProviderRequestBudget,
        indicator: ProgressIndicator,
        streaming: Boolean = false,
        reasoningCompatibility: Boolean = false,
    ): String {
        ensurePreparedProfile(prepared.profileFingerprint, profileSnapshot)
        val (profile, apiKey) = credentials(profileSnapshot)
        val completion = providerClient.complete(
            profile = profile,
            apiKey = apiKey,
            request = LlmCompletionRequest(
                systemPrompt = prepared.envelope.systemPrompt,
                userPrompt = prepared.envelope.userPrompt,
                structured = true,
                streaming = streaming,
                reasoningCompatibility = reasoningCompatibility,
                structuredOutput = LlmStructuredOutput.COMMIT_DRAFT,
            ),
            budget = budget,
            indicator = indicator,
        )
        val draft = parseOrRepair(
            profile,
            apiKey,
            completion.content,
            prepared.allowedTypes,
            prepared.stylePrompt,
            budget,
            indicator,
            prepared.confirmedInstruction,
            prepared.envelope.systemPrompt,
            prepared.envelope.userPrompt,
        )
        val result = renderValidatedTemplate(renderer, prepared.template, draft)
        if (result.isBlank()) throw InvalidAiOutputException()
        return result
    }

    fun smartEcho(
        draft: CommitDraft,
        profileSnapshot: LlmProfile,
        allowedTypeSnapshot: List<String>,
        stylePrompt: String,
        persistentInstructions: String,
        indicator: ProgressIndicator,
    ): CommitDraft {
        val (profile, apiKey) = credentials(profileSnapshot)
        val budget = ProviderRequestBudget(3)
        val allowedTypes = normalizedAllowedTypes(allowedTypeSnapshot)
        val systemPrompt = structuredCommitSystemPrompt(profile, allowedTypes, stylePrompt, persistentInstructions)
        val completion = providerClient.complete(
            profile = profile,
            apiKey = apiKey,
            request = LlmCompletionRequest(
                systemPrompt = systemPrompt,
                userPrompt = "Improve this parsed draft without inventing repository facts:\n$draft",
                structured = true,
                streaming = profile.streaming,
                reasoningCompatibility = profile.reasoningCompatibility,
                structuredOutput = LlmStructuredOutput.COMMIT_DRAFT,
            ),
            budget = budget,
            indicator = indicator,
        )
        return parseOrRepair(
            profile,
            apiKey,
            completion.content,
            allowedTypes,
            stylePrompt,
            budget,
            indicator,
            repairSystemPrompt = systemPrompt,
        )
    }

    fun fetchModels(profile: LlmProfile, indicator: ProgressIndicator): List<String> {
        val apiKey = if (profile.provider == emohce.domain.commitmessage.LlmProviderType.CHATGPT_CODEX) {
            ""
        } else {
            CommitMessageCredentialAccess.transaction {
                val current = settings.snapshot().profiles.firstOrNull { it.id == profile.id }
                    ?: throw StaleProviderProfileException()
                if (!hasSameCredentialDestination(current, profile)) throw StaleProviderProfileException()
                secretStore.credentialSnapshotWithinTransaction(profile.id).apiKey.orEmpty().also {
                    if (it.isBlank()) throw MissingApiKeyException()
                }
            }
        }
        return providerClient.fetchModels(profile, apiKey, ProviderRequestBudget(3), indicator)
    }

    fun generateStyleProposal(
        profileSnapshot: LlmProfile,
        styleDescription: String,
        currentPrompt: String,
        baseTemplate: CommitTemplateDefinition,
        indicator: ProgressIndicator,
    ): CommitStyleProposal {
        val (profile, apiKey) = credentials(profileSnapshot)
        val completion = providerClient.complete(
            profile = profile,
            apiKey = apiKey,
            request = LlmCompletionRequest(
                systemPrompt = """
                    You design reusable Git commit-message styles. Return exactly one JSON object with string fields prompt, template, and explanation.
                    The prompt controls wording, detail, tone, and verbosity. The template must be a valid Apache Velocity template and may use only:
                    type, scope, subject, body, changes, breakingChanges, closes, skipCi, and newline.
                    Preserve every structured field unless the requested style explicitly makes an optional field conditional. Never add network, file, reflection, or method calls.
                """.trimIndent(),
                userPrompt = buildString {
                    append("Create a reusable commit style from this natural-language description:\n")
                    append(styleDescription.trim().take(4_000))
                    if (currentPrompt.isNotBlank()) {
                        append("\n\n## Current style prompt\n").append(currentPrompt.take(4_000))
                    }
                    append("\n\n## Current validated template\n").append(baseTemplate.content.take(8_000))
                },
                structured = true,
                streaming = false,
                reasoningCompatibility = profile.reasoningCompatibility,
                maxOutputTokens = 4_096,
                structuredOutput = LlmStructuredOutput.STYLE_PROPOSAL,
            ),
            budget = ProviderRequestBudget(3),
            indicator = indicator,
        )
        val proposal = emohce.domain.commitmessage.LlmResponseParser.parseStyleProposalOrNull(completion.content)
            ?: throw InvalidAiOutputException()
        if (proposal.prompt.length > 4_000 || proposal.template.length > 8_000) throw InvalidAiOutputException()
        val candidate = CommitTemplateDefinition(
            id = "style-proposal",
            name = "Style proposal",
            content = proposal.template,
        )
        if (!renderer.validate(candidate).valid) throw InvalidAiOutputException()
        return proposal
    }

    private fun credentials(profileSnapshot: LlmProfile): ResolvedCredentials {
        val profile = profileSnapshot.copy()
        if (profile.model.isBlank() ||
            (profile.provider != emohce.domain.commitmessage.LlmProviderType.CHATGPT_CODEX && profile.baseUrl.isBlank())
        ) {
            throw MissingActiveProfileException()
        }
        return CommitMessageCredentialAccess.transaction {
            val projectState = CommitProjectStateService.getInstance(project)
            val selected = projectState.activeProfileRef()
            val projectProfile = projectState.profile(profile.id).takeIf {
                selected?.scope == emohce.domain.commitmessage.LlmProfileScope.PROJECT && selected.id == profile.id
            }
            val global = settings.snapshot()
            val current = projectProfile?.copy(
                temperature = global.llmTemperature,
                responseLanguage = global.llmResponseLanguage,
                streaming = global.llmStreaming,
            ) ?: global.profiles.firstOrNull { it.id == profile.id }?.copy(
                temperature = global.llmTemperature,
                responseLanguage = global.llmResponseLanguage,
                streaming = global.llmStreaming,
            ) ?: throw StaleProviderProfileException()
            if (profileConfigurationFingerprint(current) != profileConfigurationFingerprint(profile)) {
                throw StaleProviderProfileException()
            }
            val credential = if (profile.provider == emohce.domain.commitmessage.LlmProviderType.CHATGPT_CODEX) {
                CommitMessageCredentialSnapshot(
                    apiKey = "",
                    generation = CodexAppServerService.getInstance().authGeneration(),
                )
            } else {
                val credentialId = if (projectProfile != null) {
                    projectState.projectCredentialId(profile.id)
                } else {
                    profile.id
                }
                secretStore.credentialSnapshotWithinTransaction(credentialId).also {
                    if (it.apiKey.isNullOrBlank()) throw MissingApiKeyException()
                }
            }
            ResolvedCredentials(profile, credential.apiKey.orEmpty(), credential.generation)
        }
    }

    private data class ResolvedCredentials(
        val profile: LlmProfile,
        val apiKey: String,
        val generation: String,
    )

    private fun parseOrRepair(
        profile: LlmProfile,
        apiKey: String,
        content: String,
        allowedTypes: List<String>,
        stylePrompt: String,
        budget: ProviderRequestBudget,
        indicator: ProgressIndicator,
        operationInstructions: String = "",
        repairSystemPrompt: String? = null,
        repairSourcePrompt: String = "",
        expectedAuthGeneration: String? = null,
    ): CommitDraft {
        parseAllowedCommitDraft(content, allowedTypes)?.let { return it }
        if (budget.remainingRequests() <= 0) throw InvalidAiOutputException()
        val repaired = providerClient.complete(
            profile = profile,
            apiKey = apiKey,
            request = LlmCompletionRequest(
                systemPrompt = repairSystemPrompt ?: structuredCommitSystemPrompt(profile, allowedTypes, stylePrompt),
                userPrompt = buildString {
                    append("Repair this invalid response into the required JSON object without changing its meaning.")
                    if (operationInstructions.isNotBlank()) {
                        append(" Preserve these one-off operation instructions while repairing:\n")
                        append(operationInstructions.trim().take(4_000))
                    }
                    if (repairSourcePrompt.isNotBlank()) {
                        append("\n\n## Confirmed source request\n")
                        append(repairSourcePrompt)
                    }
                    append("\n\n## Invalid response\n").append(content.take(8_000))
                },
                structured = true,
                streaming = false,
                reasoningCompatibility = false,
                structuredOutput = LlmStructuredOutput.COMMIT_DRAFT,
                expectedAuthGeneration = expectedAuthGeneration,
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
        return snapshot.candidates.firstOrNull { renderer.validate(it).valid }
            ?: CommitMessageDefaults.templates().single()
    }

    companion object {
        private const val MAX_ALLOWED_TYPES: Int = 100
        private const val MAX_REFINEMENT_INSTRUCTION_LENGTH: Int = 4_000

        fun getInstance(project: Project): CommitMessageAiService =
            project.getService(CommitMessageAiService::class.java)
    }
}

internal fun buildPromptOptimizationEnvelope(
    currentCommitMessage: String,
    originalInstruction: String,
    profile: LlmProfile,
): LlmPromptEnvelope = LlmPromptEnvelope(
    systemPrompt = buildString {
        append("You improve one one-off instruction for rewriting a single Git commit message. ")
        append("Return exactly one JSON object with string fields optimizedInstruction and explanation. ")
        append("Preserve the user's intent, make the instruction precise and testable, and write it in ")
        append(profile.responseLanguage).append(". Do not rewrite the commit message, invent repository facts, ")
        append("request Git diff/files/revision history/secrets/external context, or include Markdown fences.")
    },
    userPrompt = buildString {
        append("## Current commit message\n").append(currentCommitMessage.take(16_000))
        append("\n\n## User modification request\n").append(originalInstruction.trim().take(4_000))
    },
)

internal fun buildFormatCommitPrompt(
    currentCommitMessage: String,
    templateContent: String,
    additionalInstructions: String,
): String = buildString {
    append("Rewrite only the current Git commit message into the configured structured fields. ")
    append("Preserve factual meaning, use only the text supplied below, and do not invent repository facts.\n\n")
    append("## Configured Velocity template\n").append(templateContent.take(8_000))
    append("\n\n## Current commit message\n").append(currentCommitMessage.take(16_000))
    if (additionalInstructions.isNotBlank()) {
        append("\n\n## One-off optimization instructions\n")
        append(additionalInstructions.trim().take(4_000))
    }
}

internal fun commitRefinementSystemPrompt(
    profile: LlmProfile,
    allowedTypes: List<String>,
    stylePrompt: String,
    persistentInstructions: String = "",
): String = buildString {
    append(structuredCommitSystemPrompt(profile, allowedTypes, stylePrompt, persistentInstructions))
    append("\n\nRefine only the supplied current commit message and treat it as the sole factual source. ")
    append("Apply the confirmed instruction; do not infer or request repository facts or Git context.")
}

internal fun profileConfigurationFingerprint(profile: LlmProfile): String = listOf(
    profile.id,
    profile.provider.name,
    profile.baseUrl.trim().trimEnd('/'),
    profile.model,
    profile.temperature.toString(),
    profile.responseLanguage,
    profile.streaming.toString(),
    profile.reasoningCompatibility.toString(),
    profile.reasoningEffort,
).joinToString("\u001F")

private fun ensurePreparedProfile(expectedFingerprint: String, profile: LlmProfile) {
    if (profileConfigurationFingerprint(profile) != expectedFingerprint) throw StaleProviderProfileException()
}

internal fun renderValidatedTemplate(
    renderer: CommitTemplateRenderer,
    template: CommitTemplateDefinition,
    draft: CommitDraft,
): String = renderer.render(template, draft).trim()

internal fun structuredCommitSystemPrompt(
    profile: LlmProfile,
    allowedTypes: List<String>,
    stylePrompt: String = "",
    persistentInstructions: String = "",
): String = buildString {
    append("You create Git commit messages in ${profile.responseLanguage}. Return exactly one JSON object with these fields:\n")
    append("type, scope, subject, body, breakingChanges, closes, skipCi. All text fields are strings and skipCi is boolean.\n")
    append("The type field must be exactly one of: ${allowedTypes.joinToString(", ")}.\n")
    append("Subject is required, concise, imperative, and has no trailing period. Do not include Markdown fences.")
    if (persistentInstructions.isNotBlank()) {
        append("\n\nFollow these configured extra instructions when they do not conflict with the required schema, factuality, or privacy rules:\n")
        append(persistentInstructions.trim().take(CommitProjectSharedSettingsState.MAX_EFFECTIVE_INSTRUCTIONS))
    }
    if (stylePrompt.isNotBlank()) {
        append("\n\nFollow this configured commit style:\n")
        append(stylePrompt.trim().take(4_000))
    }
}

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
