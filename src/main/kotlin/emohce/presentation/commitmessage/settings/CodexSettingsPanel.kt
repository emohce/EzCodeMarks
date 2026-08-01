package emohce.presentation.commitmessage.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import emohce.data.commitmessage.CodexAccountSettingsGateway
import emohce.data.commitmessage.CodexAppServerErrorKind
import emohce.data.commitmessage.CodexAppServerException
import emohce.data.commitmessage.CodexAppServerService
import emohce.data.commitmessage.CodexInstallationProblem
import emohce.domain.commitmessage.ProviderErrorSanitizer
import emohce.presentation.commitmessage.CommitMessageBundle
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

internal class CodexSettingsPanel(
    private val gateway: CodexAccountSettingsGateway = CodexAppServerService.getInstance(),
    private val requestRunner: ProviderSettingsRequestRunner = DEFAULT_PROVIDER_SETTINGS_REQUEST_RUNNER,
    private val openCodexUrl: (String) -> Unit = BrowserUtil::browse,
    private val showCodexDeviceCode: (String) -> Unit = { code ->
        Messages.showInfoMessage(
            CommitMessageBundle.message("settings.providers.codex.deviceCode", code),
            CommitMessageBundle.message("settings.providers.codex.group"),
        )
    },
    private val confirmCodexLogin: (Boolean) -> Boolean = { deviceCode ->
        Messages.showYesNoDialog(
            CommitMessageBundle.message(
                if (deviceCode) "settings.providers.codex.deviceLoginConfirm"
                else "settings.providers.codex.loginConfirm",
            ),
            CommitMessageBundle.message("settings.providers.codex.group"),
            Messages.getWarningIcon(),
        ) == Messages.YES
    },
    private val confirmCodexLogout: () -> Boolean = {
        Messages.showYesNoDialog(
            CommitMessageBundle.message("settings.providers.codex.logoutConfirm"),
            CommitMessageBundle.message("settings.providers.codex.group"),
            Messages.getQuestionIcon(),
        ) == Messages.YES
    },
) : Disposable {
    private val executableField = JBTextField().apply { columns = 42 }
    private val accountStatus = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val refreshButton = JButton(CommitMessageBundle.message("settings.providers.codex.refresh"))
    private val signInButton = JButton(CommitMessageBundle.message("settings.providers.codex.signIn"))
    private val deviceButton = JButton(CommitMessageBundle.message("settings.providers.codex.deviceSignIn"))
    private val logoutButton = JButton(CommitMessageBundle.message("settings.providers.codex.logout"))

    private val requestGeneration = AtomicLong()
    private val currentRequest = AtomicReference<CodexRequestHandle?>()
    private val requestLock = Any()
    @Volatile
    private var signedIn = false
    @Volatile
    private var disposed = false

    private var loadedExecutable = ""

    val component: JComponent = panel {
        row {
            cell(JBLabel(CommitMessageBundle.message("settings.providers.codex.interactiveIdentity")))
                .align(Align.FILL)
                .resizableColumn()
        }
        row {
            cell(JBLabel(CommitMessageBundle.message("settings.providers.codex.isolatedIdentity")))
                .align(Align.FILL)
                .resizableColumn()
        }
        row(CommitMessageBundle.message("settings.providers.codex.executable")) {
            cell(executableField).align(Align.FILL).resizableColumn()
            button(CommitMessageBundle.message("settings.providers.codex.browse")) {
                FileChooser.chooseFile(
                    FileChooserDescriptorFactory.singleFile(),
                    null,
                    null,
                )?.let { executableField.text = it.path }
            }
            cell(refreshButton)
        }
        row(CommitMessageBundle.message("settings.providers.codex.account")) {
            cell(accountStatus).align(Align.FILL).resizableColumn()
            cell(signInButton)
            cell(deviceButton)
            cell(logoutButton)
        }
    }

    init {
        executableField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                if (disposed) return
                if (!isExecutableApplied()) {
                    accountStatus.text = CommitMessageBundle.message("settings.providers.codex.applyExecutableFirst")
                }
                setButtons(running = currentRequest.get() != null, signedIn = signedIn)
            }
        })
        refreshButton.addActionListener { refreshAccount() }
        signInButton.addActionListener { startLogin(deviceCode = false) }
        deviceButton.addActionListener { startLogin(deviceCode = true) }
        logoutButton.addActionListener { logoutAccount() }
    }

    fun reset() {
        cancelRequest(showCancelled = false)
        loadedExecutable = gateway.executablePath()
        executableField.text = loadedExecutable
        executableField.emptyText.text = loadedExecutable.ifBlank {
            gateway.resolvedExecutablePath().takeIf { it.isNotBlank() }
        }.orEmpty()
        accountStatus.text = CommitMessageBundle.message("settings.providers.codex.notChecked")
        signedIn = false
        setButtons(running = false, signedIn = false)
    }

    fun applySettings() {
        cancelRequest(showCancelled = false)
        val requested = executableField.text.trim()
        val current = gateway.executablePath()
        if (current != loadedExecutable && current != requested) {
            throw ConfigurationException(CommitMessageBundle.message("settings.providers.concurrentChange"))
        }
        if (requested != current) {
            gateway.setExecutablePath(requested, current)
        }
        loadedExecutable = requested
        accountStatus.text = CommitMessageBundle.message("settings.providers.codex.notChecked")
        setButtons(running = false, signedIn = signedIn)
    }

    fun isModified(): Boolean = executableField.text.trim() != loadedExecutable

    override fun dispose() {
        disposed = true
        cancelRequest(showCancelled = false)
    }

    private fun refreshAccount() {
        runCodexRequest(CommitMessageBundle.message("settings.providers.codex.checking")) { service, indicator, generation ->
            val installation = service.installationStatus()
            if (!installation.available) {
                onCodexEdt(generation) { showInstallation(installation) }
                return@runCodexRequest
            }
            val account = service.account(indicator)
            onCodexEdt(generation) {
                showAccount(account.type == "chatgpt" && !account.requiresOpenAiAuth, account.email, account.planType)
            }
        }
    }

    private fun startLogin(deviceCode: Boolean) {
        if (!isExecutableApplied()) {
            accountStatus.text = CommitMessageBundle.message("settings.providers.codex.applyExecutableFirst")
            return
        }
        if (!confirmCodexLogin(deviceCode)) return
        runCodexRequest(CommitMessageBundle.message("settings.providers.codex.signingIn")) { service, indicator, generation ->
            val installation = service.installationStatus()
            if (!installation.available) {
                onCodexEdt(generation) { showInstallation(installation) }
                return@runCodexRequest
            }
            val completed = if (deviceCode) {
                service.deviceLogin(indicator) { login ->
                    onCodexEdt(generation) {
                        openCodexUrl(login.verificationUrl)
                        showCodexDeviceCode(login.userCode)
                    }
                }
            } else {
                service.browserLogin(indicator) { login ->
                    onCodexEdt(generation) { openCodexUrl(login.authUrl) }
                }
            }
            if (!completed.success) {
                throw CodexAppServerException(
                    CodexAppServerErrorKind.REQUEST,
                    completed.error.orEmpty().ifBlank {
                        CommitMessageBundle.message("settings.providers.codex.signInFailed")
                    },
                )
            }
            val account = service.account(indicator)
            onCodexEdt(generation) { showAccount(true, account.email, account.planType) }
        }
    }

    private fun logoutAccount() {
        if (!confirmCodexLogout()) return
        runCodexRequest(
            CommitMessageBundle.message("settings.providers.codex.loggingOut"),
            requireAppliedExecutable = false,
        ) { service, _, generation ->
            service.logout()
            onCodexEdt(generation) { showAccount(false, null, null) }
        }
    }

    private fun runCodexRequest(
        status: String,
        requireAppliedExecutable: Boolean = true,
        operation: (CodexAccountSettingsGateway, ProgressIndicator, Long) -> Unit,
    ) {
        if (requireAppliedExecutable && executableField.text.trim() != gateway.executablePath()) {
            accountStatus.text = CommitMessageBundle.message("settings.providers.codex.applyExecutableFirst")
            return
        }
        val handle = synchronized(requestLock) {
            if (currentRequest.get() != null) return
            CodexRequestHandle(requestGeneration.incrementAndGet()).also(currentRequest::set)
        }
        val generation = handle.generation
        accountStatus.text = status
        setButtons(running = true, signedIn = signedIn)
        requestRunner.run(status) { indicator ->
            handle.indicator.set(indicator)
            if (currentRequest.get() !== handle || generation != requestGeneration.get()) indicator.cancel()
            try {
                indicator.checkCanceled()
                operation(gateway, indicator, generation)
            } catch (_: ProcessCanceledException) {
                onCodexEdt(generation) {
                    accountStatus.text = CommitMessageBundle.message("settings.providers.testCancelled")
                }
            } catch (error: Throwable) {
                val safe = ProviderErrorSanitizer.sanitize(error.message).take(300)
                onCodexEdt(generation) {
                    accountStatus.text = safe.ifBlank { CommitMessageBundle.message("settings.providers.requestFailed") }
                }
            } finally {
                handle.indicator.compareAndSet(indicator, null)
                currentRequest.compareAndSet(handle, null)
                onCodexEdt(generation) { setButtons(running = false, signedIn = signedIn) }
            }
        }
    }

    private fun cancelRequest(showCancelled: Boolean) {
        val cancelled = synchronized(requestLock) {
            requestGeneration.incrementAndGet()
            currentRequest.getAndSet(null)
        }
        cancelled?.indicator?.getAndSet(null)?.cancel()
        if (!disposed) {
            if (showCancelled) {
                accountStatus.text = CommitMessageBundle.message("settings.providers.testCancelled")
            }
            setButtons(running = false, signedIn = signedIn)
        }
    }

    private fun onCodexEdt(generation: Long, action: () -> Unit) {
        val task = {
            if (!disposed && generation == requestGeneration.get()) action()
        }
        if (ApplicationManager.getApplication().isDispatchThread) task()
        else ApplicationManager.getApplication().invokeLater(task, ModalityState.any())
    }

    private fun showInstallation(status: emohce.data.commitmessage.CodexInstallationStatus) {
        signedIn = false
        accountStatus.text = when (status.problem) {
            CodexInstallationProblem.NOT_FOUND -> CommitMessageBundle.message("settings.providers.codex.error.notFound")
            CodexInstallationProblem.VERSION_TIMEOUT -> CommitMessageBundle.message("settings.providers.codex.error.versionTimeout")
            CodexInstallationProblem.VERSION_UNKNOWN -> CommitMessageBundle.message("settings.providers.codex.error.versionUnknown")
            CodexInstallationProblem.VERSION_TOO_OLD -> CommitMessageBundle.message(
                "settings.providers.codex.error.versionTooOld",
                CodexAppServerService.MINIMUM_CODEX_VERSION,
            )
            CodexInstallationProblem.VERSION_CHECK_FAILED -> CommitMessageBundle.message(
                "settings.providers.codex.error.versionCheckFailed",
            )
            null -> status.message
        }
        setButtons(running = false, signedIn = false)
    }

    private fun showAccount(signedIn: Boolean, email: String?, planType: String?) {
        this.signedIn = signedIn
        accountStatus.text = if (signedIn) {
            CommitMessageBundle.message(
                "settings.providers.codex.signedIn",
                email.orEmpty().ifBlank { CommitMessageBundle.message("settings.providers.codex.accountUnknown") },
                planType.orEmpty().ifBlank { CommitMessageBundle.message("settings.providers.codex.planUnknown") },
            )
        } else {
            CommitMessageBundle.message("settings.providers.codex.signedOut")
        }
        setButtons(running = false, signedIn = signedIn)
    }

    private fun setButtons(running: Boolean, signedIn: Boolean) {
        val applied = isExecutableApplied()
        refreshButton.isEnabled = !running && applied
        signInButton.isEnabled = !running && !signedIn && applied
        deviceButton.isEnabled = !running && !signedIn && applied
        logoutButton.isEnabled = !running && signedIn
    }

    private fun isExecutableApplied(): Boolean = executableField.text.trim() == loadedExecutable

    private class CodexRequestHandle(val generation: Long) {
        val indicator = AtomicReference<ProgressIndicator?>()
    }
}
