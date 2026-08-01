package emohce.presentation.environmentaction

import com.intellij.ide.DataManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.LocalChangeList
import com.intellij.openapi.vcs.changes.ui.CommitMessageProvider
import com.intellij.openapi.wm.WindowManager
import com.intellij.vcs.commit.CommitMessageUi
import emohce.environmentaction.EnvironmentActionsBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.awt.datatransfer.StringSelection
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JPanel

internal data class PendingCommitMessage(
    val token: String,
    val message: String,
    val expiresAtMillis: Long,
)

internal enum class PreparedCommitMessageChoice {
    REPLACE,
    APPEND,
    CANCEL,
}

internal fun preparedCommitMessageText(
    current: String,
    prepared: String,
    choice: PreparedCommitMessageChoice,
): String? = when {
    current.isBlank() -> prepared
    choice == PreparedCommitMessageChoice.REPLACE -> prepared
    choice == PreparedCommitMessageChoice.APPEND -> current.trimEnd() + "\n\n" + prepared
    else -> null
}

internal fun findVisibleCommitMessageControl(root: Component?): CommitMessageI? {
    if (root == null) return null
    if (root is CommitMessageI && root.isShowing) return root
    if (root is Container) {
        root.components.forEach { child ->
            findVisibleCommitMessageControl(child)?.let { return it }
        }
    }
    return null
}

@Service(Service.Level.PROJECT)
class PendingCommitMessageService : Disposable {
    private val pending = AtomicReference<PendingCommitMessage?>()

    internal fun offer(message: String, lifetimeMillis: Long = PENDING_LIFETIME_MILLIS): PendingCommitMessage {
        val offered = PendingCommitMessage(
            token = UUID.randomUUID().toString(),
            message = message,
            expiresAtMillis = System.currentTimeMillis() + lifetimeMillis,
        )
        pending.set(offered)
        return offered
    }

    fun consume(token: String? = null): String? {
        while (true) {
            val current = pending.get() ?: return null
            if (current.expiresAtMillis < System.currentTimeMillis()) {
                pending.compareAndSet(current, null)
                return null
            }
            if (token != null && token != current.token) return null
            if (pending.compareAndSet(current, null)) return current.message
        }
    }

    fun clear(token: String): String? = consume(token)

    override fun dispose() {
        pending.set(null)
    }

    companion object {
        const val PENDING_LIFETIME_MILLIS: Long = 5_000

        fun getInstance(project: Project): PendingCommitMessageService =
            project.getService(PendingCommitMessageService::class.java)
    }
}

class EnvironmentCommitMessageProvider : CommitMessageProvider {
    override fun getCommitMessage(forChangelist: LocalChangeList, project: Project): String? =
        PendingCommitMessageService.getInstance(project).consume()
}

@Service(Service.Level.PROJECT)
class NativeCommitWorkflowLauncher(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    fun prepare(message: String) {
        val trimmed = message.trim()
        if (trimmed.isBlank()) {
            notify(EnvironmentActionsBundle.message("commit.message.required"), NotificationType.ERROR)
            return
        }
        if (project.isDisposed || !ProjectLevelVcsManager.getInstance(project).hasActiveVcss()) {
            copyFallback(trimmed, EnvironmentActionsBundle.message("commit.noVcs"))
            return
        }
        findCurrentCommitMessageControl()?.let { writer ->
            writeCommitMessage(writer, currentCommitMessageText(writer), trimmed)
            return
        }
        val action = ActionManager.getInstance().getAction(CHECKIN_ACTION_ID)
        if (action == null) {
            copyFallback(trimmed, EnvironmentActionsBundle.message("commit.action.unavailable"))
            return
        }

        val pendingService = PendingCommitMessageService.getInstance(project)
        val pending = pendingService.offer(trimmed)
        val contextComponent = UiDataProvider.wrapComponent(JPanel()) { sink ->
            sink[CommonDataKeys.PROJECT] = project
        }
        val callback = ActionManager.getInstance().tryToExecute(
            action,
            null,
            contextComponent,
            ActionPlaces.UNKNOWN,
            true,
        )
        callback.doWhenDone {
            ApplicationManager.getApplication().invokeLater {
                applyToVisibleCommitEditor(pending.token)
            }
            scheduleFallback(pending)
        }
        callback.doWhenRejected(Runnable {
            val remaining = pendingService.clear(pending.token)
            if (remaining != null) {
                copyFallback(remaining, EnvironmentActionsBundle.message("commit.action.rejected"))
            }
        })
    }

    private fun applyToVisibleCommitEditor(token: String) {
        val writer = findCurrentCommitMessageControl() ?: return
        val message = PendingCommitMessageService.getInstance(project).clear(token) ?: return
        writeCommitMessage(writer, currentCommitMessageText(writer), message)
    }

    private fun findCurrentCommitMessageControl(): CommitMessageI? {
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val focusedWriter = focusOwner?.let { component ->
            DataManager.getInstance().getDataContext(component).getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
        }
        return focusedWriter
            ?: findVisibleCommitMessageControl(WindowManager.getInstance().getFrame(project))
    }

    private fun currentCommitMessageText(writer: CommitMessageI): String =
        (writer as? CommitMessageUi)?.text
            ?: (writer as? Component)?.let { component ->
                DataManager.getInstance().getDataContext(component)
                    .getData(VcsDataKeys.COMMIT_MESSAGE_DOCUMENT)
                    ?.text
            }
            ?: ""

    private fun writeCommitMessage(writer: CommitMessageI, currentText: String, prepared: String) {
        if (currentText.isBlank()) {
            writer.setCommitMessage(
                preparedCommitMessageText(currentText, prepared, PreparedCommitMessageChoice.REPLACE) ?: return,
            )
            return
        }
        val choice = Messages.showDialog(
            project,
            EnvironmentActionsBundle.message("commit.existing.message"),
            EnvironmentActionsBundle.message("commit.existing.title"),
            arrayOf(
                EnvironmentActionsBundle.message("commit.existing.replace"),
                EnvironmentActionsBundle.message("commit.existing.append"),
                EnvironmentActionsBundle.message("commit.existing.cancel"),
            ),
            2,
            Messages.getQuestionIcon(),
        )
        val selected = when (choice) {
            0 -> PreparedCommitMessageChoice.REPLACE
            1 -> PreparedCommitMessageChoice.APPEND
            else -> PreparedCommitMessageChoice.CANCEL
        }
        preparedCommitMessageText(currentText, prepared, selected)?.let(writer::setCommitMessage)
    }

    private fun scheduleFallback(pending: PendingCommitMessage) {
        val delayMillis = (pending.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(1)
        coroutineScope.launch {
            delay(delayMillis)
            val remaining = PendingCommitMessageService.getInstance(project).clear(pending.token) ?: return@launch
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    copyFallback(remaining, EnvironmentActionsBundle.message("commit.editor.unavailable"))
                }
            }
        }
    }

    private fun copyFallback(message: String, reason: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(message))
        notify(EnvironmentActionsBundle.message("commit.copy.hint", reason), NotificationType.WARNING)
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("EzCodeMarks")
            .createNotification(message, type)
            .notify(project)
    }

    companion object {
        private const val CHECKIN_ACTION_ID = "CheckinProject"

        fun getInstance(project: Project): NativeCommitWorkflowLauncher =
            project.getService(NativeCommitWorkflowLauncher::class.java)
    }
}
