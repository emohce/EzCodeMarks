package emohce.presentation.environmentaction

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import emohce.data.commitmessage.CodexAppServerService
import emohce.data.environmentaction.ENVIRONMENT_ACTION_SLOT_COUNT
import emohce.data.environmentaction.EnvironmentActionDefinition
import emohce.data.environmentaction.EnvironmentActionProjectStateService
import emohce.data.environmentaction.EnvironmentActionSettingsService
import emohce.data.environmentaction.EnvironmentDefinition
import emohce.environmentaction.EnvironmentActionsBundle

open class EnvironmentActionSlotAction(private val slot: Int) : DumbAwareAction() {
    init {
        require(slot in 1..ENVIRONMENT_ACTION_SLOT_COUNT)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val target = event.project?.let(::target)
        event.presentation.isVisible = true
        event.presentation.isEnabled = target != null
        event.presentation.text = target?.second?.name?.ifBlank {
            EnvironmentActionsBundle.message("action.slot.fallback", slot)
        } ?: EnvironmentActionsBundle.message("action.slot.fallback", slot)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val (environment, action) = target(project) ?: return
        val executable = CodexAppServerService.getInstance().resolvedExecutablePath().ifBlank { "codex" }
        EnvironmentActionInvoker.invoke(project, environment, action, codexExecutable = executable)
    }

    private fun target(project: com.intellij.openapi.project.Project): Pair<EnvironmentDefinition, EnvironmentActionDefinition>? {
        val snapshot = EnvironmentActionSettingsService.getInstance().snapshot()
        val environment = EnvironmentActionProjectStateService.getInstance(project).activeEnvironment(snapshot) ?: return null
        val action = environment.actions.firstOrNull { it.slot == slot && it.enabled } ?: return null
        return environment.copyForSnapshot() to action.copyForSnapshot()
    }
}

class EnvironmentActionSlot1 : EnvironmentActionSlotAction(1)
class EnvironmentActionSlot2 : EnvironmentActionSlotAction(2)
class EnvironmentActionSlot3 : EnvironmentActionSlotAction(3)
class EnvironmentActionSlot4 : EnvironmentActionSlotAction(4)
class EnvironmentActionSlot5 : EnvironmentActionSlotAction(5)
class EnvironmentActionSlot6 : EnvironmentActionSlotAction(6)
class EnvironmentActionSlot7 : EnvironmentActionSlotAction(7)
class EnvironmentActionSlot8 : EnvironmentActionSlotAction(8)
class EnvironmentActionSlot9 : EnvironmentActionSlotAction(9)
class EnvironmentActionSlot10 : EnvironmentActionSlotAction(10)
