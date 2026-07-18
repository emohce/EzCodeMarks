package emohce.presentation.commitmessage.settings

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import emohce.data.commitmessage.CommitMessageSettingsService
import emohce.data.commitmessage.CommitProjectSharedSettingsService
import emohce.data.commitmessage.CommitProjectSharedSettingsState
import emohce.data.commitmessage.VelocityCommitTemplateRenderer
import emohce.domain.commitmessage.CommitStyleDefinition
import emohce.domain.commitmessage.CommitTemplateDefinition
import emohce.domain.commitmessage.ProjectInstructionMode
import emohce.presentation.commitmessage.CommitMessageBundle
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.UUID
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import org.jetbrains.annotations.TestOnly

internal class CommitProjectSharedConfigurable(private val project: Project) : SearchableConfigurable {
    private val service: CommitProjectSharedSettingsService
        get() = CommitProjectSharedSettingsService.getInstance(project)
    private var loaded = CommitProjectSharedSettingsState()
    private var working = loaded.deepCopy()
    private var loading = false
    private var root: JComponent? = null

    private val instructionMode = ComboBox(
        ProjectInstructionMode.entries.map { mode ->
            InstructionChoice(mode, CommitMessageBundle.message("settings.project.shared.instructions.${mode.name.lowercase()}"))
        }.toTypedArray(),
    )
    private val instructions = JBTextArea(5, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val defaultTemplate = ComboBox<DefinitionChoice>()
    private val defaultStyle = ComboBox<DefinitionChoice>()
    private val templateModel = TemplateTableModel()
    private val styleModel = StyleTableModel()
    private val templateTable = JBTable(templateModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        emptyText.text = CommitMessageBundle.message("settings.project.shared.templates.empty")
        accessibleContext.accessibleName = CommitMessageBundle.message("settings.project.shared.templates.accessible")
    }
    private val styleTable = JBTable(styleModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        emptyText.text = CommitMessageBundle.message("settings.project.shared.styles.empty")
        accessibleContext.accessibleName = CommitMessageBundle.message("settings.project.shared.styles.accessible")
    }

    init {
        instructionMode.addActionListener {
            if (loading) return@addActionListener
            working.instructionMode = (instructionMode.selectedItem as? InstructionChoice)?.mode
                ?: ProjectInstructionMode.INHERIT
            updateInstructionControl()
        }
        defaultTemplate.addActionListener {
            if (!loading) working.defaultTemplateId = (defaultTemplate.selectedItem as? DefinitionChoice)?.id.orEmpty()
        }
        defaultStyle.addActionListener {
            if (!loading) working.defaultStyleId = (defaultStyle.selectedItem as? DefinitionChoice)?.id.orEmpty()
        }
        templateTable.addMouseListener(EditOnDoubleClick(templateTable) { editTemplate() })
        styleTable.addMouseListener(EditOnDoubleClick(styleTable) { editStyle() })
    }

    override fun getId(): String = ID

    override fun getDisplayName(): String = CommitMessageBundle.message("settings.project.shared.title")

    override fun createComponent(): JComponent {
        root?.let { return it }
        reset()
        val templatePanel = ToolbarDecorator.createDecorator(templateTable)
            .setAddAction { addTemplate() }
            .setEditAction { editTemplate() }
            .setRemoveAction { removeTemplate() }
            .disableUpDownActions()
            .createPanel()
        val stylePanel = ToolbarDecorator.createDecorator(styleTable)
            .setAddAction { addStyle() }
            .setEditAction { editStyle() }
            .setRemoveAction { removeStyle() }
            .disableUpDownActions()
            .createPanel()
        val definitions = JBTabbedPane().apply {
            addTab(CommitMessageBundle.message("settings.templates.tab"), templatePanel)
            addTab(CommitMessageBundle.message("settings.styles.tab"), stylePanel)
            preferredSize = Dimension(JBUI.scale(820), JBUI.scale(360))
        }
        return panel {
            group(CommitMessageBundle.message("settings.project.shared.instructions")) {
                row(CommitMessageBundle.message("settings.project.shared.instructions.mode")) {
                    cell(instructionMode)
                }
                row(CommitMessageBundle.message("settings.project.shared.instructions.text")) {
                    cell(JBScrollPane(instructions)).align(Align.FILL).resizableColumn()
                }.resizableRow()
            }
            group(CommitMessageBundle.message("settings.project.shared.defaults")) {
                row(CommitMessageBundle.message("settings.project.shared.defaultTemplate")) {
                    cell(defaultTemplate).align(Align.FILL).resizableColumn()
                }
                row(CommitMessageBundle.message("settings.project.shared.defaultStyle")) {
                    cell(defaultStyle).align(Align.FILL).resizableColumn()
                }
            }
            row { cell(definitions).align(Align.FILL).resizableColumn() }.resizableRow()
        }.also { root = it }
    }

    override fun isModified(): Boolean = controlSnapshot() != loaded

    override fun apply() {
        working = controlSnapshot()
        validateWorkingState()
        val current = service.state.deepCopy().apply { normalize() }
        if (current != loaded && current != working) {
            throw ConfigurationException(CommitMessageBundle.message("settings.project.shared.concurrentChange"))
        }
        service.replaceState(working)
        reset()
    }

    override fun reset() {
        loaded = service.state.deepCopy().apply { normalize() }
        working = loaded.deepCopy()
        loading = true
        try {
            instructionMode.selectedItem = (0 until instructionMode.itemCount)
                .map(instructionMode::getItemAt)
                .first { it.mode == working.instructionMode }
            instructions.text = working.extraInstructions
            refreshTablesAndDefaults()
        } finally {
            loading = false
        }
        updateInstructionControl()
    }

    override fun disposeUIResources() {
        root = null
    }

    @TestOnly
    internal fun removeSelectedTemplateForTest() = removeTemplate()

    private fun controlSnapshot(): CommitProjectSharedSettingsState = working.deepCopy().apply {
        instructionMode = (this@CommitProjectSharedConfigurable.instructionMode.selectedItem as? InstructionChoice)?.mode
            ?: ProjectInstructionMode.INHERIT
        extraInstructions = instructions.text.trim()
        defaultTemplateId = (defaultTemplate.selectedItem as? DefinitionChoice)?.id.orEmpty()
        defaultStyleId = (defaultStyle.selectedItem as? DefinitionChoice)?.id.orEmpty()
    }

    private fun validateWorkingState() {
        if (working.extraInstructions.length > CommitProjectSharedSettingsState.MAX_EXTRA_INSTRUCTIONS) {
            throw ConfigurationException(
                CommitMessageBundle.message(
                    "settings.project.shared.instructions.tooLong",
                    CommitProjectSharedSettingsState.MAX_EXTRA_INSTRUCTIONS,
                ),
            )
        }
        val global = CommitMessageSettingsService.getInstance().snapshot()
        val renderer = VelocityCommitTemplateRenderer()
        val invalidTemplates = working.templates.any {
            it.id.isBlank() || it.name.isBlank() || !renderer.validate(it).valid
        }
        val invalidStyles = working.styles.any {
            it.id.isBlank() || it.name.isBlank() || it.prompt.length > MAX_STYLE_PROMPT_LENGTH ||
                it.templateContent.length > MAX_STYLE_TEMPLATE_LENGTH ||
                (it.templateContent.isNotBlank() && !renderer.validate(
                    CommitTemplateDefinition("shared-style", it.name, it.templateContent),
                ).valid)
        }
        val duplicateIds = working.templates.map { it.id }.distinct().size != working.templates.size ||
            working.styles.map { it.id }.distinct().size != working.styles.size
        val globalCollision = working.templates.any { candidate -> global.templates.any { it.id == candidate.id } } ||
            working.styles.any { candidate -> global.styles.any { it.id == candidate.id } }
        if (invalidTemplates || invalidStyles || duplicateIds || globalCollision) {
            throw ConfigurationException(CommitMessageBundle.message("settings.project.shared.validation"))
        }
    }

    private fun addTemplate() {
        val candidate = CommitTemplateDefinition(
            id = "project-${UUID.randomUUID()}",
            name = CommitMessageBundle.message("settings.project.shared.template.new"),
            content = "${'$'}{type}: ${'$'}{subject}",
        )
        editTemplate(candidate, isNew = true)
    }

    private fun editTemplate() {
        selectedModelRow(templateTable)?.let(working.templates::getOrNull)?.copy()?.let {
            editTemplate(it, isNew = false)
        }
    }

    private fun editTemplate(candidate: CommitTemplateDefinition, isNew: Boolean) {
        val existingIds = working.templates.map { it.id }.filterNot { it == candidate.id }.toSet()
        val edited = SharedTemplateDialog(project, candidate, existingIds).showResult() ?: return
        if (isNew) {
            working.templates += edited
        } else {
            val index = working.templates.indexOfFirst { it.id == candidate.id }
            if (index >= 0) working.templates[index] = edited
            if (working.defaultTemplateId == candidate.id) working.defaultTemplateId = edited.id
        }
        refreshTablesAndDefaults(edited.id, null)
    }

    private fun removeTemplate() {
        val index = selectedModelRow(templateTable) ?: return
        if (index !in working.templates.indices) return
        val removed = working.templates.removeAt(index)
        if (working.defaultTemplateId == removed.id) working.defaultTemplateId = ""
        refreshTablesAndDefaults()
    }

    private fun addStyle() {
        val candidate = CommitStyleDefinition(
            id = "project-${UUID.randomUUID()}",
            name = CommitMessageBundle.message("settings.project.shared.style.new"),
        )
        editStyle(candidate, isNew = true)
    }

    private fun editStyle() {
        selectedModelRow(styleTable)?.let(working.styles::getOrNull)?.copy()?.let { editStyle(it, isNew = false) }
    }

    private fun editStyle(candidate: CommitStyleDefinition, isNew: Boolean) {
        val existingIds = working.styles.map { it.id }.filterNot { it == candidate.id }.toSet()
        val edited = SharedStyleDialog(project, candidate, existingIds).showResult() ?: return
        if (isNew) {
            working.styles += edited
        } else {
            val index = working.styles.indexOfFirst { it.id == candidate.id }
            if (index >= 0) working.styles[index] = edited
            if (working.defaultStyleId == candidate.id) working.defaultStyleId = edited.id
        }
        refreshTablesAndDefaults(null, edited.id)
    }

    private fun removeStyle() {
        val index = selectedModelRow(styleTable) ?: return
        if (index !in working.styles.indices) return
        val removed = working.styles.removeAt(index)
        if (working.defaultStyleId == removed.id) working.defaultStyleId = ""
        refreshTablesAndDefaults()
    }

    private fun refreshTablesAndDefaults(selectedTemplateId: String? = null, selectedStyleId: String? = null) {
        val previousLoading = loading
        loading = true
        try {
            templateModel.fireTableDataChanged()
            styleModel.fireTableDataChanged()
            val templateChoices = buildList {
                add(DefinitionChoice("", CommitMessageBundle.message("settings.project.shared.useGlobal")))
                working.templates.forEach { add(DefinitionChoice(it.id, it.name)) }
            }.toTypedArray()
            defaultTemplate.model = DefaultComboBoxModel(templateChoices)
            defaultTemplate.selectedItem = templateChoices.firstOrNull { it.id == working.defaultTemplateId }
                ?: templateChoices.first()
            working.defaultTemplateId = (defaultTemplate.selectedItem as? DefinitionChoice)?.id.orEmpty()

            val styleChoices = buildList {
                add(DefinitionChoice("", CommitMessageBundle.message("settings.project.shared.useGlobal")))
                working.styles.forEach { add(DefinitionChoice(it.id, it.name)) }
            }.toTypedArray()
            defaultStyle.model = DefaultComboBoxModel(styleChoices)
            defaultStyle.selectedItem = styleChoices.firstOrNull { it.id == working.defaultStyleId }
                ?: styleChoices.first()
            working.defaultStyleId = (defaultStyle.selectedItem as? DefinitionChoice)?.id.orEmpty()

            selectedTemplateId?.let { id ->
                working.templates.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let {
                    val viewIndex = templateTable.convertRowIndexToView(it)
                    if (viewIndex >= 0) templateTable.setRowSelectionInterval(viewIndex, viewIndex)
                }
            }
            selectedStyleId?.let { id ->
                working.styles.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let {
                    val viewIndex = styleTable.convertRowIndexToView(it)
                    if (viewIndex >= 0) styleTable.setRowSelectionInterval(viewIndex, viewIndex)
                }
            }
        } finally {
            loading = previousLoading
        }
    }

    private fun updateInstructionControl() {
        instructions.isEnabled = (instructionMode.selectedItem as? InstructionChoice)?.mode != ProjectInstructionMode.INHERIT
    }

    private inner class TemplateTableModel : AbstractTableModel() {
        override fun getRowCount(): Int = working.templates.size
        override fun getColumnCount(): Int = 2
        override fun getColumnName(column: Int): String = CommitMessageBundle.message(
            if (column == 0) "settings.templates.name" else "settings.project.shared.id",
        )

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = working.templates[rowIndex].let {
            if (columnIndex == 0) it.name else it.id
        }
    }

    private inner class StyleTableModel : AbstractTableModel() {
        override fun getRowCount(): Int = working.styles.size
        override fun getColumnCount(): Int = 2
        override fun getColumnName(column: Int): String = CommitMessageBundle.message(
            if (column == 0) "settings.templates.name" else "settings.project.shared.id",
        )

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = working.styles[rowIndex].let {
            if (columnIndex == 0) it.name else it.id
        }
    }

    private data class InstructionChoice(val mode: ProjectInstructionMode, val label: String) {
        override fun toString(): String = label
    }

    private data class DefinitionChoice(val id: String, val label: String) {
        override fun toString(): String = label
    }

    private fun selectedModelRow(table: JBTable): Int? = table.selectedRow
        .takeIf { it >= 0 }
        ?.let(table::convertRowIndexToModel)

    private class EditOnDoubleClick(
        private val table: JBTable,
        private val edit: () -> Unit,
    ) : MouseAdapter() {
        override fun mouseClicked(event: MouseEvent) {
            if (event.button != MouseEvent.BUTTON1 || event.clickCount != 2) return
            val row = table.rowAtPoint(event.point)
            if (row < 0) return
            table.setRowSelectionInterval(row, row)
            edit()
        }
    }

    companion object {
        const val ID = "emohce.settings.commitMessage.projectShared"
        private const val MAX_STYLE_PROMPT_LENGTH = 4_000
        private const val MAX_STYLE_TEMPLATE_LENGTH = 8_000
    }
}

private class SharedTemplateDialog(
    project: Project,
    private val initial: CommitTemplateDefinition,
    private val existingIds: Set<String>,
) : DialogWrapper(project, true) {
    private val id = JBTextField(initial.id)
    private val name = JBTextField(initial.name)
    private val content = JBTextArea(initial.content, 12, 64)

    init {
        title = CommitMessageBundle.message("settings.project.shared.template.edit")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(CommitMessageBundle.message("settings.project.shared.id")) { cell(id).align(Align.FILL).resizableColumn() }
        row(CommitMessageBundle.message("settings.templates.name")) { cell(name).align(Align.FILL).resizableColumn() }
        row(CommitMessageBundle.message("settings.templates.content")) {
            cell(JBScrollPane(content)).align(Align.FILL).resizableColumn()
        }.resizableRow()
    }

    override fun doValidate(): ValidationInfo? {
        val candidateId = id.text.trim()
        if (candidateId.isBlank() || name.text.trim().isBlank() || candidateId in existingIds) {
            return ValidationInfo(CommitMessageBundle.message("settings.project.shared.validation"), id)
        }
        val candidate = CommitTemplateDefinition(candidateId, name.text.trim(), content.text)
        if (!VelocityCommitTemplateRenderer().validate(candidate).valid) {
            return ValidationInfo(CommitMessageBundle.message("settings.project.shared.template.invalid"), content)
        }
        return null
    }

    fun showResult(): CommitTemplateDefinition? = if (showAndGet()) {
        CommitTemplateDefinition(id.text.trim(), name.text.trim(), content.text, false)
    } else {
        null
    }
}

private class SharedStyleDialog(
    project: Project,
    private val initial: CommitStyleDefinition,
    private val existingIds: Set<String>,
) : DialogWrapper(project, true) {
    private val id = JBTextField(initial.id)
    private val name = JBTextField(initial.name)
    private val description = JBTextArea(initial.description, 3, 64)
    private val prompt = JBTextArea(initial.prompt, 7, 64)
    private val template = JBTextArea(initial.templateContent, 10, 64)

    init {
        title = CommitMessageBundle.message("settings.project.shared.style.edit")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(CommitMessageBundle.message("settings.project.shared.id")) { cell(id).align(Align.FILL).resizableColumn() }
        row(CommitMessageBundle.message("settings.templates.name")) { cell(name).align(Align.FILL).resizableColumn() }
        row(CommitMessageBundle.message("settings.templates.description")) {
            cell(JBScrollPane(description)).align(Align.FILL).resizableColumn()
        }
        row(CommitMessageBundle.message("settings.styles.prompt")) {
            cell(JBScrollPane(prompt)).align(Align.FILL).resizableColumn()
        }.resizableRow()
        row(CommitMessageBundle.message("settings.styles.template")) {
            cell(JBScrollPane(template)).align(Align.FILL).resizableColumn()
        }.resizableRow()
    }

    override fun doValidate(): ValidationInfo? {
        val candidateId = id.text.trim()
        if (candidateId.isBlank() || name.text.trim().isBlank() || candidateId in existingIds) {
            return ValidationInfo(CommitMessageBundle.message("settings.project.shared.validation"), id)
        }
        if (prompt.text.length > 4_000 || template.text.length > 8_000) {
            return ValidationInfo(CommitMessageBundle.message("settings.project.shared.style.tooLong"), prompt)
        }
        if (template.text.isNotBlank() && !VelocityCommitTemplateRenderer().validate(
                CommitTemplateDefinition("shared-style", name.text.trim(), template.text),
            ).valid
        ) {
            return ValidationInfo(CommitMessageBundle.message("settings.project.shared.template.invalid"), template)
        }
        return null
    }

    fun showResult(): CommitStyleDefinition? = if (showAndGet()) {
        CommitStyleDefinition(
            id = id.text.trim(),
            name = name.text.trim(),
            description = description.text.trim(),
            prompt = prompt.text.trim(),
            templateContent = template.text,
            builtIn = false,
        )
    } else {
        null
    }
}
