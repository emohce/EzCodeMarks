package emohce.data.commitmessage

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import emohce.domain.commitmessage.CommitMessageDefaults
import emohce.domain.commitmessage.CommitTemplateDefinition
import emohce.domain.commitmessage.CommitTypeDefinition
import emohce.domain.commitmessage.LlmProfile
import emohce.domain.commitmessage.TypeDisplayMode

data class CommitMessageSettingsState(
    var schemaVersion: Int = 1,
    var showCreateInToolbar: Boolean = true,
    var showGenerateInToolbar: Boolean = true,
    var showGenerateWithContextInToolbar: Boolean = true,
    var showFormatInToolbar: Boolean = false,
    var showType: Boolean = true,
    var showScope: Boolean = true,
    var showBody: Boolean = true,
    var showBreakingChanges: Boolean = true,
    var showCloses: Boolean = true,
    var showSkipCi: Boolean = true,
    var typeDisplayMode: TypeDisplayMode = TypeDisplayMode.COMBO,
    var defaultSkipCi: Boolean = false,
    var smartEcho: Boolean = false,
    var defaultTemplateId: String = CommitMessageDefaults.DEFAULT_TEMPLATE_ID,
    var templates: MutableList<CommitTemplateDefinition> = CommitMessageDefaults.templates(),
    var types: MutableList<CommitTypeDefinition> = CommitMessageDefaults.types(),
    var activeProfileId: String = "",
    var profiles: MutableList<LlmProfile> = mutableListOf(),
) {
    fun deepCopy(): CommitMessageSettingsState = copy(
        templates = templates.map { it.copy() }.toMutableList(),
        types = types.map { it.copy() }.toMutableList(),
        profiles = profiles.map { it.copy() }.toMutableList(),
    )

    fun normalize() {
        schemaVersion = 1
        if (templates.none { it.id == CommitMessageDefaults.DEFAULT_TEMPLATE_ID }) {
            templates.add(0, CommitMessageDefaults.templates().single())
        }
        templates.first { it.id == CommitMessageDefaults.DEFAULT_TEMPLATE_ID }.apply {
            name = "Conventional Commit"
            builtIn = true
            if (content.isBlank()) content = CommitMessageDefaults.defaultTemplateContent
        }
        if (types.isEmpty()) types = CommitMessageDefaults.types()
        if (templates.none { it.id == defaultTemplateId }) {
            defaultTemplateId = CommitMessageDefaults.DEFAULT_TEMPLATE_ID
        }
        if (profiles.none { it.id == activeProfileId }) activeProfileId = profiles.firstOrNull()?.id.orEmpty()
        profiles.forEach {
            it.baseUrl = it.baseUrl.trim().trimEnd('/')
            it.temperature = it.temperature.coerceIn(0.0, 2.0)
        }
    }
}

@Service(Service.Level.APP)
@State(
    name = "EzCodeMarksCommitMessageSettings",
    storages = [Storage("ezCodeMarksCommitMessage.xml")],
)
class CommitMessageSettingsService : PersistentStateComponent<CommitMessageSettingsState> {
    @Volatile
    private var currentState = CommitMessageSettingsState()

    override fun getState(): CommitMessageSettingsState = currentState

    override fun loadState(state: CommitMessageSettingsState) {
        val migrated = CommitMessageSettingsState()
        XmlSerializerUtil.copyBean(state, migrated)
        migrated.normalize()
        currentState = migrated
    }

    fun replaceState(state: CommitMessageSettingsState) {
        val replacement = state.deepCopy()
        replacement.normalize()
        currentState = replacement
    }

    private fun activeProfile(): LlmProfile? = currentState.profiles.firstOrNull { it.id == currentState.activeProfileId }

    fun activeProfileSnapshot(): LlmProfile? = CommitMessageCredentialAccess.read { activeProfile()?.copy() }

    fun grantSourceContextConsent(expected: LlmProfile): LlmProfile? = CommitMessageCredentialAccess.write {
        val current = currentState.profiles.firstOrNull { it.id == expected.id } ?: return@write null
        if (current.provider != expected.provider || normalizedEndpoint(current.baseUrl) != normalizedEndpoint(expected.baseUrl)) {
            return@write null
        }
        SourceContextConsent.grant(current)
        current.copy()
    }

    internal fun profile(profileId: String): LlmProfile? = currentState.profiles.firstOrNull { it.id == profileId }

    fun template(id: String): CommitTemplateDefinition? = currentState.templates.firstOrNull { it.id == id }

    fun defaultTemplate(): CommitTemplateDefinition =
        template(currentState.defaultTemplateId)
            ?: template(CommitMessageDefaults.DEFAULT_TEMPLATE_ID)
            ?: CommitMessageDefaults.templates().single()

    private fun normalizedEndpoint(value: String): String = value.trim().trimEnd('/')

    companion object {
        fun getInstance(): CommitMessageSettingsService =
            ApplicationManager.getApplication().getService(CommitMessageSettingsService::class.java)
    }
}
