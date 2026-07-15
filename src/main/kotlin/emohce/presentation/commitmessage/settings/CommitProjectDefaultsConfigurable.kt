package emohce.presentation.commitmessage.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import emohce.data.commitmessage.CommitMessageSettingsService
import emohce.data.commitmessage.CommitProjectStateService
import emohce.presentation.commitmessage.CommitMessageBundle
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

class CommitProjectDefaultsConfigurable(private val project: Project) : SearchableConfigurable {
    private val projectState: CommitProjectStateService
        get() = CommitProjectStateService.getInstance(project)
    private val templateCombo = ComboBox<TemplateChoice>()
    private var workingTemplateId = ""
    private var clearDraftRequested = false
    private var root: JComponent? = null

    init {
        templateCombo.addActionListener {
            workingTemplateId = (templateCombo.selectedItem as? TemplateChoice)?.id.orEmpty()
        }
    }

    override fun getId(): String = ID

    override fun getDisplayName(): String = CommitMessageBundle.message("settings.project.title")

    override fun createComponent(): JComponent {
        reset()
        return panel {
            row(CommitMessageBundle.message("settings.project.template")) {
                cell(templateCombo).align(Align.FILL)
            }
            row {
                button(CommitMessageBundle.message("settings.project.restore")) {
                    workingTemplateId = ""
                    templateCombo.selectedIndex = 0
                }
                button(CommitMessageBundle.message("settings.project.clearDraft")) {
                    clearDraftRequested = true
                }
            }
        }.also { root = it }
    }

    override fun isModified(): Boolean =
        workingTemplateId != projectState.state.templateId || clearDraftRequested

    override fun apply() {
        projectState.setTemplateId(workingTemplateId)
        if (clearDraftRequested) projectState.clearDraft()
        clearDraftRequested = false
    }

    override fun reset() {
        workingTemplateId = projectState.state.templateId
        clearDraftRequested = false
        val choices = buildList {
            add(TemplateChoice("", CommitMessageBundle.message("settings.project.globalDefault")))
            CommitMessageSettingsService.getInstance().state.templates.forEach {
                add(TemplateChoice(it.id, it.name))
            }
        }.toTypedArray()
        templateCombo.model = DefaultComboBoxModel(choices)
        templateCombo.selectedItem = choices.firstOrNull { it.id == workingTemplateId } ?: choices.first()
        if (choices.none { it.id == workingTemplateId }) workingTemplateId = ""
    }

    override fun disposeUIResources() {
        root = null
    }

    private data class TemplateChoice(val id: String, val name: String) {
        override fun toString(): String = name
    }

    companion object {
        const val ID = "emohce.settings.commitMessage.project"
    }
}
