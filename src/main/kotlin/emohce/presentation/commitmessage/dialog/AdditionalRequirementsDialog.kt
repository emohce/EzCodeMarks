package emohce.presentation.commitmessage.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.annotations.TestOnly
import emohce.presentation.commitmessage.CommitMessageBundle
import java.awt.Dimension
import javax.swing.JComponent

class AdditionalRequirementsDialog(
    project: Project,
    private val purpose: Purpose = Purpose.GENERATE,
) : DialogWrapper(project) {
    private val requirementsField = JBTextArea(8, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        title = CommitMessageBundle.message(purpose.titleKey)
        init()
    }

    val requirements: String
        get() = requirementsField.text.trim()

    override fun createCenterPanel(): JComponent = panel {
        row { label(CommitMessageBundle.message(purpose.promptKey)) }
        row { scrollCell(requirementsField).align(Align.FILL) }.resizableRow()
    }.apply { preferredSize = Dimension(620, 300) }

    override fun getPreferredFocusedComponent(): JComponent = requirementsField

    @TestOnly
    internal fun componentForTest(): JComponent = createCenterPanel()

    enum class Purpose(
        internal val titleKey: String,
        internal val promptKey: String,
    ) {
        GENERATE("dialog.additional.title", "dialog.additional.prompt"),
        FORMAT("dialog.formatInstructions.title", "dialog.formatInstructions.prompt"),
    }
}
