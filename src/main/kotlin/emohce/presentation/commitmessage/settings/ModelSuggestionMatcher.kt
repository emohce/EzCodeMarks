package emohce.presentation.commitmessage.settings

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.util.Locale
import javax.swing.DefaultComboBoxModel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.plaf.basic.BasicComboBoxEditor
import javax.swing.text.Document

internal object ModelSuggestionMatcher {
    fun rank(models: List<String>, query: String): List<String> {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        if (normalizedQuery.isEmpty()) return models.distinct()
        return models.asSequence()
            .distinct()
            .mapIndexedNotNull { index, model ->
                score(model, normalizedQuery)?.let { score -> RankedModel(model, score, index) }
            }
            .sortedWith(compareByDescending<RankedModel> { it.score }.thenBy { it.index })
            .map { it.model }
            .toList()
    }

    private fun score(model: String, query: String): Int? {
        val candidate = model.lowercase(Locale.ROOT)
        if (candidate == query) return 1_000_000
        if (candidate.startsWith(query)) return 900_000 - candidate.length
        val substring = candidate.indexOf(query)
        if (substring >= 0) {
            val boundary = substring == 0 || candidate[substring - 1] in BOUNDARIES
            return 800_000 - substring * 50 - candidate.length + if (boundary) 5_000 else 0
        }

        var queryIndex = 0
        var previousMatch = -2
        var firstMatch = -1
        var gapPenalty = 0
        var consecutiveBonus = 0
        var boundaryBonus = 0
        candidate.forEachIndexed { index, character ->
            if (queryIndex >= query.length || character != query[queryIndex]) return@forEachIndexed
            if (firstMatch < 0) firstMatch = index
            if (index == previousMatch + 1) consecutiveBonus += 35
            if (index == 0 || candidate[index - 1] in BOUNDARIES) boundaryBonus += 45
            if (previousMatch >= 0) gapPenalty += (index - previousMatch - 1) * 18
            previousMatch = index
            queryIndex += 1
        }
        if (queryIndex != query.length) return null
        return 600_000 + consecutiveBonus + boundaryBonus - gapPenalty - firstMatch * 25 - candidate.length
    }

    private data class RankedModel(val model: String, val score: Int, val index: Int)

    private val BOUNDARIES = setOf('/', '-', '_', '.', ':')
}

internal class FilterableModelComboBox(
    debounceMillis: Int = 75,
) : ComboBox<String>() {
    @Suppress("UNCHECKED_CAST")
    private val suggestionModel = model as DefaultComboBoxModel<String>
    private var allModels: List<String> = emptyList()
    private var adjusting = false
    private val debounceTimer = Timer(debounceMillis) { refreshSuggestions() }.apply { isRepeats = false }
    private var observedDocument: Document? = null
    private val filterDocumentListener = object : DocumentAdapter() {
        override fun textChanged(event: DocumentEvent) {
            if (adjusting || isCommittedSuggestion()) return
            val text = (editor.editorComponent as? JTextField)?.text ?: return
            adjusting = true
            try {
                suggestionModel.selectedItem = text
            } finally {
                adjusting = false
            }
            debounceTimer.restart()
        }
    }

    init {
        setEditor(BasicComboBoxEditor())
        setEditable(true)
        minimumSize = Dimension(JBUI.scale(420), JBUI.scale(30))
        preferredSize = Dimension(JBUI.scale(420), JBUI.scale(30))
        attachEditorListener()
        addPropertyChangeListener("editor") { attachEditorListener() }
        addPropertyChangeListener("UI") { SwingUtilities.invokeLater { attachEditorListener() } }
        addActionListener {
            if (adjusting) return@addActionListener
            val selected = selectedItem?.toString().orEmpty()
            if (selected.isNotEmpty() && allModels.contains(selected)) {
                debounceTimer.stop()
                adjusting = true
                try {
                    editor.item = selected
                    editorField().caretPosition = selected.length
                } finally {
                    adjusting = false
                }
            }
        }
    }

    fun setSuggestions(models: List<String>, selectedModel: String) {
        debounceTimer.stop()
        allModels = models.distinct()
        replaceModel(allModels, selectedModel)
    }

    fun currentText(): String = editor.item?.toString()?.trim().orEmpty()

    fun commitCurrentText(): String {
        debounceTimer.stop()
        val text = currentText()
        adjusting = true
        try {
            if (isPopupVisible) hidePopup()
            suggestionModel.selectedItem = text
            editor.item = text
        } finally {
            adjusting = false
        }
        return text
    }

    fun filterNow() {
        debounceTimer.stop()
        refreshSuggestions()
    }

    fun allSuggestions(): List<String> = allModels.toList()

    override fun addNotify() {
        super.addNotify()
        attachEditorListener()
    }

    override fun removeNotify() {
        debounceTimer.stop()
        super.removeNotify()
    }

    private fun refreshSuggestions() {
        val query = currentText()
        val matches = ModelSuggestionMatcher.rank(allModels, query)
        replaceModel(matches, query)
        val field = editorField()
        if (matches.isEmpty()) {
            if (isPopupVisible) hidePopup()
        } else if (isShowing && field.isFocusOwner && !isPopupVisible) {
            showPopup()
        }
    }

    private fun replaceModel(models: List<String>, editorText: String) {
        val field = editorField()
        val caret = field.caretPosition.coerceAtMost(editorText.length)
        adjusting = true
        try {
            suggestionModel.removeAllElements()
            models.forEach(suggestionModel::addElement)
            suggestionModel.selectedItem = editorText
            editor.item = editorText
            attachEditorListener()
            field.caretPosition = caret.coerceAtMost(field.text.length)
        } finally {
            adjusting = false
        }
    }

    private fun editorField(): JTextField = editor.editorComponent as JTextField

    private fun attachEditorListener() {
        val document = (editor?.editorComponent as? JTextField)?.document ?: return
        if (observedDocument === document) return
        observedDocument?.removeDocumentListener(filterDocumentListener)
        observedDocument = document
        document.addDocumentListener(filterDocumentListener)
    }

    private fun isCommittedSuggestion(): Boolean {
        val selected = selectedItem?.toString() ?: return false
        return allModels.contains(selected) && selected == currentText()
    }
}
