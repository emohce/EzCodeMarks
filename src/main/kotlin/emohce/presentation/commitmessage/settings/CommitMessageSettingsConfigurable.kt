package emohce.presentation.commitmessage.settings

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableWithId
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import emohce.data.commitmessage.CommitMessageSettingsService
import emohce.data.commitmessage.CommitMessageSettingsState
import emohce.domain.commitmessage.TypeDisplayMode
import emohce.presentation.commitmessage.CommitMessageBundle
import java.util.function.Consumer
import java.util.function.Predicate
import javax.swing.JComponent

class CommitMessageSettingsConfigurable : SearchableConfigurable {
    private var working = CommitMessageSettingsService.getInstance().state.deepCopy()
    private var dialogPanel: DialogPanel? = null

    override fun getId(): String = ID

    override fun getDisplayName(): String = CommitMessageBundle.message("settings.commitMessage.title")

    override fun createComponent(): JComponent {
        working = CommitMessageSettingsService.getInstance().state.deepCopy()
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
                    checkBox(CommitMessageBundle.message("settings.smartEcho"))
                        .bindSelected({ working.smartEcho }, { working.smartEcho = it })
                }
            }
        }.also { dialogPanel = it }
    }

    override fun isModified(): Boolean = dialogPanel?.isModified() == true

    override fun apply() {
        dialogPanel?.apply()
        val service = CommitMessageSettingsService.getInstance()
        val merged = service.state.deepCopy().apply {
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
            smartEcho = working.smartEcho
        }
        service.replaceState(merged)
        working = service.state.deepCopy()
        dialogPanel?.reset()
    }

    override fun reset() {
        working = CommitMessageSettingsService.getInstance().state.deepCopy()
        dialogPanel?.reset()
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }

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
        internal const val KEYMAP_CONFIGURABLE_ID = "preferences.keymap"
    }

    private data class TypeDisplayChoice(val mode: TypeDisplayMode, val label: String) {
        override fun toString(): String = label
    }
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
