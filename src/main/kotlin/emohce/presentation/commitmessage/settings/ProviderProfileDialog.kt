package emohce.presentation.commitmessage.settings

import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import emohce.domain.commitmessage.LlmProfile
import emohce.domain.commitmessage.LlmProviderType
import emohce.domain.commitmessage.ProviderErrorKind
import emohce.domain.commitmessage.ProviderException
import emohce.presentation.commitmessage.CommitMessageBundle
import java.net.URI
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.event.DocumentEvent
import org.jetbrains.annotations.TestOnly

internal data class ProviderProfileEditInput(
    val title: String,
    val profile: LlmProfile,
    val sessionKey: CharArray? = null,
    val hasStoredKey: Boolean = false,
    val modelSuggestions: List<String> = emptyList(),
)

internal data class ProviderProfileEditResult(
    val profile: LlmProfile,
    val apiKey: CharArray? = null,
    val clearApiKey: Boolean = false,
    val modelSuggestions: List<String> = emptyList(),
)

internal fun interface ProviderModelFetcher {
    fun fetch(
        profile: LlmProfile,
        apiKey: CharArray?,
        completed: (Result<List<String>>) -> Unit,
    ): ProviderModelRequest
}

internal fun interface ProviderModelRequest {
    fun cancel()
}

internal fun interface ProviderProfileEditor {
    fun edit(input: ProviderProfileEditInput, fetcher: ProviderModelFetcher): ProviderProfileEditResult?
}

internal val DEFAULT_PROVIDER_PROFILE_EDITOR = ProviderProfileEditor { input, fetcher ->
    try {
        val dialog = ProviderProfileDialog(input, fetcher)
        if (dialog.showAndGet()) dialog.result() else null
    } finally {
        input.sessionKey?.fill('\u0000')
    }
}

internal class ProviderProfileDialog(
    private val input: ProviderProfileEditInput,
    private val fetcher: ProviderModelFetcher,
) : DialogWrapper(true) {
    private val nameField = JBTextField(input.profile.name).apply { columns = 42 }
    private val providerCombo = ComboBox(
        arrayOf(
            ProviderChoice(LlmProviderType.OPENAI_COMPATIBLE, CommitMessageBundle.message("provider.openaiCompatible")),
            ProviderChoice(LlmProviderType.ANTHROPIC, CommitMessageBundle.message("provider.anthropic")),
            ProviderChoice(LlmProviderType.CHATGPT_CODEX, CommitMessageBundle.message("provider.chatgptCodex")),
        ),
    ).apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean,
            ): java.awt.Component = super.getListCellRendererComponent(list, value, index, selected, focused).also {
                (it as JLabel).text = (value as? ProviderChoice)?.label.orEmpty()
            }
        }
    }
    private val endpointField = JBTextField(input.profile.baseUrl).apply { columns = 42 }
    private val apiKeyField = JBPasswordField().apply {
        columns = 42
        input.sessionKey?.let { text = it.concatToString() }
    }
    private val apiKeyStatus = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val clearApiKeyButton = JButton(CommitMessageBundle.message("settings.providers.clearApiKey"))
    private val modelCombo = FilterableModelComboBox().apply {
        setMinimumAndPreferredWidth(JBUI.scale(420))
        setSuggestions(input.modelSuggestions, input.profile.model)
    }
    private val fetchModelsButton = JButton(CommitMessageBundle.message("settings.providers.fetchModels"))
    private val fetchStatus = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val reasoningField = JBCheckBox(CommitMessageBundle.message("settings.providers.reasoning")).apply {
        isSelected = input.profile.reasoningCompatibility
    }
    private val reasoningEffortCombo = ComboBox(
        listOf("", "minimal", "low", "medium", "high", "xhigh").map { value ->
            ReasoningEffortChoice(
                value,
                CommitMessageBundle.message(
                    if (value.isBlank()) "settings.providers.reasoningEffort.default"
                    else "settings.providers.reasoningEffort.$value",
                ),
            )
        }.toTypedArray(),
    ).apply {
        selectedItem = (0 until itemCount).map(::getItemAt)
            .firstOrNull { it.value == input.profile.reasoningEffort }
            ?: getItemAt(0)
    }
    private var previousProvider = input.profile.provider
    private var suggestionProvider = input.profile.provider
    private var suggestionEndpoint = normalizedEndpoint(input.profile.baseUrl)
    private var clearRequested = false
    private var loadedModels = input.modelSuggestions
    private var acceptedResult: ProviderProfileEditResult? = null
    private var activeModelRequest: ProviderModelRequest? = null
    private var activeRequestKey: CharArray? = null
    private var modelRequestGeneration = 0L

    init {
        title = input.title
        providerCombo.selectedItem = (0 until providerCombo.itemCount)
            .map(providerCombo::getItemAt)
            .first { it.type == input.profile.provider }
        clearApiKeyButton.isEnabled = input.hasStoredKey || input.sessionKey?.isNotEmpty() == true
        updateApiKeyStatus()
        bindListeners()
        updateProviderControls()
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(CommitMessageBundle.message("settings.providers.name")) {
            cell(nameField).align(Align.FILL).resizableColumn()
        }
        row(CommitMessageBundle.message("settings.providers.provider")) {
            cell(providerCombo).align(Align.FILL)
        }
        row(CommitMessageBundle.message("settings.providers.endpoint")) {
            cell(endpointField).align(Align.FILL)
        }
        row(CommitMessageBundle.message("settings.providers.apiKey")) {
            cell(apiKeyField).align(Align.FILL).resizableColumn()
            cell(clearApiKeyButton)
        }
        row("") { cell(apiKeyStatus).align(Align.FILL) }
        row(CommitMessageBundle.message("settings.providers.model")) {
            cell(modelCombo).align(Align.FILL).resizableColumn()
            cell(fetchModelsButton)
        }
        row("") { cell(fetchStatus).align(Align.FILL) }
        row { cell(reasoningField) }
        row(CommitMessageBundle.message("settings.providers.reasoningEffort")) {
            cell(reasoningEffortCombo).align(Align.FILL)
        }
    }.apply {
        preferredSize = preferredSize.apply { width = JBUI.scale(620) }
    }

    @TestOnly
    internal fun componentForTest(): JComponent = createCenterPanel()

    @TestOnly
    internal fun confirmForTest() = doOKAction()

    @TestOnly
    internal fun acceptedResultForTest(): ProviderProfileEditResult? = acceptedResult

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.trim().isBlank()) {
            return ValidationInfo(CommitMessageBundle.message("settings.providers.nameRequired"), nameField)
        }
        if (selectedProvider() != LlmProviderType.CHATGPT_CODEX && !isSafeEndpoint(endpointField.text)) {
            return ValidationInfo(CommitMessageBundle.message("settings.providers.endpointInvalid"), endpointField)
        }
        return null
    }

    fun result(): ProviderProfileEditResult = checkNotNull(acceptedResult)

    override fun doOKAction() {
        val validation = doValidate()
        if (validation != null) {
            validation.component?.requestFocusInWindow()
            return
        }
        modelCombo.commitCurrentText()
        acceptedResult = buildResult()
        super.doOKAction()
    }

    private fun buildResult(): ProviderProfileEditResult {
        val password = apiKeyField.password
        val key = try {
            password.takeIf { it.isNotEmpty() }?.copyOf()
        } finally {
            password.fill('\u0000')
        }
        val acceptedKey = if (selectedProvider() == LlmProviderType.CHATGPT_CODEX) {
            key?.fill('\u0000')
            null
        } else {
            key
        }
        return ProviderProfileEditResult(
            profile = profileSnapshot(),
            apiKey = acceptedKey,
            clearApiKey = clearRequested,
            modelSuggestions = loadedModels,
        )
    }

    override fun dispose() {
        cancelModelRequest(showStatus = false)
        val password = apiKeyField.password
        try {
            apiKeyField.text = ""
        } finally {
            password.fill('\u0000')
        }
        super.dispose()
    }

    private fun bindListeners() {
        providerCombo.addActionListener {
            val selected = (providerCombo.selectedItem as? ProviderChoice)?.type ?: return@addActionListener
            val currentEndpoint = endpointField.text.trim()
            if (currentEndpoint.isBlank() || currentEndpoint == defaultEndpoint(previousProvider)) {
                endpointField.text = defaultEndpoint(selected)
            }
            if (selected != previousProvider) {
                loadedModels = emptyList()
                modelCombo.setSuggestions(emptyList(), modelCombo.currentText())
            }
            previousProvider = selected
            invalidateSuggestionsIfConnectionChanged()
            updateProviderControls()
        }
        endpointField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = invalidateSuggestionsIfConnectionChanged()
        })
        apiKeyField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                if (activeModelRequest != null) cancelModelRequest(showStatus = false)
                val password = apiKeyField.password
                try {
                    if (password.isNotEmpty()) clearRequested = false
                } finally {
                    password.fill('\u0000')
                }
                updateApiKeyStatus()
            }
        })
        clearApiKeyButton.addActionListener {
            cancelModelRequest(showStatus = false)
            clearRequested = true
            apiKeyField.text = ""
            updateApiKeyStatus()
        }
        fetchModelsButton.addActionListener {
            if (activeModelRequest != null) cancelModelRequest(showStatus = true) else fetchModels()
        }
    }

    private fun fetchModels() {
        val validation = doValidate()
        if (validation != null) {
            validation.component?.requestFocusInWindow()
            fetchStatus.text = validation.message
            fetchStatus.foreground = JBColor.RED
            return
        }
        val password = apiKeyField.password
        val key = try {
            password.takeIf { it.isNotEmpty() }?.copyOf() ?: if (clearRequested) CharArray(0) else null
        } finally {
            password.fill('\u0000')
        }
        val generation = ++modelRequestGeneration
        activeRequestKey?.fill('\u0000')
        activeRequestKey = key
        fetchModelsButton.isEnabled = true
        fetchModelsButton.text = CommitMessageBundle.message("settings.providers.cancelFetchModels")
        fetchStatus.foreground = UIUtil.getContextHelpForeground()
        fetchStatus.text = CommitMessageBundle.message("settings.providers.modelsStage")
        val requestedProfile = profileSnapshot()
        var callbackCompleted = false
        val request = fetcher.fetch(requestedProfile, key) { outcome ->
            callbackCompleted = true
            if (generation != modelRequestGeneration) return@fetch
            activeRequestKey?.fill('\u0000')
            activeRequestKey = null
            activeModelRequest = null
            if (isDisposed) return@fetch
            val currentProfile = profileSnapshot()
            if (currentProfile.provider != requestedProfile.provider ||
                currentProfile.baseUrl != requestedProfile.baseUrl
            ) {
                fetchModelsButton.isEnabled = true
                fetchModelsButton.text = CommitMessageBundle.message("settings.providers.fetchModels")
                fetchStatus.foreground = UIUtil.getContextHelpForeground()
                fetchStatus.text = CommitMessageBundle.message("settings.providers.staleModelsIgnored")
                return@fetch
            }
            fetchModelsButton.isEnabled = true
            fetchModelsButton.text = CommitMessageBundle.message("settings.providers.fetchModels")
            outcome.onSuccess { models ->
                loadedModels = models.distinct()
                suggestionProvider = requestedProfile.provider
                suggestionEndpoint = normalizedEndpoint(requestedProfile.baseUrl)
                modelCombo.setSuggestions(loadedModels, modelCombo.currentText())
                modelCombo.filterNow()
                fetchStatus.foreground = UIUtil.getContextHelpForeground()
                fetchStatus.text = if (models.isEmpty()) {
                    CommitMessageBundle.message("settings.providers.noModels")
                } else {
                    CommitMessageBundle.message("settings.providers.modelsLoaded", models.size)
                }
            }.onFailure { error ->
                fetchStatus.foreground = JBColor.RED
                fetchStatus.text = providerFailureMessage(error)
            }
        }
        if (!callbackCompleted && generation == modelRequestGeneration && !isDisposed) {
            activeModelRequest = request
        } else if (generation != modelRequestGeneration || isDisposed) {
            request.cancel()
        }
    }

    private fun cancelModelRequest(showStatus: Boolean) {
        modelRequestGeneration += 1
        activeModelRequest?.cancel()
        activeModelRequest = null
        activeRequestKey?.fill('\u0000')
        activeRequestKey = null
        fetchModelsButton.isEnabled = true
        fetchModelsButton.text = CommitMessageBundle.message("settings.providers.fetchModels")
        if (showStatus) {
            fetchStatus.foreground = UIUtil.getContextHelpForeground()
            fetchStatus.text = CommitMessageBundle.message("settings.providers.testCancelled")
        }
    }

    private fun profileSnapshot(): LlmProfile = input.profile.copy(
        name = nameField.text.trim(),
        provider = selectedProvider(),
        baseUrl = if (selectedProvider() == LlmProviderType.CHATGPT_CODEX) "" else endpointField.text.trim().trimEnd('/'),
        model = modelCombo.currentText(),
        reasoningCompatibility = reasoningField.isSelected,
        reasoningEffort = (reasoningEffortCombo.selectedItem as? ReasoningEffortChoice)?.value.orEmpty(),
    )

    private fun updateApiKeyStatus() {
        val password = apiKeyField.password
        val hasEnteredKey = try {
            password.isNotEmpty()
        } finally {
            password.fill('\u0000')
        }
        apiKeyStatus.text = when {
            selectedProvider() == LlmProviderType.CHATGPT_CODEX ->
                CommitMessageBundle.message("settings.providers.apiKey.codexManaged")
            hasEnteredKey -> CommitMessageBundle.message("settings.providers.apiKey.pendingSave")
            clearRequested -> CommitMessageBundle.message("settings.providers.apiKey.pendingClear")
            input.hasStoredKey -> CommitMessageBundle.message("settings.providers.apiKey.stored")
            else -> CommitMessageBundle.message("settings.providers.apiKey.hint")
        }
        clearApiKeyButton.isEnabled = selectedProvider() != LlmProviderType.CHATGPT_CODEX &&
            (hasEnteredKey || input.hasStoredKey || input.sessionKey?.isNotEmpty() == true)
    }

    private fun invalidateSuggestionsIfConnectionChanged() {
        if (loadedModels.isEmpty()) return
        val selectedProvider = (providerCombo.selectedItem as? ProviderChoice)?.type ?: input.profile.provider
        if (selectedProvider == suggestionProvider && normalizedEndpoint(endpointField.text) == suggestionEndpoint) return
        loadedModels = emptyList()
        modelCombo.setSuggestions(emptyList(), modelCombo.currentText())
        fetchStatus.foreground = UIUtil.getContextHelpForeground()
        fetchStatus.text = CommitMessageBundle.message("settings.providers.staleModelsIgnored")
    }

    private fun isSafeEndpoint(value: String): Boolean {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() in setOf("http", "https") &&
            uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
    }

    private fun defaultEndpoint(provider: LlmProviderType): String = when (provider) {
        LlmProviderType.OPENAI_COMPATIBLE -> "https://api.openai.com/v1"
        LlmProviderType.ANTHROPIC -> "https://api.anthropic.com"
        LlmProviderType.CHATGPT_CODEX -> ""
    }

    private fun selectedProvider(): LlmProviderType =
        (providerCombo.selectedItem as? ProviderChoice)?.type ?: input.profile.provider

    private fun updateProviderControls() {
        val codex = selectedProvider() == LlmProviderType.CHATGPT_CODEX
        endpointField.isEnabled = !codex
        apiKeyField.isEnabled = !codex
        reasoningField.isEnabled = !codex
        reasoningEffortCombo.isEnabled = codex
        if (codex) {
            endpointField.text = ""
            reasoningField.isSelected = false
        } else if (endpointField.text.isBlank()) {
            endpointField.text = defaultEndpoint(selectedProvider())
        }
        updateApiKeyStatus()
    }

    private fun normalizedEndpoint(value: String): String = value.trim().trimEnd('/')

    private fun providerFailureMessage(error: Throwable): String = when ((error as? ProviderException)?.kind) {
        ProviderErrorKind.AUTHENTICATION -> CommitMessageBundle.message("error.provider.authentication")
        ProviderErrorKind.RATE_LIMIT -> CommitMessageBundle.message("error.provider.rateLimit")
        ProviderErrorKind.TIMEOUT -> CommitMessageBundle.message("error.provider.timeout")
        else -> error.message?.take(300).orEmpty().ifBlank {
            CommitMessageBundle.message("settings.providers.requestFailed")
        }
    }

    private data class ProviderChoice(val type: LlmProviderType, val label: String) {
        override fun toString(): String = label
    }

    private data class ReasoningEffortChoice(val value: String, val label: String) {
        override fun toString(): String = label
    }
}
