package emohce.presentation.commitmessage.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import emohce.data.commitmessage.CommitMessageAiService
import emohce.data.commitmessage.CommitMessageSettingsService
import emohce.data.commitmessage.CommitMessageSettingsConflictException
import emohce.data.commitmessage.MissingChatGptLoginException
import emohce.data.commitmessage.CommitProjectStateService
import emohce.data.commitmessage.CommitProjectSharedSettingsService
import emohce.data.commitmessage.VelocityCommitTemplateRenderer
import emohce.domain.commitmessage.CommitDraft
import emohce.domain.commitmessage.CommitMessageDefaults
import emohce.domain.commitmessage.CommitStyleDefinition
import emohce.domain.commitmessage.CommitTemplateDefinition
import emohce.domain.commitmessage.CommitTypeDefinition
import emohce.presentation.commitmessage.CommitMessageBundle
import emohce.presentation.commitmessage.dialog.CommitStyleProposalDialog
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import org.jetbrains.annotations.TestOnly

class CommitTemplatesConfigurable @JvmOverloads constructor(
    private val project: Project? = null,
) : SearchableConfigurable {
    private val renderer: VelocityCommitTemplateRenderer
        get() = ApplicationManager.getApplication().getService(VelocityCommitTemplateRenderer::class.java)
    private val projectState: CommitProjectStateService?
        get() = project?.takeIf { !it.isDisposed }?.let(CommitProjectStateService::getInstance)
    private val projectSharedState: CommitProjectSharedSettingsService?
        get() = project?.takeIf { !it.isDisposed }?.let(CommitProjectSharedSettingsService::getInstance)

    private var editorDisposable: Disposable? = null
    private var templates = mutableListOf<CommitTemplateDefinition>()
    private var types = mutableListOf<CommitTypeDefinition>()
    private var styles = mutableListOf<CommitStyleDefinition>()
    private var defaultTemplateId = CommitMessageDefaults.DEFAULT_TEMPLATE_ID
    private var defaultStyleId = CommitMessageDefaults.STANDARD_STYLE_ID
    private var projectTemplateId = ""
    private var projectStyleId = ""
    private var loadedProjectTemplateId = ""
    private var loadedProjectStyleId = ""
    private var loadedGlobalDefinitions = GlobalDefinitionFields()
    private var selectedTemplateIndex = -1
    private var selectedStyleIndex = -1
    private var loading = false
    private var settingsDisposed = false
    private var styleOptimizationGeneration = 0L
    private val styleOptimizationIndicator = AtomicReference<ProgressIndicator?>()
    private var root: JComponent? = null

    private val templateModel = DefaultListModel<CommitTemplateDefinition>()
    private val templateList = JBList(templateModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        accessibleContext.accessibleName = CommitMessageBundle.message("settings.templates.list")
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean,
            ): java.awt.Component = super.getListCellRendererComponent(list, value, index, selected, focused).also {
                (it as JLabel).text = (value as? CommitTemplateDefinition)?.let(::displayTemplateName).orEmpty()
            }
        }
    }
    private val globalTemplateCombo = ComboBox<TemplateChoice>()
    private val projectTemplateCombo = ComboBox<TemplateChoice>()
    private val templateHeading = JBLabel()
    private lateinit var templateEditor: EditorTextField
    private lateinit var previewEditor: EditorTextField
    private val templateValidationLabel = JBLabel()
    private val previewType = JBCheckBox(CommitMessageBundle.message("settings.preview.type"), true)
    private val previewScope = JBCheckBox(CommitMessageBundle.message("settings.preview.scope"), true)
    private val previewSubject = JBCheckBox(CommitMessageBundle.message("settings.preview.subject"), true)
    private val previewBody = JBCheckBox(CommitMessageBundle.message("settings.preview.body"), true)
    private val previewChanges = JBCheckBox(CommitMessageBundle.message("settings.preview.changes"), true)
    private val previewCloses = JBCheckBox(CommitMessageBundle.message("settings.preview.closes"), true)
    private val previewSkipCi = JBCheckBox(CommitMessageBundle.message("settings.preview.skipCi"), true)

    private val typeTableModel = TypeTableModel()
    private val typeTable = JBTable(typeTableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setShowGrid(true)
        accessibleContext.accessibleName = CommitMessageBundle.message("settings.types.list")
    }

    private val styleModel = DefaultListModel<CommitStyleDefinition>()
    private val styleList = JBList(styleModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        accessibleContext.accessibleName = CommitMessageBundle.message("settings.styles.list")
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean,
            ): java.awt.Component = super.getListCellRendererComponent(list, value, index, selected, focused).also {
                val style = value as? CommitStyleDefinition
                (it as JLabel).text = style?.let(::displayStyleName).orEmpty()
            }
        }
    }
    private val globalStyleCombo = ComboBox<StyleChoice>()
    private val projectStyleCombo = ComboBox<StyleChoice>()
    private val styleNameField = JBTextField()
    private val styleDescriptionField = JBTextArea(3, 50).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private lateinit var stylePromptEditor: EditorTextField
    private lateinit var styleTemplateEditor: EditorTextField
    private lateinit var stylePreviewEditor: EditorTextField
    private val styleValidationLabel = JBLabel()
    private val optimizeStyleButton = javax.swing.JButton(CommitMessageBundle.message("settings.styles.optimize"))

    init {
        templateList.addListSelectionListener {
            if (!it.valueIsAdjusting && !loading) {
                selectedTemplateIndex = templateList.selectedIndex
                loadSelectedTemplate()
            }
        }
        templateList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && event.button == MouseEvent.BUTTON1) renameTemplate()
            }
        })
        styleList.addListSelectionListener {
            if (!it.valueIsAdjusting && !loading) {
                selectedStyleIndex = styleList.selectedIndex
                loadSelectedStyle()
            }
        }
        typeTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && event.button == MouseEvent.BUTTON1) editType()
            }
        })
        globalTemplateCombo.addActionListener {
            if (!loading) defaultTemplateId = (globalTemplateCombo.selectedItem as? TemplateChoice)?.id
                ?: CommitMessageDefaults.DEFAULT_TEMPLATE_ID
        }
        projectTemplateCombo.addActionListener {
            if (!loading) projectTemplateId = (projectTemplateCombo.selectedItem as? TemplateChoice)?.id.orEmpty()
        }
        globalStyleCombo.addActionListener {
            if (!loading) defaultStyleId = (globalStyleCombo.selectedItem as? StyleChoice)?.id
                ?: CommitMessageDefaults.STANDARD_STYLE_ID
        }
        projectStyleCombo.addActionListener {
            if (!loading) projectStyleId = (projectStyleCombo.selectedItem as? StyleChoice)?.id.orEmpty()
        }
        styleNameField.document.addDocumentListener(swingDocumentListener(::saveSelectedStyle))
        styleDescriptionField.document.addDocumentListener(swingDocumentListener(::saveSelectedStyle))
        listOf(previewType, previewScope, previewSubject, previewBody, previewChanges, previewCloses, previewSkipCi)
            .forEach { it.addActionListener { refreshTemplatePreview() } }
        optimizeStyleButton.addActionListener { optimizeStyleWithAi() }
    }

    override fun getId(): String = ID

    override fun getDisplayName(): String = CommitMessageBundle.message("settings.templates.title")

    override fun createComponent(): JComponent {
        root?.let { return it }
        settingsDisposed = false
        initializeEditors()
        reset()
        return JBTabbedPane().apply {
            addTab(CommitMessageBundle.message("settings.templates.tab"), templatePanel())
            addTab(CommitMessageBundle.message("settings.types.tab"), typePanel())
            addTab(CommitMessageBundle.message("settings.styles.tab"), stylePanel())
            preferredSize = Dimension(1_100, 760)
        }.also { root = it }
    }

    override fun isModified(): Boolean {
        val currentProject = projectState?.state
        return workingGlobalDefinitions() != loadedGlobalDefinitions ||
            (currentProject != null && (
                projectTemplateId != loadedProjectTemplateId || projectStyleId != loadedProjectStyleId
            ))
    }

    override fun apply() {
        validateWorkingState()
        validateProjectDefaultsHaveNotChanged()
        val service = CommitMessageSettingsService.getInstance()
        val current = service.snapshot()
        val workingDefinitions = workingGlobalDefinitions()
        if (service.hasPortableConflict() ||
            (globalDefinitions(current) != loadedGlobalDefinitions && globalDefinitions(current) != workingDefinitions)
        ) {
            throw ConfigurationException(CommitMessageBundle.message("settings.portable.concurrentChange"))
        }
        val merged = current.apply {
            templates = this@CommitTemplatesConfigurable.templates.map { it.copy() }.toMutableList()
            types = this@CommitTemplatesConfigurable.types.map { it.copy() }.toMutableList()
            styles = this@CommitTemplatesConfigurable.styles.map { it.copy() }.toMutableList()
            defaultTemplateId = this@CommitTemplatesConfigurable.defaultTemplateId
            defaultStyleId = this@CommitTemplatesConfigurable.defaultStyleId
        }
        try {
            service.replaceState(merged)
        } catch (_: CommitMessageSettingsConflictException) {
            throw ConfigurationException(CommitMessageBundle.message("settings.portable.concurrentChange"))
        }
        projectState?.apply {
            if (projectTemplateId != loadedProjectTemplateId) setTemplateId(projectTemplateId)
            if (projectStyleId != loadedProjectStyleId) setStyleId(projectStyleId)
        }
        reset()
    }

    override fun reset() {
        styleOptimizationGeneration += 1
        styleOptimizationIndicator.getAndSet(null)?.cancel()
        val state = CommitMessageSettingsService.getInstance().snapshot()
        templates = state.templates.map { it.copy() }.toMutableList()
        types = state.types.map { it.copy() }.toMutableList()
        styles = state.styles.map { it.copy() }.toMutableList()
        defaultTemplateId = state.defaultTemplateId
        defaultStyleId = state.defaultStyleId
        loadedGlobalDefinitions = globalDefinitions(state)
        projectTemplateId = projectState?.state?.templateId.orEmpty()
        projectStyleId = projectState?.state?.styleId.orEmpty()
        loadedProjectTemplateId = projectTemplateId
        loadedProjectStyleId = projectStyleId
        if (::templateEditor.isInitialized) rebuildUiModels()
    }

    override fun disposeUIResources() {
        settingsDisposed = true
        styleOptimizationGeneration += 1
        styleOptimizationIndicator.getAndSet(null)?.cancel()
        root = null
        editorDisposable?.let(Disposer::dispose)
        editorDisposable = null
    }

    @TestOnly
    internal fun moveSelectedTypeForTest(delta: Int) = moveType(delta)

    private fun templatePanel(): JComponent {
        val templateDirectory = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = false
            add(JBLabel(CommitMessageBundle.message("settings.templates.list")), BorderLayout.NORTH)
            add(
                ToolbarDecorator.createDecorator(templateList)
                    .setAddAction { addTemplate() }
                    .setRemoveAction { removeTemplate() }
                    .setEditAction { renameTemplate() }
                    .disableUpDownActions()
                    .addExtraAction(object : DumbAwareAction(
                        CommitMessageBundle.message("settings.templates.copy"),
                        null,
                        AllIcons.Actions.Copy,
                    ) {
                        override fun actionPerformed(event: AnActionEvent) = copyTemplate()
                    })
                    .createPanel(),
                BorderLayout.CENTER,
            )
        }
        val variableDescription = JBTextArea(CommitMessageBundle.message("settings.templates.variables")).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            background = UIUtil.getPanelBackground()
            rows = 3
        }
        val right = panel {
            row {
                cell(templateHeading).align(Align.FILL).resizableColumn()
                button(CommitMessageBundle.message("settings.templates.restore")) { restoreTemplate() }
            }
            row {
                cell(templateEditor).align(Align.FILL).resizableColumn()
            }.resizableRow()
            row { cell(templateValidationLabel).align(Align.FILL) }
            row(CommitMessageBundle.message("settings.templates.preview")) {
                cell(previewType); cell(previewScope); cell(previewSubject); cell(previewBody)
                cell(previewChanges); cell(previewCloses); cell(previewSkipCi)
            }
            row {
                cell(previewEditor).align(Align.FILL).resizableColumn()
            }.resizableRow()
            row(CommitMessageBundle.message("settings.templates.description")) {
                scrollCell(variableDescription).align(Align.FILL).resizableColumn()
            }
        }
        val splitter = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, templateDirectory, right).apply {
            resizeWeight = 0.29
            dividerLocation = JBUI.scale(290)
            border = null
        }
        val header = panel {
            row { comment(CommitMessageBundle.message("settings.templates.intro")) }
            row {
                label(CommitMessageBundle.message("settings.templates.default"))
                cell(globalTemplateCombo)
                label(CommitMessageBundle.message("settings.templates.projectDefault"))
                cell(projectTemplateCombo)
            }
        }
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        }
    }

    private fun typePanel(): JComponent {
        val decorated = ToolbarDecorator.createDecorator(typeTable)
            .setAddAction { addType() }
            .setRemoveAction { removeType() }
            .setEditAction { editType() }
            .setMoveUpAction { moveType(-1) }
            .setMoveDownAction { moveType(1) }
            .addExtraAction(object : DumbAwareAction(
                CommitMessageBundle.message("settings.types.restore"),
                null,
                AllIcons.Actions.Rollback,
            ) {
                override fun actionPerformed(event: AnActionEvent) = restoreTypes()
            })
            .createPanel()
        typeTable.columnModel.getColumn(0).apply {
            preferredWidth = JBUI.scale(180)
            minWidth = JBUI.scale(150)
            maxWidth = JBUI.scale(250)
        }
        typeTable.columnModel.getColumn(1).apply {
            preferredWidth = JBUI.scale(700)
            minWidth = JBUI.scale(550)
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(decorated, BorderLayout.CENTER)
        }
    }

    private fun stylePanel(): JComponent {
        val styleDirectory = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = false
            add(JBLabel(CommitMessageBundle.message("settings.styles.list")), BorderLayout.NORTH)
            add(
                ToolbarDecorator.createDecorator(styleList)
                    .setAddAction { addStyle() }
                    .setRemoveAction { removeStyle() }
                    .disableUpDownActions()
                    .addExtraAction(object : DumbAwareAction(
                        CommitMessageBundle.message("settings.styles.copy"),
                        null,
                        AllIcons.Actions.Copy,
                    ) {
                        override fun actionPerformed(event: AnActionEvent) = copyStyle()
                    })
                    .createPanel(),
                BorderLayout.CENTER,
            )
        }
        val right = panel {
            row(CommitMessageBundle.message("settings.styles.name")) {
                cell(styleNameField).align(Align.FILL).resizableColumn()
            }
            row(CommitMessageBundle.message("settings.styles.description")) {
                scrollCell(styleDescriptionField).align(Align.FILL).resizableColumn()
            }
            row(CommitMessageBundle.message("settings.styles.prompt")) {
                cell(stylePromptEditor).align(Align.FILL).resizableColumn()
            }.resizableRow()
            row(CommitMessageBundle.message("settings.styles.template")) {
                cell(styleTemplateEditor).align(Align.FILL).resizableColumn()
            }.resizableRow()
            row(CommitMessageBundle.message("settings.styles.preview")) {
                cell(stylePreviewEditor).align(Align.FILL).resizableColumn()
            }.resizableRow()
            row {
                cell(optimizeStyleButton)
                cell(styleValidationLabel).align(Align.FILL).resizableColumn()
            }
        }
        val splitter = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, styleDirectory, right).apply {
            resizeWeight = 0.29
            dividerLocation = JBUI.scale(290)
            border = null
        }
        val header = panel {
            row { comment(CommitMessageBundle.message("settings.styles.intro")) }
            row {
                label(CommitMessageBundle.message("settings.styles.globalDefault"))
                cell(globalStyleCombo)
                label(CommitMessageBundle.message("settings.styles.projectDefault"))
                cell(projectStyleCombo)
            }
        }
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        }
    }

    private fun initializeEditors() {
        val disposable = Disposer.newDisposable("EzCodeMarks commit template and style settings")
        editorDisposable = disposable
        templateEditor = multiLineEditor(disposable) {
            selectedTemplate()?.content = templateEditor.text
            refreshTemplatePreview()
        }
        previewEditor = multiLineEditor(disposable, viewer = true) {}
        stylePromptEditor = multiLineEditor(disposable) { saveSelectedStyle() }
        styleTemplateEditor = multiLineEditor(disposable) {
            saveSelectedStyle()
            refreshStyleValidation()
        }
        stylePreviewEditor = multiLineEditor(disposable, viewer = true) {}
    }

    private fun multiLineEditor(
        disposable: Disposable,
        viewer: Boolean = false,
        changed: () -> Unit,
    ): EditorTextField = EditorTextField("", project, PlainTextFileType.INSTANCE).apply {
        setOneLineMode(false)
        setViewer(viewer)
        setDisposedWith(disposable)
        addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                if (!loading) changed()
            }
        })
    }

    private fun rebuildUiModels() {
        loading = true
        try {
            templateModel.clear()
            templates.forEach(templateModel::addElement)
            styleModel.clear()
            styles.forEach(styleModel::addElement)
            typeTableModel.fireTableDataChanged()
            refreshTemplateChoices()
            refreshStyleChoices()
            selectedTemplateIndex = if (templates.isEmpty()) -1 else 0
            selectedStyleIndex = if (styles.isEmpty()) -1 else 0
            templateList.selectedIndex = selectedTemplateIndex
            styleList.selectedIndex = selectedStyleIndex
            loadSelectedTemplate()
            loadSelectedStyle()
        } finally {
            loading = false
        }
    }

    private fun loadSelectedTemplate() {
        loading = true
        try {
            val template = selectedTemplate()
            templateHeading.text = CommitMessageBundle.message(
                "settings.templates.setting",
                template?.let(::displayTemplateName).orEmpty(),
            )
            templateEditor.text = template?.content.orEmpty()
            templateEditor.isEnabled = template != null
            refreshTemplatePreview()
        } finally {
            loading = false
        }
    }

    private fun refreshTemplatePreview() {
        if (!::templateEditor.isInitialized) return
        val template = selectedTemplate() ?: return
        template.content = templateEditor.text
        val validation = renderer.validate(template)
        templateValidationLabel.text = if (validation.valid) {
            CommitMessageBundle.message("settings.templates.validation.valid")
        } else {
            templateErrorMessage(validation.error)
        }
        templateValidationLabel.foreground = if (validation.valid) JBColor(0x2E7D32, 0x7CB342) else JBColor.RED
        previewEditor.text = if (validation.valid) {
            renderer.render(
                template,
                CommitDraft(
                    type = if (previewType.isSelected) "feat" else "",
                    scope = if (previewScope.isSelected) "scope" else "",
                    subject = if (previewSubject.isSelected) "<subject>" else "",
                    body = if (previewBody.isSelected) "<body>" else "",
                    breakingChanges = if (previewChanges.isSelected) "<changes>" else "",
                    closes = if (previewCloses.isSelected) "<closed>" else "",
                    skipCi = previewSkipCi.isSelected,
                ),
            )
        } else {
            ""
        }
    }

    private fun addTemplate() {
        val template = CommitTemplateDefinition(
            id = UUID.randomUUID().toString(),
            name = uniqueTemplateName(CommitMessageBundle.message("settings.templates.newName")),
            content = CommitMessageDefaults.defaultTemplateContent,
        )
        templates += template
        templateModel.addElement(template)
        selectTemplate(templates.lastIndex)
        refreshTemplateChoices()
    }

    private fun copyTemplate() {
        val selected = selectedTemplate() ?: return
        val copy = selected.copy(
            id = UUID.randomUUID().toString(),
            name = uniqueTemplateName(CommitMessageBundle.message("common.copyName", displayTemplateName(selected))),
            builtIn = false,
        )
        templates += copy
        templateModel.addElement(copy)
        selectTemplate(templates.lastIndex)
        refreshTemplateChoices()
    }

    private fun removeTemplate() {
        val selected = selectedTemplate() ?: return
        if (selected.builtIn) {
            Messages.showWarningDialog(
                project,
                CommitMessageBundle.message("settings.templates.builtInLocked"),
                CommitMessageBundle.message("settings.templates.title"),
            )
            return
        }
        val index = selectedTemplateIndex
        templates.removeAt(index)
        templateModel.remove(index)
        if (defaultTemplateId == selected.id) defaultTemplateId = CommitMessageDefaults.DEFAULT_TEMPLATE_ID
        if (projectTemplateId == selected.id) projectTemplateId = ""
        selectTemplate(index.coerceAtMost(templates.lastIndex))
        refreshTemplateChoices()
    }

    private fun renameTemplate() {
        val selected = selectedTemplate() ?: return
        if (selected.builtIn) {
            Messages.showWarningDialog(
                project,
                CommitMessageBundle.message("settings.templates.builtInLocked"),
                CommitMessageBundle.message("settings.templates.title"),
            )
            return
        }
        val value = Messages.showInputDialog(
            project,
            CommitMessageBundle.message("settings.templates.renamePrompt"),
            CommitMessageBundle.message("settings.templates.rename"),
            Messages.getQuestionIcon(),
            selected.name,
            null,
        )?.trim().orEmpty()
        if (value.isBlank()) return
        selected.name = uniqueTemplateName(value, selected.id)
        templateList.repaint()
        refreshTemplateChoices()
        loadSelectedTemplate()
    }

    private fun restoreTemplate() {
        val selected = selectedTemplate() ?: return
        selected.content = CommitMessageDefaults.defaultTemplateContent
        templateEditor.text = selected.content
        refreshTemplatePreview()
    }

    private fun addType() {
        val dialog = CommitTypeDialog(project, CommitTypeDefinition("", ""), CommitMessageBundle.message("settings.types.add"))
        if (!dialog.showAndGet()) return
        types += dialog.result
        typeTableModel.fireTableDataChanged()
        selectType(types.lastIndex)
    }

    private fun editType() {
        val index = typeTable.selectedRow
        val selected = types.getOrNull(index) ?: return
        val dialog = CommitTypeDialog(
            project,
            selected.copy(description = displayTypeDescription(selected)),
            CommitMessageBundle.message("settings.types.edit"),
        )
        if (!dialog.showAndGet()) return
        types[index] = dialog.result
        typeTableModel.fireTableRowsUpdated(index, index)
        selectType(index)
    }

    private fun removeType() {
        val index = typeTable.selectedRow
        if (types.size <= 1 || index !in types.indices) return
        types.removeAt(index)
        typeTableModel.fireTableDataChanged()
        selectType(index.coerceAtMost(types.lastIndex))
    }

    private fun moveType(delta: Int) {
        val index = typeTable.selectedRow
        val target = index + delta
        if (index !in types.indices || target !in types.indices) return
        val type = types.removeAt(index)
        types.add(target, type)
        typeTableModel.fireTableDataChanged()
        selectType(target)
    }

    private fun restoreTypes() {
        types = CommitMessageDefaults.types()
        typeTableModel.fireTableDataChanged()
        selectType(0)
    }

    private fun loadSelectedStyle() {
        loading = true
        try {
            val style = selectedStyle()
            styleNameField.text = style?.let(::displayStyleName).orEmpty()
            styleDescriptionField.text = style?.let(::displayStyleDescription).orEmpty()
            stylePromptEditor.text = style?.prompt.orEmpty()
            styleTemplateEditor.text = style?.templateContent.orEmpty()
            val editable = style != null && !style.builtIn
            styleNameField.isEnabled = editable
            styleDescriptionField.isEnabled = editable
            stylePromptEditor.isEnabled = editable
            styleTemplateEditor.isEnabled = editable
            optimizeStyleButton.isEnabled = editable && project != null
            refreshStyleValidation()
        } finally {
            loading = false
        }
    }

    private fun saveSelectedStyle() {
        if (loading) return
        val style = selectedStyle()?.takeIf { !it.builtIn } ?: return
        style.name = styleNameField.text.trim()
        style.description = styleDescriptionField.text.trim()
        style.prompt = stylePromptEditor.text.trim()
        style.templateContent = styleTemplateEditor.text.trim()
        styleList.repaint()
        refreshStyleChoices()
    }

    private fun refreshStyleValidation() {
        if (!::styleTemplateEditor.isInitialized) return
        val style = selectedStyle()
        val content = style?.templateContent.orEmpty()
        val effectiveContent = content.ifBlank {
            templates.firstOrNull { it.id == defaultTemplateId }?.content
                ?: CommitMessageDefaults.defaultTemplateContent
        }
        val effectiveTemplate = CommitTemplateDefinition("style-preview", "Style preview", effectiveContent)
        val validation = if (style == null) null else renderer.validate(effectiveTemplate)
        styleValidationLabel.text = when {
            style == null -> ""
            style.builtIn -> CommitMessageBundle.message("settings.styles.builtInHint")
            content.isBlank() -> CommitMessageBundle.message("settings.styles.templateFallback")
            validation?.valid == true -> CommitMessageBundle.message("settings.templates.validation.valid")
            else -> templateErrorMessage(validation?.error.orEmpty())
        }
        styleValidationLabel.foreground = if (validation == null || validation.valid) {
            UIUtil.getContextHelpForeground()
        } else {
            JBColor.RED
        }
        if (::stylePreviewEditor.isInitialized) {
            stylePreviewEditor.text = if (validation?.valid == true) {
                renderer.render(
                    effectiveTemplate,
                    CommitDraft(
                        type = "feat",
                        scope = "scope",
                        subject = "<subject>",
                        body = "<body>",
                        breakingChanges = "<changes>",
                        closes = "<closed>",
                        skipCi = true,
                    ),
                )
            } else {
                ""
            }
        }
    }

    private fun addStyle() {
        val style = CommitStyleDefinition(
            id = UUID.randomUUID().toString(),
            name = uniqueStyleName(CommitMessageBundle.message("settings.styles.newName")),
        )
        styles += style
        styleModel.addElement(style)
        selectStyle(styles.lastIndex)
        refreshStyleChoices()
    }

    private fun copyStyle() {
        val selected = selectedStyle() ?: return
        val copy = selected.copy(
            id = UUID.randomUUID().toString(),
            name = uniqueStyleName(CommitMessageBundle.message("common.copyName", displayStyleName(selected))),
            builtIn = false,
        )
        styles += copy
        styleModel.addElement(copy)
        selectStyle(styles.lastIndex)
        refreshStyleChoices()
    }

    private fun removeStyle() {
        val selected = selectedStyle() ?: return
        if (selected.builtIn) return
        val index = selectedStyleIndex
        styles.removeAt(index)
        styleModel.remove(index)
        if (defaultStyleId == selected.id) defaultStyleId = CommitMessageDefaults.STANDARD_STYLE_ID
        if (projectStyleId == selected.id) projectStyleId = ""
        selectStyle(index.coerceAtMost(styles.lastIndex))
        refreshStyleChoices()
    }

    private fun optimizeStyleWithAi() {
        saveSelectedStyle()
        val currentProject = project ?: return
        val style = selectedStyle()?.takeIf { !it.builtIn } ?: return
        if (style.description.isBlank()) {
            Messages.showWarningDialog(
                currentProject,
                CommitMessageBundle.message("settings.styles.descriptionRequired"),
                CommitMessageBundle.message("settings.styles.optimize"),
            )
            return
        }
        val service = CommitMessageSettingsService.getInstance()
        val privateState = projectState ?: return
        val sharedState = projectSharedState ?: return
        val profile = privateState.resolveProfileSnapshot(service)
        if (profile == null || profile.model.isBlank()) {
            Messages.showWarningDialog(
                currentProject,
                CommitMessageBundle.message("error.profile.missing"),
                CommitMessageBundle.message("settings.styles.optimize"),
            )
            return
        }
        val styleTemplate = CommitTemplateDefinition("style-base", style.name, style.templateContent)
        val baseTemplate = if (style.templateContent.isNotBlank() && renderer.validate(styleTemplate).valid) {
            styleTemplate
        } else {
            privateState.resolveValidTemplate(service, sharedState, renderer)
        }
        val styleSnapshot = style.copy()
        val styleId = style.id
        val generation = ++styleOptimizationGeneration
        optimizeStyleButton.isEnabled = false
        object : Task.Backgroundable(
            currentProject,
            CommitMessageBundle.message("progress.styleOptimize"),
            true,
        ) {
            private var proposal: emohce.domain.commitmessage.CommitStyleProposal? = null

            override fun run(indicator: ProgressIndicator) {
                styleOptimizationIndicator.set(indicator)
                try {
                    if (settingsDisposed || generation != styleOptimizationGeneration) indicator.cancel()
                    indicator.checkCanceled()
                    proposal = CommitMessageAiService.getInstance(currentProject).generateStyleProposal(
                        profile,
                        style.description,
                        style.prompt,
                        baseTemplate,
                        indicator,
                    )
                } finally {
                    styleOptimizationIndicator.compareAndSet(indicator, null)
                }
            }

            override fun onSuccess() {
                val generated = proposal ?: return
                val target = styles.firstOrNull { it.id == styleId }
                if (settingsDisposed || root == null || generation != styleOptimizationGeneration ||
                    selectedStyle()?.id != styleId || target != styleSnapshot
                ) {
                    if (!settingsDisposed && root != null && generation == styleOptimizationGeneration) {
                        Messages.showMessageDialog(
                            currentProject,
                            CommitMessageBundle.message("settings.styles.resultStale"),
                            CommitMessageBundle.message("settings.styles.optimize"),
                            Messages.getInformationIcon(),
                        )
                    }
                    return
                }
                val dialog = CommitStyleProposalDialog(currentProject, generated)
                if (!dialog.showAndGet()) return
                val accepted = dialog.result
                if (accepted.prompt.length > MAX_STYLE_PROMPT_LENGTH ||
                    accepted.template.length > MAX_STYLE_TEMPLATE_LENGTH
                ) {
                    Messages.showErrorDialog(
                        currentProject,
                        CommitMessageBundle.message("settings.styles.tooLong"),
                        CommitMessageBundle.message("dialog.styleProposal.title"),
                    )
                    return
                }
                val validation = renderer.validate(
                    CommitTemplateDefinition("style-proposal", "Style proposal", accepted.template),
                )
                if (!validation.valid) {
                    Messages.showErrorDialog(
                        currentProject,
                        templateErrorMessage(validation.error),
                        CommitMessageBundle.message("dialog.styleProposal.title"),
                    )
                    return
                }
                target.prompt = accepted.prompt
                target.templateContent = accepted.template
                if (selectedStyle()?.id == styleId) loadSelectedStyle()
            }

            override fun onThrowable(error: Throwable) {
                if (settingsDisposed || root == null || generation != styleOptimizationGeneration) return
                val message = if (error is MissingChatGptLoginException) {
                    CommitMessageBundle.message("error.chatgpt.loginRequired")
                } else {
                    error.message?.take(400).orEmpty().ifBlank {
                        CommitMessageBundle.message("error.provider.response")
                    }
                }
                Messages.showErrorDialog(
                    currentProject,
                    message,
                    CommitMessageBundle.message("settings.styles.optimize"),
                )
            }

            override fun onFinished() {
                if (!settingsDisposed && root != null && generation == styleOptimizationGeneration) {
                    optimizeStyleButton.isEnabled = selectedStyle()?.builtIn == false
                }
            }
        }.queue()
    }

    private fun validateWorkingState() {
        val invalidTemplate = templates.firstOrNull { !renderer.validate(it).valid }
        if (invalidTemplate != null) throw ConfigurationException(
            templateErrorMessage(renderer.validate(invalidTemplate).error),
        )
        val invalidStyle = styles.firstOrNull { style ->
            style.templateContent.isNotBlank() && !renderer.validate(
                CommitTemplateDefinition("style-validation", style.name, style.templateContent),
            ).valid
        }
        if (invalidStyle != null) throw ConfigurationException(
            CommitMessageBundle.message("settings.styles.invalidTemplate", invalidStyle.name),
        )
        if (templates.map { it.id }.toSet().size != templates.size ||
            templates.any { it.id.isBlank() || it.name.isBlank() } ||
            types.map { it.id.lowercase() }.toSet().size != types.size ||
            types.any { it.id.isBlank() } ||
            styles.map { it.id }.toSet().size != styles.size ||
            styles.any { it.id.isBlank() || it.name.isBlank() } ||
            styles.any { it.prompt.length > MAX_STYLE_PROMPT_LENGTH || it.templateContent.length > MAX_STYLE_TEMPLATE_LENGTH }
        ) {
            throw ConfigurationException(CommitMessageBundle.message("settings.validation.unique"))
        }
    }

    private fun validateProjectDefaultsHaveNotChanged() {
        val current = projectState?.state ?: return
        val templateConflict = projectTemplateId != loadedProjectTemplateId &&
            current.templateId != loadedProjectTemplateId && current.templateId != projectTemplateId
        val styleConflict = projectStyleId != loadedProjectStyleId &&
            current.styleId != loadedProjectStyleId && current.styleId != projectStyleId
        if (templateConflict || styleConflict) {
            throw ConfigurationException(CommitMessageBundle.message("settings.project.concurrentChange"))
        }
    }

    private fun refreshTemplateChoices() {
        val requestedDefaultId = defaultTemplateId
        val requestedProjectId = projectTemplateId
        val previousLoading = loading
        loading = true
        try {
        val globalChoices = templates.map { TemplateChoice(it.id, displayTemplateName(it)) }.toTypedArray()
        globalTemplateCombo.model = DefaultComboBoxModel(globalChoices)
        globalTemplateCombo.selectedItem = globalChoices.firstOrNull { it.id == requestedDefaultId }
            ?: globalChoices.firstOrNull()
        defaultTemplateId = (globalTemplateCombo.selectedItem as? TemplateChoice)?.id
            ?: CommitMessageDefaults.DEFAULT_TEMPLATE_ID

        val projectChoices = buildList {
            add(TemplateChoice("", CommitMessageBundle.message("settings.project.inheritDefault")))
            addAll(globalChoices)
            projectSharedState?.state?.templates
                ?.filterNot { candidate -> templates.any { it.id == candidate.id } }
                ?.forEach {
                    add(TemplateChoice(it.id, CommitMessageBundle.message("settings.project.shared.choice", it.name)))
                }
            if (requestedProjectId.isNotBlank() && none { it.id == requestedProjectId }) {
                add(TemplateChoice(requestedProjectId, CommitMessageBundle.message("settings.project.unavailable.choice", requestedProjectId)))
            }
        }.toTypedArray()
        projectTemplateCombo.model = DefaultComboBoxModel(projectChoices)
        projectTemplateCombo.selectedItem = projectChoices.firstOrNull { it.id == requestedProjectId }
            ?: projectChoices.first()
        projectTemplateId = (projectTemplateCombo.selectedItem as? TemplateChoice)?.id.orEmpty()
        projectTemplateCombo.isEnabled = projectState != null
        } finally {
            loading = previousLoading
        }
    }

    private fun refreshStyleChoices() {
        val requestedDefaultId = defaultStyleId
        val requestedProjectId = projectStyleId
        val previousLoading = loading
        loading = true
        try {
        val globalChoices = styles.map { StyleChoice(it.id, displayStyleName(it)) }.toTypedArray()
        globalStyleCombo.model = DefaultComboBoxModel(globalChoices)
        globalStyleCombo.selectedItem = globalChoices.firstOrNull { it.id == requestedDefaultId }
            ?: globalChoices.firstOrNull()
        defaultStyleId = (globalStyleCombo.selectedItem as? StyleChoice)?.id
            ?: CommitMessageDefaults.STANDARD_STYLE_ID

        val projectChoices = buildList {
            add(StyleChoice("", CommitMessageBundle.message("settings.project.inheritDefault")))
            addAll(globalChoices)
            projectSharedState?.state?.styles
                ?.filterNot { candidate -> styles.any { it.id == candidate.id } }
                ?.forEach {
                    add(StyleChoice(it.id, CommitMessageBundle.message("settings.project.shared.choice", it.name)))
                }
            if (requestedProjectId.isNotBlank() && none { it.id == requestedProjectId }) {
                add(StyleChoice(requestedProjectId, CommitMessageBundle.message("settings.project.unavailable.choice", requestedProjectId)))
            }
        }.toTypedArray()
        projectStyleCombo.model = DefaultComboBoxModel(projectChoices)
        projectStyleCombo.selectedItem = projectChoices.firstOrNull { it.id == requestedProjectId }
            ?: projectChoices.first()
        projectStyleId = (projectStyleCombo.selectedItem as? StyleChoice)?.id.orEmpty()
        projectStyleCombo.isEnabled = projectState != null
        } finally {
            loading = previousLoading
        }
    }

    private fun selectedTemplate(): CommitTemplateDefinition? = templates.getOrNull(selectedTemplateIndex)

    private fun selectedStyle(): CommitStyleDefinition? = styles.getOrNull(selectedStyleIndex)

    private fun workingGlobalDefinitions(): GlobalDefinitionFields = GlobalDefinitionFields(
        templates = templates.map { it.copy() },
        types = types.map { it.copy() },
        styles = styles.map { it.copy() },
        defaultTemplateId = defaultTemplateId,
        defaultStyleId = defaultStyleId,
    )

    private fun globalDefinitions(state: emohce.data.commitmessage.CommitMessageSettingsState): GlobalDefinitionFields =
        GlobalDefinitionFields(
            templates = state.templates.map { it.copy() },
            types = state.types.map { it.copy() },
            styles = state.styles.map { it.copy() },
            defaultTemplateId = state.defaultTemplateId,
            defaultStyleId = state.defaultStyleId,
        )

    private fun selectTemplate(index: Int) {
        selectedTemplateIndex = index
        templateList.selectedIndex = index
        loadSelectedTemplate()
    }

    private fun selectStyle(index: Int) {
        selectedStyleIndex = index
        styleList.selectedIndex = index
        loadSelectedStyle()
    }

    private fun selectType(index: Int) {
        if (index >= 0) {
            typeTable.selectionModel.setSelectionInterval(index, index)
            typeTable.scrollRectToVisible(typeTable.getCellRect(index, 0, true))
        }
    }

    private fun uniqueTemplateName(base: String, ignoredId: String? = null): String = uniqueName(
        base,
        templates.filterNot { it.id == ignoredId }.map { it.name },
    )

    private fun uniqueStyleName(base: String): String = uniqueName(base, styles.map { it.name })

    private fun uniqueName(base: String, existing: List<String>): String {
        if (base !in existing) return base
        var suffix = 2
        while ("$base $suffix" in existing) suffix += 1
        return "$base $suffix"
    }

    private fun displayTemplateName(template: CommitTemplateDefinition): String =
        if (template.builtIn) CommitMessageBundle.message("template.default.name") else template.name

    private fun displayStyleName(style: CommitStyleDefinition): String = when (style.id) {
        CommitMessageDefaults.STANDARD_STYLE_ID -> CommitMessageBundle.message("style.standard.name")
        CommitMessageDefaults.CONCISE_STYLE_ID -> CommitMessageBundle.message("style.concise.name")
        else -> style.name
    }

    private fun displayStyleDescription(style: CommitStyleDefinition): String = when (style.id) {
        CommitMessageDefaults.STANDARD_STYLE_ID -> CommitMessageBundle.message("style.standard.description")
        CommitMessageDefaults.CONCISE_STYLE_ID -> CommitMessageBundle.message("style.concise.description")
        else -> style.description
    }

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

    private fun swingDocumentListener(changed: () -> Unit): DocumentListener = object : DocumentListener {
        override fun insertUpdate(event: DocumentEvent?) = changed()
        override fun removeUpdate(event: DocumentEvent?) = changed()
        override fun changedUpdate(event: DocumentEvent?) = changed()
    }

    private inner class TypeTableModel : AbstractTableModel() {
        override fun getRowCount(): Int = types.size
        override fun getColumnCount(): Int = 2
        override fun getColumnName(column: Int): String = CommitMessageBundle.message(
            if (column == 0) "settings.types.column.title" else "settings.types.column.description",
        )
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = if (columnIndex == 0) {
            types[rowIndex].id
        } else {
            displayTypeDescription(types[rowIndex])
        }
    }

    private data class TemplateChoice(val id: String, val name: String) {
        override fun toString(): String = name
    }

    private data class StyleChoice(val id: String, val name: String) {
        override fun toString(): String = name
    }

    private data class GlobalDefinitionFields(
        val templates: List<CommitTemplateDefinition> = emptyList(),
        val types: List<CommitTypeDefinition> = emptyList(),
        val styles: List<CommitStyleDefinition> = emptyList(),
        val defaultTemplateId: String = CommitMessageDefaults.DEFAULT_TEMPLATE_ID,
        val defaultStyleId: String = CommitMessageDefaults.STANDARD_STYLE_ID,
    )

    companion object {
        const val ID = "emohce.settings.commitMessage.templates"
        private const val MAX_STYLE_PROMPT_LENGTH = 4_000
        private const val MAX_STYLE_TEMPLATE_LENGTH = 8_000
        private val DEFAULT_TYPE_DESCRIPTIONS = CommitMessageDefaults.types().associate { it.id to it.description }
    }
}

private class CommitTypeDialog(
    project: Project?,
    initial: CommitTypeDefinition,
    dialogTitle: String,
) : DialogWrapper(project) {
    private val titleField = JBTextField(initial.id)
    private val descriptionField = JBTextField(initial.description)

    init {
        title = dialogTitle
        init()
    }

    val result: CommitTypeDefinition
        get() = CommitTypeDefinition(titleField.text.trim().lowercase(), descriptionField.text.trim())

    override fun createCenterPanel(): JComponent = panel {
        row(CommitMessageBundle.message("settings.types.column.title")) {
            cell(titleField).align(Align.FILL).resizableColumn()
        }
        row(CommitMessageBundle.message("settings.types.column.description")) {
            cell(descriptionField).align(Align.FILL).resizableColumn()
        }
    }.apply { preferredSize = Dimension(620, preferredSize.height) }

    override fun doValidate(): ValidationInfo? = if (titleField.text.trim().isBlank()) {
        ValidationInfo(CommitMessageBundle.message("settings.types.titleRequired"), titleField)
    } else {
        null
    }
}
