package emohce.presentation.environmentaction

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableWithId
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import emohce.data.environmentaction.ENVIRONMENT_ACTION_SLOT_COUNT
import emohce.data.environmentaction.EnvironmentActionClassifier
import emohce.data.environmentaction.EnvironmentActionConflictResolution
import emohce.data.environmentaction.EnvironmentActionDefinition
import emohce.data.environmentaction.EnvironmentActionSettingsConflictException
import emohce.data.environmentaction.EnvironmentActionSettingsService
import emohce.data.environmentaction.EnvironmentActionSettingsState
import emohce.data.environmentaction.EnvironmentActionSecurity
import emohce.data.environmentaction.EnvironmentActionType
import emohce.data.environmentaction.EnvironmentDefinition
import emohce.environmentaction.EnvironmentActionsBundle
import java.awt.BorderLayout
import java.awt.Dimension
import java.util.function.Consumer
import java.util.function.Predicate
import javax.swing.DefaultCellEditor
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel
import javax.swing.table.DefaultTableCellRenderer

class EnvironmentActionsConfigurable(
    private val settingsService: EnvironmentActionSettingsService = EnvironmentActionSettingsService.getInstance(),
) : Configurable {
    private var root: JComponent? = null
    private val environments = DefaultListModel<EnvironmentDefinition>()
    private val environmentList = JBList(environments)
    private val nameField = JBTextField()
    private val directoryField = JBTextField()
    private val defaultEnvironmentField = JBCheckBox(EnvironmentActionsBundle.message("settings.environment.default"))
    private val variablesArea = JBTextArea(4, 40).apply {
        lineWrap = false
        toolTipText = EnvironmentActionsBundle.message("settings.environment.variables.help")
    }
    private val actionTableModel = object : DefaultTableModel(
        arrayOf(
            EnvironmentActionsBundle.message("settings.column.slot"),
            EnvironmentActionsBundle.message("settings.column.name"),
            EnvironmentActionsBundle.message("settings.column.type"),
            EnvironmentActionsBundle.message("settings.column.definition"),
            EnvironmentActionsBundle.message("settings.column.arguments"),
            EnvironmentActionsBundle.message("settings.column.enabled"),
            EnvironmentActionsBundle.message("settings.column.nonGit"),
            EnvironmentActionsBundle.message("settings.column.shortcut"),
        ),
        0,
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = column in 1..6
        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            0 -> Int::class.java
            5, 6 -> Boolean::class.java
            else -> String::class.java
        }
    }
    private val actionTable = JTable(actionTableModel)
    private var loaded = settingsService.snapshot()
    private var currentEnvironmentId: String? = null
    private var defaultEnvironmentId: String = loaded.defaultEnvironmentId

    init {
        environmentList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        environmentList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                saveVisibleEnvironment()
                showSelectedEnvironment()
            }
        }
        defaultEnvironmentField.addActionListener {
            if (defaultEnvironmentField.isSelected) {
                environmentList.selectedValue?.let { defaultEnvironmentId = it.id }
            }
        }
    }

    override fun getDisplayName(): String = EnvironmentActionsBundle.message("settings.title")

    override fun createComponent(): JComponent {
        reloadFrom(loaded)
        val environmentButtons = JPanel().apply {
            add(JButton(EnvironmentActionsBundle.message("settings.add")).apply { addActionListener { addEnvironment() } })
            add(JButton(EnvironmentActionsBundle.message("settings.remove")).apply { addActionListener { removeEnvironment() } })
        }
        val left = JPanel(BorderLayout()).apply {
            add(JBScrollPane(environmentList), BorderLayout.CENTER)
            add(environmentButtons, BorderLayout.SOUTH)
            preferredSize = Dimension(220, 420)
        }
        val actionTypes = arrayOf(
            EnvironmentActionType.SHELL,
            EnvironmentActionType.SCRIPT,
            EnvironmentActionType.CODEX,
            EnvironmentActionType.PREPARE_COMMIT,
        )
        val typeSelector = JComboBox(actionTypes).apply {
            val delegate = javax.swing.DefaultListCellRenderer()
            renderer = javax.swing.ListCellRenderer { list, value, index, selected, focused ->
                delegate.getListCellRendererComponent(
                    list,
                    actionTypeLabel(value),
                    index,
                    selected,
                    focused,
                )
            }
        }
        actionTable.columnModel.getColumn(2).cellEditor = DefaultCellEditor(typeSelector)
        actionTable.columnModel.getColumn(2).cellRenderer = object : DefaultTableCellRenderer() {
            override fun setValue(value: Any?) {
                super.setValue(actionTypeLabel(value as? EnvironmentActionType))
            }
        }
        val actionButtons = JPanel().apply {
            add(JButton(EnvironmentActionsBundle.message("settings.move.up")).apply { addActionListener { moveAction(-1) } })
            add(JButton(EnvironmentActionsBundle.message("settings.move.down")).apply { addActionListener { moveAction(1) } })
            add(JButton(EnvironmentActionsBundle.message("settings.detect")).apply { addActionListener { detectActionType() } })
            add(JButton(EnvironmentActionsBundle.message("settings.shortcuts")).apply { addActionListener { openKeymap() } })
        }
        val directoryPanel = JPanel(BorderLayout()).apply {
            add(directoryField, BorderLayout.CENTER)
            add(JButton(EnvironmentActionsBundle.message("settings.environment.directory.browse")).apply {
                addActionListener { chooseDirectory() }
            }, BorderLayout.EAST)
        }
        val right = panel {
            row(EnvironmentActionsBundle.message("settings.environment.name")) {
                cell(nameField).align(Align.FILL).resizableColumn()
            }
            row(EnvironmentActionsBundle.message("settings.environment.directory")) {
                cell(directoryPanel).align(Align.FILL).resizableColumn()
            }
            row { cell(defaultEnvironmentField) }
            row(EnvironmentActionsBundle.message("settings.environment.variables")) {
                cell(JBScrollPane(variablesArea)).align(Align.FILL).resizableColumn()
            }
            row { cell(JLabel(EnvironmentActionsBundle.message("settings.actions.help"))) }
            row { cell(JLabel(EnvironmentActionsBundle.message("settings.slots.help"))) }
            row {
                cell(JBScrollPane(actionTable)).align(Align.FILL).resizableColumn()
            }.resizableRow()
            row { cell(actionButtons) }
        }
        root = panel {
            row {
                cell(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply { resizeWeight = 0.25 })
                    .align(Align.FILL)
                    .resizableColumn()
            }.resizableRow()
        }
        return root!!
    }

    override fun isModified(): Boolean = snapshotFromUi() != loaded

    @Throws(ConfigurationException::class)
    override fun apply() {
        val updated = snapshotFromUi()
        val sensitiveVariables = updated.environments.flatMap { environment ->
            environment.variables.keys.filter(EnvironmentActionSecurity::isSensitiveVariableName)
        }.distinct()
        if (sensitiveVariables.isNotEmpty()) {
            throw ConfigurationException(
                EnvironmentActionsBundle.message("settings.environment.variables.secret", sensitiveVariables.joinToString(", ")),
            )
        }
        updated.normalize()
        try {
            loaded = settingsService
                .replaceState(loaded, updated)
                .copyForSnapshot()
        } catch (conflict: EnvironmentActionSettingsConflictException) {
            val choice = Messages.showDialog(
                EnvironmentActionsBundle.message(
                    "settings.concurrentChange.message",
                    conflict.conflictingPaths.take(5).joinToString("\n"),
                ),
                EnvironmentActionsBundle.message("settings.concurrentChange.title"),
                arrayOf(
                    EnvironmentActionsBundle.message("settings.concurrentChange.local"),
                    EnvironmentActionsBundle.message("settings.concurrentChange.remote"),
                    EnvironmentActionsBundle.message("settings.concurrentChange.cancel"),
                ),
                2,
                Messages.getWarningIcon(),
            )
            val resolution = when (choice) {
                0 -> EnvironmentActionConflictResolution.LOCAL
                1 -> EnvironmentActionConflictResolution.REMOTE
                else -> throw ConfigurationException(EnvironmentActionsBundle.message("settings.concurrentChange.cancelled"))
            }
            loaded = settingsService
                .replaceState(loaded, updated, resolution)
                .copyForSnapshot()
        }
        reloadFrom(loaded)
    }

    override fun reset() {
        loaded = settingsService.snapshot()
        reloadFrom(loaded)
    }

    override fun disposeUIResources() {
        root = null
    }

    private fun reloadFrom(state: EnvironmentActionSettingsState) {
        environments.clear()
        state.environments.map(EnvironmentDefinition::copyForSnapshot).forEach(environments::addElement)
        defaultEnvironmentId = state.defaultEnvironmentId
        val index = state.environments.indexOfFirst { it.id == defaultEnvironmentId }.takeIf { it >= 0 } ?: 0
        if (environments.size > 0) environmentList.selectedIndex = index else clearEnvironmentFields()
    }

    private fun showSelectedEnvironment() {
        val environment = environmentList.selectedValue ?: run {
            currentEnvironmentId = null
            clearEnvironmentFields()
            return
        }
        currentEnvironmentId = environment.id
        nameField.text = environment.name
        directoryField.text = environment.workingDirectory
        defaultEnvironmentField.isSelected = environment.id == defaultEnvironmentId
        variablesArea.text = environment.variables.entries.joinToString("\n") { (key, value) -> "$key=$value" }
        actionTableModel.rowCount = 0
        environment.orderedActions().forEach { action ->
            actionTableModel.addRow(
                arrayOf<Any>(
                    action.slot,
                    action.name,
                    action.type,
                    if (action.type == EnvironmentActionType.SCRIPT) action.scriptPath else action.command,
                    action.arguments,
                    action.enabled,
                    action.allowNonGitDirectory,
                    shortcutSummary(action.slot),
                ),
            )
        }
    }

    private fun clearEnvironmentFields() {
        nameField.text = ""
        directoryField.text = ""
        defaultEnvironmentField.isSelected = false
        variablesArea.text = ""
        actionTableModel.rowCount = 0
    }

    private fun addEnvironment() {
        saveVisibleEnvironment()
        val environment = EnvironmentDefinition(name = EnvironmentActionsBundle.message("settings.newEnvironment")).apply {
            actions = (1..ENVIRONMENT_ACTION_SLOT_COUNT).map { slot ->
                EnvironmentActionDefinition(
                    slot = slot,
                    name = EnvironmentActionsBundle.message("settings.defaultAction", slot),
                )
            }.toMutableList()
            actionOrder = (1..ENVIRONMENT_ACTION_SLOT_COUNT).toMutableList()
        }
        environments.addElement(environment)
        if (defaultEnvironmentId.isBlank()) defaultEnvironmentId = environment.id
        environmentList.selectedIndex = environments.size - 1
    }

    private fun removeEnvironment() {
        val index = environmentList.selectedIndex
        if (index < 0) return
        val selected = environmentList.selectedValue ?: return
        if (
            Messages.showYesNoDialog(
                EnvironmentActionsBundle.message("settings.remove.message", selected.name),
                EnvironmentActionsBundle.message("settings.remove.title"),
                null,
            ) != Messages.YES
        ) return
        environments.remove(index)
        if (selected.id == defaultEnvironmentId) defaultEnvironmentId = environments.elements().toList().firstOrNull()?.id.orEmpty()
        if (environments.size > 0) environmentList.selectedIndex = index.coerceAtMost(environments.size - 1)
    }

    private fun moveAction(delta: Int) {
        val selectedRow = actionTable.selectedRow
        val targetRow = selectedRow + delta
        if (selectedRow < 0 || targetRow !in 0 until actionTableModel.rowCount) return
        actionTableModel.moveRow(selectedRow, selectedRow, targetRow)
        actionTable.selectionModel.setSelectionInterval(targetRow, targetRow)
    }

    private fun detectActionType() {
        val row = actionTable.selectedRow
        if (row < 0) return
        val definition = actionTableModel.getValueAt(row, 3).toString()
        actionTableModel.setValueAt(EnvironmentActionClassifier.classify(definition), row, 2)
    }

    private fun saveVisibleEnvironment() {
        val id = currentEnvironmentId ?: return
        val item = (0 until environments.size).map(environments::get).firstOrNull { it.id == id } ?: return
        item.name = nameField.text
        item.workingDirectory = directoryField.text
        item.variables = parseVariables(variablesArea.text)
        item.actions = (0 until actionTableModel.rowCount).map { row ->
            val slot = actionTableModel.getValueAt(row, 0) as Int
            val type = (actionTableModel.getValueAt(row, 2) as? EnvironmentActionType)
                ?: runCatching { EnvironmentActionType.valueOf(actionTableModel.getValueAt(row, 2).toString()) }
                    .getOrDefault(EnvironmentActionType.SHELL)
            EnvironmentActionDefinition(
                id = item.actions.firstOrNull { it.slot == slot }?.id.orEmpty(),
                slot = slot,
                name = actionTableModel.getValueAt(row, 1).toString(),
                type = type,
                command = if (type == EnvironmentActionType.SCRIPT) "" else actionTableModel.getValueAt(row, 3).toString(),
                scriptPath = if (type == EnvironmentActionType.SCRIPT) actionTableModel.getValueAt(row, 3).toString() else "",
                arguments = actionTableModel.getValueAt(row, 4).toString(),
                enabled = actionTableModel.getValueAt(row, 5) as? Boolean ?: true,
                allowNonGitDirectory = type == EnvironmentActionType.CODEX &&
                    (actionTableModel.getValueAt(row, 6) as? Boolean ?: false),
            )
        }.toMutableList()
        item.actionOrder = item.actions.map(EnvironmentActionDefinition::slot).toMutableList()
    }

    private fun snapshotFromUi(): EnvironmentActionSettingsState {
        saveVisibleEnvironment()
        return loaded.copyForSnapshot().apply {
            environments = (0 until this@EnvironmentActionsConfigurable.environments.size).map { index ->
                this@EnvironmentActionsConfigurable.environments.get(index).copyForSnapshot()
            }.toMutableList()
            defaultEnvironmentId = this@EnvironmentActionsConfigurable.defaultEnvironmentId
        }
    }

    private fun parseVariables(raw: String): MutableMap<String, String> = raw.lineSequence()
        .mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 1)
        }
        .toMap(LinkedHashMap())

    private fun shortcutSummary(slot: Int): String {
        val actionId = "EzCodeMarks.EnvironmentAction.Slot$slot"
        val manager = KeymapManager.getInstance()
            ?: return EnvironmentActionsBundle.message("settings.shortcut.none")
        return KeymapUtil.getShortcutsText(manager.activeKeymap.getShortcuts(actionId))
            .ifBlank { EnvironmentActionsBundle.message("settings.shortcut.none") }
    }

    private fun openKeymap() {
        val slot = actionTable.selectedRow.takeIf { it >= 0 }
            ?.let { actionTableModel.getValueAt(it, 0) as Int }
            ?: 1
        val actionId = "EzCodeMarks.EnvironmentAction.Slot$slot"
        val actionName = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction(actionId)?.templateText ?: actionId
        ShowSettingsUtil.getInstance().showSettingsDialog(
            null,
            Predicate<Configurable> { (it as? ConfigurableWithId)?.id == "preferences.keymap" },
            Consumer<Configurable> { (it as? SearchableConfigurable)?.enableSearch(actionName)?.run() },
        )
    }

    private fun chooseDirectory() {
        val selected = FileChooser.chooseFile(
            FileChooserDescriptorFactory.singleDir(),
            null,
            null,
        ) ?: return
        directoryField.text = selected.path
    }

    private fun actionTypeLabel(type: EnvironmentActionType?): String = EnvironmentActionsBundle.message(
        when (type) {
            EnvironmentActionType.SHELL -> "action.type.shell"
            EnvironmentActionType.SCRIPT -> "action.type.script"
            EnvironmentActionType.CODEX -> "action.type.codex"
            EnvironmentActionType.PREPARE_COMMIT -> "action.type.prepareCommit"
            @Suppress("DEPRECATION")
            EnvironmentActionType.GIT_COMMIT -> "action.type.prepareCommit"
            null -> "action.type.shell"
        },
    )
}
