package emohce.data.commitmessage

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import emohce.domain.commitmessage.CommitDraft
import emohce.domain.commitmessage.CommitMessageDefaults
import emohce.domain.commitmessage.CommitTemplateDefinition

data class CommitProjectState(
    var templateId: String = "",
    var draft: CommitDraft? = null,
)

@Service(Service.Level.PROJECT)
@State(
    name = "EzCodeMarksCommitProjectState",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class CommitProjectStateService : PersistentStateComponent<CommitProjectState> {
    private var currentState = CommitProjectState()

    override fun getState(): CommitProjectState = currentState

    override fun loadState(state: CommitProjectState) {
        currentState = state.copy(draft = state.draft?.copy())
    }

    fun saveDraft(draft: CommitDraft?) {
        currentState.draft = draft?.copy()
    }

    fun clearDraft() {
        currentState.draft = null
    }

    fun setTemplateId(templateId: String) {
        currentState.templateId = templateId
    }

    fun resolveTemplate(settings: CommitMessageSettingsService): CommitTemplateDefinition {
        val projectId = currentState.templateId
        return settings.template(projectId)
            ?: settings.defaultTemplate()
    }

    companion object {
        fun getInstance(project: Project): CommitProjectStateService =
            project.getService(CommitProjectStateService::class.java)
    }
}
