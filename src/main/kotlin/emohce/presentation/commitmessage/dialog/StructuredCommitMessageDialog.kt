package emohce.presentation.commitmessage.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import emohce.data.commitmessage.CommitMessageSettingsState
import emohce.domain.commitmessage.CommitDraft
import emohce.domain.commitmessage.CommitTypeDefinition
import emohce.domain.commitmessage.TypeDisplayMode
import emohce.presentation.commitmessage.CommitMessageBundle
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton

class StructuredCommitMessageDialog(
    project: Project,
    initial: CommitDraft,
    private val settings: CommitMessageSettingsState,
    private val types: List<CommitTypeDefinition>,
) : DialogWrapper(project) {
    private var selectedType = initial.type.ifBlank { types.firstOrNull()?.id.orEmpty() }
    private val scopeField = JBTextField(initial.scope)
    private val subjectField = JBTextField(initial.subject)
    private val bodyField = textArea(initial.body, 5)
    private val breakingField = textArea(initial.breakingChanges, 3)
    private val closesField = JBTextField(initial.closes)
    private val skipCiField = JBCheckBox(CommitMessageBundle.message("settings.field.skipCi"), initial.skipCi)
    private val typeComponent = createTypeComponent()

    init {
        title = CommitMessageBundle.message("dialog.create.title")
        init()
    }

    val draft: CommitDraft
        get() = CommitDraft(
            type = selectedType,
            scope = scopeField.text,
            subject = subjectField.text,
            body = bodyField.text,
            breakingChanges = breakingField.text,
            closes = closesField.text,
            skipCi = skipCiField.isSelected,
        ).normalized()

    override fun createCenterPanel(): JComponent = panel {
        if (settings.showType) {
            row(CommitMessageBundle.message("settings.field.type")) {
                cell(typeComponent).align(Align.FILL)
            }
        }
        if (settings.showScope) {
            row(CommitMessageBundle.message("settings.field.scope")) {
                cell(scopeField).align(Align.FILL)
            }
        }
        row(CommitMessageBundle.message("settings.field.subject")) {
            cell(subjectField).align(Align.FILL)
        }
        if (settings.showBody) {
            row(CommitMessageBundle.message("settings.field.body")) {
                scrollCell(bodyField).align(Align.FILL)
            }.resizableRow()
        }
        if (settings.showBreakingChanges) {
            row(CommitMessageBundle.message("settings.field.breakingChanges")) {
                scrollCell(breakingField).align(Align.FILL)
            }.resizableRow()
        }
        if (settings.showCloses) {
            row(CommitMessageBundle.message("settings.field.closes")) {
                cell(closesField).align(Align.FILL)
            }
        }
        if (settings.showSkipCi) row { cell(skipCiField) }
    }.apply { preferredSize = Dimension(680, 480) }

    override fun getPreferredFocusedComponent(): JComponent = subjectField

    override fun doValidate(): ValidationInfo? = if (subjectField.text.isBlank()) {
        ValidationInfo(CommitMessageBundle.message("dialog.field.required"), subjectField)
    } else {
        null
    }

    private fun createTypeComponent(): JComponent {
        val ids = types.map { it.id }.ifEmpty { listOf("feat") }
        if (selectedType !in ids) selectedType = ids.first()
        return when (settings.typeDisplayMode) {
            TypeDisplayMode.COMBO -> ComboBox(ids.toTypedArray()).apply {
                selectedItem = selectedType
                addActionListener { selectedType = selectedItem?.toString().orEmpty() }
            }
            TypeDisplayMode.RADIO -> radioPanel(ids)
            TypeDisplayMode.MIXED -> mixedPanel(ids)
        }
    }

    private fun radioPanel(ids: List<String>): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
        val group = ButtonGroup()
        ids.forEach { id ->
            add(JRadioButton(id, id == selectedType).also { button ->
                group.add(button)
                button.addActionListener { if (button.isSelected) selectedType = id }
            })
        }
    }

    private fun mixedPanel(ids: List<String>): JPanel {
        val primary = ids.take(5)
        val remaining = ids.drop(5)
        return JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            val group = ButtonGroup()
            primary.forEach { id ->
                add(JRadioButton(id, id == selectedType).also { button ->
                    group.add(button)
                    button.addActionListener { if (button.isSelected) selectedType = id }
                })
            }
            if (remaining.isNotEmpty()) {
                add(ComboBox(remaining.toTypedArray()).apply {
                    if (selectedType in remaining) selectedItem = selectedType else selectedIndex = -1
                    addActionListener {
                        selectedItem?.toString()?.let { selectedType = it }
                        group.clearSelection()
                    }
                })
            }
        }
    }

    private fun textArea(value: String, rows: Int): JBTextArea = JBTextArea(value, rows, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }
}
