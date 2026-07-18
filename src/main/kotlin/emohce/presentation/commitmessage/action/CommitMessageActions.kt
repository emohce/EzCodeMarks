package emohce.presentation.commitmessage.action

import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import emohce.data.commitmessage.CommitMessageAiService
import emohce.data.commitmessage.CodexAppServerService
import emohce.data.commitmessage.CommitMessageCoordinatorService
import emohce.data.commitmessage.CommitOperationHandle
import emohce.data.commitmessage.CommitMessageSettingsService
import emohce.data.commitmessage.CommitMessageSettingsState
import emohce.data.commitmessage.CommitMessageSecretStore
import emohce.data.commitmessage.CommitProjectStateService
import emohce.data.commitmessage.CommitProjectSharedSettingsService
import emohce.data.commitmessage.MissingActiveProfileException
import emohce.data.commitmessage.MissingApiKeyException
import emohce.data.commitmessage.MissingChatGptLoginException
import emohce.data.commitmessage.SourceContextConsent
import emohce.data.commitmessage.VelocityCommitTemplateRenderer
import emohce.domain.commitmessage.AiPreview
import emohce.domain.commitmessage.CommitActionKind
import emohce.domain.commitmessage.CommitDraft
import emohce.domain.commitmessage.CommitMessageParser
import emohce.domain.commitmessage.CommitMessageDefaults
import emohce.domain.commitmessage.CommitTemplateSnapshot
import emohce.domain.commitmessage.LlmProfile
import emohce.domain.commitmessage.LlmProviderType
import emohce.domain.commitmessage.LlmProfileScope
import emohce.domain.commitmessage.ProviderErrorKind
import emohce.domain.commitmessage.ProviderException
import emohce.domain.commitmessage.withEffectivePrompt
import emohce.presentation.commitmessage.CommitActionSnapshot
import emohce.presentation.commitmessage.CommitMessageBundle
import emohce.presentation.commitmessage.IntelliJCommitActionContextAdapter
import emohce.presentation.commitmessage.dialog.AdditionalRequirementsDialog
import emohce.presentation.commitmessage.dialog.AiCommitMessagePreviewDialog
import emohce.presentation.commitmessage.dialog.CommitMessagePreviewRefiner
import emohce.presentation.commitmessage.dialog.DefaultCommitMessagePreviewRefiner
import emohce.presentation.commitmessage.dialog.StructuredCommitMessageDialog
import javax.swing.Icon

abstract class BaseCommitMessageAction(
    private val kind: CommitActionKind,
    private val normalIcon: Icon,
) : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    final override fun update(event: AnActionEvent) {
        val context = IntelliJCommitActionContextAdapter.availability(event)
        val settings = CommitMessageSettingsService.getInstance().snapshot(refreshPortable = false)
        val toolbarPlace = event.isFromActionToolbar && event.place in COMMIT_MESSAGE_TOOLBAR_PLACES
        event.presentation.isVisible = !toolbarPlace || toolbarVisible(settings)

        val running = context?.document?.let {
            CommitMessageCoordinatorService.getInstance(context.project).status(it)
        }
        event.presentation.icon = if (running?.action == kind) AllIcons.Actions.StopRefresh else normalIcon
        event.presentation.isEnabled = context != null && supports(context) &&
            (running == null || running.action == kind)
    }

    final override fun actionPerformed(event: AnActionEvent) {
        val snapshot = IntelliJCommitActionContextAdapter.snapshot(event) ?: return
        val coordinator = CommitMessageCoordinatorService.getInstance(snapshot.project)
        if (coordinator.cancel(snapshot.document, kind)) {
            IntelliJCommitActionContextAdapter.stopLoading(snapshot)
            ActivityTracker.getInstance().inc()
            return
        }
        perform(snapshot)
    }

    protected abstract fun toolbarVisible(settings: CommitMessageSettingsState): Boolean

    protected abstract fun supports(context: emohce.presentation.commitmessage.CommitActionAvailability): Boolean

    protected abstract fun perform(snapshot: CommitActionSnapshot)

    protected fun <R : Any> runBackground(
        snapshot: CommitActionSnapshot,
        title: String,
        computation: (ProgressIndicator) -> R,
        success: (R, CommitOperationHandle) -> Unit,
    ) {
        val coordinator = CommitMessageCoordinatorService.getInstance(snapshot.project)
        val handle = coordinator.start(snapshot.document, kind) ?: return
        IntelliJCommitActionContextAdapter.startLoading(snapshot)
        ActivityTracker.getInstance().inc()

        object : Task.Backgroundable(snapshot.project, title, true) {
            private var result: R? = null

            override fun run(indicator: ProgressIndicator) {
                coordinator.attachIndicator(handle, indicator)
                indicator.checkCanceled()
                if (handle.isCancelled()) throw ProcessCanceledException()
                result = computation(indicator)
                indicator.checkCanceled()
            }

            override fun onSuccess() {
                val value = result ?: return
                if (!coordinator.isCurrent(handle)) return
                if (IntelliJCommitActionContextAdapter.isUnchanged(snapshot)) {
                    success(value, handle)
                } else {
                    notifySourceChanged(snapshot)
                }
            }

            override fun onThrowable(error: Throwable) {
                if (!coordinator.isCurrent(handle) || handle.isCancelled()) return
                notifyFailure(snapshot, error)
            }

            override fun onFinished() {
                IntelliJCommitActionContextAdapter.stopLoading(snapshot)
                coordinator.finish(handle)
                ActivityTracker.getInstance().inc()
            }
        }.queue()
    }

    internal fun applyAiResult(
        snapshot: CommitActionSnapshot,
        preview: AiPreview,
        showPreview: Boolean,
        operationHandle: CommitOperationHandle? = null,
        refiner: CommitMessagePreviewRefiner? = null,
    ) {
        val result = if (showPreview) {
            val dialog = AiCommitMessagePreviewDialog(snapshot.project, preview, refiner)
            if (!dialog.showAndGet()) return
            dialog.result
        } else {
            preview.result
        }
        if (operationHandle != null &&
            !CommitMessageCoordinatorService.getInstance(snapshot.project).isCurrent(operationHandle)
        ) {
            return
        }
        if (IntelliJCommitActionContextAdapter.isUnchanged(snapshot)) {
            IntelliJCommitActionContextAdapter.write(snapshot, result)
        } else {
            notifySourceChanged(snapshot)
        }
    }

    protected fun activeProfileSnapshot(snapshot: CommitActionSnapshot): LlmProfile? {
        val settings = CommitMessageSettingsService.getInstance()
        val profile = CommitProjectStateService.getInstance(snapshot.project).resolveProfileSnapshot(settings)
        if (profile == null) {
            notifyMissingProfile(snapshot)
            return null
        }
        return profile
    }

    protected fun consentedProfileSnapshot(snapshot: CommitActionSnapshot): LlmProfile? {
        val service = CommitMessageSettingsService.getInstance()
        val projectState = CommitProjectStateService.getInstance(snapshot.project)
        val current = projectState.resolveProfileSnapshot(service) ?: run {
            notifyMissingProfile(snapshot)
            return null
        }
        val accountGeneration = when (current.provider) {
            LlmProviderType.CHATGPT_CODEX -> CodexAppServerService.getInstance().authGeneration()
            else -> {
                val selected = projectState.activeProfileRef()
                val credentialId = if (selected?.scope == LlmProfileScope.PROJECT &&
                    selected.id == current.id && projectState.profile(current.id) != null
                ) {
                    projectState.projectCredentialId(current.id)
                } else {
                    current.id
                }
                CommitMessageSecretStore().credentialGeneration(credentialId)
            }
        }
        if (projectState.hasSourceContextConsent(current, accountGeneration)) return current.copy()
        val destination = current.baseUrl.ifBlank { "ChatGPT / Codex" }
        val choice = Messages.showYesNoDialog(
            snapshot.project,
            CommitMessageBundle.message("error.context.consent.message", destination),
            CommitMessageBundle.message("error.context.consent.title"),
            Messages.getQuestionIcon(),
        )
        if (choice != Messages.YES) return null
        projectState.grantSourceContextConsent(current, accountGeneration)
        return current.copy()
    }

    protected fun templateSnapshot(snapshot: CommitActionSnapshot): CommitTemplateSnapshot {
        val service = CommitMessageSettingsService.getInstance()
        val state = service.snapshot()
        val projectState = CommitProjectStateService.getInstance(snapshot.project)
        val shared = CommitProjectSharedSettingsService.getInstance(snapshot.project)
        val style = projectState.resolveStyle(service, shared).withEffectivePrompt()
        return CommitTemplateSnapshot(
            candidates = projectState.templateCandidates(service, shared).map { it.copy() },
            allowedTypes = state.types.map { it.id }.filter { it.isNotBlank() }.distinct(),
            style = style.copy(),
            persistentInstructions = shared.effectiveExtraInstructions(state.persistentExtraInstructions),
        )
    }

    private fun notifyFailure(snapshot: CommitActionSnapshot, error: Throwable) {
        when (error) {
            is MissingActiveProfileException -> {
                notifyProviderSetup(snapshot, "error.profile.missing", providerSettingsTarget(snapshot))
                return
            }
            is MissingApiKeyException -> {
                notifyProviderSetup(snapshot, "error.apiKey.missing", providerSettingsTarget(snapshot))
                return
            }
            is MissingChatGptLoginException -> {
                notifyProviderSetup(snapshot, "error.chatgpt.loginRequired", PROVIDERS_CONFIGURABLE_ID)
                return
            }
        }
        val message = when ((error as? ProviderException)?.kind) {
            ProviderErrorKind.AUTHENTICATION -> CommitMessageBundle.message("error.provider.authentication")
            ProviderErrorKind.RATE_LIMIT -> CommitMessageBundle.message("error.provider.rateLimit")
            ProviderErrorKind.TIMEOUT -> CommitMessageBundle.message("error.provider.timeout")
            ProviderErrorKind.INVALID_RESPONSE -> CommitMessageBundle.message("error.output.invalid")
            else -> CommitMessageBundle.message(
                "error.operation.failed",
                error.message?.take(300).orEmpty(),
            )
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("EzCodeMarks")
            .createNotification(message, NotificationType.ERROR)
            .notify(snapshot.project)
    }

    private fun notifyMissingProfile(snapshot: CommitActionSnapshot) {
        notifyProviderSetup(snapshot, "error.profile.missing", providerSettingsTarget(snapshot))
    }

    private fun notifyProviderSetup(snapshot: CommitActionSnapshot, messageKey: String, configurableId: String) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("EzCodeMarks")
            .createNotification(
                CommitMessageBundle.message(messageKey),
                NotificationType.WARNING,
            )
        notification.addAction(
            NotificationAction.createSimpleExpiring(
                CommitMessageBundle.message("error.profile.openSettings"),
            ) {
                ShowSettingsUtil.getInstance().showSettingsDialog(
                    snapshot.project,
                    configurableId,
                )
            },
        )
        notification.notify(snapshot.project)
    }

    private fun providerSettingsTarget(snapshot: CommitActionSnapshot): String {
        val ref = CommitProjectStateService.getInstance(snapshot.project).activeProfileRef()
        return if (ref?.scope == LlmProfileScope.PROJECT) PROJECT_PROVIDERS_CONFIGURABLE_ID
        else PROVIDERS_CONFIGURABLE_ID
    }

    private fun notifySourceChanged(snapshot: CommitActionSnapshot) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("EzCodeMarks")
            .createNotification(
                CommitMessageBundle.message("error.sourceChanged"),
                NotificationType.WARNING,
            )
            .notify(snapshot.project)
    }

    companion object {
        const val COMMIT_MESSAGE_PLACE: String = "CommitMessage"
        const val CHANGES_VIEW_COMMIT_TOOLBAR_PLACE: String = "ChangesView.CommitToolbar"
        const val PROVIDERS_CONFIGURABLE_ID: String = "emohce.settings.commitMessage.providers"
        const val PROJECT_PROVIDERS_CONFIGURABLE_ID: String = "emohce.settings.commitMessage.projectProviders"

        private val COMMIT_MESSAGE_TOOLBAR_PLACES = setOf(
            COMMIT_MESSAGE_PLACE,
            CHANGES_VIEW_COMMIT_TOOLBAR_PLACE,
        )
    }
}

class CreateCommitMessageAction : BaseCommitMessageAction(CommitActionKind.CREATE, AllIcons.Actions.Edit) {
    override fun toolbarVisible(settings: CommitMessageSettingsState): Boolean = settings.showCreateInToolbar

    override fun supports(context: emohce.presentation.commitmessage.CommitActionAvailability): Boolean = true

    override fun perform(snapshot: CommitActionSnapshot) {
        val settingsService = CommitMessageSettingsService.getInstance()
        val settings = settingsService.snapshot()
        val projectState = CommitProjectStateService.getInstance(snapshot.project)
        val initial = when {
            snapshot.currentText.isNotBlank() -> CommitMessageParser.parse(
                snapshot.currentText,
                settings.types.firstOrNull()?.id ?: "feat",
            )
            projectState.state.draft != null -> projectState.state.draft!!.copy()
            else -> CommitDraft(
                type = settings.types.firstOrNull()?.id ?: "feat",
                skipCi = settings.defaultSkipCi,
            )
        }

        val smartEchoProfile = projectState.resolveProfileSnapshot(settingsService)
        if (settings.smartEcho && snapshot.currentText.isNotBlank() && smartEchoProfile != null) {
            val profile = smartEchoProfile
            runBackground(
                snapshot = snapshot,
                title = CommitMessageBundle.message("progress.smartEcho"),
                computation = {
                    CommitMessageAiService.getInstance(snapshot.project).smartEcho(
                        initial,
                        profile,
                        settings.types.map { type -> type.id },
                        projectState.resolveStyle(
                            settingsService,
                            CommitProjectSharedSettingsService.getInstance(snapshot.project),
                        ).withEffectivePrompt().prompt,
                        CommitProjectSharedSettingsService.getInstance(snapshot.project)
                            .effectiveExtraInstructions(settings.persistentExtraInstructions),
                        it,
                    )
                },
                success = { result, _ -> showStructuredEditor(snapshot, result, settings) },
            )
        } else {
            showStructuredEditor(snapshot, initial, settings)
        }
    }

    private fun showStructuredEditor(
        snapshot: CommitActionSnapshot,
        initial: CommitDraft,
        settings: CommitMessageSettingsState,
    ) {
        val projectState = CommitProjectStateService.getInstance(snapshot.project)
        val dialog = StructuredCommitMessageDialog(snapshot.project, initial, settings, settings.types)
        if (!dialog.showAndGet()) {
            projectState.saveDraft(dialog.draft)
            return
        }
        val settingsService = CommitMessageSettingsService.getInstance()
        val renderer = ApplicationManager.getApplication().getService(VelocityCommitTemplateRenderer::class.java)
        val template = projectState.resolveValidTemplate(
            settingsService,
            CommitProjectSharedSettingsService.getInstance(snapshot.project),
            renderer,
        )
        val rendered = renderer.render(template, dialog.draft)
        if (rendered.isBlank()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("EzCodeMarks")
                .createNotification(CommitMessageBundle.message("error.template.empty"), NotificationType.ERROR)
                .notify(snapshot.project)
            projectState.saveDraft(dialog.draft)
            return
        }
        if (IntelliJCommitActionContextAdapter.isUnchanged(snapshot)) {
            IntelliJCommitActionContextAdapter.write(snapshot, rendered)
            projectState.clearDraft()
        } else {
            projectState.saveDraft(dialog.draft)
            NotificationGroupManager.getInstance()
                .getNotificationGroup("EzCodeMarks")
                .createNotification(
                    CommitMessageBundle.message("error.sourceChanged"),
                    NotificationType.WARNING,
                )
                .notify(snapshot.project)
        }
    }

}

class GenerateCommitMessageAction : BaseCommitMessageAction(
    CommitActionKind.GENERATE,
    AllIcons.Actions.IntentionBulb,
) {
    override fun toolbarVisible(settings: CommitMessageSettingsState): Boolean = settings.showGenerateInToolbar

    override fun supports(context: emohce.presentation.commitmessage.CommitActionAvailability): Boolean =
        context.hasChanges || context.hasRevision

    override fun perform(snapshot: CommitActionSnapshot) {
        val previewBeforeApply = CommitMessageSettingsService.getInstance().snapshot().previewAiResultBeforeApply
        val profile = consentedProfileSnapshot(snapshot) ?: return
        val template = templateSnapshot(snapshot)
        runBackground(
            snapshot = snapshot,
            title = CommitMessageBundle.message("progress.generate"),
            computation = {
                CommitMessageAiService.getInstance(snapshot.project).generate(snapshot, "", profile, template, it)
            },
            success = { preview, handle ->
                applyAiResult(
                    snapshot,
                    preview,
                    previewBeforeApply,
                    handle,
                    DefaultCommitMessagePreviewRefiner(snapshot.project, profile, template, handle),
                )
            },
        )
    }
}

class GenerateCommitMessageWithContextAction : BaseCommitMessageAction(
    CommitActionKind.GENERATE_WITH_CONTEXT,
    AllIcons.Actions.PreviewDetails,
) {
    override fun toolbarVisible(settings: CommitMessageSettingsState): Boolean =
        settings.showGenerateWithContextInToolbar

    override fun supports(context: emohce.presentation.commitmessage.CommitActionAvailability): Boolean =
        context.hasChanges || context.hasRevision

    override fun perform(snapshot: CommitActionSnapshot) {
        val dialog = AdditionalRequirementsDialog(snapshot.project)
        if (!dialog.showAndGet()) return
        val requirements = dialog.requirements
        val previewBeforeApply = CommitMessageSettingsService.getInstance().snapshot().previewAiResultBeforeApply
        val profile = consentedProfileSnapshot(snapshot) ?: return
        val template = templateSnapshot(snapshot)
        runBackground(
            snapshot = snapshot,
            title = CommitMessageBundle.message("progress.generate"),
            computation = {
                CommitMessageAiService.getInstance(snapshot.project)
                    .generate(snapshot, requirements, profile, template, it)
            },
            success = { preview, handle ->
                applyAiResult(
                    snapshot,
                    preview,
                    previewBeforeApply,
                    handle,
                    DefaultCommitMessagePreviewRefiner(snapshot.project, profile, template, handle),
                )
            },
        )
    }
}

internal fun interface FormatInstructionsProvider {
    fun request(project: com.intellij.openapi.project.Project): String?
}

class FormatCommitMessageAction internal constructor(
    private val instructionsProvider: FormatInstructionsProvider,
) : BaseCommitMessageAction(
    CommitActionKind.FORMAT,
    AllIcons.Actions.ReformatCode,
) {
    constructor() : this(FormatInstructionsProvider { project ->
        val dialog = AdditionalRequirementsDialog(
            project,
            AdditionalRequirementsDialog.Purpose.FORMAT,
        )
        if (dialog.showAndGet()) dialog.requirements else null
    })

    override fun toolbarVisible(settings: CommitMessageSettingsState): Boolean = settings.showFormatInToolbar

    override fun supports(context: emohce.presentation.commitmessage.CommitActionAvailability): Boolean =
        context.currentText.isNotBlank()

    override fun perform(snapshot: CommitActionSnapshot) {
        val instructions = instructionsProvider.request(snapshot.project) ?: return
        val previewBeforeApply = CommitMessageSettingsService.getInstance().snapshot().previewAiResultBeforeApply
        val profile = activeProfileSnapshot(snapshot) ?: return
        val template = templateSnapshot(snapshot)
        runBackground(
            snapshot = snapshot,
            title = CommitMessageBundle.message("progress.format"),
            computation = {
                CommitMessageAiService.getInstance(snapshot.project).format(
                    snapshot.currentText,
                    instructions,
                    profile,
                    template,
                    it,
                )
            },
            success = { preview, handle ->
                applyAiResult(
                    snapshot,
                    preview,
                    previewBeforeApply,
                    handle,
                    DefaultCommitMessagePreviewRefiner(snapshot.project, profile, template, handle),
                )
            },
        )
    }
}

class SelectCommitStyleAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val context = IntelliJCommitActionContextAdapter.availability(event)
        val running = context?.document?.let {
            CommitMessageCoordinatorService.getInstance(context.project).status(it)
        }
        event.presentation.isEnabled = project != null && running == null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val service = CommitMessageSettingsService.getInstance()
        val projectState = CommitProjectStateService.getInstance(project)
        val shared = CommitProjectSharedSettingsService.getInstance(project)
        val global = service.snapshot()
        val styles = global.styles
        val defaultStyle = shared.defaultStyle()
            ?: global.styles.firstOrNull { it.id == global.defaultStyleId }
            ?: CommitMessageDefaults.standardStyle()
        val choices = buildList {
            add(StyleChoice("", CommitMessageBundle.message("action.style.useProjectDefault", displayStyleName(defaultStyle))))
            addAll(styles.map { style -> StyleChoice(style.id, displayStyleName(style)) })
            addAll(
                shared.state.styles
                    .filterNot { candidate -> styles.any { it.id == candidate.id } }
                    .map { style ->
                        StyleChoice(
                            style.id,
                            CommitMessageBundle.message("settings.project.shared.choice", displayStyleName(style)),
                        )
                    },
            )
        }
        val selected = choices.firstOrNull { it.id == projectState.state.styleId } ?: choices.first()
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(choices)
            .setTitle(CommitMessageBundle.message("action.style.title"))
            .setSelectedValue(selected, true)
            .setItemChosenCallback { choice -> projectState.setStyleId(choice.id) }
            .createPopup()
            .showInBestPositionFor(event.dataContext)
    }

    private fun displayStyleName(style: emohce.domain.commitmessage.CommitStyleDefinition): String = when (style.id) {
        CommitMessageDefaults.STANDARD_STYLE_ID -> CommitMessageBundle.message("style.standard.name")
        CommitMessageDefaults.CONCISE_STYLE_ID -> CommitMessageBundle.message("style.concise.name")
        else -> style.name
    }

    private data class StyleChoice(val id: String, val label: String) {
        override fun toString(): String = label
    }
}
