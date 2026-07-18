package emohce.data.commitmessage

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import emohce.domain.commitmessage.CommitDraft
import emohce.domain.commitmessage.CommitMessageDefaults
import emohce.domain.commitmessage.CommitTemplateDefinition
import emohce.domain.commitmessage.CommitTemplateRenderer
import emohce.domain.commitmessage.CommitStyleDefinition
import emohce.domain.commitmessage.LlmProfile
import emohce.domain.commitmessage.LlmProfileRef
import emohce.domain.commitmessage.LlmProfileScope
import java.util.UUID

data class CommitProjectState(
    var schemaVersion: Int = 2,
    var credentialNamespace: String = UUID.randomUUID().toString(),
    var templateId: String = "",
    var styleId: String = "",
    var activeProfileScope: LlmProfileScope = LlmProfileScope.GLOBAL,
    var activeProfileId: String = "",
    var profiles: MutableList<LlmProfile> = mutableListOf(),
    var sourceConsentGrants: MutableList<SourceContextConsentGrant> = mutableListOf(),
    var draft: CommitDraft? = null,
)

data class SourceContextConsentGrant(
    var key: String = "",
    var fingerprint: String = "",
)

@Service(Service.Level.PROJECT)
@State(
    name = "EzCodeMarksCommitProjectState",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class CommitProjectStateService : PersistentStateComponent<CommitProjectState> {
    private var currentState = CommitProjectState()
    private var unsupportedSchema = false

    override fun getState(): CommitProjectState = currentState

    override fun loadState(state: CommitProjectState) {
        if (state.schemaVersion !in 0..CURRENT_SCHEMA_VERSION) {
            currentState = state.copy(
                profiles = state.profiles.map { it.copy(sourceConsentFingerprint = "") }.toMutableList(),
                sourceConsentGrants = state.sourceConsentGrants.map { it.copy() }.toMutableList(),
                draft = state.draft?.copy(),
            )
            unsupportedSchema = true
            return
        }
        unsupportedSchema = false
        currentState = state.copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            credentialNamespace = state.credentialNamespace.takeIf(::isUuid) ?: UUID.randomUUID().toString(),
            profiles = state.profiles.map(::normalizedProfile).toMutableList(),
            sourceConsentGrants = state.sourceConsentGrants.map { it.copy() }.toMutableList(),
            draft = state.draft?.copy(),
        )
    }

    fun saveDraft(draft: CommitDraft?) {
        ensureSupported()
        currentState.draft = draft?.copy()
    }

    fun clearDraft() {
        ensureSupported()
        currentState.draft = null
    }

    fun setTemplateId(templateId: String) {
        ensureSupported()
        currentState.templateId = templateId
    }

    fun setStyleId(styleId: String) {
        ensureSupported()
        currentState.styleId = styleId
    }

    fun setActiveProfile(ref: LlmProfileRef?) {
        ensureSupported()
        currentState.activeProfileScope = ref?.scope ?: LlmProfileScope.GLOBAL
        currentState.activeProfileId = ref?.id.orEmpty()
    }

    fun replaceProfiles(profiles: List<LlmProfile>) {
        ensureSupported()
        currentState.profiles = profiles
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .map(::normalizedProfile)
            .toMutableList()
        if (currentState.activeProfileScope == LlmProfileScope.PROJECT &&
            currentState.profiles.none { it.id == currentState.activeProfileId }
        ) {
            setActiveProfile(null)
        }
    }

    fun projectCredentialId(profileId: String): String =
        "project:${currentState.credentialNamespace}:$profileId".also { ensureSupported() }

    fun activeProfileRef(): LlmProfileRef? = currentState.activeProfileId
        .also { ensureSupported() }
        .takeIf(String::isNotBlank)
        ?.let { LlmProfileRef(currentState.activeProfileScope, it) }

    internal fun profile(profileId: String): LlmProfile? {
        ensureSupported()
        return currentState.profiles.firstOrNull { it.id == profileId }
    }

    fun hasSourceContextConsent(profile: LlmProfile, accountGeneration: String): Boolean {
        ensureSupported()
        val expected = SourceContextConsent.fingerprint(profile, accountGeneration)
        return currentState.sourceConsentGrants.any { it.key == profile.id && it.fingerprint == expected }
    }

    fun grantSourceContextConsent(profile: LlmProfile, accountGeneration: String) {
        ensureSupported()
        currentState.sourceConsentGrants.removeAll { it.key == profile.id }
        currentState.sourceConsentGrants += SourceContextConsentGrant(
            key = profile.id,
            fingerprint = SourceContextConsent.fingerprint(profile, accountGeneration),
        )
    }

    fun invalidateSourceContextConsent(profileId: String) {
        ensureSupported()
        currentState.sourceConsentGrants.removeAll { it.key == profileId }
    }

    fun resolveProfileSnapshot(settings: CommitMessageSettingsService): LlmProfile? {
        ensureSupported()
        val selected = activeProfileRef()
        val globals = settings.snapshot()
        if (selected?.scope == LlmProfileScope.PROJECT) {
            currentState.profiles.firstOrNull { it.id == selected.id }?.let { profile ->
                return profile.copy(
                    temperature = globals.llmTemperature,
                    responseLanguage = globals.llmResponseLanguage,
                    streaming = globals.llmStreaming,
                    sourceConsentFingerprint = "",
                )
            }
        }
        if (selected?.scope == LlmProfileScope.GLOBAL) {
            globals.profiles.firstOrNull { it.id == selected.id }?.let { profile ->
                return profile.copy(
                    temperature = globals.llmTemperature,
                    responseLanguage = globals.llmResponseLanguage,
                    streaming = globals.llmStreaming,
                )
            }
        }
        return globals.profiles.firstOrNull { it.id == globals.activeProfileId }
            ?.copy(
                temperature = globals.llmTemperature,
                responseLanguage = globals.llmResponseLanguage,
                streaming = globals.llmStreaming,
            )
            ?: globals.profiles.firstOrNull()?.copy(
                temperature = globals.llmTemperature,
                responseLanguage = globals.llmResponseLanguage,
                streaming = globals.llmStreaming,
            )
    }

    fun resolveStyle(settings: CommitMessageSettingsService): CommitStyleDefinition {
        ensureSupported()
        val globals = settings.snapshot()
        return globals.styles.firstOrNull { it.id == currentState.styleId }
            ?: globals.styles.firstOrNull { it.id == globals.defaultStyleId }
            ?: globals.styles.firstOrNull { it.id == CommitMessageDefaults.STANDARD_STYLE_ID }
            ?: CommitMessageDefaults.standardStyle()
    }

    fun resolveStyle(
        settings: CommitMessageSettingsService,
        shared: CommitProjectSharedSettingsService,
    ): CommitStyleDefinition {
        ensureSupported()
        val globals = settings.snapshot()
        return globals.styles.firstOrNull { it.id == currentState.styleId }
            ?: shared.style(currentState.styleId)
            ?: shared.defaultStyle()
            ?: globals.styles.firstOrNull { it.id == globals.defaultStyleId }
            ?: globals.styles.firstOrNull { it.id == CommitMessageDefaults.STANDARD_STYLE_ID }
            ?: CommitMessageDefaults.standardStyle()
    }

    fun resolveTemplate(settings: CommitMessageSettingsService): CommitTemplateDefinition {
        ensureSupported()
        return templateCandidates(settings).first()
    }

    fun resolveValidTemplate(
        settings: CommitMessageSettingsService,
        renderer: CommitTemplateRenderer,
    ): CommitTemplateDefinition = templateCandidates(settings).firstOrNull { renderer.validate(it).valid }
        ?: CommitMessageDefaults.templates().single()

    fun resolveValidTemplate(
        settings: CommitMessageSettingsService,
        shared: CommitProjectSharedSettingsService,
        renderer: CommitTemplateRenderer,
    ): CommitTemplateDefinition = templateCandidates(settings, shared).firstOrNull { renderer.validate(it).valid }
        ?: CommitMessageDefaults.templates().single()

    fun templateCandidates(settings: CommitMessageSettingsService): List<CommitTemplateDefinition> {
        ensureSupported()
        return templateCandidates(settings, null)
    }

    fun templateCandidates(
        settings: CommitMessageSettingsService,
        shared: CommitProjectSharedSettingsService?,
    ): List<CommitTemplateDefinition> {
        ensureSupported()
        val globals = settings.snapshot()
        val projectId = currentState.templateId
        val style = globals.styles.firstOrNull { it.id == currentState.styleId }
            ?: shared?.style(currentState.styleId)
            ?: shared?.defaultStyle()
            ?: globals.styles.firstOrNull { it.id == globals.defaultStyleId }
            ?: globals.styles.firstOrNull { it.id == CommitMessageDefaults.STANDARD_STYLE_ID }
            ?: CommitMessageDefaults.standardStyle()
        val builtIn = CommitMessageDefaults.templates().single()
        return buildList {
            globals.templates.firstOrNull { it.id == projectId }?.let { add(it.copy()) }
            shared?.template(projectId)?.let { add(it.copy()) }
            if (style.templateContent.isNotBlank()) {
                add(
                    CommitTemplateDefinition(
                        id = "${style.id}.template",
                        name = style.name,
                        content = style.templateContent,
                        builtIn = style.builtIn,
                    ),
                )
            }
            shared?.defaultTemplate()?.let { add(it.copy()) }
            globals.templates.firstOrNull { it.id == globals.defaultTemplateId }?.let { add(it.copy()) }
            globals.templates.firstOrNull { it.id == CommitMessageDefaults.DEFAULT_TEMPLATE_ID }?.let { add(it.copy()) }
            add(builtIn)
        }
            .distinctBy { it.id to it.content }
    }

    companion object {
        private const val CURRENT_SCHEMA_VERSION = 2
        private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

        private fun normalizedProfile(profile: LlmProfile): LlmProfile = profile.copy(
            baseUrl = if (profile.provider == emohce.domain.commitmessage.LlmProviderType.CHATGPT_CODEX) {
                ""
            } else {
                profile.baseUrl.trim().trimEnd('/').takeIf(::isSafeProviderEndpoint).orEmpty()
            },
            sourceConsentFingerprint = "",
        )

        fun getInstance(project: Project): CommitProjectStateService =
            project.getService(CommitProjectStateService::class.java)
    }

    private fun ensureSupported() {
        check(!unsupportedSchema) { "Project commit-message settings use an unsupported schema" }
    }
}
