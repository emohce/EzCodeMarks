package emohce.data.environmentaction

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

data class EnvironmentActionProjectState(
    var activeEnvironmentId: String = "",
)

@Service(Service.Level.PROJECT)
@State(
    name = "EzCodeMarksEnvironmentActionProjectState",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE, roamingType = RoamingType.DISABLED)],
)
class EnvironmentActionProjectStateService : PersistentStateComponent<EnvironmentActionProjectState> {
    private var state = EnvironmentActionProjectState()

    override fun getState(): EnvironmentActionProjectState = state.copy()

    override fun loadState(state: EnvironmentActionProjectState) {
        this.state = state.copy()
    }

    fun activeEnvironment(settings: EnvironmentActionSettingsState): EnvironmentDefinition? {
        val requested = state.activeEnvironmentId.ifBlank { settings.defaultEnvironmentId }
        return settings.environments.firstOrNull { it.id == requested }
            ?: settings.environments.firstOrNull()
    }

    fun select(environmentId: String, settings: EnvironmentActionSettingsState): Boolean {
        if (settings.environments.none { it.id == environmentId }) return false
        state.activeEnvironmentId = environmentId
        return true
    }

    companion object {
        fun getInstance(project: Project): EnvironmentActionProjectStateService =
            project.getService(EnvironmentActionProjectStateService::class.java)
    }
}
