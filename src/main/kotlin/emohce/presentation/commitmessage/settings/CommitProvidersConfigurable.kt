package emohce.presentation.commitmessage.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import emohce.data.commitmessage.CommitMessageSecretStore
import emohce.data.commitmessage.CommitMessageCredentialAccess
import emohce.data.commitmessage.CommitMessageSettingsService
import emohce.data.commitmessage.HttpLlmProviderClient
import emohce.data.commitmessage.SourceContextConsent
import emohce.domain.commitmessage.LlmProfile
import emohce.domain.commitmessage.LlmProviderType
import emohce.domain.commitmessage.ProviderRequestBudget
import emohce.presentation.commitmessage.CommitMessageBundle
import java.awt.GridLayout
import java.util.UUID
import java.net.URI
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import java.util.concurrent.atomic.AtomicReference

class CommitProvidersConfigurable(
    private val secretStore: CommitMessageSecretStore = CommitMessageSecretStore(),
) : SearchableConfigurable {
    private var profiles = mutableListOf<LlmProfile>()
    private var activeProfileId = ""
    private var selectedIndex = -1
    private var loading = false
    private var connectionRequestGeneration = 0L
    private val removedProfileIds = linkedSetOf<String>()
    private val pendingKeys = linkedMapOf<String, CharArray>()
    private val profileModel = DefaultListModel<LlmProfile>()
    private val profileList = JBList(profileModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean,
            ): java.awt.Component = super.getListCellRendererComponent(list, value, index, selected, focused).also {
                (it as JLabel).text = (value as? LlmProfile)?.name.orEmpty()
            }
        }
    }
    private val activeProfileCombo = ComboBox<ProfileChoice>()
    private val profileNameField = JBTextField()
    private val providerCombo = ComboBox(
        arrayOf(
            ProviderChoice(
                LlmProviderType.OPENAI_COMPATIBLE,
                CommitMessageBundle.message("provider.openaiCompatible"),
            ),
            ProviderChoice(LlmProviderType.ANTHROPIC, CommitMessageBundle.message("provider.anthropic")),
        ),
    )
    private val endpointField = JBTextField()
    private val apiKeyField = JBPasswordField()
    private val modelField = JBTextField()
    private val temperatureField = JBTextField()
    private val languageField = JBTextField()
    private val streamingField = JBCheckBox(CommitMessageBundle.message("settings.providers.streaming"))
    private val reasoningField = JBCheckBox(CommitMessageBundle.message("settings.providers.reasoning"))
    private var root: JComponent? = null

    init {
        profileList.addListSelectionListener {
            if (!it.valueIsAdjusting && !loading) {
                connectionRequestGeneration++
                saveSelectedProfile()
                selectedIndex = profileList.selectedIndex
                loadSelectedProfile()
            }
        }
        activeProfileCombo.addActionListener {
            if (!loading) activeProfileId = (activeProfileCombo.selectedItem as? ProfileChoice)?.id.orEmpty()
        }
    }

    override fun getId(): String = ID

    override fun getDisplayName(): String = CommitMessageBundle.message("settings.providers.title")

    override fun createComponent(): JComponent {
        reset()
        return panel {
            row(CommitMessageBundle.message("settings.providers.active")) {
                cell(activeProfileCombo).align(Align.FILL)
            }
            row {
                scrollCell(profileList).align(Align.FILL)
                cell(buttons(
                    JButton(CommitMessageBundle.message("settings.providers.add")).apply {
                        addActionListener { addProfile() }
                    },
                    JButton(CommitMessageBundle.message("settings.providers.copy")).apply {
                        addActionListener { copyProfile() }
                    },
                    JButton(CommitMessageBundle.message("settings.providers.delete")).apply {
                        addActionListener { deleteProfile() }
                    },
                ))
            }.resizableRow()
            group(CommitMessageBundle.message("settings.providers.details")) {
                row(CommitMessageBundle.message("settings.providers.name")) {
                    cell(profileNameField).align(Align.FILL)
                }
                row(CommitMessageBundle.message("settings.providers.provider")) {
                    cell(providerCombo).align(Align.FILL)
                }
                row(CommitMessageBundle.message("settings.providers.endpoint")) {
                    cell(endpointField).align(Align.FILL)
                }
                row(CommitMessageBundle.message("settings.providers.apiKey")) {
                    cell(apiKeyField).align(Align.FILL)
                    comment(CommitMessageBundle.message("settings.providers.saved"))
                }
                row(CommitMessageBundle.message("settings.providers.model")) {
                    cell(modelField).align(Align.FILL)
                }
                row(CommitMessageBundle.message("settings.providers.temperature")) {
                    cell(temperatureField)
                }
                row(CommitMessageBundle.message("settings.providers.language")) {
                    cell(languageField).align(Align.FILL)
                }
                row { cell(streamingField); cell(reasoningField) }
                row {
                    button(CommitMessageBundle.message("settings.providers.test")) { testConnection(false) }
                    button(CommitMessageBundle.message("settings.providers.fetchModels")) { testConnection(true) }
                }
            }
        }.also { root = it }
    }

    override fun isModified(): Boolean {
        saveSelectedProfile()
        val state = CommitMessageSettingsService.getInstance().state
        return profiles != state.profiles || activeProfileId != state.activeProfileId ||
            pendingKeys.isNotEmpty() || removedProfileIds.isNotEmpty()
    }

    override fun apply() {
        saveSelectedProfile()
        if (profiles.any { it.name.isBlank() || !isSafeEndpoint(it.baseUrl) } ||
            profiles.map { it.id }.toSet().size != profiles.size
        ) {
            throw ConfigurationException(CommitMessageBundle.message("settings.providers.validation"))
        }
        val service = CommitMessageSettingsService.getInstance()
        val merged = service.state.deepCopy().apply {
            profiles = this@CommitProvidersConfigurable.profiles.map { it.copy() }.toMutableList()
            activeProfileId = this@CommitProvidersConfigurable.activeProfileId
        }
        val keyWrites = pendingKeys.mapValues { it.value.copyOf() }
        val keyDeletes = removedProfileIds.toList()
        val failure = AtomicReference<Throwable?>()
        val completed = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            Runnable {
                val store = secretStore
                try {
                    CommitMessageCredentialAccess.write {
                        val affected = (keyDeletes + keyWrites.keys).distinct()
                        val originals = affected.associateWith(store::getApiKey)
                        try {
                            keyDeletes.forEach(store::clearApiKey)
                            keyWrites.forEach { (id, value) -> store.setApiKey(id, value) }
                            service.replaceState(merged)
                        } catch (error: Throwable) {
                            runCatching {
                                originals.forEach { (id, value) ->
                                    if (value == null) {
                                        store.clearApiKey(id)
                                    } else {
                                        val chars = value.toCharArray()
                                        try {
                                            store.setApiKey(id, chars)
                                        } finally {
                                            chars.fill('\u0000')
                                        }
                                    }
                                }
                            }.exceptionOrNull()?.let(error::addSuppressed)
                            throw error
                        }
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    keyWrites.values.forEach { it.fill('\u0000') }
                }
            },
            CommitMessageBundle.message("progress.saveCredentials"),
            false,
            null,
        )
        if (!completed || failure.get() != null) {
            throw ConfigurationException(
                CommitMessageBundle.message("error.credentials.save"),
                failure.get(),
                CommitMessageBundle.message("settings.providers.title"),
            )
        }
        clearPendingKeys()
        removedProfileIds.clear()
        reset()
    }

    override fun reset() {
        connectionRequestGeneration++
        clearPendingKeys()
        removedProfileIds.clear()
        val state = CommitMessageSettingsService.getInstance().state
        profiles = state.profiles.map { it.copy() }.toMutableList()
        activeProfileId = state.activeProfileId
        rebuildModels()
    }

    override fun disposeUIResources() {
        connectionRequestGeneration++
        clearPendingKeys()
        val password = apiKeyField.password
        try {
            apiKeyField.text = ""
        } finally {
            password.fill('\u0000')
        }
        root = null
    }

    private fun rebuildModels() {
        loading = true
        profileModel.clear()
        profiles.forEach(profileModel::addElement)
        refreshActiveChoices()
        selectedIndex = if (profiles.isEmpty()) -1 else 0
        profileList.selectedIndex = selectedIndex
        loadSelectedProfile()
        loading = false
    }

    private fun refreshActiveChoices() {
        val choices = profiles.map { ProfileChoice(it.id, it.name) }.toTypedArray()
        activeProfileCombo.model = DefaultComboBoxModel(choices)
        activeProfileCombo.selectedItem = choices.firstOrNull { it.id == activeProfileId }
            ?: choices.firstOrNull()
        if (activeProfileId.isBlank()) activeProfileId = choices.firstOrNull()?.id.orEmpty()
    }

    private fun loadSelectedProfile() {
        loading = true
        val profile = profiles.getOrNull(selectedIndex)
        profileNameField.text = profile?.name.orEmpty()
        providerCombo.selectedItem = profile?.let {
            (0 until providerCombo.itemCount).map(providerCombo::getItemAt).firstOrNull { item -> item.type == it.provider }
        }
        endpointField.text = profile?.baseUrl.orEmpty()
        apiKeyField.text = ""
        modelField.text = profile?.model.orEmpty()
        temperatureField.text = profile?.temperature?.toString().orEmpty()
        languageField.text = profile?.responseLanguage.orEmpty()
        streamingField.isSelected = profile?.streaming ?: true
        reasoningField.isSelected = profile?.reasoningCompatibility ?: false
        val enabled = profile != null
        listOf(
            profileNameField,
            providerCombo,
            endpointField,
            apiKeyField,
            modelField,
            temperatureField,
            languageField,
            streamingField,
            reasoningField,
        ).forEach { it.isEnabled = enabled }
        loading = false
    }

    private fun saveSelectedProfile() {
        if (loading) return
        val profile = profiles.getOrNull(selectedIndex) ?: return
        val provider = (providerCombo.selectedItem as? ProviderChoice)?.type ?: profile.provider
        val endpoint = endpointField.text.trim().trimEnd('/')
        if (provider != profile.provider || endpoint != profile.baseUrl) SourceContextConsent.invalidate(profile)
        profile.name = profileNameField.text.trim()
        profile.provider = provider
        profile.baseUrl = endpoint
        profile.model = modelField.text.trim()
        profile.temperature = temperatureField.text.toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: profile.temperature
        profile.responseLanguage = languageField.text.trim().ifBlank { "English" }
        profile.streaming = streamingField.isSelected
        profile.reasoningCompatibility = reasoningField.isSelected
        val password = apiKeyField.password
        try {
            if (password.isNotEmpty()) {
                pendingKeys.remove(profile.id)?.fill('\u0000')
                pendingKeys[profile.id] = password.copyOf()
                apiKeyField.text = ""
            }
        } finally {
            password.fill('\u0000')
        }
        profileList.repaint()
    }

    private fun addProfile() {
        saveSelectedProfile()
        val profile = LlmProfile(name = CommitMessageBundle.message("settings.providers.newName"))
        val newIndex = profiles.size
        loading = true
        try {
            profiles += profile
            profileModel.addElement(profile)
            selectedIndex = newIndex
            profileList.selectedIndex = newIndex
            refreshActiveChoices()
        } finally {
            loading = false
        }
        loadSelectedProfile()
    }

    private fun copyProfile() {
        saveSelectedProfile()
        val selected = profiles.getOrNull(selectedIndex) ?: return
        val copy = selected.copy(
            id = UUID.randomUUID().toString(),
            name = CommitMessageBundle.message("common.copyName", selected.name),
            sourceConsentFingerprint = "",
        )
        val newIndex = profiles.size
        loading = true
        try {
            profiles += copy
            profileModel.addElement(copy)
            selectedIndex = newIndex
            profileList.selectedIndex = newIndex
            refreshActiveChoices()
        } finally {
            loading = false
        }
        loadSelectedProfile()
    }

    private fun deleteProfile() {
        saveSelectedProfile()
        val selected = profiles.getOrNull(selectedIndex) ?: return
        if (CommitMessageSettingsService.getInstance().state.profiles.any { it.id == selected.id }) {
            removedProfileIds += selected.id
        }
        pendingKeys.remove(selected.id)?.fill('\u0000')
        val removedIndex = selectedIndex
        val newIndex = removedIndex.coerceAtMost(profiles.lastIndex - 1)
        loading = true
        try {
            profiles.removeAt(removedIndex)
            profileModel.remove(removedIndex)
            if (activeProfileId == selected.id) activeProfileId = profiles.firstOrNull()?.id.orEmpty()
            selectedIndex = newIndex
            profileList.selectedIndex = newIndex
            refreshActiveChoices()
        } finally {
            loading = false
        }
        loadSelectedProfile()
    }

    private fun testConnection(fetchModels: Boolean) {
        saveSelectedProfile()
        val profile = profiles.getOrNull(selectedIndex)?.copy() ?: return
        val stagedKey = pendingKeys[profile.id]?.copyOf()
        val generation = ++connectionRequestGeneration
        ApplicationManager.getApplication().executeOnPooledThread {
            val key = try {
                stagedKey?.concatToString() ?: secretStore.getApiKey(profile.id).orEmpty()
            } finally {
                stagedKey?.fill('\u0000')
            }
            if (key.isBlank()) {
                ApplicationManager.getApplication().invokeLater {
                    if (!isCurrentConnectionRequest(profile, generation)) return@invokeLater
                    Messages.showWarningDialog(
                        CommitMessageBundle.message("error.apiKey.missing"),
                        CommitMessageBundle.message("settings.providers.title"),
                    )
                }
                return@executeOnPooledThread
            }
            runCatching {
                HttpLlmProviderClient().fetchModels(
                    profile,
                    key,
                    ProviderRequestBudget(3),
                    EmptyProgressIndicator(),
                )
            }.onSuccess { models ->
                ApplicationManager.getApplication().invokeLater {
                    if (!isCurrentConnectionRequest(profile, generation)) return@invokeLater
                    if (fetchModels && models.isNotEmpty() && modelField.text.isBlank()) modelField.text = models.first()
                    Messages.showInfoMessage(
                        if (fetchModels) {
                            models.joinToString("\n").ifBlank {
                                CommitMessageBundle.message("settings.providers.noModels")
                            }
                        } else {
                            CommitMessageBundle.message("settings.providers.testSuccess")
                        },
                        CommitMessageBundle.message("settings.providers.test"),
                    )
                }
            }.onFailure { error ->
                ApplicationManager.getApplication().invokeLater {
                    if (!isCurrentConnectionRequest(profile, generation)) return@invokeLater
                    Messages.showErrorDialog(
                        error.message?.take(500).orEmpty(),
                        CommitMessageBundle.message("settings.providers.test"),
                    )
                }
            }
        }
    }

    private fun isCurrentConnectionRequest(profile: LlmProfile, generation: Long): Boolean {
        val selected = profiles.getOrNull(selectedIndex) ?: return false
        val selectedProvider = (providerCombo.selectedItem as? ProviderChoice)?.type ?: return false
        return root != null && generation == connectionRequestGeneration &&
            selected.id == profile.id && selectedProvider == profile.provider &&
            endpointField.text.trim().trimEnd('/') == profile.baseUrl
    }

    private fun isSafeEndpoint(value: String): Boolean {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() in setOf("http", "https") &&
            uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
    }

    private fun clearPendingKeys() {
        pendingKeys.values.forEach { it.fill('\u0000') }
        pendingKeys.clear()
    }

    private fun buttons(vararg buttons: JButton): JPanel = JPanel(GridLayout(0, 1, 4, 4)).apply {
        buttons.forEach(::add)
    }

    private data class ProfileChoice(val id: String, val name: String) {
        override fun toString(): String = name
    }

    private data class ProviderChoice(val type: LlmProviderType, val name: String) {
        override fun toString(): String = name
    }

    companion object {
        const val ID = "emohce.settings.commitMessage.providers"
    }
}
