package emohce.presentation.commitmessage.settings

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import emohce.data.commitmessage.CommitMessageSettingsService
import emohce.data.commitmessage.CommitProjectStateService
import emohce.data.commitmessage.CommitProjectSharedSettingsService
import emohce.domain.commitmessage.CommitMessageDefaults
import emohce.domain.commitmessage.CommitStyleDefinition
import emohce.presentation.commitmessage.CommitMessageBundle
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

class CommitProjectDefaultsConfigurable(private val project: Project) : SearchableConfigurable {
    private val projectState: CommitProjectStateService
        get() = CommitProjectStateService.getInstance(project)
    private val sharedState: CommitProjectSharedSettingsService
        get() = CommitProjectSharedSettingsService.getInstance(project)
    private val templateCombo = ComboBox<TemplateChoice>()
    private val styleCombo = ComboBox<TemplateChoice>()
    private var workingTemplateId = ""
    private var workingStyleId = ""
    private var loadedTemplateId = ""
    private var loadedStyleId = ""
    private var clearDraftRequested = false
    private var loading = false
    private var root: JComponent? = null

    init {
        templateCombo.addActionListener {
            if (!loading) workingTemplateId = (templateCombo.selectedItem as? TemplateChoice)?.id.orEmpty()
        }
        styleCombo.addActionListener {
            if (!loading) workingStyleId = (styleCombo.selectedItem as? TemplateChoice)?.id.orEmpty()
        }
    }

    override fun getId(): String = ID

    override fun getDisplayName(): String = CommitMessageBundle.message("settings.project.private.title")

    override fun createComponent(): JComponent {
        reset()
        return panel {
            row(CommitMessageBundle.message("settings.project.template")) {
                cell(templateCombo).align(Align.FILL)
            }
            row(CommitMessageBundle.message("settings.project.style")) {
                cell(styleCombo).align(Align.FILL)
            }
            row {
                button(CommitMessageBundle.message("settings.project.restore")) {
                    workingTemplateId = ""
                    workingStyleId = ""
                    templateCombo.selectedIndex = 0
                    styleCombo.selectedIndex = 0
                }
                button(CommitMessageBundle.message("settings.project.clearDraft")) {
                    clearDraftRequested = true
                }
            }
        }.also { root = it }
    }

    override fun isModified(): Boolean =
        workingTemplateId != loadedTemplateId || workingStyleId != loadedStyleId || clearDraftRequested

    override fun apply() {
        val current = projectState.state
        val templateConflict = workingTemplateId != loadedTemplateId &&
            current.templateId != loadedTemplateId && current.templateId != workingTemplateId
        val styleConflict = workingStyleId != loadedStyleId &&
            current.styleId != loadedStyleId && current.styleId != workingStyleId
        if (templateConflict || styleConflict) {
            throw ConfigurationException(CommitMessageBundle.message("settings.project.concurrentChange"))
        }
        if (workingTemplateId != loadedTemplateId) projectState.setTemplateId(workingTemplateId)
        if (workingStyleId != loadedStyleId) projectState.setStyleId(workingStyleId)
        if (clearDraftRequested) projectState.clearDraft()
        reset()
    }

    override fun reset() {
        val storedTemplateId = projectState.state.templateId
        val storedStyleId = projectState.state.styleId
        workingTemplateId = storedTemplateId
        workingStyleId = storedStyleId
        loadedTemplateId = workingTemplateId
        loadedStyleId = workingStyleId
        clearDraftRequested = false
        val global = CommitMessageSettingsService.getInstance().snapshot()
        val shared = sharedState.state
        val choices = buildList {
            add(TemplateChoice("", CommitMessageBundle.message("settings.project.inheritDefault")))
            global.templates.forEach {
                add(TemplateChoice(it.id, it.name))
            }
            shared.templates.filterNot { candidate -> global.templates.any { it.id == candidate.id } }.forEach {
                add(TemplateChoice(it.id, CommitMessageBundle.message("settings.project.shared.choice", it.name)))
            }
            if (storedTemplateId.isNotBlank() && none { it.id == storedTemplateId }) {
                add(TemplateChoice(storedTemplateId, CommitMessageBundle.message("settings.project.unavailable.choice", storedTemplateId)))
            }
        }.toTypedArray()
        val styleChoices = buildList {
            add(TemplateChoice("", CommitMessageBundle.message("settings.project.inheritDefault")))
            global.styles.forEach {
                add(TemplateChoice(it.id, displayStyleName(it)))
            }
            shared.styles.filterNot { candidate -> global.styles.any { it.id == candidate.id } }.forEach {
                add(TemplateChoice(it.id, CommitMessageBundle.message("settings.project.shared.choice", it.name)))
            }
            if (storedStyleId.isNotBlank() && none { it.id == storedStyleId }) {
                add(TemplateChoice(storedStyleId, CommitMessageBundle.message("settings.project.unavailable.choice", storedStyleId)))
            }
        }.toTypedArray()
        loading = true
        try {
            templateCombo.model = DefaultComboBoxModel(choices)
            templateCombo.selectedItem = choices.firstOrNull { it.id == storedTemplateId } ?: choices.first()
            workingTemplateId = (templateCombo.selectedItem as? TemplateChoice)?.id.orEmpty()
            styleCombo.model = DefaultComboBoxModel(styleChoices)
            styleCombo.selectedItem = styleChoices.firstOrNull { it.id == storedStyleId } ?: styleChoices.first()
            workingStyleId = (styleCombo.selectedItem as? TemplateChoice)?.id.orEmpty()
        } finally {
            loading = false
        }
    }

    override fun disposeUIResources() {
        root = null
    }

    private data class TemplateChoice(val id: String, val name: String) {
        override fun toString(): String = name
    }

    private fun displayStyleName(style: CommitStyleDefinition): String = when (style.id) {
        CommitMessageDefaults.STANDARD_STYLE_ID -> CommitMessageBundle.message("style.standard.name")
        CommitMessageDefaults.CONCISE_STYLE_ID -> CommitMessageBundle.message("style.concise.name")
        else -> style.name
    }

    companion object {
        const val ID = "emohce.settings.commitMessage.project"
    }
}
