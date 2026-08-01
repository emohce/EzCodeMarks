package emohce.presentation.commitmessage.settings

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import emohce.data.commitmessage.CommitMessageCredentialAccess
import emohce.data.commitmessage.CommitMessageSecretStore
import emohce.data.commitmessage.CommitMessageSettingsConflictException
import emohce.data.commitmessage.CommitMessageSettingsService
import emohce.data.commitmessage.CodexAppServerService
import emohce.data.commitmessage.DefaultLlmProviderClient
import emohce.data.commitmessage.HttpLlmProviderClient
import emohce.data.commitmessage.LlmProviderClient
import emohce.data.commitmessage.MissingApiKeyException
import emohce.data.commitmessage.SourceContextConsent
import emohce.data.commitmessage.StaleProviderProfileException
import emohce.data.commitmessage.hasSameCredentialDestination
import emohce.domain.commitmessage.LlmCompletionRequest
import emohce.domain.commitmessage.LlmProfile
import emohce.domain.commitmessage.LlmProviderType
import emohce.domain.commitmessage.ProviderErrorKind
import emohce.domain.commitmessage.ProviderErrorSanitizer
import emohce.domain.commitmessage.ProviderException
import emohce.domain.commitmessage.ProviderRequestBudget
import emohce.presentation.commitmessage.CommitMessageBundle
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.jetbrains.annotations.TestOnly
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JSpinner
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.table.AbstractTableModel

internal fun interface ProviderSettingsRequestRunner {
    fun run(title: String, operation: (ProgressIndicator) -> Unit)
}

internal val DEFAULT_PROVIDER_SETTINGS_REQUEST_RUNNER = ProviderSettingsRequestRunner { title, operation ->
    ProgressManager.getInstance().run(object : Task.Backgroundable(null, title, true) {
        override fun run(indicator: ProgressIndicator) = operation(indicator)
    })
}

internal class CommitProvidersConfigurable(
    private val secretStore: CommitMessageSecretStore = CommitMessageSecretStore(),
    private val providerClient: LlmProviderClient = DefaultLlmProviderClient(),
    private val requestRunner: ProviderSettingsRequestRunner = DEFAULT_PROVIDER_SETTINGS_REQUEST_RUNNER,
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
            CommitMessageBundle.message("settings.providers.title"),
        )
    },
    private val profileEditor: ProviderProfileEditor = DEFAULT_PROVIDER_PROFILE_EDITOR,
) : SearchableConfigurable {
    private var profiles = mutableListOf<LlmProfile>()
    private var activeProfileId = ""
    private var displayedProfileId = ""
    private var loading = false
    private val removedProfileIds = linkedSetOf<String>()
    private val sessionKeys = linkedMapOf<String, CharArray>()
    private val dirtyKeyIds = linkedSetOf<String>()
    private val clearedKeyIds = linkedSetOf<String>()
    private val fetchedModels = linkedMapOf<String, List<String>>()
    private var testRunning = false
    private var loadedProviderFields = ProviderFields()
    private val currentConnectionTest = AtomicReference<ConnectionTestHandle?>()
    private val codexStatusLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    private val tableModel = ProfileTableModel()
    private val profileTable = JBTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setShowGrid(true)
        emptyText.text = CommitMessageBundle.message("settings.providers.empty")
        accessibleContext.accessibleName = CommitMessageBundle.message("settings.providers.list")
    }
    private val activeProfileCombo = ComboBox<ProfileChoice>().apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean,
            ): java.awt.Component = super.getListCellRendererComponent(list, value, index, selected, focused).also {
                val choice = value as? ProfileChoice
                (it as JLabel).text = choice?.label.orEmpty()
            }
        }
    }
    private val temperatureSpinner = JSpinner(SpinnerNumberModel(0.5, 0.0, 2.0, 0.1)).apply {
        (editor as? JSpinner.NumberEditor)?.format?.apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        preferredSize = preferredSize.apply { width = JBUI.scale(110) }
    }
    private val responseLanguageField = JBTextField("English").apply {
        preferredSize = preferredSize.apply { width = JBUI.scale(220) }
    }
    private val smartEchoField = JBCheckBox(CommitMessageBundle.message("settings.providers.smartEcho"))
    private val streamingField = JBCheckBox(CommitMessageBundle.message("settings.providers.streaming"))
    private val reasoningField = JBCheckBox(CommitMessageBundle.message("settings.providers.reasoning"))
    private val testConnectionButton = javax.swing.JButton(CommitMessageBundle.message("settings.providers.test"))
    private val connectionStatusLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    private val connectionCostHint = JBLabel(CommitMessageBundle.message("settings.providers.testCostHint")).apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    @Volatile
    private var settingsDisposed = false
    private var root: JComponent? = null

    init {
        activeProfileCombo.addActionListener {
            if (loading) return@addActionListener
            cancelConnectionTest(showCancelled = false)
            commitDisplayedReasoning()
            activeProfileId = (activeProfileCombo.selectedItem as? ProfileChoice)?.id.orEmpty()
            displayedProfileId = activeProfileId
            selectActiveProfileRow()
            loadReasoningForActiveProfile()
        }
        profileTable.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.clickCount != 2 || event.button != java.awt.event.MouseEvent.BUTTON1) return
                val viewRow = profileTable.rowAtPoint(event.point)
                if (viewRow < 0) return
                profileTable.setRowSelectionInterval(viewRow, viewRow)
                editSelectedProfile()
                event.consume()
            }
        })
        reasoningField.addActionListener {
            if (!loading) commitDisplayedReasoning()
        }
        testConnectionButton.addActionListener {
            if (testRunning) cancelConnectionTest() else testConnection()
        }
    }

    override fun getId(): String = ID

    override fun getDisplayName(): String = CommitMessageBundle.message("settings.providers.title")

    override fun createComponent(): JComponent {
        root?.let { return it }
        settingsDisposed = false
        reset()
        val tablePanel = ToolbarDecorator.createDecorator(profileTable)
            .setAddAction { addProfile() }
            .setRemoveAction { removeSelectedProfile() }
            .setEditAction { editSelectedProfile() }
            .disableUpDownActions()
            .addExtraAction(object : DumbAwareAction(
                CommitMessageBundle.message("settings.providers.copy"),
                null,
                AllIcons.Actions.Copy,
            ) {
                override fun actionPerformed(event: AnActionEvent) = copySelectedProfile()
            })
            .createPanel()
        configureTableColumns()

        return panel {
            group(CommitMessageBundle.message("settings.providers.codex.group")) {
                row {
                    cell(codexStatusLabel).align(Align.FILL).resizableColumn()
                    button(CommitMessageBundle.message("settings.providers.codex.configure")) {
                        ShowSettingsUtil.getInstance().showSettingsDialog(
                            null,
                            "emohce.settings.commitMessage.codex",
                        )
                        refreshCodexStatusLabel()
                    }
                }
            }
            row(CommitMessageBundle.message("settings.providers.active")) {
                cell(activeProfileCombo).align(Align.FILL).resizableColumn()
            }
            row {
                label(CommitMessageBundle.message("settings.providers.temperature"))
                cell(temperatureSpinner)
                label(CommitMessageBundle.message("settings.providers.language"))
                cell(responseLanguageField).align(Align.FILL).resizableColumn()
            }
            row {
                cell(smartEchoField)
                cell(streamingField)
                cell(reasoningField)
                cell(testConnectionButton)
                cell(connectionStatusLabel).align(Align.FILL).resizableColumn()
            }
            row { cell(connectionCostHint).align(Align.FILL) }
            row {
                cell(tablePanel).align(Align.FILL).resizableColumn()
            }.resizableRow()
        }.apply {
            preferredSize = preferredSize.apply { height = JBUI.scale(620) }
        }.also { root = it }
    }

    override fun isModified(): Boolean {
        return workingProviderFields() != loadedProviderFields ||
            dirtyKeyIds.isNotEmpty() || clearedKeyIds.isNotEmpty() || removedProfileIds.isNotEmpty()
    }

    override fun apply() {
        val selectedProfileId = selectedProfile()?.id
        cancelConnectionTest(showCancelled = false)
        commitDisplayedReasoning()
        val snapshot = profiles.map { it.copy() }.toMutableList()
        if (snapshot.any {
                it.name.isBlank() ||
                    it.id.startsWith("project:") ||
                    (it.provider != LlmProviderType.CHATGPT_CODEX && !isSafeEndpoint(it.baseUrl))
            } ||
            snapshot.map { it.id }.toSet().size != snapshot.size
        ) {
            throw ConfigurationException(CommitMessageBundle.message("settings.providers.validation"))
        }
        val service = CommitMessageSettingsService.getInstance()
        val current = service.snapshot()
        val workingFields = workingProviderFields()
        if (service.hasPortableConflict() ||
            (providerFields(current) != loadedProviderFields && providerFields(current) != workingFields)
        ) {
            throw ConfigurationException(CommitMessageBundle.message("settings.portable.concurrentChange"))
        }
        val merged = current.apply {
            profiles = snapshot
            activeProfileId = this@CommitProvidersConfigurable.activeProfileId
            llmTemperature = temperatureValue()
            llmResponseLanguage = responseLanguage()
            smartEcho = smartEchoField.isSelected
            llmStreaming = streamingField.isSelected
        }
        val keyWrites = dirtyKeyIds
            .filterNot(clearedKeyIds::contains)
            .mapNotNull { id -> sessionKeys[id]?.copyOf()?.let { id to it } }
            .toMap()
        val keyDeletes = (removedProfileIds + clearedKeyIds).distinct()
        val failure = AtomicReference<Throwable?>()
        val completed = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            Runnable {
                try {
                    CommitMessageCredentialAccess.transaction {
                        val affected = (keyDeletes + keyWrites.keys).distinct()
                        val originals = affected.associateWith(secretStore::getCredentials)
                        try {
                            keyDeletes.forEach(secretStore::clearApiKey)
                            keyWrites.forEach { (id, value) -> secretStore.setApiKey(id, value) }
                            service.replaceState(merged)
                        } catch (error: Throwable) {
                            originals.forEach { (id, value) ->
                                runCatching { secretStore.restoreCredentials(id, value) }
                                    .exceptionOrNull()
                                    ?.let(error::addSuppressed)
                            }
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
            if (failure.get() is CommitMessageSettingsConflictException) {
                throw ConfigurationException(CommitMessageBundle.message("settings.portable.concurrentChange"))
            }
            throw ConfigurationException(
                CommitMessageBundle.message("error.credentials.save"),
                failure.get(),
                CommitMessageBundle.message("settings.providers.title"),
            )
        }

        dirtyKeyIds.clear()
        clearedKeyIds.clear()
        removedProfileIds.clear()
        loadAppliedState(service, selectedProfileId)
    }

    override fun reset() {
        cancelConnectionTest(showCancelled = false)
        clearSessionKeys()
        dirtyKeyIds.clear()
        clearedKeyIds.clear()
        removedProfileIds.clear()
        fetchedModels.clear()
        refreshCodexStatusLabel()
        loadAppliedState(CommitMessageSettingsService.getInstance())
    }

    override fun disposeUIResources() {
        settingsDisposed = true
        cancelConnectionTest(showCancelled = false)
        clearSessionKeys()
        dirtyKeyIds.clear()
        clearedKeyIds.clear()
        removedProfileIds.clear()
        fetchedModels.clear()
        root = null
    }

    private fun refreshCodexStatusLabel() {
        val executable = CodexAppServerService.getInstance().executablePath()
        codexStatusLabel.text = if (executable.isNotBlank()) executable else CodexAppServerService.getInstance().resolvedExecutablePath()
    }

    @TestOnly
    internal fun editProfileForTest(profileId: String) {
        cancelConnectionTest(showCancelled = false)
        commitDisplayedReasoning()
        profiles.firstOrNull { it.id == profileId }?.copy()?.let { editProfile(it, isNew = false) }
    }

    private fun loadAppliedState(service: CommitMessageSettingsService, preferredProfileId: String? = null) {
        val state = service.snapshot()
        loading = true
        try {
            profiles = state.profiles.map { it.copy() }.toMutableList()
            activeProfileId = state.activeProfileId
            displayedProfileId = activeProfileId
            temperatureSpinner.value = state.llmTemperature
            responseLanguageField.text = state.llmResponseLanguage
            smartEchoField.isSelected = state.smartEcho
            streamingField.isSelected = state.llmStreaming
            refreshActiveChoices()
            tableModel.fireTableDataChanged()
            val selectedId = preferredProfileId?.takeIf { id -> profiles.any { it.id == id } } ?: activeProfileId
            selectProfileRow(selectedId)
            loadReasoningForActiveProfile()
            resetConnectionStatus()
            loadedProviderFields = providerFields(state)
        } finally {
            loading = false
        }
    }

    private fun refreshActiveChoices() {
        val choices = profiles.map { profile ->
            ProfileChoice(profile.id, "${profile.name} (${providerLabel(profile.provider)})")
        }.toTypedArray()
        activeProfileCombo.model = DefaultComboBoxModel(choices)
        val selected = choices.firstOrNull { it.id == activeProfileId } ?: choices.firstOrNull()
        activeProfileCombo.selectedItem = selected
        activeProfileId = selected?.id.orEmpty()
        displayedProfileId = activeProfileId
    }

    private fun loadReasoningForActiveProfile() {
        val active = profiles.firstOrNull { it.id == activeProfileId }
        reasoningField.isEnabled = active != null && active.provider != LlmProviderType.CHATGPT_CODEX
        reasoningField.isSelected = active?.reasoningCompatibility == true && active.provider != LlmProviderType.CHATGPT_CODEX
    }

    private fun commitDisplayedReasoning() {
        if (loading || displayedProfileId.isBlank()) return
        profiles.firstOrNull { it.id == displayedProfileId }?.reasoningCompatibility = reasoningField.isSelected
    }

    private fun profileSnapshot(): List<LlmProfile> = profiles.map { profile ->
        if (profile.id == displayedProfileId) profile.copy(reasoningCompatibility = reasoningField.isSelected)
        else profile.copy()
    }

    private fun workingProviderFields(): ProviderFields = ProviderFields(
        profiles = profileSnapshot(),
        activeProfileId = activeProfileId,
        temperature = temperatureValue(),
        responseLanguage = responseLanguage(),
        smartEcho = smartEchoField.isSelected,
        streaming = streamingField.isSelected,
    )

    private fun providerFields(state: emohce.data.commitmessage.CommitMessageSettingsState): ProviderFields = ProviderFields(
        profiles = state.profiles.map { it.copy() },
        activeProfileId = state.activeProfileId,
        temperature = state.llmTemperature,
        responseLanguage = state.llmResponseLanguage,
        smartEcho = state.smartEcho,
        streaming = state.llmStreaming,
    )

    private fun addProfile() {
        cancelConnectionTest(showCancelled = false)
        commitDisplayedReasoning()
        val profile = LlmProfile(
            id = UUID.randomUUID().toString(),
            name = uniqueProfileName(CommitMessageBundle.message("settings.providers.newName")),
            temperature = temperatureValue(),
            responseLanguage = responseLanguage(),
            streaming = streamingField.isSelected,
        )
        editProfile(profile, isNew = true)
    }

    private fun editSelectedProfile() {
        val profileId = selectedProfile()?.id ?: return
        cancelConnectionTest(showCancelled = false)
        commitDisplayedReasoning()
        profiles.firstOrNull { it.id == profileId }?.copy()?.let { editProfile(it, isNew = false) }
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

        val edited = result.profile.copy(
            temperature = temperatureValue(),
            responseLanguage = responseLanguage(),
            streaming = streamingField.isSelected,
        )
        val original = profiles.firstOrNull { it.id == edited.id }
        if (original != null && (original.provider != edited.provider || original.baseUrl != edited.baseUrl)) {
            SourceContextConsent.invalidate(edited)
            fetchedModels.remove(edited.id)
        }
        if (isNew) profiles += edited else {
            val index = profiles.indexOfFirst { it.id == edited.id }
            if (index >= 0) profiles[index] = edited
        }
        if (result.modelSuggestions.isNotEmpty()) fetchedModels[edited.id] = result.modelSuggestions.distinct()
        applyEditedKey(edited.id, result)
        if (activeProfileId.isBlank()) activeProfileId = edited.id
        displayedProfileId = activeProfileId
        loading = true
        try {
            refreshActiveChoices()
            tableModel.fireTableDataChanged()
            selectProfileRow(edited.id)
            loadReasoningForActiveProfile()
        } finally {
            loading = false
        }
    }

    private fun applyEditedKey(profileId: String, result: ProviderProfileEditResult) {
        if (result.clearApiKey && confirmApiKeyClear()) {
            sessionKeys.remove(profileId)?.fill('\u0000')
            dirtyKeyIds -= profileId
            if (CommitMessageSettingsService.getInstance().snapshot(refreshPortable = false).profiles.any { it.id == profileId }) {
                clearedKeyIds += profileId
            }
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

    private fun copySelectedProfile() {
        val selectedId = selectedProfile()?.id ?: return
        cancelConnectionTest(showCancelled = false)
        commitDisplayedReasoning()
        val selected = profiles.firstOrNull { it.id == selectedId } ?: return
        val copy = selected.copy(
            id = UUID.randomUUID().toString(),
            name = uniqueProfileName(CommitMessageBundle.message("common.copyName", selected.name)),
            sourceConsentFingerprint = "",
        )
        profiles += copy
        fetchedModels[copy.id] = fetchedModels[selected.id].orEmpty()
        activeProfileId = copy.id
        displayedProfileId = copy.id
        loading = true
        try {
            refreshActiveChoices()
            tableModel.fireTableDataChanged()
            selectProfileRow(copy.id)
            loadReasoningForActiveProfile()
        } finally {
            loading = false
        }
    }

    private fun removeSelectedProfile() {
        cancelConnectionTest(showCancelled = false)
        if (profiles.size <= 1) return
        val selected = selectedProfile() ?: return
        if (CommitMessageSettingsService.getInstance().snapshot(refreshPortable = false).profiles.any { it.id == selected.id }) {
            removedProfileIds += selected.id
        }
        sessionKeys.remove(selected.id)?.fill('\u0000')
        dirtyKeyIds -= selected.id
        clearedKeyIds -= selected.id
        fetchedModels.remove(selected.id)
        val index = profiles.indexOfFirst { it.id == selected.id }
        profiles.removeAt(index)
        if (activeProfileId == selected.id) activeProfileId = profiles[index.coerceAtMost(profiles.lastIndex)].id
        displayedProfileId = activeProfileId
        loading = true
        try {
            refreshActiveChoices()
            tableModel.fireTableDataChanged()
            selectActiveProfileRow()
            loadReasoningForActiveProfile()
        } finally {
            loading = false
        }
    }

    private fun modelFetcher(): ProviderModelFetcher = ProviderModelFetcher { profile, enteredKey, completed ->
        val request = ProviderModelRequestHandle()
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
                onEdt {
                    completed(Result.success(models))
                }
            }.onFailure { error ->
                onEdt { completed(Result.failure(sanitizedProviderError(error, key))) }
            }
        }
        request
    }

    private fun testConnection() {
        commitDisplayedReasoning()
        val profile = profiles.firstOrNull { it.id == activeProfileId }?.copy(
            temperature = 0.0,
            responseLanguage = responseLanguage(),
            streaming = false,
            reasoningCompatibility = false,
        ) ?: return
        if (profile.provider != LlmProviderType.CHATGPT_CODEX && !isSafeEndpoint(profile.baseUrl)) {
            showConnectionFailure(CommitMessageBundle.message("settings.providers.endpointInvalid"))
            return
        }
        val handle = ConnectionTestHandle()
        currentConnectionTest.set(handle)
        val startedAt = System.nanoTime()
        testRunning = true
        testConnectionButton.text = CommitMessageBundle.message("settings.providers.cancelTest")
        connectionStatusLabel.foreground = UIUtil.getContextHelpForeground()
        connectionStatusLabel.text = CommitMessageBundle.message("settings.providers.modelsStage")

        requestRunner.run(CommitMessageBundle.message("settings.providers.test")) { indicator ->
            handle.indicator.set(indicator)
            var key = ""
            try {
                checkConnectionActive(handle, indicator)
                key = if (profile.provider == LlmProviderType.CHATGPT_CODEX) {
                    ""
                } else {
                    resolveApiKey(profile, null)
                }
                if (profile.provider != LlmProviderType.CHATGPT_CODEX && key.isBlank()) {
                    throw MissingApiKeyException()
                }
                val budget = ProviderRequestBudget(3)
                val models = providerClient.fetchModels(profile, key, budget, indicator).distinct()
                onEdt {
                    if (isCurrentRequest(handle)) {
                        fetchedModels[profile.id] = models
                        connectionStatusLabel.text = if (profile.model.isBlank()) {
                            CommitMessageBundle.message("settings.providers.selectModelAfterFetch", models.size)
                        } else {
                            CommitMessageBundle.message("settings.providers.inferenceStage")
                        }
                    }
                }
                if (profile.model.isBlank()) {
                    onEdt { finishConnectionSuccess(handle, startedAt, modelsOnly = true) }
                    return@run
                }
                checkConnectionActive(handle, indicator)
                providerClient.complete(
                    profile = profile,
                    apiKey = key,
                    request = LlmCompletionRequest(
                        systemPrompt = "You are a connectivity test assistant.",
                        userPrompt = "Reply with OK only.",
                        structured = false,
                        streaming = false,
                        reasoningCompatibility = false,
                        maxOutputTokens = 8,
                    ),
                    budget = budget,
                    indicator = indicator,
                )
                onEdt { finishConnectionSuccess(handle, startedAt, modelsOnly = false) }
            } catch (error: ProcessCanceledException) {
                onEdt { finishConnectionCancelled(handle) }
            } catch (error: Throwable) {
                val safe = connectionErrorText(sanitizedProviderError(error, key))
                onEdt {
                    if (!isCurrentRequest(handle)) return@onEdt
                    if (error is MissingApiKeyException) notifyMissingApiKey()
                    finishConnectionFailure(handle, safe)
                }
            } finally {
                handle.indicator.compareAndSet(indicator, null)
            }
        }
    }

    private fun cancelConnectionTest(showCancelled: Boolean = true) {
        if (!testRunning) return
        val handle = currentConnectionTest.getAndSet(null)
        handle?.cancelled?.set(true)
        handle?.indicator?.get()?.cancel()
        testRunning = false
        testConnectionButton.text = CommitMessageBundle.message("settings.providers.test")
        if (showCancelled) {
            connectionStatusLabel.foreground = UIUtil.getContextHelpForeground()
            connectionStatusLabel.text = CommitMessageBundle.message("settings.providers.testCancelled")
        }
    }

    private fun finishConnectionSuccess(handle: ConnectionTestHandle, startedAt: Long, modelsOnly: Boolean) {
        if (!isCurrentRequest(handle)) return
        currentConnectionTest.compareAndSet(handle, null)
        testRunning = false
        testConnectionButton.text = CommitMessageBundle.message("settings.providers.test")
        connectionStatusLabel.foreground = JBColor(0x2E7D32, 0x7CB342)
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        connectionStatusLabel.text = CommitMessageBundle.message(
            if (modelsOnly) "settings.providers.modelsOnlySuccess" else "settings.providers.testSuccessWithTime",
            elapsedMillis,
        )
    }

    private fun finishConnectionFailure(handle: ConnectionTestHandle, message: String) {
        if (!isCurrentRequest(handle)) return
        currentConnectionTest.compareAndSet(handle, null)
        testRunning = false
        testConnectionButton.text = CommitMessageBundle.message("settings.providers.test")
        showConnectionFailure(message)
    }

    private fun finishConnectionCancelled(handle: ConnectionTestHandle) {
        if (!isCurrentRequest(handle)) return
        currentConnectionTest.compareAndSet(handle, null)
        testRunning = false
        testConnectionButton.text = CommitMessageBundle.message("settings.providers.test")
        connectionStatusLabel.foreground = UIUtil.getContextHelpForeground()
        connectionStatusLabel.text = CommitMessageBundle.message("settings.providers.testCancelled")
    }

    private fun showConnectionFailure(message: String) {
        connectionStatusLabel.foreground = JBColor.RED
        connectionStatusLabel.text = CommitMessageBundle.message("settings.providers.testFailed", message.take(300))
    }

    private fun resetConnectionStatus() {
        val handle = currentConnectionTest.getAndSet(null)
        handle?.cancelled?.set(true)
        handle?.indicator?.get()?.cancel()
        testRunning = false
        testConnectionButton.text = CommitMessageBundle.message("settings.providers.test")
        connectionStatusLabel.foreground = UIUtil.getContextHelpForeground()
        connectionStatusLabel.text = ""
    }

    private fun isCurrentRequest(handle: ConnectionTestHandle): Boolean =
        currentConnectionTest.get() === handle && !handle.cancelled.get()

    private fun checkConnectionActive(handle: ConnectionTestHandle, indicator: ProgressIndicator) {
        if (!isCurrentRequest(handle)) {
            indicator.cancel()
            throw ProcessCanceledException()
        }
        indicator.checkCanceled()
    }

    private fun resolveApiKey(profile: LlmProfile, enteredKey: CharArray?): String {
        val profileId = profile.id
        if (enteredKey != null) return enteredKey.concatToString()
        if (clearedKeyIds.contains(profileId)) return ""
        sessionKeys[profileId]?.let { return it.concatToString() }
        return CommitMessageCredentialAccess.transaction {
            val loaded = loadedProviderFields.profiles.firstOrNull { it.id == profileId }
                ?: throw StaleProviderProfileException()
            val current = CommitMessageSettingsService.getInstance().snapshot().profiles.firstOrNull { it.id == profileId }
                ?: throw StaleProviderProfileException()
            if (!hasSameCredentialDestination(current, loaded)) throw StaleProviderProfileException()
            secretStore.credentialSnapshotWithinTransaction(profileId).apiKey.orEmpty()
        }
    }

    private fun hasStoredKey(profileId: String): Boolean {
        if (clearedKeyIds.contains(profileId)) return false
        if (sessionKeys[profileId]?.isNotEmpty() == true) return true
        return CommitMessageCredentialAccess.read { !secretStore.getApiKey(profileId).isNullOrBlank() }
    }

    private fun sanitizedProviderError(error: Throwable, knownKey: String): Throwable {
        if (error is ProcessCanceledException) return error
        val sanitized = ProviderErrorSanitizer.sanitize(error.message, knownKey)
        return if (error is ProviderException) ProviderException(error.kind, sanitized, error) else RuntimeException(sanitized, error)
    }

    private fun connectionErrorText(error: Throwable): String = when ((error as? ProviderException)?.kind) {
        ProviderErrorKind.AUTHENTICATION -> CommitMessageBundle.message("error.provider.authentication")
        ProviderErrorKind.RATE_LIMIT -> CommitMessageBundle.message("error.provider.rateLimit")
        ProviderErrorKind.TIMEOUT -> CommitMessageBundle.message("error.provider.timeout")
        else -> error.message?.take(300).orEmpty().ifBlank { CommitMessageBundle.message("error.provider.response") }
    }

    private fun configureTableColumns() {
        val widths = intArrayOf(160, 150, 260, 180)
        widths.forEachIndexed { index, width -> profileTable.columnModel.getColumn(index).preferredWidth = JBUI.scale(width) }
    }

    private fun selectActiveProfileRow() = selectProfileRow(activeProfileId)

    private fun selectProfileRow(profileId: String) {
        val modelIndex = profiles.indexOfFirst { it.id == profileId }
        if (modelIndex >= 0) {
            val viewIndex = profileTable.convertRowIndexToView(modelIndex)
            if (viewIndex >= 0) {
                profileTable.selectionModel.setSelectionInterval(viewIndex, viewIndex)
                profileTable.scrollRectToVisible(profileTable.getCellRect(viewIndex, 0, true))
            } else {
                profileTable.clearSelection()
            }
        } else {
            profileTable.clearSelection()
        }
    }

    private fun selectedProfile(): LlmProfile? {
        val viewIndex = profileTable.selectedRow
        if (viewIndex < 0) return null
        return profiles.getOrNull(profileTable.convertRowIndexToModel(viewIndex))
    }

    private fun uniqueProfileName(base: String): String {
        if (profiles.none { it.name == base }) return base
        var suffix = 2
        while (profiles.any { it.name == "$base $suffix" }) suffix += 1
        return "$base $suffix"
    }

    private fun temperatureValue(): Double = (temperatureSpinner.value as? Number)?.toDouble()?.coerceIn(0.0, 2.0) ?: 0.5

    private fun responseLanguage(): String = responseLanguageField.text.trim().ifBlank { "English" }

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
        private val columns = listOf(
            "settings.providers.column.name",
            "settings.providers.column.provider",
            "settings.providers.column.endpoint",
            "settings.providers.column.model",
        )

        override fun getRowCount(): Int = profiles.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = CommitMessageBundle.message(columns[column])

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val profile = profiles[rowIndex]
            return when (columnIndex) {
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

    private data class ProviderFields(
        val profiles: List<LlmProfile> = emptyList(),
        val activeProfileId: String = "",
        val temperature: Double = 0.5,
        val responseLanguage: String = "English",
        val smartEcho: Boolean = false,
        val streaming: Boolean = true,
    )

    companion object {
        const val ID = "emohce.settings.commitMessage.providers"
    }

    private class ConnectionTestHandle {
        val cancelled = AtomicBoolean(false)
        val indicator = AtomicReference<ProgressIndicator?>()
    }

    private class ProviderModelRequestHandle : ProviderModelRequest {
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
}
