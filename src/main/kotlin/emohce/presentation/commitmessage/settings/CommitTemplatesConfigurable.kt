package emohce.presentation.commitmessage.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import emohce.data.commitmessage.CommitMessageSettingsService
import emohce.data.commitmessage.VelocityCommitTemplateRenderer
import emohce.domain.commitmessage.CommitDraft
import emohce.domain.commitmessage.CommitMessageDefaults
import emohce.domain.commitmessage.CommitTemplateDefinition
import emohce.domain.commitmessage.CommitTypeDefinition
import emohce.presentation.commitmessage.CommitMessageBundle
import java.awt.GridLayout
import java.util.UUID
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class CommitTemplatesConfigurable : SearchableConfigurable {
    private var editorDisposable: Disposable? = null
    private val renderer: VelocityCommitTemplateRenderer
        get() = ApplicationManager.getApplication().getService(VelocityCommitTemplateRenderer::class.java)

    private var templates = mutableListOf<CommitTemplateDefinition>()
    private var types = mutableListOf<CommitTypeDefinition>()
    private var defaultTemplateId = CommitMessageDefaults.DEFAULT_TEMPLATE_ID
    private var loading = false
    private var selectedTemplateIndex = -1
    private var selectedTypeIndex = -1

    private val templateModel = DefaultListModel<CommitTemplateDefinition>()
    private val templateList = JBList(templateModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean,
            ): java.awt.Component = super.getListCellRendererComponent(list, value, index, selected, focused).also {
                (it as JLabel).text = (value as? CommitTemplateDefinition)?.let(::displayTemplateName).orEmpty()
            }
        }
    }
    private val defaultTemplateCombo = ComboBox<TemplateChoice>()
    private val templateNameField = JBTextField()
    private lateinit var templateEditor: EditorTextField
    private lateinit var previewEditor: EditorTextField
    private val validationLabel = JLabel()

    private val typeModel = DefaultListModel<CommitTypeDefinition>()
    private val typeList = JBList(typeModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean,
            ): java.awt.Component = super.getListCellRendererComponent(list, value, index, selected, focused).also {
                val type = value as? CommitTypeDefinition
                (it as JLabel).text = type?.let { item ->
                    "${item.id} — ${displayTypeDescription(item)}"
                }.orEmpty()
            }
        }
    }
    private val typeIdField = JBTextField()
    private val typeDescriptionField = JBTextField()
    private var loadedTypeDescriptionCanonical = ""
    private var loadedTypeDescriptionDisplay = ""
    private var root: JComponent? = null

    init {
        templateList.addListSelectionListener {
            if (!it.valueIsAdjusting && !loading) {
                saveTemplate(selectedTemplateIndex)
                selectedTemplateIndex = templateList.selectedIndex
                loadTemplate(selectedTemplateIndex)
            }
        }
        typeList.addListSelectionListener {
            if (!it.valueIsAdjusting && !loading) {
                saveType(selectedTypeIndex)
                selectedTypeIndex = typeList.selectedIndex
                loadType(selectedTypeIndex)
            }
        }
        templateNameField.document.addDocumentListener(swingDocumentListener {
            if (!loading) saveTemplate(selectedTemplateIndex)
        })
        typeIdField.document.addDocumentListener(swingDocumentListener {
            if (!loading) saveType(selectedTypeIndex)
        })
        typeDescriptionField.document.addDocumentListener(swingDocumentListener {
            if (!loading) saveType(selectedTypeIndex)
        })
        defaultTemplateCombo.addActionListener {
            if (!loading) defaultTemplateId = (defaultTemplateCombo.selectedItem as? TemplateChoice)?.id
                ?: CommitMessageDefaults.DEFAULT_TEMPLATE_ID
        }
    }

    override fun getId(): String = ID

    override fun getDisplayName(): String = CommitMessageBundle.message("settings.templates.title")

    override fun createComponent(): JComponent {
        root?.let { return it }
        initializeEditors()
        reset()
        val tabs = JBTabbedPane().apply {
            addTab(CommitMessageBundle.message("settings.templates.list"), templatePanel())
            addTab(CommitMessageBundle.message("settings.types.list"), typePanel())
        }
        return tabs.also { root = it }
    }

    override fun isModified(): Boolean {
        saveTemplate(selectedTemplateIndex)
        saveType(selectedTypeIndex)
        val current = CommitMessageSettingsService.getInstance().state
        return templates != current.templates || types != current.types || defaultTemplateId != current.defaultTemplateId
    }

    override fun apply() {
        saveTemplate(selectedTemplateIndex)
        saveType(selectedTypeIndex)
        val invalid = templates.firstOrNull { !renderer.validate(it).valid }
        if (invalid != null) {
            val error = renderer.validate(invalid).error
            throw ConfigurationException(templateErrorMessage(error))
        }
        if (templates.map { it.id }.toSet().size != templates.size ||
            templates.any { it.id.isBlank() || it.name.isBlank() } ||
            types.map { it.id }.toSet().size != types.size ||
            types.any { it.id.isBlank() }
        ) {
            throw ConfigurationException(CommitMessageBundle.message("settings.validation.unique"))
        }
        val service = CommitMessageSettingsService.getInstance()
        val merged = service.state.deepCopy().apply {
            templates = this@CommitTemplatesConfigurable.templates.map { it.copy() }.toMutableList()
            types = this@CommitTemplatesConfigurable.types.map { it.copy() }.toMutableList()
            defaultTemplateId = this@CommitTemplatesConfigurable.defaultTemplateId
        }
        service.replaceState(merged)
        reset()
    }

    override fun reset() {
        val state = CommitMessageSettingsService.getInstance().state
        templates = state.templates.map { it.copy() }.toMutableList()
        types = state.types.map { it.copy() }.toMutableList()
        defaultTemplateId = state.defaultTemplateId
        rebuildModels()
    }

    override fun disposeUIResources() {
        root = null
        editorDisposable?.let(Disposer::dispose)
        editorDisposable = null
    }

    private fun templatePanel(): JComponent = panel {
        row {
            scrollCell(templateList).align(Align.FILL)
            cell(buttons(
                JButton(CommitMessageBundle.message("settings.templates.add")).apply { addActionListener { addTemplate() } },
                JButton(CommitMessageBundle.message("settings.templates.copy")).apply { addActionListener { copyTemplate() } },
                JButton(CommitMessageBundle.message("settings.templates.delete")).apply { addActionListener { deleteTemplate() } },
                JButton(CommitMessageBundle.message("settings.templates.restore")).apply { addActionListener { restoreTemplate() } },
            ))
        }.resizableRow()
        row(CommitMessageBundle.message("settings.templates.default")) {
            cell(defaultTemplateCombo).align(Align.FILL)
        }
        row(CommitMessageBundle.message("settings.templates.name")) {
            cell(templateNameField).align(Align.FILL)
        }
        row(CommitMessageBundle.message("settings.templates.content")) {
            cell(templateEditor).align(Align.FILL)
        }.resizableRow()
        row(CommitMessageBundle.message("settings.templates.preview")) {
            cell(previewEditor).align(Align.FILL)
        }.resizableRow()
        row { cell(validationLabel) }
    }

    private fun typePanel(): JComponent = panel {
        row {
            scrollCell(typeList).align(Align.FILL)
            cell(buttons(
                JButton(CommitMessageBundle.message("settings.types.add")).apply { addActionListener { addType() } },
                JButton(CommitMessageBundle.message("settings.types.delete")).apply { addActionListener { deleteType() } },
                JButton(CommitMessageBundle.message("settings.types.up")).apply { addActionListener { moveType(-1) } },
                JButton(CommitMessageBundle.message("settings.types.down")).apply { addActionListener { moveType(1) } },
            ))
        }.resizableRow()
        row(CommitMessageBundle.message("settings.types.id")) { cell(typeIdField).align(Align.FILL) }
        row(CommitMessageBundle.message("settings.types.description")) {
            cell(typeDescriptionField).align(Align.FILL)
        }
    }

    private fun rebuildModels() {
        loading = true
        templateModel.clear()
        templates.forEach(templateModel::addElement)
        typeModel.clear()
        types.forEach(typeModel::addElement)
        refreshDefaultChoices()
        selectedTemplateIndex = if (templates.isEmpty()) -1 else 0
        selectedTypeIndex = if (types.isEmpty()) -1 else 0
        templateList.selectedIndex = selectedTemplateIndex
        typeList.selectedIndex = selectedTypeIndex
        loadTemplate(selectedTemplateIndex)
        loadType(selectedTypeIndex)
        loading = false
    }

    private fun loadTemplate(index: Int) {
        loading = true
        val template = templates.getOrNull(index)
        templateNameField.text = template?.let(::displayTemplateName).orEmpty()
        templateNameField.isEnabled = template?.builtIn != true
        templateEditor.text = template?.content.orEmpty()
        refreshPreview()
        loading = false
    }

    private fun saveTemplate(index: Int) {
        val template = templates.getOrNull(index) ?: return
        template.name = if (template.builtIn) DEFAULT_TEMPLATE_NAME else templateNameField.text.trim()
        template.content = templateEditor.text
        templateList.repaint()
    }

    private fun loadType(index: Int) {
        loading = true
        val type = types.getOrNull(index)
        typeIdField.text = type?.id.orEmpty()
        loadedTypeDescriptionCanonical = type?.description.orEmpty()
        loadedTypeDescriptionDisplay = type?.let(::displayTypeDescription).orEmpty()
        typeDescriptionField.text = loadedTypeDescriptionDisplay
        loading = false
    }

    private fun saveType(index: Int) {
        val type = types.getOrNull(index) ?: return
        type.id = typeIdField.text.trim().lowercase()
        val editedDescription = typeDescriptionField.text.trim()
        type.description = if (editedDescription == loadedTypeDescriptionDisplay &&
            type.description == loadedTypeDescriptionCanonical
        ) {
            loadedTypeDescriptionCanonical
        } else {
            editedDescription
        }
        typeList.repaint()
    }

    private fun refreshPreview() {
        val template = templates.getOrNull(selectedTemplateIndex) ?: return
        template.content = templateEditor.text
        val validation = renderer.validate(template)
        validationLabel.text = if (validation.valid) {
            CommitMessageBundle.message("settings.templates.validation.valid")
        } else {
            templateErrorMessage(validation.error)
        }
        validationLabel.foreground = if (validation.valid) JBColor.foreground() else JBColor.RED
        previewEditor.text = if (validation.valid) {
            renderer.render(
                template,
                CommitDraft(
                    "feat",
                    "ui",
                    CommitMessageBundle.message("settings.templates.previewSubject"),
                    CommitMessageBundle.message("settings.templates.previewBody"),
                    "",
                    "#123",
                    false,
                ),
            )
        } else {
            ""
        }
    }

    private fun refreshDefaultChoices() {
        val choices = templates.map { TemplateChoice(it.id, displayTemplateName(it)) }.toTypedArray()
        defaultTemplateCombo.model = DefaultComboBoxModel(choices)
        defaultTemplateCombo.selectedItem = choices.firstOrNull { it.id == defaultTemplateId }
            ?: choices.firstOrNull()
    }

    private fun addTemplate() {
        saveTemplate(selectedTemplateIndex)
        val template = CommitTemplateDefinition(
            id = UUID.randomUUID().toString(),
            name = CommitMessageBundle.message("settings.templates.newName"),
            content = CommitMessageDefaults.defaultTemplateContent,
        )
        val newIndex = templates.size
        loading = true
        try {
            templates += template
            templateModel.addElement(template)
            selectedTemplateIndex = newIndex
            templateList.selectedIndex = newIndex
            refreshDefaultChoices()
        } finally {
            loading = false
        }
        loadTemplate(newIndex)
    }

    private fun copyTemplate() {
        saveTemplate(selectedTemplateIndex)
        val selected = templates.getOrNull(selectedTemplateIndex) ?: return
        val copy = selected.copy(
            id = UUID.randomUUID().toString(),
            name = CommitMessageBundle.message("common.copyName", displayTemplateName(selected)),
            builtIn = false,
        )
        val newIndex = templates.size
        loading = true
        try {
            templates += copy
            templateModel.addElement(copy)
            selectedTemplateIndex = newIndex
            templateList.selectedIndex = newIndex
            refreshDefaultChoices()
        } finally {
            loading = false
        }
        loadTemplate(newIndex)
    }

    private fun deleteTemplate() {
        saveTemplate(selectedTemplateIndex)
        val selected = templates.getOrNull(selectedTemplateIndex) ?: return
        if (selected.builtIn) return
        val removedIndex = selectedTemplateIndex
        val newIndex = removedIndex.coerceAtMost(templates.lastIndex - 1)
        loading = true
        try {
            templates.removeAt(removedIndex)
            templateModel.remove(removedIndex)
            if (defaultTemplateId == selected.id) defaultTemplateId = CommitMessageDefaults.DEFAULT_TEMPLATE_ID
            selectedTemplateIndex = newIndex
            templateList.selectedIndex = newIndex
            refreshDefaultChoices()
        } finally {
            loading = false
        }
        loadTemplate(newIndex)
    }

    private fun restoreTemplate() {
        val selected = templates.getOrNull(selectedTemplateIndex) ?: return
        if (!selected.builtIn) return
        selected.content = CommitMessageDefaults.defaultTemplateContent
        templateEditor.text = selected.content
        refreshPreview()
    }

    private fun addType() {
        saveType(selectedTypeIndex)
        val type = CommitTypeDefinition("type${types.size + 1}", "")
        val newIndex = types.size
        loading = true
        try {
            types += type
            typeModel.addElement(type)
            selectedTypeIndex = newIndex
            typeList.selectedIndex = newIndex
        } finally {
            loading = false
        }
        loadType(newIndex)
    }

    private fun deleteType() {
        if (types.size <= 1 || selectedTypeIndex !in types.indices) return
        saveType(selectedTypeIndex)
        val removedIndex = selectedTypeIndex
        val newIndex = removedIndex.coerceAtMost(types.lastIndex - 1)
        loading = true
        try {
            types.removeAt(removedIndex)
            typeModel.remove(removedIndex)
            selectedTypeIndex = newIndex
            typeList.selectedIndex = newIndex
        } finally {
            loading = false
        }
        loadType(newIndex)
    }

    private fun moveType(delta: Int) {
        saveType(selectedTypeIndex)
        val target = selectedTypeIndex + delta
        if (selectedTypeIndex !in types.indices || target !in types.indices) return
        val source = selectedTypeIndex
        loading = true
        try {
            val item = types.removeAt(source)
            types.add(target, item)
            typeModel.remove(source)
            typeModel.add(target, item)
            selectedTypeIndex = target
            typeList.selectedIndex = target
        } finally {
            loading = false
        }
        loadType(target)
    }

    private fun initializeEditors() {
        val disposable = Disposer.newDisposable("EzCodeMarks commit templates settings")
        editorDisposable = disposable
        templateEditor = EditorTextField("", null, PlainTextFileType.INSTANCE).apply {
            setOneLineMode(false)
            setDisposedWith(disposable)
            addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
                override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                    if (!loading) {
                        saveTemplate(selectedTemplateIndex)
                        refreshPreview()
                    }
                }
            })
        }
        previewEditor = EditorTextField("", null, PlainTextFileType.INSTANCE).apply {
            setOneLineMode(false)
            setViewer(true)
            setDisposedWith(disposable)
        }
    }

    private fun displayTemplateName(template: CommitTemplateDefinition): String =
        if (template.builtIn) CommitMessageBundle.message("template.default.name") else template.name

    private fun displayTypeDescription(type: CommitTypeDefinition): String {
        val defaultDescription = DEFAULT_TYPE_DESCRIPTIONS[type.id]
        return if (defaultDescription != null && type.description == defaultDescription) {
            CommitMessageBundle.message("type.${type.id}")
        } else {
            type.description
        }
    }

    private fun templateErrorMessage(error: String): String =
        if (error == VelocityCommitTemplateRenderer.EMPTY_OUTPUT_ERROR) {
            CommitMessageBundle.message("error.template.empty")
        } else {
            CommitMessageBundle.message("error.template.invalid", error)
        }

    private fun buttons(vararg buttons: JButton): JPanel = JPanel(GridLayout(0, 1, 4, 4)).apply {
        buttons.forEach(::add)
    }

    private fun swingDocumentListener(changed: () -> Unit): DocumentListener = object : DocumentListener {
        override fun insertUpdate(event: DocumentEvent?) = changed()
        override fun removeUpdate(event: DocumentEvent?) = changed()
        override fun changedUpdate(event: DocumentEvent?) = changed()
    }

    private data class TemplateChoice(val id: String, val name: String) {
        override fun toString(): String = name
    }

    companion object {
        const val ID = "emohce.settings.commitMessage.templates"
        private const val DEFAULT_TEMPLATE_NAME = "Conventional Commit"
        private val DEFAULT_TYPE_DESCRIPTIONS = CommitMessageDefaults.types().associate { it.id to it.description }
    }
}
