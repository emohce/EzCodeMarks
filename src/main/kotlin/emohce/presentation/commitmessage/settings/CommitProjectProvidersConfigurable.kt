package emohce.presentation.commitmessage.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import emohce.data.commitmessage.CommitMessageCredentialAccess
import emohce.data.commitmessage.CommitMessageSecretStore
import emohce.data.commitmessage.CommitProjectStateService
import emohce.data.commitmessage.DefaultLlmProviderClient
import emohce.data.commitmessage.LlmProviderClient
import emohce.data.commitmessage.MissingApiKeyException
import emohce.data.commitmessage.StaleProviderProfileException
import emohce.data.commitmessage.hasSameCredentialDestination
import emohce.domain.commitmessage.LlmProfile
import emohce.domain.commitmessage.LlmProfileRef
import emohce.domain.commitmessage.LlmProfileScope
import emohce.domain.commitmessage.LlmProviderType
import emohce.domain.commitmessage.ProviderErrorSanitizer
import emohce.domain.commitmessage.ProviderException
import emohce.domain.commitmessage.ProviderRequestBudget
import emohce.presentation.commitmessage.CommitMessageBundle
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import org.jetbrains.annotations.TestOnly

private val DEFAULT_PROJECT_PROVIDER_REQUEST_RUNNER = ProviderSettingsRequestRunner { title, operation ->
    ProgressManager.getInstance().run(object : Task.Backgroundable(null, title, true) {
        override fun run(indicator: ProgressIndicator) = operation(indicator)
    })
}

internal class CommitProjectProvidersConfigurable(
    private val project: Project,
    private val secretStore: CommitMessageSecretStore = CommitMessageSecretStore(),
    private val providerClient: LlmProviderClient = DefaultLlmProviderClient(),
    private val requestRunner: ProviderSettingsRequestRunner = DEFAULT_PROJECT_PROVIDER_REQUEST_RUNNER,
    private val profileEditor: ProviderProfileEditor = DEFAULT_PROVIDER_PROFILE_EDITOR,
    private val confirmApiKeyClear: () -> Boolean = {
        Messages.showYesNoDialog(
            CommitMessageBundle.message("settings.providers.clearApiKey.confirm"),
            CommitMessageBundle.message("settings.providers.clearApiKey"),
            Messages.getQuestionIcon(),
        ) == Messages.YES
    },
    private val notifyMissingApiKey: () -> Unit = {
        Messages.showWarningDialog(
            CommitMessageBundle.message("error.apiKey.missing"),
            CommitMessageBundle.message("settings.project.providers.title"),
        )
    },
) : SearchableConfigurable {
    private val projectState: CommitProjectStateService
        get() = CommitProjectStateService.getInstance(project)
    private var profiles = mutableListOf<LlmProfile>()
    private var loadedProfiles = emptyList<LlmProfile>()
    private var loadedActiveRef: LlmProfileRef? = null
    private var activeProfileId = ""
    private var activeTouched = false
    private var loading = false
    private val sessionKeys = linkedMapOf<String, CharArray>()
    private val dirtyKeyIds = linkedSetOf<String>()
    private val clearedKeyIds = linkedSetOf<String>()
    private val removedProfileIds = linkedSetOf<String>()
    private val fetchedModels = linkedMapOf<String, List<String>>()
    private var root: JComponent? = null

    private val tableModel = ProfileTableModel()
    private val profileTable = JBTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        emptyText.text = CommitMessageBundle.message("settings.project.providers.empty")
        accessibleContext.accessibleName = CommitMessageBundle.message("settings.project.providers.title")
    }
    private val activeProfileCombo = ComboBox<ProfileChoice>().apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean,
            ): java.awt.Component = super.getListCellRendererComponent(list, value, index, selected, focused).also {
                (it as JLabel).text = (value as? ProfileChoice)?.label.orEmpty()
            }
        }
    }

    init {
        activeProfileCombo.addActionListener {
            if (loading) return@addActionListener
            activeProfileId = (activeProfileCombo.selectedItem as? ProfileChoice)?.id.orEmpty()
            activeTouched = true
            selectProfile(activeProfileId)
        }
        profileTable.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.button != java.awt.event.MouseEvent.BUTTON1 || event.clickCount != 2) return
                val row = profileTable.rowAtPoint(event.point)
                if (row < 0) return
                profileTable.setRowSelectionInterval(row, row)
                editSelectedProfile()
            }
        })
    }

    override fun getId(): String = ID

    override fun getDisplayName(): String = CommitMessageBundle.message("settings.project.providers.title")

    override fun createComponent(): JComponent {
        root?.let { return it }
        reset()
        val tablePanel = ToolbarDecorator.createDecorator(profileTable)
            .setAddAction { addProfile() }
            .setEditAction { editSelectedProfile() }
            .setRemoveAction { removeSelectedProfile() }
            .disableUpDownActions()
            .addExtraAction(object : DumbAwareAction(
                CommitMessageBundle.message("settings.providers.copy"),
                null,
                AllIcons.Actions.Copy,
            ) {
                override fun actionPerformed(event: AnActionEvent) = copySelectedProfile()
            })
            .createPanel()
        return panel {
            row(CommitMessageBundle.message("settings.project.providers.active")) {
                cell(activeProfileCombo).align(Align.FILL).resizableColumn()
            }
            row {
                cell(tablePanel).align(Align.FILL).resizableColumn()
            }.resizableRow()
        }.apply {
            preferredSize = preferredSize.apply { height = JBUI.scale(520) }
        }.also { root = it }
    }

    override fun isModified(): Boolean = profiles != loadedProfiles || activeTouched ||
        dirtyKeyIds.isNotEmpty() || clearedKeyIds.isNotEmpty() || removedProfileIds.isNotEmpty()

    override fun apply() {
        validateProfiles()
        val currentProfiles = projectState.state.profiles.map { it.copy(sourceConsentFingerprint = "") }
        val currentActive = projectState.activeProfileRef()
        if (currentProfiles != loadedProfiles || currentActive != loadedActiveRef) {
            throw ConfigurationException(CommitMessageBundle.message("settings.project.providers.concurrentChange"))
        }
        val keyWrites = dirtyKeyIds
            .filterNot(clearedKeyIds::contains)
            .mapNotNull { id -> sessionKeys[id]?.copyOf()?.let { id to it } }
            .toMap()
        val keyDeletes = (removedProfileIds + clearedKeyIds).distinct()
        val consentInvalidations = loadedProfiles.mapNotNull { previous ->
            val current = profiles.firstOrNull { it.id == previous.id }
            previous.id.takeIf {
                current == null || current.provider != previous.provider || current.baseUrl != previous.baseUrl
            }
        }
        try {
            CommitMessageCredentialAccess.transaction {
                val credentialIds = (keyDeletes + keyWrites.keys).associateWith(projectState::projectCredentialId)
                val originals = credentialIds.mapValues { (_, credentialId) -> secretStore.getCredentials(credentialId) }
                try {
                    keyDeletes.forEach { id -> secretStore.clearApiKey(checkNotNull(credentialIds[id])) }
                    keyWrites.forEach { (id, key) -> secretStore.setApiKey(checkNotNull(credentialIds[id]), key) }
                    projectState.replaceProfiles(profiles)
                    if (activeTouched) {
                        projectState.setActiveProfile(
                            activeProfileId.takeIf(String::isNotBlank)?.let {
                                LlmProfileRef(LlmProfileScope.PROJECT, it)
                            },
                        )
                    }
                    consentInvalidations.forEach(projectState::invalidateSourceContextConsent)
                } catch (error: Throwable) {
                    originals.forEach { (id, value) ->
                        val credentialId = checkNotNull(credentialIds[id])
                        runCatching { secretStore.restoreCredentials(credentialId, value) }
                            .exceptionOrNull()
                            ?.let(error::addSuppressed)
                    }
                    throw error
                }
            }
        } catch (error: Throwable) {
            throw ConfigurationException(
                CommitMessageBundle.message("error.credentials.save"),
                error,
                CommitMessageBundle.message("settings.project.providers.title"),
            )
        } finally {
            keyWrites.values.forEach { it.fill('\u0000') }
        }
        loadAppliedState(clearSessionData = false)
    }

    override fun reset() {
        loadAppliedState(clearSessionData = true)
    }

    private fun loadAppliedState(clearSessionData: Boolean) {
        if (clearSessionData) {
            clearSessionKeys()
            fetchedModels.clear()
        }
        dirtyKeyIds.clear()
        clearedKeyIds.clear()
        removedProfileIds.clear()
        loadedProfiles = projectState.state.profiles
            .map { it.copy(sourceConsentFingerprint = "") }
        profiles = loadedProfiles.map { it.copy() }.toMutableList()
        loadedActiveRef = projectState.activeProfileRef()?.copy()
        activeProfileId = loadedActiveRef
            ?.takeIf { it.scope == LlmProfileScope.PROJECT && profiles.any { profile -> profile.id == it.id } }
            ?.id
            .orEmpty()
        activeTouched = false
        refreshUi()
    }

    override fun disposeUIResources() {
        clearSessionKeys()
        root = null
    }

    @TestOnly
    internal fun addProfileForTest() = addProfile()

    @TestOnly
    internal fun editProfileForTest(profileId: String) {
        selectProfile(profileId)
        editSelectedProfile()
    }

    private fun addProfile() {
        val profile = LlmProfile(
            id = UUID.randomUUID().toString(),
            name = uniqueName(CommitMessageBundle.message("settings.providers.newName")),
        )
        editProfile(profile, isNew = true)
    }

    private fun editSelectedProfile() {
        val selected = selectedProfile() ?: return
        editProfile(selected.copy(), isNew = false)
    }

    private fun editProfile(profile: LlmProfile, isNew: Boolean) {
        val result = profileEditor.edit(
            ProviderProfileEditInput(
                title = CommitMessageBundle.message(
                    if (isNew) "settings.providers.dialog.add" else "settings.providers.dialog.edit",
                ),
                profile = profile,
                sessionKey = sessionKeys[profile.id]?.copyOf(),
                hasStoredKey = hasStoredKey(profile.id),
                modelSuggestions = fetchedModels[profile.id].orEmpty(),
            ),
            modelFetcher(),
        ) ?: return
        val edited = result.profile.copy(sourceConsentFingerprint = "")
        val original = profiles.firstOrNull { it.id == edited.id }
        if (original != null && (original.provider != edited.provider || original.baseUrl != edited.baseUrl)) {
            fetchedModels.remove(edited.id)
        }
        if (isNew) {
            profiles += edited
        } else {
            profiles.indexOfFirst { it.id == edited.id }.takeIf { it >= 0 }?.let { profiles[it] = edited }
        }
        if (result.modelSuggestions.isNotEmpty()) fetchedModels[edited.id] = result.modelSuggestions.distinct()
        applyEditedKey(edited.id, result)
        if (activeProfileId.isBlank() && profiles.size == 1) {
            activeProfileId = edited.id
            activeTouched = true
        }
        refreshUi(edited.id)
    }

    private fun copySelectedProfile() {
        val selected = selectedProfile() ?: return
        val copy = selected.copy(
            id = UUID.randomUUID().toString(),
            name = uniqueName(CommitMessageBundle.message("common.copyName", selected.name)),
            sourceConsentFingerprint = "",
        )
        profiles += copy
        fetchedModels[copy.id] = fetchedModels[selected.id].orEmpty()
        activeProfileId = copy.id
        activeTouched = true
        refreshUi(copy.id)
    }

    private fun removeSelectedProfile() {
        val selected = selectedProfile() ?: return
        if (loadedProfiles.any { it.id == selected.id }) removedProfileIds += selected.id
        sessionKeys.remove(selected.id)?.fill('\u0000')
        dirtyKeyIds -= selected.id
        clearedKeyIds -= selected.id
        fetchedModels.remove(selected.id)
        profiles.removeAll { it.id == selected.id }
        if (activeProfileId == selected.id) {
            activeProfileId = ""
            activeTouched = true
        }
        refreshUi()
    }

    private fun applyEditedKey(profileId: String, result: ProviderProfileEditResult) {
        if (result.clearApiKey && confirmApiKeyClear()) {
            sessionKeys.remove(profileId)?.fill('\u0000')
            dirtyKeyIds -= profileId
            if (loadedProfiles.any { it.id == profileId }) clearedKeyIds += profileId
        }
        result.apiKey?.let { key ->
            try {
                sessionKeys.remove(profileId)?.fill('\u0000')
                sessionKeys[profileId] = key.copyOf()
                dirtyKeyIds += profileId
                clearedKeyIds -= profileId
            } finally {
                key.fill('\u0000')
            }
        }
    }

    private fun modelFetcher(): ProviderModelFetcher = ProviderModelFetcher { profile, enteredKey, completed ->
        val request = ProjectModelRequest()
        requestRunner.run(CommitMessageBundle.message("settings.providers.fetchModels")) { indicator ->
            request.attach(indicator)
            val key = try {
                if (profile.provider == LlmProviderType.CHATGPT_CODEX) "" else resolveApiKey(profile, enteredKey)
            } catch (error: Throwable) {
                onEdt { completed(Result.failure(error)) }
                return@run
            }
            if (profile.provider != LlmProviderType.CHATGPT_CODEX && key.isBlank()) {
                onEdt {
                    completed(Result.failure(MissingApiKeyException()))
                }
                return@run
            }
            runCatching {
                providerClient.fetchModels(profile, key, ProviderRequestBudget(3), indicator)
            }.onSuccess { models ->
                onEdt { completed(Result.success(models)) }
            }.onFailure { error ->
                onEdt {
                    val safe = ProviderErrorSanitizer.sanitize(error.message, key)
                    val sanitized = if (error is ProviderException) {
                        ProviderException(error.kind, safe, error)
                    } else {
                        RuntimeException(safe, error)
                    }
                    completed(Result.failure(sanitized))
                }
            }
        }
        request
    }

    private fun resolveApiKey(profile: LlmProfile, enteredKey: CharArray?): String {
        val profileId = profile.id
        if (enteredKey != null) return enteredKey.concatToString()
        if (profileId in clearedKeyIds) return ""
        sessionKeys[profileId]?.let { return it.concatToString() }
        return CommitMessageCredentialAccess.transaction {
            val loaded = loadedProfiles.firstOrNull { it.id == profileId }
                ?: throw StaleProviderProfileException()
            val current = projectState.state.profiles.firstOrNull { it.id == profileId }
                ?: throw StaleProviderProfileException()
            if (!hasSameCredentialDestination(current, loaded)) throw StaleProviderProfileException()
            secretStore.credentialSnapshotWithinTransaction(projectState.projectCredentialId(profileId)).apiKey.orEmpty()
        }
    }

    private fun hasStoredKey(profileId: String): Boolean {
        if (profileId in clearedKeyIds) return false
        if (sessionKeys[profileId]?.isNotEmpty() == true) return true
        return CommitMessageCredentialAccess.read {
            !secretStore.getApiKey(projectState.projectCredentialId(profileId)).isNullOrBlank()
        }
    }

    private fun validateProfiles() {
        val invalid = profiles.any {
            it.id.isBlank() || it.name.isBlank() ||
                (it.provider != LlmProviderType.CHATGPT_CODEX && !isSafeEndpoint(it.baseUrl))
        } || profiles.map { it.id }.distinct().size != profiles.size
        if (invalid) throw ConfigurationException(CommitMessageBundle.message("settings.providers.validation"))
    }

    private fun refreshUi(selectedId: String? = null) {
        loading = true
        try {
            tableModel.fireTableDataChanged()
            val choices = buildList {
                add(ProfileChoice("", CommitMessageBundle.message("settings.project.providers.useGlobal")))
                profiles.forEach { add(ProfileChoice(it.id, "${it.name} (${providerLabel(it.provider)})")) }
            }.toTypedArray()
            activeProfileCombo.model = DefaultComboBoxModel(choices)
            activeProfileCombo.selectedItem = choices.firstOrNull { it.id == activeProfileId } ?: choices.first()
            activeProfileId = (activeProfileCombo.selectedItem as? ProfileChoice)?.id.orEmpty()
            selectProfile(selectedId.orEmpty())
        } finally {
            loading = false
        }
    }

    private fun selectProfile(profileId: String) {
        val modelIndex = profiles.indexOfFirst { it.id == profileId }
        val viewIndex = modelIndex.takeIf { it >= 0 }?.let(profileTable::convertRowIndexToView) ?: -1
        if (viewIndex >= 0) profileTable.setRowSelectionInterval(viewIndex, viewIndex) else profileTable.clearSelection()
    }

    private fun selectedProfile(): LlmProfile? {
        val viewIndex = profileTable.selectedRow
        if (viewIndex < 0) return null
        return profiles.getOrNull(profileTable.convertRowIndexToModel(viewIndex))
    }

    private fun uniqueName(base: String): String {
        if (profiles.none { it.name == base }) return base
        var suffix = 2
        while (profiles.any { it.name == "$base $suffix" }) suffix += 1
        return "$base $suffix"
    }

    private fun providerLabel(provider: LlmProviderType): String = CommitMessageBundle.message(
        when (provider) {
            LlmProviderType.OPENAI_COMPATIBLE -> "provider.openaiCompatible"
            LlmProviderType.ANTHROPIC -> "provider.anthropic"
            LlmProviderType.CHATGPT_CODEX -> "provider.chatgptCodex"
        },
    )

    private fun isSafeEndpoint(value: String): Boolean {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() in setOf("http", "https") &&
            uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
    }

    private fun onEdt(action: () -> Unit) {
        if (ApplicationManager.getApplication().isDispatchThread) action()
        else ApplicationManager.getApplication().invokeLater(action, ModalityState.any())
    }

    private fun clearSessionKeys() {
        sessionKeys.values.forEach { it.fill('\u0000') }
        sessionKeys.clear()
    }

    private inner class ProfileTableModel : AbstractTableModel() {
        override fun getRowCount(): Int = profiles.size
        override fun getColumnCount(): Int = 4
        override fun getColumnName(column: Int): String = CommitMessageBundle.message(
            listOf(
                "settings.providers.column.name",
                "settings.providers.column.provider",
                "settings.providers.column.endpoint",
                "settings.providers.column.model",
            )[column],
        )

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = profiles[rowIndex].let { profile ->
            when (columnIndex) {
                0 -> profile.name
                1 -> providerLabel(profile.provider)
                2 -> profile.baseUrl.ifBlank {
                    if (profile.provider == LlmProviderType.CHATGPT_CODEX) {
                        CommitMessageBundle.message("settings.providers.codex.managed")
                    } else {
                        ""
                    }
                }
                else -> profile.model
            }
        }
    }

    private data class ProfileChoice(val id: String, val label: String) {
        override fun toString(): String = label
    }

    private class ProjectModelRequest : ProviderModelRequest {
        private val cancelled = AtomicBoolean(false)
        private val indicator = AtomicReference<ProgressIndicator?>()

        fun attach(value: ProgressIndicator) {
            indicator.set(value)
            if (cancelled.get()) value.cancel()
            value.checkCanceled()
        }

        override fun cancel() {
            cancelled.set(true)
            indicator.get()?.cancel()
        }
    }

    companion object {
        const val ID = "emohce.settings.commitMessage.projectProviders"
    }
}
