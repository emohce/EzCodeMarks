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
import emohce.data.commitmessage.CommitMessageAiService
import emohce.data.commitmessage.CommitMessageCoordinatorService
import emohce.data.commitmessage.CommitMessageSettingsService
import emohce.data.commitmessage.CommitMessageSettingsState
import emohce.data.commitmessage.CommitProjectStateService
import emohce.data.commitmessage.MissingActiveProfileException
import emohce.data.commitmessage.MissingApiKeyException
import emohce.data.commitmessage.SourceContextConsent
import emohce.data.commitmessage.VelocityCommitTemplateRenderer
import emohce.domain.commitmessage.AiPreview
import emohce.domain.commitmessage.CommitActionKind
import emohce.domain.commitmessage.CommitDraft
import emohce.domain.commitmessage.CommitMessageParser
import emohce.domain.commitmessage.CommitMessageDefaults
import emohce.domain.commitmessage.CommitTemplateSnapshot
import emohce.domain.commitmessage.LlmProfile
import emohce.domain.commitmessage.ProviderErrorKind
import emohce.domain.commitmessage.ProviderException
import emohce.presentation.commitmessage.CommitActionSnapshot
import emohce.presentation.commitmessage.CommitMessageBundle
import emohce.presentation.commitmessage.IntelliJCommitActionContextAdapter
import emohce.presentation.commitmessage.dialog.AdditionalRequirementsDialog
import emohce.presentation.commitmessage.dialog.AiCommitMessagePreviewDialog
import emohce.presentation.commitmessage.dialog.StructuredCommitMessageDialog
import javax.swing.Icon

abstract class BaseCommitMessageAction(
    private val kind: CommitActionKind,
    private val normalIcon: Icon,
) : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    final override fun update(event: AnActionEvent) {
        val context = IntelliJCommitActionContextAdapter.availability(event)
        val settings = CommitMessageSettingsService.getInstance().state
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
        success: (R) -> Unit,
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
                    success(value)
                } else {
                    notifySourceChanged(snapshot)
                }
            }

            override fun onThrowable(error: Throwable) {
                notifyFailure(snapshot, error)
            }

            override fun onFinished() {
                IntelliJCommitActionContextAdapter.stopLoading(snapshot)
                coordinator.finish(handle)
                ActivityTracker.getInstance().inc()
            }
        }.queue()
    }

    protected fun showPreview(snapshot: CommitActionSnapshot, preview: AiPreview) {
        val dialog = AiCommitMessagePreviewDialog(snapshot.project, preview)
        if (!dialog.showAndGet()) return
        if (IntelliJCommitActionContextAdapter.isUnchanged(snapshot)) {
            IntelliJCommitActionContextAdapter.write(snapshot, dialog.result)
        } else {
            notifySourceChanged(snapshot)
        }
    }

    protected fun activeProfileSnapshot(snapshot: CommitActionSnapshot): LlmProfile? {
        val profile = CommitMessageSettingsService.getInstance().activeProfileSnapshot()
        if (profile == null) {
            notifyMissingProfile(snapshot)
            return null
        }
        return profile
    }

    protected fun consentedProfileSnapshot(snapshot: CommitActionSnapshot): LlmProfile? {
        val service = CommitMessageSettingsService.getInstance()
        val current = service.activeProfileSnapshot() ?: run {
            notifyMissingProfile(snapshot)
            return null
        }
        if (SourceContextConsent.isGranted(current)) return current.copy()
        val choice = Messages.showYesNoDialog(
            snapshot.project,
            CommitMessageBundle.message("error.context.consent.message", current.baseUrl),
            CommitMessageBundle.message("error.context.consent.title"),
            Messages.getQuestionIcon(),
        )
        if (choice != Messages.YES) return null
        return service.grantSourceContextConsent(current) ?: run {
            notifySourceChanged(snapshot)
            null
        }
    }

    protected fun templateSnapshot(snapshot: CommitActionSnapshot): CommitTemplateSnapshot {
        val service = CommitMessageSettingsService.getInstance()
        val state = service.state.deepCopy()
        val projectTemplateId = CommitProjectStateService.getInstance(snapshot.project).state.templateId
        val selected = state.templates.firstOrNull { it.id == projectTemplateId }
            ?: state.templates.firstOrNull { it.id == state.defaultTemplateId }
            ?: state.templates.firstOrNull { it.id == CommitMessageDefaults.DEFAULT_TEMPLATE_ID }
            ?: CommitMessageDefaults.templates().single()
        val fallback = state.templates.firstOrNull { it.id == CommitMessageDefaults.DEFAULT_TEMPLATE_ID }?.copy()
            ?: CommitMessageDefaults.templates().single()
        return CommitTemplateSnapshot(
            selected = selected.copy(),
            fallback = fallback,
            allowedTypes = state.types.map { it.id }.filter { it.isNotBlank() }.distinct(),
        )
    }

    private fun notifyFailure(snapshot: CommitActionSnapshot, error: Throwable) {
        if (error is MissingActiveProfileException || error is MissingApiKeyException) {
            notifyMissingProfile(snapshot)
            return
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
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("EzCodeMarks")
            .createNotification(
                CommitMessageBundle.message("error.profile.missing"),
                NotificationType.WARNING,
            )
        notification.addAction(
            NotificationAction.createSimpleExpiring(
                CommitMessageBundle.message("error.profile.openSettings"),
            ) {
                ShowSettingsUtil.getInstance().showSettingsDialog(
                    snapshot.project,
                    PROVIDERS_CONFIGURABLE_ID,
                )
            },
        )
        notification.notify(snapshot.project)
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
        val settings = settingsService.state.deepCopy()
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

        val smartEchoProfile = settingsService.activeProfileSnapshot()
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
                        it,
                    )
                },
                success = { showStructuredEditor(snapshot, it, settings) },
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
        val template = projectState.resolveTemplate(CommitMessageSettingsService.getInstance())
        val renderer = ApplicationManager.getApplication().getService(VelocityCommitTemplateRenderer::class.java)
        val validation = renderer.validate(template)
        if (!validation.valid) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("EzCodeMarks")
                .createNotification(
                    templateErrorMessage(validation.error),
                    NotificationType.ERROR,
                )
                .notify(snapshot.project)
            projectState.saveDraft(dialog.draft)
            return
        }
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

    private fun templateErrorMessage(error: String): String =
        if (error == VelocityCommitTemplateRenderer.EMPTY_OUTPUT_ERROR) {
            CommitMessageBundle.message("error.template.empty")
        } else {
            CommitMessageBundle.message("error.template.invalid", error)
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
        val profile = consentedProfileSnapshot(snapshot) ?: return
        val template = templateSnapshot(snapshot)
        runBackground(
            snapshot = snapshot,
            title = CommitMessageBundle.message("progress.generate"),
            computation = {
                CommitMessageAiService.getInstance(snapshot.project).generate(snapshot, "", profile, template, it)
            },
            success = { showPreview(snapshot, it) },
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
        val profile = consentedProfileSnapshot(snapshot) ?: return
        val template = templateSnapshot(snapshot)
        runBackground(
            snapshot = snapshot,
            title = CommitMessageBundle.message("progress.generate"),
            computation = {
                CommitMessageAiService.getInstance(snapshot.project)
                    .generate(snapshot, requirements, profile, template, it)
            },
            success = { showPreview(snapshot, it) },
        )
    }
}

class FormatCommitMessageAction : BaseCommitMessageAction(
    CommitActionKind.FORMAT,
    AllIcons.Actions.ReformatCode,
) {
    override fun toolbarVisible(settings: CommitMessageSettingsState): Boolean = settings.showFormatInToolbar

    override fun supports(context: emohce.presentation.commitmessage.CommitActionAvailability): Boolean =
        context.currentText.isNotBlank()

    override fun perform(snapshot: CommitActionSnapshot) {
        val profile = activeProfileSnapshot(snapshot) ?: return
        val template = templateSnapshot(snapshot)
        runBackground(
            snapshot = snapshot,
            title = CommitMessageBundle.message("progress.format"),
            computation = {
                CommitMessageAiService.getInstance(snapshot.project).format(snapshot, profile, template, it)
            },
            success = { showPreview(snapshot, it) },
        )
    }
}
