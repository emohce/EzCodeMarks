package emohce.presentation.commitmessage.settings

import com.intellij.openapi.options.Configurable
import emohce.presentation.commitmessage.CommitMessageBundle
import javax.swing.JComponent

class CodexSettingsConfigurable : Configurable {
    private var panel: CodexSettingsPanel? = null

    override fun getDisplayName(): String = CommitMessageBundle.message("settings.providers.codex.settingsTitle")

    override fun createComponent(): JComponent = CodexSettingsPanel().also {
        panel = it
        it.reset()
    }.component

    override fun isModified(): Boolean = panel?.isModified() == true

    override fun apply() {
        panel?.applySettings()
    }

    override fun reset() {
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel?.dispose()
        panel = null
    }
}
