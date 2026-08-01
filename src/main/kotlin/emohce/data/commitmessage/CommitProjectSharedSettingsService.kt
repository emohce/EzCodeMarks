package emohce.data.commitmessage

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import emohce.domain.commitmessage.CommitStyleDefinition
import emohce.domain.commitmessage.CommitTemplateDefinition
import emohce.domain.commitmessage.ProjectInstructionMode

data class CommitProjectSharedSettingsState(
    var schemaVersion: Int = 1,
    var instructionMode: ProjectInstructionMode = ProjectInstructionMode.INHERIT,
    var extraInstructions: String = "",
    var templates: MutableList<CommitTemplateDefinition> = mutableListOf(),
    var styles: MutableList<CommitStyleDefinition> = mutableListOf(),
    var defaultTemplateId: String = "",
    var defaultStyleId: String = "",
) {
    fun deepCopy(): CommitProjectSharedSettingsState = copy(
        templates = templates.map { it.copy() }.toMutableList(),
        styles = styles.map { it.copy() }.toMutableList(),
    )

    fun normalize() {
        require(schemaVersion in 0..CURRENT_SCHEMA_VERSION) { "Unsupported shared commit-message settings schema" }
        schemaVersion = CURRENT_SCHEMA_VERSION
        extraInstructions = extraInstructions.trim().take(MAX_EXTRA_INSTRUCTIONS)
        templates = templates
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .map { it.copy(name = it.name.trim(), builtIn = false) }
            .toMutableList()
        styles = styles
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .map { it.copy(name = it.name.trim(), builtIn = false) }
            .toMutableList()
        if (templates.none { it.id == defaultTemplateId }) defaultTemplateId = ""
        if (styles.none { it.id == defaultStyleId }) defaultStyleId = ""
    }

    companion object {
        const val MAX_EXTRA_INSTRUCTIONS: Int = 4_000
        const val MAX_EFFECTIVE_INSTRUCTIONS: Int = MAX_EXTRA_INSTRUCTIONS * 2 + 2
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

@Service(Service.Level.PROJECT)
@State(
    name = "EzCodeMarksCommitProjectSharedSettings",
    storages = [Storage("ezCodeMarkCommitMessage.xml")],
)
class CommitProjectSharedSettingsService : PersistentStateComponent<CommitProjectSharedSettingsState> {
    private var currentState = CommitProjectSharedSettingsState()
    private var rawState: CommitProjectSharedSettingsState? = null
    private var unsupportedSchema = false

    override fun getState(): CommitProjectSharedSettingsState = rawState ?: currentState

    override fun loadState(state: CommitProjectSharedSettingsState) {
        if (state.schemaVersion !in 0..CommitProjectSharedSettingsState.CURRENT_SCHEMA_VERSION) {
            rawState = state.deepCopy()
            unsupportedSchema = true
            return
        }
        unsupportedSchema = false
        rawState = null
        currentState = state.deepCopy().apply { normalize() }
    }

    fun replaceState(state: CommitProjectSharedSettingsState) {
        ensureSupported()
        currentState = state.deepCopy().apply { normalize() }
        rawState = null
    }

    fun snapshot(): CommitProjectSharedSettingsState = currentState.deepCopy()

    fun isSchemaSupported(): Boolean = !unsupportedSchema

    fun template(id: String): CommitTemplateDefinition? {
        ensureSupported()
        return currentState.templates.firstOrNull { it.id == id }
    }

    fun style(id: String): CommitStyleDefinition? {
        ensureSupported()
        return currentState.styles.firstOrNull { it.id == id }
    }

    fun defaultTemplate(): CommitTemplateDefinition? = template(currentState.defaultTemplateId)

    fun defaultStyle(): CommitStyleDefinition? = style(currentState.defaultStyleId)

    fun effectiveExtraInstructions(globalInstructions: String): String {
        ensureSupported()
        return when (currentState.instructionMode) {
        ProjectInstructionMode.INHERIT -> globalInstructions.trim()
        ProjectInstructionMode.APPEND -> listOf(globalInstructions, currentState.extraInstructions)
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString("\n\n")
        ProjectInstructionMode.REPLACE -> currentState.extraInstructions.trim()
        }.take(CommitProjectSharedSettingsState.MAX_EFFECTIVE_INSTRUCTIONS)
    }

    private fun ensureSupported() {
        check(!unsupportedSchema) { "Shared commit-message settings use an unsupported schema" }
    }

    companion object {
        fun getInstance(project: Project): CommitProjectSharedSettingsService =
            project.getService(CommitProjectSharedSettingsService::class.java)
    }
}
