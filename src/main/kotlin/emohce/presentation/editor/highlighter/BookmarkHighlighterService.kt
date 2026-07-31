package emohce.presentation.editor.highlighter

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import emohce.core.di.ServiceLocator
import emohce.domain.event.BookmarkEvent
import emohce.domain.model.BookmarkNode
import emohce.presentation.index.BookmarkIndexService
import emohce.presentation.action.CodemarkNavigationHelper
import emohce.presentation.selection.SelectionBus
import emohce.presentation.toolwindow.BookmarkEditDialogUtil
import emohce.presentation.toolwindow.BookmarkIntent
import emohce.presentation.toolwindow.panel.render.NodeIcons
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.awt.event.ActionEvent
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class BookmarkHighlighterService(private val project: Project) {
    private val logger = Logger.getInstance(BookmarkHighlighterService::class.java)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val locator by lazy { ServiceLocator.get(project) }
    private val selectionBus by lazy { SelectionBus.getInstance(project) }
    private val toolWindowManager by lazy { ToolWindowManager.getInstance(project) }
    private val viewModel by lazy { locator.bookmarkViewModel }
    private val editorHighlighters = mutableMapOf<Editor, MutableList<com.intellij.openapi.editor.markup.RangeHighlighter>>()
    private val fileIndex = AtomicReference<Map<String, List<MarkerEntry>>>(emptyMap())
    private val pendingEditors = ConcurrentLinkedQueue<Pair<Editor, VirtualFile>>()
    @Volatile private var started = false
    /** Gutter/hints only paint after first rebuild; avoids empty/wrong flash at startup. */
    @Volatile private var indexReady = false
    private val rebuildChannel = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        if (project.isDisposed) return
        if (started) return
        started = true
        logger.debug("[GUTTER_HIGHLIGHT] service start")
        startRebuildProcessor()
        listenEditors()
        listenRepository()
        requestRebuild()
    }

    fun dispose() {
        clearAll()
        rebuildChannel.close()
        scope.cancel()
    }

    private fun listenEditors() {
        if (project.isDisposed) return
        val fem = FileEditorManager.getInstance(project)
        fem.addFileEditorManagerListener(object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                if (project.isDisposed) return
                source.getSelectedTextEditor()?.let { refreshEditor(it, file) }
            }

            override fun selectionChanged(event: FileEditorManagerEvent) {
                if (project.isDisposed) return
                val editor = event.manager.getSelectedTextEditor() ?: return
                val file = editor.virtualFile ?: return
                refreshEditor(editor, file)
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                if (project.isDisposed) return
                source.getAllEditors(file).forEach { ed ->
                    if (ed is com.intellij.openapi.fileEditor.TextEditor) {
                        clearEditor(ed.editor)
                    }
                }
            }
        })
    }

    private fun listenRepository() {
        if (project.isDisposed) return
        scope.launch {
            locator.bookmarkRepository.observeChanges().collect { event ->
                if (project.isDisposed) return@collect
                logger.debug("[GUTTER_HIGHLIGHT] repo event=${event.javaClass.simpleName}")
                when (event) {
                    is BookmarkEvent.NodeLineSynced -> {
                        val path = when (val node = event.node) {
                            is BookmarkNode.Bookmark -> node.filePath
                            is BookmarkNode.Process -> node.entryFilePath
                            else -> null
                        }
                        path?.let { refreshGutterForFile(it) }
                    }
                    else -> requestRebuild()
                }
            }
        }
    }

    private fun refreshOpenEditors() {
        if (project.isDisposed) return
        val fem = FileEditorManager.getInstance(project)
        val editors = fem.allEditors.filterIsInstance<com.intellij.openapi.fileEditor.TextEditor>()
        editors.forEach { ed ->
            ed.editor.virtualFile?.let { refreshEditor(ed.editor, it) }
        }
    }

    private fun refreshEditor(editor: Editor, file: VirtualFile) {
        if (project.isDisposed) return
        if (!indexReady) {
            pendingEditors.offer(editor to file)
            return
        }
        doRefreshEditor(editor, file)
    }

    private fun doRefreshEditor(editor: Editor, file: VirtualFile) {
        if (project.isDisposed) return
        scope.launch {
            if (project.isDisposed) return@launch
            val normalized = FileUtil.toSystemIndependentName(file.path)
            val entries = withContext(Dispatchers.IO) { fileIndex.get()[normalized].orEmpty() }
            if (project.isDisposed) return@launch
            try {
                applyHighlighters(editor, entries)
                editor.contentComponent.repaint()
            } catch (_: Exception) {
                // Editor may have been closed while pending
            }
        }
    }

    private fun processPendingEditors() {
        if (project.isDisposed) return
        while (true) {
            val pair = pendingEditors.poll() ?: break
            val (editor, file) = pair
            doRefreshEditor(editor, file)
        }
    }

    /**
     * Flash the given line in open editors for the file (e.g. after tree-node navigation).
     * Same visual effect as gutter icon click.
     */
    fun flashLineForFile(filePath: String, line: Int) {
        if (project.isDisposed) return
        scope.launch {
            val normalized = FileUtil.toSystemIndependentName(filePath)
            withContext(Dispatchers.Main) {
                if (project.isDisposed) return@withContext
                val file = LocalFileSystem.getInstance().findFileByPath(normalized) ?: return@withContext
                val fem = FileEditorManager.getInstance(project)
                fem.getEditors(file).forEach { editor ->
                    if (editor is TextEditor) {
                        flashLine(editor.editor, line)
                    }
                }
            }
        }
    }

    /**
     * Refresh gutter for a single file immediately (e.g. after CreateBookmark).
     * Does not depend on full rebuild; reads current root and applies highlighters for open editors of this file.
     */
    fun refreshGutterForFile(path: String) {
        if (project.isDisposed) return
        scope.launch {
            val normalized = FileUtil.toSystemIndependentName(path)
            val root = withContext(Dispatchers.IO) { locator.bookmarkRepository.getRootNode() }
            val entries = withContext(Dispatchers.IO) {
                val index = BookmarkIndexService.getInstance(project)
                val revision = currentStoreRevision()
                if (revision >= 0) {
                    index.rebuildIfStale(root, revision)
                } else {
                    index.rebuild(root)
                }
                index.entriesForFile(normalized).map { entry ->
                    MarkerEntry(entry.nodeId, entry.label, entry.line, entry.filePath)
                }
            }
            withContext(Dispatchers.Main) {
                if (project.isDisposed) return@withContext
                val fem = FileEditorManager.getInstance(project)
                val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(normalized)
                    ?: return@withContext
                val editors = fem.getEditors(file)
                editors.forEach { editor ->
                    if (editor is com.intellij.openapi.fileEditor.TextEditor) {
                        applyHighlighters(editor.editor, entries)
                        editor.editor.contentComponent.repaint()
                    }
                }
            }
        }
    }

    private fun collectEntriesForPath(root: BookmarkNode.Group, targetPath: String): List<MarkerEntry> {
        val list = mutableListOf<MarkerEntry>()
        traverse(root) { node ->
            when (node) {
                is BookmarkNode.Bookmark -> {
                    if (FileUtil.toSystemIndependentName(node.filePath) == targetPath) {
                        list.add(MarkerEntry(node.uuid, node.name, node.line, node.filePath))
                    }
                }
                is BookmarkNode.Process -> {
                    val entryPath = node.entryFilePath
                    val entryLine = node.entryLine
                    if (entryPath != null && entryLine != null && FileUtil.toSystemIndependentName(entryPath) == targetPath) {
                        list.add(MarkerEntry(node.uuid, node.name, entryLine, entryPath))
                    }
                }
                else -> Unit
            }
        }
        return list.sortedBy { it.line }
    }

    private fun startRebuildProcessor() {
        scope.launch {
            for (signal in rebuildChannel) {
                if (project.isDisposed) return@launch
                delay(50)
                if (project.isDisposed) return@launch
                while (rebuildChannel.tryReceive().isSuccess) { /* drain */ }
                if (project.isDisposed) return@launch
                rebuildIndexInternal()
            }
        }
    }

    private fun requestRebuild() {
        if (project.isDisposed) return
        rebuildChannel.trySend(Unit)
    }

    private fun currentStoreRevision(): Long {
        val repository = locator.bookmarkRepository
        return if (repository is emohce.data.repository.BookmarkRepositoryImpl) {
            repository.getStore().revision
        } else {
            -1L
        }
    }

    private suspend fun rebuildIndexInternal() {
        if (project.isDisposed) return
        val (root, map) = withContext(Dispatchers.IO) {
            val built = mutableMapOf<String, MutableList<MarkerEntry>>()
            val rootNode = locator.bookmarkRepository.getRootNode()
            traverse(rootNode) { node ->
                when (node) {
                    is BookmarkNode.Bookmark -> {
                        val path = FileUtil.toSystemIndependentName(node.filePath)
                        built.getOrPut(path) { mutableListOf() }
                            .add(MarkerEntry(node.uuid, node.name, node.line, node.filePath))
                    }
                    is BookmarkNode.Process -> {
                        val entryPath = node.entryFilePath
                        val entryLine = node.entryLine
                        if (entryPath != null && entryLine != null) {
                            val path = FileUtil.toSystemIndependentName(entryPath)
                            built.getOrPut(path) { mutableListOf() }
                                .add(MarkerEntry(node.uuid, node.name, entryLine, entryPath))
                        }
                    }
                    else -> Unit
                }
            }
            built.forEach { (_, v) -> v.sortBy { it.line } }
            rootNode to built
        }
        if (project.isDisposed) return
        fileIndex.set(map)
        withContext(Dispatchers.IO) { BookmarkIndexService.getInstance(project).rebuild(root, currentStoreRevision()) }
        indexReady = true
        logger.debug("[GUTTER_HIGHLIGHT] index rebuilt files=${map.size}")
        withContext(Dispatchers.Main) {
            if (project.isDisposed) return@withContext
            processPendingEditors()
            refreshOpenEditors()
        }
    }

    private fun applyHighlighters(editor: Editor, entries: List<MarkerEntry>) {
        if (project.isDisposed) return
        clearEditor(editor)
        if (entries.isEmpty()) return
        val doc = editor.document
        val highlightList = mutableListOf<com.intellij.openapi.editor.markup.RangeHighlighter>()
        entries.forEach { entry ->
            val line = entry.lineProvider(doc) ?: return@forEach
            if (line < 0 || line >= doc.lineCount) return@forEach
            val start = doc.getLineStartOffset(line)
            val end = doc.getLineEndOffset(line)
            val hl = editor.markupModel.addRangeHighlighter(
                start,
                end,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                null,
                HighlighterTargetArea.LINES_IN_RANGE
            )
            hl.isGreedyToLeft = true
            hl.isGreedyToRight = true
            hl.gutterIconRenderer = object : GutterIconRenderer() {
                override fun getIcon() = GutterIcons.codemark
                override fun getClickAction() = object : com.intellij.openapi.actionSystem.AnAction("Select Bookmark") {
                    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                        logger.debug("[GUTTER_HIGHLIGHT] click node=${entry.nodeId} line=$line")
                        toolWindowManager.getToolWindow("EzCodeMarks")?.show(null)
                        CodemarkNavigationHelper.navigateToEntry(project, entry.filePath, line, 0)
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                            selectionBus.requestSelect(entry.nodeId, entry.filePath, line, focusTree = false)
                            flashLine(editor, line)
                        }
                    }
                }
                override fun getPopupMenuActions(): com.intellij.openapi.actionSystem.ActionGroup? {
                    val group = com.intellij.openapi.actionSystem.DefaultActionGroup()
                    group.add(object : com.intellij.openapi.actionSystem.AnAction("Next CodeMark") {
                        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                            scope.launch {
                                selectionBus.setLastSelectedNodeId(entry.nodeId)
                                val nextEntry = withContext(Dispatchers.IO) {
                                    locator.globalCodemarkNavigationUseCase.findNext(entry.nodeId)
                                } ?: return@launch
                                selectionBus.setLastSelectedNodeId(nextEntry.nodeId)
                                if (nextEntry.hasEditorTarget()) {
                                    CodemarkNavigationHelper.navigateToEntry(project, nextEntry.filePath!!, nextEntry.line!!, nextEntry.column)
                                }
                                viewModel.processIntent(BookmarkIntent.NavigateToNode(nextEntry.nodeId))
                                toolWindowManager.getToolWindow("EzCodeMarks")?.show(null)
                            }
                        }
                    })
                    group.add(object : com.intellij.openapi.actionSystem.AnAction("Prev CodeMark") {
                        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                            scope.launch {
                                selectionBus.setLastSelectedNodeId(entry.nodeId)
                                val prevEntry = withContext(Dispatchers.IO) {
                                    locator.globalCodemarkNavigationUseCase.findPrevious(entry.nodeId)
                                } ?: return@launch
                                selectionBus.setLastSelectedNodeId(prevEntry.nodeId)
                                if (prevEntry.hasEditorTarget()) {
                                    CodemarkNavigationHelper.navigateToEntry(project, prevEntry.filePath!!, prevEntry.line!!, prevEntry.column)
                                }
                                viewModel.processIntent(BookmarkIntent.NavigateToNode(prevEntry.nodeId))
                                toolWindowManager.getToolWindow("EzCodeMarks")?.show(null)
                            }
                        }
                    })
                    group.add(object : com.intellij.openapi.actionSystem.AnAction("Edit") {
                        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                            scope.launch {
                                val node = locator.bookmarkRepository.findByUuid(entry.nodeId) ?: return@launch
                                val updated = withContext(Dispatchers.Main) {
                                    when (node) {
                                        is BookmarkNode.Bookmark -> BookmarkEditDialogUtil.editBookmark(project, node)
                                        is BookmarkNode.DescriptiveBookmark -> BookmarkEditDialogUtil.editDescriptive(project, node)
                                        is BookmarkNode.Group -> BookmarkEditDialogUtil.editGroup(project, node)
                                        is BookmarkNode.Process -> BookmarkEditDialogUtil.editProcess(project, node)
                                        else -> null
                                    }
                                } ?: return@launch
                                viewModel.processIntent(BookmarkIntent.EditNode(updated))
                            }
                        }
                    })
                    group.add(object : com.intellij.openapi.actionSystem.AnAction("Add Bookmark After This Node") {
                        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                            scope.launch {
                                val pos = withContext(Dispatchers.IO) {
                                    locator.bookmarkRepository.getInsertPositionAfterNode(entry.nodeId)
                                }
                                val (parentId, insertIndex) = pos ?: (entry.nodeId to null)
                                val child = BookmarkEditDialogUtil.editBookmark(
                                    project,
                                    BookmarkNode.Bookmark(name = "", filePath = entry.filePath, line = line),
                                    "Add Bookmark"
                                ) ?: return@launch
                                if (child.name.isBlank()) return@launch
                                viewModel.processIntent(BookmarkIntent.CreateBookmark(parentId, child, insertIndex))
                            }
                        }
                    })
                    group.add(object : com.intellij.openapi.actionSystem.AnAction("Delete") {
                        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                            val result = Messages.showYesNoDialog(
                                project,
                                "Delete this CodeMark?",
                                "Delete",
                                Messages.getQuestionIcon()
                            )
                            if (result == Messages.YES) {
                                scope.launch {
                                    viewModel.processIntent(BookmarkIntent.DeleteNode(entry.nodeId))
                                }
                            }
                        }
                    })
                    return group
                }

                override fun isNavigateAction() = false
                override fun equals(other: Any?): Boolean = other is BookmarkGutterRenderer && other.id == entry.nodeId
                override fun hashCode(): Int = entry.nodeId.hashCode()
            }.let { BookmarkGutterRenderer(entry.nodeId, it) }
            highlightList.add(hl)
        }
        editorHighlighters[editor] = highlightList
    }

    private fun flashLine(editor: Editor, line: Int) {
        val doc = editor.document
        if (line < 0 || line >= doc.lineCount) return
        val start = doc.getLineStartOffset(line)
        val end = doc.getLineEndOffset(line)
        val attrs = com.intellij.openapi.editor.markup.TextAttributes(null, java.awt.Color(255, 235, 156), null, null, 0)
        val hl = editor.markupModel.addRangeHighlighter(start, end, HighlighterLayer.SELECTION - 1, attrs, HighlighterTargetArea.EXACT_RANGE)
        javax.swing.Timer(350) { _: ActionEvent? -> try { hl.dispose() } catch (_: Exception) {} }.apply { isRepeats = false; start() }
    }

    private fun clearEditor(editor: Editor) {
        editorHighlighters.remove(editor)?.forEach { hl ->
            try { hl.dispose() } catch (_: Exception) {}
        }
    }

    private fun clearAll() {
        editorHighlighters.keys.toList().forEach { clearEditor(it) }
        editorHighlighters.clear()
    }

    private fun traverse(node: BookmarkNode, visitor: (BookmarkNode) -> Unit) {
        visitor(node)
        when (node) {
            is BookmarkNode.Group -> node.children.forEach { traverse(it, visitor) }
            is BookmarkNode.Process -> node.steps.forEach { traverse(it, visitor) }
            else -> Unit
        }
    }

    data class MarkerEntry(
        val nodeId: String,
        val title: String,
        val line: Int,
        val filePath: String,
        val lineProvider: (com.intellij.openapi.editor.Document) -> Int? = { line }
    )

    private data class BookmarkGutterRenderer(val id: String, val delegate: GutterIconRenderer) : GutterIconRenderer() {
        override fun getIcon() = delegate.icon
        override fun getClickAction() = delegate.clickAction
        override fun getPopupMenuActions() = delegate.popupMenuActions
        override fun isNavigateAction() = delegate.isNavigateAction
        override fun equals(other: Any?) = other is BookmarkGutterRenderer && other.id == id
        override fun hashCode(): Int = id.hashCode()
    }

    companion object {
        fun getInstance(project: Project): BookmarkHighlighterService = project.service()
    }
}

/** Gutter icon: 16x16 scaled plugin icon (same as tree node) */
private object GutterIcons {
    val codemark = NodeIcons.bookmark
}
