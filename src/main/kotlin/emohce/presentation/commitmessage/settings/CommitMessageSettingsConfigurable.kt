package emohce.presentation.commitmessage.settings

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableWithId
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import emohce.data.commitmessage.CommitMessageSettingsService
import emohce.data.commitmessage.CommitMessageSettingsConflictException
import emohce.data.commitmessage.CommitMessageSettingsState
import emohce.domain.commitmessage.TypeDisplayMode
import emohce.presentation.commitmessage.CommitMessageBundle
import java.util.function.Consumer
import java.util.function.Predicate
import javax.swing.JButton
import javax.swing.JComponent

class CommitMessageSettingsConfigurable : SearchableConfigurable {
    private var loaded = CommitMessageSettingsService.getInstance().snapshot(refreshPortable = false)
    private var working = loaded.deepCopy()
    private var dialogPanel: DialogPanel? = null
    private val persistentInstructionsField = JBTextArea(5, 64).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val portableStatusLabel = JBLabel()
    private val keepMachineButton = JButton(CommitMessageBundle.message("settings.portable.keepMachine"))
    private val useSyncedButton = JButton(CommitMessageBundle.message("settings.portable.useSynced"))

    init {
        keepMachineButton.addActionListener { resolvePortableConflict(useIncoming = false) }
        useSyncedButton.addActionListener { resolvePortableConflict(useIncoming = true) }
    }

    override fun getId(): String = ID

    override fun getDisplayName(): String = CommitMessageBundle.message("settings.commitMessage.title")

    override fun createComponent(): JComponent {
        loadWorkingState(refreshPortable = true)
        return panel {
            group(CommitMessageBundle.message("settings.actions.section")) {
                row {
                    label(
                        CommitMessageBundle.message(
                            "settings.keymap.current",
                            KeymapManager.getInstance().activeKeymap.name,
                        ),
                    )
                }
                actionRow(
                    ACTION_CREATE,
                    "settings.action.create",
                    { working.showCreateInToolbar },
                    { working.showCreateInToolbar = it },
                )
                actionRow(
                    ACTION_GENERATE,
                    "settings.action.generate",
                    { working.showGenerateInToolbar },
                    { working.showGenerateInToolbar = it },
                )
                actionRow(
                    ACTION_GENERATE_WITH_CONTEXT,
                    "settings.action.generateWithContext",
                    { working.showGenerateWithContextInToolbar },
                    { working.showGenerateWithContextInToolbar = it },
                )
                actionRow(
                    ACTION_FORMAT,
                    "settings.action.format",
                    { working.showFormatInToolbar },
                    { working.showFormatInToolbar = it },
                )
                shortcutOnlyRow(
                    ACTION_SELECT_STYLE,
                    "settings.action.selectStyle",
                )
            }
            group(CommitMessageBundle.message("settings.fields.section")) {
                row {
                    checkBox(CommitMessageBundle.message("settings.field.type"))
                        .bindSelected({ working.showType }, { working.showType = it })
                    checkBox(CommitMessageBundle.message("settings.field.scope"))
                        .bindSelected({ working.showScope }, { working.showScope = it })
                    checkBox(CommitMessageBundle.message("settings.field.body"))
                        .bindSelected({ working.showBody }, { working.showBody = it })
                }
                row {
                    checkBox(CommitMessageBundle.message("settings.field.breakingChanges"))
                        .bindSelected({ working.showBreakingChanges }, { working.showBreakingChanges = it })
                    checkBox(CommitMessageBundle.message("settings.field.closes"))
                        .bindSelected({ working.showCloses }, { working.showCloses = it })
                    checkBox(CommitMessageBundle.message("settings.field.skipCi"))
                        .bindSelected({ working.showSkipCi }, { working.showSkipCi = it })
                }
                row(CommitMessageBundle.message("settings.field.subject")) {
                    comment(CommitMessageBundle.message("settings.subject.alwaysVisible"))
                }
            }
            group(CommitMessageBundle.message("settings.behavior.section")) {
                row(CommitMessageBundle.message("settings.typeDisplay")) {
                    val choices = TypeDisplayMode.entries.map { mode ->
                        TypeDisplayChoice(
                            mode,
                            CommitMessageBundle.message("settings.typeDisplay.${mode.name.lowercase()}"),
                        )
                    }
                    comboBox(choices)
                        .bindItem(
                            { choices.firstOrNull { it.mode == working.typeDisplayMode } },
                            { working.typeDisplayMode = it?.mode ?: TypeDisplayMode.COMBO },
                        )
                }
                row {
                    checkBox(CommitMessageBundle.message("settings.defaultSkipCi"))
                        .bindSelected({ working.defaultSkipCi }, { working.defaultSkipCi = it })
                }
                row {
                    checkBox(CommitMessageBundle.message("settings.previewBeforeApply"))
                        .bindSelected(
                            { working.previewAiResultBeforeApply },
                            { working.previewAiResultBeforeApply = it },
                        )
                }
            }
            group(CommitMessageBundle.message("settings.portable.section")) {
                row(CommitMessageBundle.message("settings.portable.instructions")) {
                    cell(JBScrollPane(persistentInstructionsField)).align(Align.FILL).resizableColumn()
                }.resizableRow()
                row {
                    cell(portableStatusLabel).align(Align.FILL).resizableColumn()
                    cell(keepMachineButton)
                    cell(useSyncedButton)
                }
            }
        }.also { dialogPanel = it }
    }

    override fun isModified(): Boolean = dialogPanel?.isModified() == true ||
        persistentInstructionsField.text.trim() != loaded.persistentExtraInstructions

    override fun apply() {
        val previousWorking = working.deepCopy()
        try {
            dialogPanel?.apply()
            val service = CommitMessageSettingsService.getInstance()
            working.persistentExtraInstructions = persistentInstructionsField.text.trim()
            if (working.persistentExtraInstructions.length > CommitMessageSettingsState.MAX_PERSISTENT_INSTRUCTIONS) {
                throw ConfigurationException(
                    CommitMessageBundle.message(
                        "settings.portable.instructionsTooLong",
                        CommitMessageSettingsState.MAX_PERSISTENT_INSTRUCTIONS,
                    ),
                )
            }
            val current = service.snapshot()
            if (service.hasPortableConflict() ||
                (mainFields(current) != mainFields(loaded) && mainFields(current) != mainFields(working))
            ) {
                throw ConfigurationException(CommitMessageBundle.message("settings.portable.concurrentChange"))
            }
            val merged = current.apply {
                showCreateInToolbar = working.showCreateInToolbar
                showGenerateInToolbar = working.showGenerateInToolbar
                showGenerateWithContextInToolbar = working.showGenerateWithContextInToolbar
                showFormatInToolbar = working.showFormatInToolbar
                showType = working.showType
                showScope = working.showScope
                showBody = working.showBody
                showBreakingChanges = working.showBreakingChanges
                showCloses = working.showCloses
                showSkipCi = working.showSkipCi
                typeDisplayMode = working.typeDisplayMode
                defaultSkipCi = working.defaultSkipCi
                previewAiResultBeforeApply = working.previewAiResultBeforeApply
                persistentExtraInstructions = working.persistentExtraInstructions
            }
            service.replaceState(merged)
            loadWorkingState(refreshPortable = false)
            dialogPanel?.reset()
        } catch (error: Throwable) {
            working = previousWorking
            if (error is CommitMessageSettingsConflictException) {
                throw ConfigurationException(CommitMessageBundle.message("settings.portable.concurrentChange"))
            }
            throw error
        }
    }

    override fun reset() {
        loadWorkingState(refreshPortable = true)
        dialogPanel?.reset()
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }

    private fun loadWorkingState(refreshPortable: Boolean) {
        val service = CommitMessageSettingsService.getInstance()
        loaded = service.snapshot(refreshPortable)
        working = loaded.deepCopy()
        persistentInstructionsField.text = loaded.persistentExtraInstructions
        updatePortableStatus(service)
    }

    private fun resolvePortableConflict(useIncoming: Boolean) {
        val service = CommitMessageSettingsService.getInstance()
        service.resolvePortableConflict(useIncoming)
        loadWorkingState(refreshPortable = false)
        dialogPanel?.reset()
    }

    private fun updatePortableStatus(service: CommitMessageSettingsService) {
        val failure = service.portableFailureMessage()
        portableStatusLabel.text = when {
            failure != null -> CommitMessageBundle.message("settings.portable.failure", failure)
            service.hasPortableConflict() -> CommitMessageBundle.message("settings.portable.conflict")
            else -> CommitMessageBundle.message("settings.portable.synced")
        }
        keepMachineButton.isVisible = service.hasPortableConflict()
        useSyncedButton.isVisible = service.hasPortableConflict()
    }

    private fun mainFields(state: CommitMessageSettingsState): MainFields = MainFields(
        showCreateInToolbar = state.showCreateInToolbar,
        showGenerateInToolbar = state.showGenerateInToolbar,
        showGenerateWithContextInToolbar = state.showGenerateWithContextInToolbar,
        showFormatInToolbar = state.showFormatInToolbar,
        showType = state.showType,
        showScope = state.showScope,
        showBody = state.showBody,
        showBreakingChanges = state.showBreakingChanges,
        showCloses = state.showCloses,
        showSkipCi = state.showSkipCi,
        typeDisplayMode = state.typeDisplayMode,
        defaultSkipCi = state.defaultSkipCi,
        previewAiResultBeforeApply = state.previewAiResultBeforeApply,
        persistentExtraInstructions = state.persistentExtraInstructions,
    )

    private fun com.intellij.ui.dsl.builder.Panel.actionRow(
        actionId: String,
        labelKey: String,
        getter: () -> Boolean,
        setter: (Boolean) -> Unit,
    ) {
        row(CommitMessageBundle.message(labelKey)) {
            checkBox(CommitMessageBundle.message("settings.action.visible"))
                .bindSelected(getter, setter)
            label(shortcutSummary(actionId))
            button(CommitMessageBundle.message("settings.keymap.configure")) {
                openKeymapForAction(actionId)
            }
        }
    }

    private fun com.intellij.ui.dsl.builder.Panel.shortcutOnlyRow(actionId: String, labelKey: String) {
        row(CommitMessageBundle.message(labelKey)) {
            label(shortcutSummary(actionId))
            button(CommitMessageBundle.message("settings.keymap.configure")) {
                openKeymapForAction(actionId)
            }
        }
    }

    private fun shortcutSummary(actionId: String): String {
        val keymap = KeymapManager.getInstance().activeKeymap
        val shortcutText = KeymapUtil.getShortcutsText(keymap.getShortcuts(actionId))
            .ifBlank { CommitMessageBundle.message("settings.keymap.none") }
        val conflicts = CommitMessageKeymapSupport.conflictingActionIds(actionId)
            .map { id -> ActionManager.getInstance().getAction(id)?.templateText ?: id }
        val conflictText = if (conflicts.isEmpty()) {
            CommitMessageBundle.message("settings.keymap.noConflict")
        } else {
            CommitMessageBundle.message("settings.keymap.conflict", conflicts.joinToString())
        }
        return "$shortcutText · $conflictText"
    }

    private fun openKeymapForAction(actionId: String) {
        val actionName = ActionManager.getInstance().getAction(actionId)?.templateText ?: actionId
        ShowSettingsUtil.getInstance().showSettingsDialog(
            null,
            Predicate<Configurable> { (it as? ConfigurableWithId)?.id == KEYMAP_CONFIGURABLE_ID },
            Consumer<Configurable> {
                (it as? SearchableConfigurable)?.enableSearch(actionName)?.run()
            },
        )
    }

    companion object {
        const val ID = "emohce.settings.commitMessage"
        const val ACTION_CREATE = "EzCodeMarks.CommitMessage.Create"
        const val ACTION_GENERATE = "EzCodeMarks.CommitMessage.Generate"
        const val ACTION_GENERATE_WITH_CONTEXT = "EzCodeMarks.CommitMessage.GenerateWithContext"
        const val ACTION_FORMAT = "EzCodeMarks.CommitMessage.Format"
        const val ACTION_SELECT_STYLE = "EzCodeMarks.CommitMessage.SelectStyle"
        internal const val KEYMAP_CONFIGURABLE_ID = "preferences.keymap"
    }

    private data class TypeDisplayChoice(val mode: TypeDisplayMode, val label: String) {
        override fun toString(): String = label
    }

    private data class MainFields(
        val showCreateInToolbar: Boolean,
        val showGenerateInToolbar: Boolean,
        val showGenerateWithContextInToolbar: Boolean,
        val showFormatInToolbar: Boolean,
        val showType: Boolean,
        val showScope: Boolean,
        val showBody: Boolean,
        val showBreakingChanges: Boolean,
        val showCloses: Boolean,
        val showSkipCi: Boolean,
        val typeDisplayMode: TypeDisplayMode,
        val defaultSkipCi: Boolean,
        val previewAiResultBeforeApply: Boolean,
        val persistentExtraInstructions: String,
    )
}

internal object CommitMessageKeymapSupport {
    fun conflictingActionIds(actionId: String): Set<String> {
        val keymap = KeymapManager.getInstance().activeKeymap
        return keymap.getShortcuts(actionId)
            .filterIsInstance<KeyboardShortcut>()
            .flatMap { keymap.getConflicts(actionId, it).keys }
            .toSet()
    }
}
