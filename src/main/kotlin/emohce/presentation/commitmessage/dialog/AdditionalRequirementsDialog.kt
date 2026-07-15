package emohce.presentation.commitmessage.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import emohce.presentation.commitmessage.CommitMessageBundle
import java.awt.Dimension
import javax.swing.JComponent

class AdditionalRequirementsDialog(project: Project) : DialogWrapper(project) {
    private val requirementsField = JBTextArea(8, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        title = CommitMessageBundle.message("dialog.additional.title")
        init()
    }

    val requirements: String
        get() = requirementsField.text.trim()

    override fun createCenterPanel(): JComponent = panel {
        row { label(CommitMessageBundle.message("dialog.additional.prompt")) }
        row { scrollCell(requirementsField).align(Align.FILL) }.resizableRow()
    }.apply { preferredSize = Dimension(620, 300) }

    override fun getPreferredFocusedComponent(): JComponent = requirementsField
}
