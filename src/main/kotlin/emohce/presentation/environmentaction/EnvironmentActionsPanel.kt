package emohce.presentation.environmentaction

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import emohce.data.commitmessage.CodexAppServerService
import emohce.data.environmentaction.EnvironmentActionDefinition
import emohce.data.environmentaction.EnvironmentActionExecutionHandle
import emohce.data.environmentaction.EnvironmentActionProjectStateService
import emohce.data.environmentaction.EnvironmentActionSecurity
import emohce.data.environmentaction.EnvironmentActionSettingsService
import emohce.data.environmentaction.EnvironmentActionType
import emohce.data.environmentaction.EnvironmentDefinition
import emohce.data.environmentaction.InteractiveCodexApprovalDecision
import emohce.data.environmentaction.InteractiveCodexEvent
import emohce.data.environmentaction.InteractiveCodexPermissionSummary
import emohce.data.environmentaction.InteractiveCodexSession
import emohce.data.environmentaction.InteractiveCodexSessionFactory
import emohce.data.environmentaction.InteractiveCodexSessionState
import emohce.environmentaction.EnvironmentActionsBundle
import emohce.presentation.commitmessage.settings.CodexSettingsConfigurable
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel

class EnvironmentActionsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val environmentModel = DefaultListModel<EnvironmentDefinition>()
    private val environmentList = JBList(environmentModel)
    private val actionModel = DefaultListModel<ActionRow>()
    private val actionList = JBList(actionModel)
    private val outputArea = readOnlyArea()
    private val chatArea = readOnlyArea()
    private val chatInput = JBTextField()
    private val permissionLabel = JBLabel(EnvironmentActionsBundle.message("panel.chat.permissionUnknown"))
    private val runButton = JButton(EnvironmentActionsBundle.message("panel.run"))
    private val stopActionButton = JButton(EnvironmentActionsBundle.message("panel.stop")).apply { isEnabled = false }
    private val sendButton = JButton(EnvironmentActionsBundle.message("panel.send"))
    private val stopChatButton = JButton(EnvironmentActionsBundle.message("panel.stop")).apply { isEnabled = false }
    private val newConversationButton = JButton(EnvironmentActionsBundle.message("panel.newConversation"))
    private val approvalLabel = JBLabel()
    private val allowApprovalButton = JButton(EnvironmentActionsBundle.message("panel.chat.approval.allow"))
    private val denyApprovalButton = JButton(EnvironmentActionsBundle.message("panel.chat.approval.deny"))
    private val approvalPanel = JPanel(BorderLayout()).apply {
        val controls = JPanel().apply {
            add(allowApprovalButton)
            add(denyApprovalButton)
        }
        add(approvalLabel, BorderLayout.CENTER)
        add(controls, BorderLayout.SOUTH)
        isVisible = false
    }
    private val sessionFactory = InteractiveCodexSessionFactory.getInstance(project)
    private val disposed = AtomicBoolean(false)
    private val chatGeneration = AtomicLong()

    @Volatile
    private var activeAction: EnvironmentActionExecutionHandle? = null
    @Volatile
    private var chatSession: InteractiveCodexSession? = null
    @Volatile
    private var chatEnvironmentId: String? = null
    private var changingSelection = false
    private var environmentChangeNoticeShown = false
    private val queuedApprovals = ArrayDeque<ApprovalUiRequest>()
    private var activeApproval: ApprovalUiRequest? = null

    init {
        environmentList.accessibleContext.accessibleName = EnvironmentActionsBundle.message("panel.environment")
        actionList.accessibleContext.accessibleName = EnvironmentActionsBundle.message("panel.actions")
        outputArea.accessibleContext.accessibleName = EnvironmentActionsBundle.message("panel.output")
        chatArea.accessibleContext.accessibleName = EnvironmentActionsBundle.message("panel.chat")
        chatInput.accessibleContext.accessibleName = EnvironmentActionsBundle.message("panel.chat")

        environmentList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                refreshActions()
                if (!changingSelection) selectEnvironment()
            }
        }
        runButton.addActionListener { runSelected() }
        stopActionButton.addActionListener { activeAction?.cancel() }
        sendButton.addActionListener { sendChat() }
        stopChatButton.addActionListener { stopChat() }
        newConversationButton.addActionListener { newConversation() }
        allowApprovalButton.addActionListener { respondToApproval(InteractiveCodexApprovalDecision.ALLOW) }
        denyApprovalButton.addActionListener { respondToApproval(InteractiveCodexApprovalDecision.DENY) }
        chatInput.addActionListener { sendChat() }

        val actionControls = JPanel().apply {
            add(JButton(EnvironmentActionsBundle.message("panel.refresh")).apply { addActionListener { refresh() } })
            add(runButton)
            add(stopActionButton)
            add(JButton(EnvironmentActionsBundle.message("panel.settings")).apply {
                addActionListener {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, EnvironmentActionsConfigurable::class.java)
                    refresh()
                }
            })
        }
        val chatControls = JPanel().apply {
            add(sendButton)
            add(stopChatButton)
            add(newConversationButton)
            add(JButton(EnvironmentActionsBundle.message("panel.openCodexSettings")).apply {
                addActionListener {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, CodexSettingsConfigurable::class.java)
                    refreshChatStatus()
                }
            })
        }
        add(
            panel {
                row(EnvironmentActionsBundle.message("panel.environment")) {
                    cell(JBScrollPane(environmentList)).align(Align.FILL).resizableColumn()
                }
                row(EnvironmentActionsBundle.message("panel.actions")) {
                    cell(JBScrollPane(actionList)).align(Align.FILL).resizableColumn()
                }
                row { cell(actionControls) }
                row(EnvironmentActionsBundle.message("panel.output")) {
                    cell(JBScrollPane(outputArea)).align(Align.FILL).resizableColumn()
                }.resizableRow()
                row(EnvironmentActionsBundle.message("panel.chat")) {
                    cell(permissionLabel).align(Align.FILL).resizableColumn()
                }
                row {
                    cell(JBScrollPane(chatArea)).align(Align.FILL).resizableColumn()
                }.resizableRow()
                row {
                    cell(approvalPanel).align(Align.FILL).resizableColumn()
                }
                row {
                    cell(chatInput).align(Align.FILL).resizableColumn()
                }
                row { cell(chatControls) }
            },
            BorderLayout.CENTER,
        )
        chatArea.text = EnvironmentActionsBundle.message("panel.chat.ready")
        refresh()
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        chatGeneration.incrementAndGet()
        activeAction?.cancel()
        activeAction = null
        clearApprovalUi()
        val closing = chatSession
        chatSession = null
        sessionFactory.launchIo { closing?.close() }
    }

    private fun refresh() {
        if (disposed.get()) return
        val snapshot = EnvironmentActionSettingsService.getInstance().snapshot()
        val selected = EnvironmentActionProjectStateService.getInstance(project).activeEnvironment(snapshot)
        changingSelection = true
        try {
            environmentModel.clear()
            snapshot.environments.forEach(environmentModel::addElement)
            environmentList.selectedIndex = snapshot.environments.indexOfFirst { it.id == selected?.id }
                .takeIf { it >= 0 }
                ?: 0
        } finally {
            changingSelection = false
        }
        refreshActions()
    }

    private fun selectEnvironment() {
        val selected = environmentList.selectedValue ?: return
        val snapshot = EnvironmentActionSettingsService.getInstance().snapshot()
        EnvironmentActionProjectStateService.getInstance(project).select(selected.id, snapshot)
        if (requiresNewConversation(chatSession != null, chatEnvironmentId, selected.id)) {
            sendButton.isEnabled = false
            if (!environmentChangeNoticeShown) {
                appendChat("\n\n${EnvironmentActionsBundle.message("panel.chat.environmentChanged")}")
                environmentChangeNoticeShown = true
            }
        }
    }

    private fun refreshActions() {
        actionModel.clear()
        environmentList.selectedValue?.orderedActions()?.forEach { action ->
            actionModel.addElement(ActionRow(action.slot, action.name, action.enabled))
        }
    }

    private fun runSelected() {
        val environment = environmentList.selectedValue ?: return
        val row = actionList.selectedValue ?: return
        val action = environment.actions.firstOrNull { it.slot == row.slot && it.enabled } ?: return
        outputArea.text = EnvironmentActionsBundle.message("panel.action.running", action.name)
        runButton.isEnabled = false
        stopActionButton.isEnabled = action.type != EnvironmentActionType.PREPARE_COMMIT
        val executable = CodexAppServerService.getInstance().resolvedExecutablePath().ifBlank { "codex" }
        activeAction = EnvironmentActionInvoker.invoke(
            project,
            environment,
            action,
            codexExecutable = executable,
            onOutput = { text, _ -> onEdt { outputArea.append(text) } },
        ) { result ->
            if (disposed.get()) return@invoke
            activeAction = null
            runButton.isEnabled = true
            stopActionButton.isEnabled = false
            if (action.type == EnvironmentActionType.PREPARE_COMMIT && result.failureMessage == null) return@invoke
            outputArea.text = buildString {
                append(
                    EnvironmentActionsBundle.message(
                        "panel.action.exit",
                        result.exitCode ?: EnvironmentActionsBundle.message("panel.action.notStarted"),
                    ),
                ).append('\n')
                result.failureMessage?.let {
                    append(EnvironmentActionsBundle.message("panel.action.failure", it)).append('\n')
                }
                if (result.stdout.isNotBlank()) {
                    append("\n").append(EnvironmentActionsBundle.message("panel.action.stdout")).append("\n").append(result.stdout)
                }
                if (result.stderr.isNotBlank()) {
                    append("\n").append(EnvironmentActionsBundle.message("panel.action.stderr")).append("\n").append(result.stderr)
                }
            }
        }
        if (activeAction == null) {
            runButton.isEnabled = true
            stopActionButton.isEnabled = false
        }
    }

    private fun sendChat() {
        if (disposed.get()) return
        if (!sendButton.isEnabled) return
        val environment = environmentList.selectedValue ?: return
        val message = chatInput.text.trim()
        if (message.isBlank()) return
        if (requiresNewConversation(chatSession != null, chatEnvironmentId, environment.id)) {
            appendChat("\n\n${EnvironmentActionsBundle.message("panel.chat.environmentChanged")}")
            return
        }
        chatInput.text = ""
        appendChat(
            "\n\n${EnvironmentActionsBundle.message("panel.chat.you")}\n$message" +
                "\n\n${EnvironmentActionsBundle.message("panel.chat.assistant")}\n",
        )
        sendButton.isEnabled = false
        chatInput.isEnabled = false
        stopChatButton.isEnabled = true
        val generation = chatGeneration.get()
        sessionFactory.launchIo {
            if (generation != chatGeneration.get() || disposed.get()) return@launchIo
            try {
                val session = chatSession ?: startSession(environment, generation)
                if (generation != chatGeneration.get() || disposed.get()) {
                    session.close()
                    return@launchIo
                }
                val permission = session.permission() ?: error("Codex Chat permission state is unavailable")
                if (permission.broad && !confirmBroadPermissions()) {
                    session.close()
                    chatSession = null
                    chatEnvironmentId = null
                    onEdt { updateChatButtons(InteractiveCodexSessionState.IDLE) }
                    return@launchIo
                }
                if (permission.broad) session.acknowledgeBroadPermissions()
                session.send(message)
            } catch (error: Exception) {
                onEdt {
                    appendChat("\n${EnvironmentActionsBundle.message("panel.chat.failed", safeMessage(error))}")
                    updateChatButtons(InteractiveCodexSessionState.FAILED)
                }
            }
        }
    }

    private fun startSession(environment: EnvironmentDefinition, generation: Long): InteractiveCodexSession {
        onEdt {
            permissionLabel.text = EnvironmentActionsBundle.message("panel.chat.starting")
            updateChatButtons(InteractiveCodexSessionState.STARTING)
        }
        val cwd = environment.workingDirectory.takeIf(String::isNotBlank)?.let(Path::of)
            ?: project.basePath?.let(Path::of)
            ?: error("The project working directory is unavailable")
        require(Files.isDirectory(cwd)) { "The Codex Chat working directory does not exist" }
        val executable = CodexAppServerService.getInstance().resolvedExecutablePath().ifBlank { "codex" }
        val session = sessionFactory.create(
            executable,
            cwd.toAbsolutePath().normalize(),
            environment.variables,
        ) { event -> onChatEvent(generation, event) }
        chatSession = session
        chatEnvironmentId = environment.id
        return try {
            session.also { it.start() }
        } catch (error: RuntimeException) {
            if (chatSession === session) {
                chatSession = null
                chatEnvironmentId = null
            }
            throw error
        }
    }

    private fun stopChat() {
        val session = chatSession ?: return
        appendChat("\n${EnvironmentActionsBundle.message("panel.chat.stopping")}")
        clearApprovalUi()
        stopChatButton.isEnabled = false
        sessionFactory.launchIo { session.interrupt() }
    }

    private fun newConversation() {
        chatGeneration.incrementAndGet()
        val old = chatSession
        chatSession = null
        chatEnvironmentId = null
        environmentChangeNoticeShown = false
        clearApprovalUi()
        chatArea.text = EnvironmentActionsBundle.message("panel.chat.ready")
        permissionLabel.text = EnvironmentActionsBundle.message("panel.chat.permissionUnknown")
        updateChatButtons(InteractiveCodexSessionState.IDLE)
        sessionFactory.launchIo { old?.close() }
    }

    private fun onChatEvent(generation: Long, event: InteractiveCodexEvent) {
        if (generation != chatGeneration.get()) return
        onEdt {
            if (generation != chatGeneration.get()) return@onEdt
            when (event) {
                is InteractiveCodexEvent.StateChanged -> updateChatButtons(event.state)
                is InteractiveCodexEvent.SessionStarted -> showPermission(event.permission)
                is InteractiveCodexEvent.AssistantDelta -> appendChat(event.text)
                is InteractiveCodexEvent.AssistantMessage -> appendChat(event.text)
                is InteractiveCodexEvent.ToolEvent -> {
                    val summary = event.summary?.takeIf(String::isNotBlank)
                        ?: event.type?.takeIf(String::isNotBlank)
                        ?: event.method
                    appendChat("\n[${EnvironmentActionsBundle.message("panel.chat.toolEvent", summary)}]\n")
                }
                is InteractiveCodexEvent.ApprovalRequested -> showApproval(event.request.token, event.request.summary)
                is InteractiveCodexEvent.TurnCompleted -> {
                    clearApprovalUi()
                    appendChat("\n\n[${EnvironmentActionsBundle.message("panel.chat.turnStatus", event.status)}]\n")
                }
                is InteractiveCodexEvent.Failure -> {
                    clearApprovalUi()
                    appendChat("\n${EnvironmentActionsBundle.message("panel.chat.failed", localizedChatFailure(event.message))}")
                }
            }
        }
    }

    private fun showApproval(token: String, summary: String) {
        queuedApprovals.addLast(
            ApprovalUiRequest(
                token = token,
                summary = EnvironmentActionSecurity.redact(summary).take(500),
            ),
        )
        showNextApproval()
    }

    private fun showNextApproval() {
        if (activeApproval != null) return
        val request = queuedApprovals.pollFirst() ?: return
        activeApproval = request
        approvalLabel.text = EnvironmentActionsBundle.message(
            "panel.chat.approval.message",
            request.summary,
        )
        approvalLabel.toolTipText = request.summary
        allowApprovalButton.isEnabled = true
        denyApprovalButton.isEnabled = true
        approvalPanel.isVisible = true
        approvalPanel.revalidate()
        approvalPanel.repaint()
    }

    private fun respondToApproval(decision: InteractiveCodexApprovalDecision) {
        val request = activeApproval ?: return
        activeApproval = null
        approvalPanel.isVisible = false
        allowApprovalButton.isEnabled = false
        denyApprovalButton.isEnabled = false
        sessionFactory.launchIo {
            chatSession?.respondToApproval(request.token, decision)
        }
        showNextApproval()
    }

    private fun clearApprovalUi() {
        activeApproval = null
        queuedApprovals.clear()
        approvalPanel.isVisible = false
        allowApprovalButton.isEnabled = false
        denyApprovalButton.isEnabled = false
    }

    private fun showPermission(permission: InteractiveCodexPermissionSummary) {
        val unavailable = EnvironmentActionsBundle.message("panel.chat.statusUnavailable")
        permissionLabel.text = EnvironmentActionsBundle.message(
            "panel.chat.permissionSummary",
            permission.profileId,
            permission.sandboxType,
            permission.approvalPolicy,
            permission.networkAccess,
            permission.skillCount ?: unavailable,
            permission.skillErrorCount,
            permission.pluginCount ?: unavailable,
            permission.pluginErrorCount,
        )
        permissionLabel.toolTipText = listOfNotNull(permission.codexHome)
            .plus(permission.instructionSources)
            .joinToString("\n")
    }

    private fun refreshChatStatus() {
        val session = chatSession
        val permission = session?.permission()
        if (permission == null) {
            permissionLabel.text = EnvironmentActionsBundle.message("panel.chat.permissionUnknown")
        } else {
            showPermission(permission)
        }
        updateChatButtons(session?.state() ?: InteractiveCodexSessionState.IDLE)
    }

    private fun confirmBroadPermissions(): Boolean {
        var accepted = false
        ApplicationManager.getApplication().invokeAndWait {
            accepted = Messages.showYesNoDialog(
                project,
                EnvironmentActionsBundle.message("panel.chat.danger.message"),
                EnvironmentActionsBundle.message("panel.chat.danger.title"),
                Messages.getWarningIcon(),
            ) == Messages.YES
        }
        return accepted
    }

    private fun updateChatButtons(state: InteractiveCodexSessionState) {
        if (disposed.get()) return
        val idle = state == InteractiveCodexSessionState.IDLE
        sendButton.isEnabled = idle && (chatSession == null || chatEnvironmentId == environmentList.selectedValue?.id)
        stopChatButton.isEnabled = state == InteractiveCodexSessionState.RUNNING ||
            state == InteractiveCodexSessionState.AWAITING_APPROVAL ||
            state == InteractiveCodexSessionState.STARTING
        chatInput.isEnabled = idle
    }

    private fun appendChat(text: String) {
        if (disposed.get()) return
        chatArea.append(EnvironmentActionSecurity.redact(text))
        val overflow = chatArea.document.length - MAX_CHAT_TRANSCRIPT_CHARS
        if (overflow > 0) runCatching { chatArea.document.remove(0, overflow) }
    }

    private fun onEdt(action: () -> Unit) {
        if (disposed.get()) return
        if (ApplicationManager.getApplication().isDispatchThread) action()
        else ApplicationManager.getApplication().invokeLater {
            if (!disposed.get() && !project.isDisposed) action()
        }
    }

    private fun safeMessage(error: Throwable): String = localizedChatFailure(
        EnvironmentActionSecurity.redact(error.message.orEmpty()).take(500),
    )

    private fun localizedChatFailure(message: String): String = when {
        message.contains("permission", ignoreCase = true) ||
            message.contains("sandbox", ignoreCase = true) ||
            message.contains("approval policy", ignoreCase = true) ->
            EnvironmentActionsBundle.message("panel.chat.error.permissionUnavailable")
        message.contains("working directory", ignoreCase = true) ->
            EnvironmentActionsBundle.message("panel.chat.error.workingDirectory")
        else -> EnvironmentActionsBundle.message("panel.chat.error.session")
    }

    private data class ActionRow(val slot: Int, val name: String, val enabled: Boolean) {
        override fun toString(): String = EnvironmentActionsBundle.message(
            "panel.action.row",
            slot,
            name,
            if (enabled) "" else EnvironmentActionsBundle.message("panel.action.disabled"),
        )
    }

    private data class ApprovalUiRequest(val token: String, val summary: String)

    companion object {
        private const val MAX_CHAT_TRANSCRIPT_CHARS = 512_000

        internal fun requiresNewConversation(
            hasSession: Boolean,
            sessionEnvironmentId: String?,
            selectedEnvironmentId: String,
        ): Boolean = hasSession && sessionEnvironmentId != selectedEnvironmentId

        private fun readOnlyArea(): JBTextArea = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }
    }
}
