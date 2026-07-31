package emohce.presentation.toolwindow.panel

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.speedSearch.SpeedSearchSupply
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.FormBuilder
import emohce.core.di.ServiceLocator
import emohce.data.datasource.BookmarkPersistentDataSource
import emohce.domain.model.BookmarkNode
import emohce.presentation.action.CodemarkNavigationHelper
import emohce.presentation.editor.highlighter.BookmarkHighlighterService
import emohce.presentation.index.BookmarkIndexService
import emohce.presentation.selection.SelectionBus
import emohce.data.repository.BookmarkStore
import emohce.presentation.toolwindow.BookmarkIntent
import emohce.presentation.toolwindow.BookmarkEditDialogUtil
import emohce.presentation.toolwindow.BookmarkSideEffect
import emohce.presentation.toolwindow.BookmarkViewModel
import emohce.presentation.toolwindow.BookmarkViewState
import emohce.presentation.toolwindow.TreeRefreshKind
import emohce.presentation.toolwindow.panel.render.BookmarkTreeCellRenderer
import emohce.presentation.toolwindow.panel.render.NodeIcons
import emohce.presentation.toolwindow.panel.util.BookmarkTreeUtil
import emohce.presentation.ui.ChipLabel
import emohce.presentation.ui.DetailsPopupPanel
import emohce.presentation.ui.RoundedPanel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.awt.*
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.KeyEventDispatcher
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import java.awt.geom.AffineTransform
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.io.File
import javax.swing.*
import javax.swing.event.HyperlinkEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

private class AnchoredTreeSpeedSearch(
    tree: JTree,
    canExpand: Boolean,
    nodeText: (TreePath) -> String,
    private val anchor: () -> JComponent?
) : TreeSpeedSearch(tree, canExpand, java.util.function.Function { path -> nodeText(path) }) {
    fun moveToAnchor() {
        SwingUtilities.invokeLater { repositionSearchPopup() }
    }

    private fun repositionSearchPopup() {
        val searchField = textField ?: return
        val popup = searchField.parent ?: return
        val layeredPane = popup.parent as? JLayeredPane ?: return
        val anchorComponent = anchor()?.takeIf { it.isShowing } ?: return
        searchField.font = searchField.font.deriveFont(Font.PLAIN, 13f)
        val anchorBounds = SwingUtilities.convertRectangle(
            anchorComponent.parent,
            anchorComponent.bounds,
            layeredPane
        )
        val preferredSize = popup.preferredSize.let { size ->
            Dimension(size.width.coerceAtLeast(JBUI.scale(132)), size.height)
        }
        val x = (anchorBounds.x + anchorBounds.width - preferredSize.width)
            .coerceIn(0, (layeredPane.width - preferredSize.width).coerceAtLeast(0))
        val y = (anchorBounds.y + anchorBounds.height + JBUI.scale(1))
            .coerceAtMost((layeredPane.height - preferredSize.height).coerceAtLeast(0))
        popup.setBounds(x, y, preferredSize.width, preferredSize.height)
        popup.validate()
    }
}

internal enum class TreeSearchMode {
    FULL,
    VISIBLE_ONLY
}

internal fun nextTreeSearchModeOnFind(currentMode: TreeSearchMode, isSearchActive: Boolean): TreeSearchMode {
    return if (!isSearchActive) {
        TreeSearchMode.FULL
    } else if (currentMode == TreeSearchMode.FULL) {
        TreeSearchMode.VISIBLE_ONLY
    } else {
        TreeSearchMode.FULL
    }
}

internal fun treeSearchModeBeforeQueryInput(): TreeSearchMode = TreeSearchMode.FULL

internal fun treeSearchModeBadgeText(mode: TreeSearchMode): String {
    return if (mode == TreeSearchMode.FULL) "Full" else "Visible"
}

internal fun treeSearchModeAfterExit(): TreeSearchMode = TreeSearchMode.VISIBLE_ONLY

internal fun shouldShowTreeSearchModeBadge(
    pendingSearchMode: Boolean,
    hasQuery: Boolean,
    isPopupActive: Boolean
): Boolean {
    return hasQuery || isPopupActive
}

internal fun resolveTreeSearchModeForPrefix(
    currentMode: TreeSearchMode,
    forceNextSearchFull: Boolean
): TreeSearchMode {
    return if (forceNextSearchFull) TreeSearchMode.FULL else currentMode
}

internal fun shouldHandleTreeFindShortcut(
    hasFocusContext: Boolean,
    isToolWindowActive: Boolean,
    hasRecentPanelInteraction: Boolean
): Boolean {
    return hasFocusContext || isToolWindowActive || hasRecentPanelInteraction
}

internal fun treeFindShortcutStrokes(): List<KeyStroke> {
    return listOf(
        KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK),
        KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.META_DOWN_MASK)
    )
}

private fun toRelativePath(project: Project, absolutePath: String?): String? {
    if (absolutePath.isNullOrBlank()) return absolutePath
    val basePath = project.basePath ?: return absolutePath

    try {
        val baseFile = File(basePath).canonicalFile
        val pathFile = File(absolutePath).canonicalFile

        val basePathStr = FileUtil.toSystemIndependentName(baseFile.absolutePath)
        val pathStr = FileUtil.toSystemIndependentName(pathFile.absolutePath)

        if (pathStr.startsWith(basePathStr)) {
            val relativePath = baseFile.toPath().relativize(pathFile.toPath())
            val relativePathStr = FileUtil.toSystemIndependentName(relativePath.toString())
            return if (relativePathStr.isEmpty()) "." else relativePathStr
        }
    } catch (e: Exception) {
    }

    return FileUtil.toSystemIndependentName(absolutePath)
}

internal object MarkdownDetailsRenderer {
    fun render(markdown: String): String {
        val body = markdown.trim()
        val rendered = if (body.isBlank()) {
            """<p class="empty">No description</p>"""
        } else {
            renderBlocks(body)
        }
        val text = JBColor.foreground().css()
        val muted = JBColor.GRAY.css()
        val link = JBColor(0x2F65CA, 0x8AB4F8).css()
        val blockBackground = JBColor(0xF3F6FB, 0x2B313A).css()
        val codeBackground = JBColor(0xF6F8FA, 0x252A31).css()
        val border = JBColor.border().css()
        val accent = JBColor(0x4B7BFF, 0x7AA2FF).css()
        return """
            <html>
            <head>
              <style>
                body {
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                  font-size: 12px;
                  margin: 0;
                  color: $text;
                }
                h1, h2, h3 {
                  margin: 6px 0 6px 0;
                  font-weight: 700;
                }
                h1 { font-size: 18px; }
                h2 { font-size: 16px; }
                h3 { font-size: 14px; }
                p { margin: 5px 0 8px 0; line-height: 1.35; }
                ul, ol { margin: 5px 0 8px 18px; padding: 0; }
                li { margin: 3px 0; }
                blockquote {
                  margin: 6px 0 8px 0;
                  padding: 4px 8px;
                  border-left: 3px solid $accent;
                  color: $muted;
                  background: $blockBackground;
                }
                pre {
                  margin: 6px 0 8px 0;
                  padding: 8px;
                  background: $codeBackground;
                  border: 1px solid $border;
                  font-family: "JetBrains Mono", Menlo, Consolas, monospace;
                  font-size: 11px;
                }
                code {
                  font-family: "JetBrains Mono", Menlo, Consolas, monospace;
                  background: $codeBackground;
                }
                a { color: $link; }
                .empty { color: $muted; font-style: italic; }
              </style>
            </head>
            <body>$rendered</body>
            </html>
        """.trimIndent()
    }

    private fun renderBlocks(markdown: String): String {
        val out = StringBuilder()
        val paragraph = mutableListOf<String>()
        var inCode = false
        val code = StringBuilder()
        var listType: String? = null

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            out.append("<p>")
                .append(paragraph.joinToString("<br>") { renderInline(it) })
                .append("</p>")
            paragraph.clear()
        }

        fun closeList() {
            listType?.let { out.append("</").append(it).append(">") }
            listType = null
        }

        markdown.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                if (inCode) {
                    out.append("<pre><code>")
                        .append(code.toString().escapeHtml())
                        .append("</code></pre>")
                    code.clear()
                    inCode = false
                } else {
                    flushParagraph()
                    closeList()
                    inCode = true
                }
                return@forEach
            }
            if (inCode) {
                code.append(line).append('\n')
                return@forEach
            }
            if (trimmed.isBlank()) {
                flushParagraph()
                closeList()
                return@forEach
            }

            val headingLevel = trimmed.takeWhile { it == '#' }.length
            if (headingLevel in 1..3 && trimmed.getOrNull(headingLevel) == ' ') {
                flushParagraph()
                closeList()
                out.append("<h").append(headingLevel).append(">")
                    .append(renderInline(trimmed.drop(headingLevel + 1)))
                    .append("</h").append(headingLevel).append(">")
                return@forEach
            }

            if (trimmed.startsWith(">")) {
                flushParagraph()
                closeList()
                out.append("<blockquote>")
                    .append(renderInline(trimmed.drop(1).trim()))
                    .append("</blockquote>")
                return@forEach
            }

            val unordered = Regex("""^[-*]\s+(.+)$""").matchEntire(trimmed)
            val ordered = Regex("""^\d+[.)]\s+(.+)$""").matchEntire(trimmed)
            if (unordered != null || ordered != null) {
                flushParagraph()
                val tag = if (unordered != null) "ul" else "ol"
                if (listType != tag) {
                    closeList()
                    out.append("<").append(tag).append(">")
                    listType = tag
                }
                val item = unordered?.groupValues?.get(1) ?: ordered!!.groupValues[1]
                out.append("<li>").append(renderInline(item)).append("</li>")
                return@forEach
            }

            closeList()
            paragraph.add(trimmed)
        }

        if (inCode) {
            out.append("<pre><code>")
                .append(code.toString().escapeHtml())
                .append("</code></pre>")
        }
        flushParagraph()
        closeList()
        return out.toString()
    }

    private fun renderInline(text: String): String {
        var html = text.escapeHtml()
        html = Regex("""`([^`]+)`""").replace(html) { "<code>${it.groupValues[1]}</code>" }
        html = Regex("""\*\*([^*]+)\*\*""").replace(html) { "<b>${it.groupValues[1]}</b>" }
        html = Regex("""\*([^*]+)\*""").replace(html) { "<i>${it.groupValues[1]}</i>" }
        html = Regex("""\[([^]]+)]\(([^)\s]+)\)""").replace(html) {
            """<a href="${it.groupValues[2]}">${it.groupValues[1]}</a>"""
        }
        return html
    }

    private fun String.escapeHtml(): String {
        return replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun Color.css(): String {
        return "#%02x%02x%02x".format(red, green, blue)
    }
}

internal data class ProjectRelativeMarkdownLink(
    val path: String,
    val line: Int = 0,
    val column: Int = 0
)

internal object ProjectRelativeMarkdownLinkParser {
    fun parse(rawTarget: String): ProjectRelativeMarkdownLink? {
        val target = rawTarget.trim()
        if (target.isBlank()) return null
        if (target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)) {
            return null
        }

        parseHashLine(target)?.let { return it }
        parseColonLine(target)?.let { return it }
        return ProjectRelativeMarkdownLink(path = target)
    }

    private fun parseHashLine(target: String): ProjectRelativeMarkdownLink? {
        val match = Regex("""^(.+)#L(\d+)$""", RegexOption.IGNORE_CASE).matchEntire(target) ?: return null
        val path = match.groupValues[1].trim()
        if (path.isBlank()) return null
        return ProjectRelativeMarkdownLink(
            path = path,
            line = match.groupValues[2].toIntOrNull()?.minus(1)?.coerceAtLeast(0) ?: 0
        )
    }

    private fun parseColonLine(target: String): ProjectRelativeMarkdownLink? {
        val match = Regex("""^(.+?):(\d+)(?::(\d+))?$""").matchEntire(target) ?: return null
        val path = match.groupValues[1].trim()
        if (path.isBlank()) return null
        return ProjectRelativeMarkdownLink(
            path = path,
            line = match.groupValues[2].toIntOrNull()?.minus(1)?.coerceAtLeast(0) ?: 0,
            column = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toIntOrNull()?.minus(1)?.coerceAtLeast(0) ?: 0
        )
    }
}

class BookmarkPanel(
    private val project: Project,
    private val viewModel: BookmarkViewModel
) : JPanel(BorderLayout()), Disposable {
    private val logger = Logger.getInstance(BookmarkPanel::class.java)
    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode("Loading"))
    private lateinit var tree: BookmarkDropPreviewTree
    private lateinit var scope: CoroutineScope
    private val renderer = BookmarkTreeCellRenderer()
    private val indexService = BookmarkIndexService.getInstance(project)
    private var currentRoot: BookmarkNode.Group? = null
    private var currentReferenceCounts: Map<String, Int> = emptyMap()
    private var currentReferenceTargets: Set<String> = emptySet()
    private var currentTargetsBySource: Map<String, List<String>> = emptyMap()
    private var currentSourcesByTarget: Map<String, List<String>> = emptyMap()
    private var currentState: BookmarkViewState = BookmarkViewState()
    private var isSelectingFromSideEffect: Boolean = false
    private var lastAppliedSelectedNodeId: String? = null
    private var lastRefreshEpoch: Long = -1L
    private var searchQuery: String = ""
    private var searchResult: BookmarkIndexService.SearchResult = BookmarkIndexService.SearchResult.EMPTY
    private var lastGroupIconToggle: Pair<TreePath, Long>? = null
    private lateinit var treeSpeedSearch: TreeSpeedSearch
    private var speedSearchKeyDispatcher: KeyEventDispatcher? = null
    private var searchBootstrapExpandIds: Set<String> = emptySet()
    // true 时表示展开/折叠由 applyExpandedIdsToTree 程序触发，监听器不得回灌 Expand/CollapseNode intent
    private var isApplyingExpansion: Boolean = false
    // true 时表示展开由搜索导航自动触发，计入临时集而非持久 expandedNodeIds
    private var isSearchAutoExpanding: Boolean = false
    private var isHandlingTreeNavigationKey: Boolean = false
    private var isKeyboardTreeNavigating: Boolean = false
    private val treeNavigationKeyDeduplicator = TreeNavigationKeyDeduplicator()
    private var detailsPopup: JBPopup? = null
    // 上次完整重建时使用的搜索临时展开集，用于判定纯选中/导航更新可跳过重建
    private var lastBuiltSearchAutoIds: Set<String> = emptySet()
    // 与 ViewModel expandedNodeIds 合并的 UI 展开缓存（Gutter/拖拽等重建前同步当前 JTree 展开，防全量收缩）
    private val gutterPersistExpandIds: MutableSet<String> = linkedSetOf()
    private var searchMode: TreeSearchMode = TreeSearchMode.VISIBLE_ONLY
    private var pendingSearchMode: Boolean = false
    private var forceNextSearchFull: Boolean = false
    private var speedSearchPrefix: String = ""
    private var lastPanelInteractionAtMillis: Long = 0L
    private val searchModeLabel = JLabel().apply {
        isVisible = false
        font = font.deriveFont(Font.PLAIN, 11.5f)
        foreground = JBColor(0x9AA0A6, 0x9AA0A6)
        background = JBColor(0xF5F7FA, 0x2B2D30)
        isOpaque = true
        horizontalAlignment = SwingConstants.CENTER
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor(0xD8DEE9, 0x3C4043), 1),
            JBUI.Borders.empty(2, 8)
        )
    }
    private val searchModeArea = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, 0, 4, 8)
        preferredSize = Dimension(JBUI.scale(148), JBUI.scale(32))
        minimumSize = preferredSize
        maximumSize = preferredSize
        add(searchModeLabel)
    }

    init {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        tree = BookmarkDropPreviewTree(treeModel)
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.toggleClickCount = 0
        tree.toolTipText = ""
        tree.cellRenderer = renderer
        treeSpeedSearch = AnchoredTreeSpeedSearch(tree, false, { path ->
            val nodeView = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? NodeView
            nodeView?.displayName ?: ""
        }) { searchModeArea }
        treeSpeedSearch.addChangeListener(PropertyChangeListener { event ->
            if (event.propertyName == "enteredPrefix") {
                onSpeedSearchPrefixChanged(event.newValue as? String)
            }
        })
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.addTreeSelectionListener {
            logger.debug("=== TreeSelectionListener triggered ===")
            // 检查是否有选择，如果没有选择则直接返回
            val selectedPath = tree.selectionPath
            logger.debug("TreeSelectionListener: selectedPath=${selectedPath != null}, isSelectingFromSideEffect=$isSelectingFromSideEffect")
            if (selectedPath == null) {
                logger.debug("TreeSelectionListener: no selection path, ignoring")
                return@addTreeSelectionListener
            }

            logger.debug("TreeSelectionListener: selection path exists, processing selection")

            if (!isSelectingFromSideEffect && isSpeedSearchActive()) {
                logger.debug("SpeedSearch active -> highlight only, skip editor navigation")
                val node = selectedNode()
                lastAppliedSelectedNodeId = node?.uuid
                if (!isKeyboardTreeNavigating && node != null) {
                    viewModel.processIntent(BookmarkIntent.SelectNode(node.uuid))
                }
                SelectionBus.getInstance(project).setCurrentContainerId(currentContainerId())
                SelectionBus.getInstance(project).setLastSelectedNodeId(node?.uuid)
                renderer.highlightNodeId = node?.uuid
                tree.repaint()
                updateExpandCurrentButtonState()
                return@addTreeSelectionListener
            }

            val node = selectedNode()
            if (isSelectingFromSideEffect) {
                lastAppliedSelectedNodeId = node?.uuid
            } else {
                lastAppliedSelectedNodeId = node?.uuid
                if (node != null) {
                    viewModel.processIntent(BookmarkIntent.SelectNode(node.uuid))
                }
            }
            SelectionBus.getInstance(project).setCurrentContainerId(currentContainerId())
            SelectionBus.getInstance(project).setLastSelectedNodeId(node?.uuid)
            renderer.highlightNodeId = node?.uuid
            tree.repaint()
            updateExpandCurrentButtonState()
        }
        suppressDefaultTreeVerticalKeys(tree)
        tree.addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
            override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent) {
                val treeNode = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val node = treeNode.userObject as? NodeView ?: return
                if (BookmarkTreeUtil.hasPlaceholder(treeNode)) {
                    populateChildren(treeNode, node.node)
                }
                // 程序化对齐展开状态时不回灌 intent，避免侵蚀 expandedNodeIds 造成级联收缩
                if (isApplyingExpansion) return
                // 搜索导航自动展开计入临时集，退出搜索后回收
                if (isSearchAutoExpanding) {
                    searchBootstrapExpandIds = searchBootstrapExpandIds + node.node.uuid
                    return
                }
                viewModel.processIntent(BookmarkIntent.ExpandNode(node.node.uuid))
            }

            override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent) {
                if (isApplyingExpansion) return
                val node = (event.path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? NodeView ?: return
                // 用户手动折叠：从临时集移除并持久化为折叠
                searchBootstrapExpandIds = searchBootstrapExpandIds - node.node.uuid
                gutterPersistExpandIds.remove(node.node.uuid)
                viewModel.processIntent(BookmarkIntent.CollapseNode(node.node.uuid))
            }
        })

        tree.dragEnabled = true
        tree.dropMode = DropMode.ON_OR_INSERT
        tree.transferHandler = BookmarkTreeDnDHandler(
            selectedNode = { selectedNode() },
            currentRoot = { currentRoot },
            isDescendant = { node, targetId -> BookmarkDomainTree.isDescendant(node, targetId) },
            currentDropPlacement = { tree.dropPreview },
            moveNode = { nodeId, parentId, index ->
                viewModel.processIntent(BookmarkIntent.MoveNode(nodeId, parentId, index))
            }
        )
        installDropPreview()

        val treeActions = BookmarkTreeActions(
            tree = tree,
            callbacks = BookmarkTreeActions.Callbacks(
                refresh = { viewModel.processIntent(BookmarkIntent.Refresh) },
                collapseAll = { collapseAll() },
                expandCurrent = { expandToCurrent() },
                canExpandCurrent = { SelectionBus.getInstance(project).getLastSelectedNodeId() != null },
                createRootFile = { createRootFile() },
                createGroup = { createGroup() },
                createProcess = { createProcess() },
                createBookmark = { createBookmark() },
                createDescriptive = { createDescriptive() },
                editSelected = { editSelected() },
                editSelectedBookmarkOrGroup = { editSelectedBookmarkOrGroup() },
                moveSelected = { moveSelected() },
                deleteSelected = { deleteSelected() },
                addToProcess = { addToProcess() },
                copyToProcess = { copyToProcess() },
                setProcessEntry = { setProcessEntry() },
                navigatePrev = { navigateToPrevCodemarkGlobal() },
                navigateNext = { navigateToNextCodemarkGlobal() },
                moveSibling = { delta -> moveSelectedSibling(delta) },
                activateSelected = { activateSelectedNode() },
                expandAllNestedUnderGroup = { expandAllNestedUnderSelectedGroup() },
                canExpandAllNestedUnderGroup = { selectedNode() is BookmarkNode.Group },
                selectedNode = { selectedNode() },
                prepareContextMenu = { event -> prepareTreeContextMenu(event) },
                toggleSearchMode = { toggleSearchModeFromFindShortcut() }
            )
        )
        tree.componentPopupMenu = treeActions.createPopupMenu()
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                markPanelInteractionForSearch()
                logger.debug("Mouse pressed: button=${e.button}, isPopupTrigger=${e.isPopupTrigger}, clickCount=${e.clickCount}")
                treeActions.onContextMenuMouseEvent(e)
                if (toggleGroupFromNodeIconClick(e)) {
                    return
                }
                if (e.isPopupTrigger || e.button == MouseEvent.BUTTON1) {
                    if (!selectNodeAt(e)) {
                        focusTreeForSearch()
                    }
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                logger.debug("Mouse released: button=${e.button}, isPopupTrigger=${e.isPopupTrigger}, clickCount=${e.clickCount}")
                treeActions.onContextMenuMouseEvent(e)
                if (e.isPopupTrigger) {
                    prepareTreeContextMenu(e)
                }
            }

            override fun mouseClicked(e: MouseEvent) {
                markPanelInteractionForSearch()
                logger.debug("Mouse clicked: button=${e.button}, clickCount=${e.clickCount}, isSelectingFromSideEffect=$isSelectingFromSideEffect")
                if (e.isConsumed) {
                    return
                }
                if (e.button == MouseEvent.BUTTON1 && e.clickCount == 2) {
                    if (wasGroupIconToggledForDoubleClick(e)) {
                        e.consume()
                        return
                    }
                    if (toggleGroupAt(e)) {
                        e.consume()
                    }
                    return
                }
                if (e.button == MouseEvent.BUTTON1 && e.clickCount == 1) {
                    if (!isNodeClick(e)) {
                        focusTreeForSearch()
                        return
                    }
                    // 保障在节点被删除后重新点击时也能正确选中
                    if (tree.selectionPath == null) {
                        selectNodeAt(e)
                    }
                    logger.debug("Single left click detected, navigating to bookmark")
                    activateSelectedNode()
                }
            }
        })

        val toolbar = treeActions.createToolbar()
        val topPanel = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.WEST)
            add(searchModeArea, BorderLayout.EAST)
        }

        val scrollPane = JBScrollPane(tree)
        installBlankViewportFocus(scrollPane)
        installFindShortcutBindings(topPanel, scrollPane)

        add(topPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        installFindShortcutBindings(this, scrollPane.viewport)

        tree.navigationKeyHandler = { event -> dispatchTreeNavigationKey(event, requireFocusContext = false) }
        treeActions.installShortcuts()
        installTreeNavigationKeyDispatcher()
        suppressDefaultTreeVerticalKeys(tree)
        updateExpandCurrentButtonState()

        viewModel.state.onEach { state ->
            updateTree(state)
        }.launchIn(scope)

        viewModel.sideEffects.onEach { effect ->
            handleSideEffect(effect)
        }.launchIn(scope)

        SelectionBus.getInstance(project).requests.onEach { req ->
            // 同步并入祖先展开 id，避免与异步 ExpandNode / Refresh 竞态导致深节点被折叠
            persistAncestorExpansion(req.nodeId)
            isSelectingFromSideEffect = true
            try {
                if (expandToNodeByDomainPath(req.nodeId)) {
                    lastAppliedSelectedNodeId = req.nodeId
                    SelectionBus.getInstance(project).setLastSelectedNodeId(req.nodeId)
                    renderer.highlightNodeId = req.nodeId
                    tree.repaint()
                    if (req.focusTree) tree.requestFocusInWindow()
                    return@onEach
                }
                if (selectNodeById(req.nodeId)) {
                    lastAppliedSelectedNodeId = req.nodeId
                    SelectionBus.getInstance(project).setLastSelectedNodeId(req.nodeId)
                    renderer.highlightNodeId = req.nodeId
                    highlightSelectedRow()
                    if (req.focusTree) tree.requestFocusInWindow()
                    return@onEach
                }
                val resolvedId = req.filePath?.let { path -> indexService.firstMatch(path, req.line)?.nodeId }
                if (resolvedId != null) {
                    persistAncestorExpansion(resolvedId)
                    if (expandToNodeByDomainPath(resolvedId) || selectNodeById(resolvedId)) {
                        SelectionBus.getInstance(project).setLastSelectedNodeId(resolvedId)
                        renderer.highlightNodeId = resolvedId
                        tree.repaint()
                        if (req.focusTree) tree.requestFocusInWindow()
                        return@onEach
                    }
                }
                if (req.filePath != null && selectNodeByPathAndLine(req.filePath, req.line)) {
                    selectedNode()?.uuid?.let { id ->
                        SelectionBus.getInstance(project).setLastSelectedNodeId(id)
                        renderer.highlightNodeId = id
                        tree.repaint()
                        if (req.focusTree) tree.requestFocusInWindow()
                    }
                    return@onEach
                }
                viewModel.processIntent(BookmarkIntent.Refresh)
                javax.swing.SwingUtilities.invokeLater {
                    isSelectingFromSideEffect = true
                    try {
                        persistAncestorExpansion(req.nodeId)
                        if (expandToNodeByDomainPath(req.nodeId)) {
                            SelectionBus.getInstance(project).setLastSelectedNodeId(req.nodeId)
                            renderer.highlightNodeId = req.nodeId
                            tree.repaint()
                        } else {
                            selectNodeWithRetry(req.nodeId)
                        }
                        if (req.focusTree) tree.requestFocusInWindow()
                    } finally {
                        isSelectingFromSideEffect = false
                    }
                }
            } finally {
                isSelectingFromSideEffect = false
            }
        }.launchIn(scope)

        SwingUtilities.invokeLater { tree.requestFocusInWindow() }
    }

    private fun installDropPreview() {
        val listener = object : DropTargetAdapter() {
            override fun dragOver(event: DropTargetDragEvent) {
                updateDropPreview(event.location)
            }

            override fun dragExit(event: DropTargetEvent) {
                clearDropPreview()
            }

            override fun drop(event: DropTargetDropEvent) {
                clearDropPreview()
            }
        }
        try {
            tree.dropTarget?.addDropTargetListener(listener)
        } catch (_: java.util.TooManyListenersException) {
            logger.debug("Drop preview listener already installed")
        }
    }

    private fun updateDropPreview(point: Point) {
        val placement = BookmarkTreeDropSupport.placementForPoint(
            tree,
            point,
            canDropInto = { treeNode ->
                when ((treeNode.userObject as? NodeView)?.node) {
                    is BookmarkNode.Group, is BookmarkNode.Process -> true
                    else -> false
                }
            }
        )
        if (tree.dropPreview != placement) {
            tree.dropPreview = placement
        }
    }

    private fun clearDropPreview() {
        if (tree.dropPreview != null) {
            tree.dropPreview = null
        }
    }

    private fun toggleGroupFromNodeIconClick(event: MouseEvent): Boolean {
        if (event.button != MouseEvent.BUTTON1) return false
        if (event.clickCount != 1) return false
        val path = BookmarkTreeUtil.pathForNodeIconClick(tree, event.x, event.y) ?: return false
        if (!isGroupPath(path)) return false
        BookmarkTreeUtil.togglePathExpansion(tree, path)
        lastGroupIconToggle = path to event.`when`
        event.consume()
        return true
    }

    private fun toggleGroupAt(event: MouseEvent): Boolean {
        val path = tree.getPathForLocation(event.x, event.y) ?: return false
        if (!isGroupPath(path)) return false
        BookmarkTreeUtil.togglePathExpansion(tree, path)
        return true
    }

    private fun wasGroupIconToggledForDoubleClick(event: MouseEvent): Boolean {
        val currentPath = tree.getPathForLocation(event.x, event.y) ?: return false
        val (lastPath, lastTime) = lastGroupIconToggle ?: return false
        return currentPath == lastPath && event.`when` - lastTime <= 500L
    }

    private fun isGroupPath(path: TreePath): Boolean {
        val treeNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return false
        val node = (treeNode.userObject as? NodeView)?.node
        return node is BookmarkNode.Group
    }

    private fun suppressDefaultTreeVerticalKeys(tree: JTree) {
        val disabled = "none"
        if (tree.actionMap.get(disabled) == null) {
            tree.actionMap.put(disabled, object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent?) {}
            })
        }
        val strokes = listOf(
            KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0),
            KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0),
            KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0),
            KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0)
        )
        listOf(
            JComponent.WHEN_FOCUSED,
            JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
            2 // InputMap.WHEN_IN_FOCUSED_WINDOW
        ).forEach { whenFocused ->
            strokes.forEach { stroke ->
                tree.getInputMap(whenFocused).put(stroke, disabled)
            }
        }
    }

    private fun moveTreeSelection(delta: Int) {
        val searchRelevantIds = if (isSpeedSearchActive()) searchNavigationStopIds() else null
        isKeyboardTreeNavigating = true
        try {
            if (searchRelevantIds != null) {
                isSearchAutoExpanding = true
                try {
                    BookmarkTreeUtil.moveSelectionConsideringLazyLoad(tree, delta, searchRelevantIds)
                } finally {
                    isSearchAutoExpanding = false
                }
            } else {
                BookmarkTreeUtil.moveSelectionConsideringLazyLoad(tree, delta)
            }
        } finally {
            isKeyboardTreeNavigating = false
        }
    }

    private fun dispatchTreeNavigationKey(event: KeyEvent, requireFocusContext: Boolean): Boolean {
        if (event.id != KeyEvent.KEY_PRESSED) return false
        if (event.isConsumed) return true
        if (isHandlingTreeNavigationKey) return true
        if (isFindShortcut(event)) {
            if (requireFocusContext && !isFindShortcutContext(event.component)) return false
            event.consume()
            toggleSearchModeFromFindShortcut()
            return true
        }
        if (requireFocusContext && !isTreeNavigationFocusContext(event.component)) return false
        if (event.keyCode == KeyEvent.VK_ESCAPE && isSearchInteractionActive()) {
            event.consume()
            exitSearchMode()
            treeSpeedSearch.hidePopup()
            return true
        }
        if (event.keyCode == KeyEvent.VK_ENTER) {
            event.consume()
            activateSelectedNode()
            if (isSpeedSearchActive()) {
                exitSearchMode()
                treeSpeedSearch.hidePopup()
            }
            return true
        }
        if (event.keyCode != KeyEvent.VK_LEFT &&
            event.keyCode != KeyEvent.VK_RIGHT &&
            event.keyCode != KeyEvent.VK_UP &&
            event.keyCode != KeyEvent.VK_DOWN
        ) {
            return false
        }
        if (hasTreeNavigationModifier(event)) return false
        if (!treeNavigationKeyDeduplicator.shouldHandle(event)) {
            event.consume()
            return true
        }
        event.consume()
        isHandlingTreeNavigationKey = true
        try {
            when (event.keyCode) {
                KeyEvent.VK_LEFT -> BookmarkTreeUtil.collapseForNavigation(tree)
                KeyEvent.VK_RIGHT -> BookmarkTreeUtil.expandForNavigation(tree, ::populateChildren)
                KeyEvent.VK_UP -> moveTreeSelection(-1)
                KeyEvent.VK_DOWN -> moveTreeSelection(1)
            }
        } finally {
            isHandlingTreeNavigationKey = false
        }
        return true
    }

    private fun installTreeNavigationKeyDispatcher() {
        speedSearchKeyDispatcher?.let {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it)
        }
        speedSearchKeyDispatcher = KeyEventDispatcher { event ->
            dispatchTreeNavigationKey(event, requireFocusContext = true)
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(speedSearchKeyDispatcher)
    }

    private fun hasTreeNavigationModifier(event: KeyEvent): Boolean {
        val mask = KeyEvent.ALT_DOWN_MASK or KeyEvent.CTRL_DOWN_MASK or KeyEvent.META_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK
        return event.modifiersEx and mask != 0
    }

    private fun isFindShortcut(event: KeyEvent): Boolean {
        if (event.id != KeyEvent.KEY_PRESSED || event.keyCode != KeyEvent.VK_F) return false
        val modifiers = event.modifiersEx
        val findMask = KeyEvent.CTRL_DOWN_MASK or KeyEvent.META_DOWN_MASK
        val blockedMask = KeyEvent.ALT_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK
        return modifiers and findMask != 0 && modifiers and blockedMask == 0
    }

    private fun isFindShortcutContext(component: Component?): Boolean {
        return shouldHandleTreeFindShortcut(
            hasFocusContext = isTreeNavigationFocusContext(component),
            isToolWindowActive = isEzCodeMarksToolWindowActive(),
            hasRecentPanelInteraction = hasRecentPanelInteractionForSearch()
        )
    }

    private fun isTreeNavigationFocusContext(component: Component?): Boolean {
        if (component == null) return false
        if (SwingUtilities.isDescendingFrom(component, this)) return true
        if (SwingUtilities.isDescendingFrom(component, tree)) return true
        val treeParent = tree.parent
        if (treeParent != null && SwingUtilities.isDescendingFrom(component, treeParent)) return true
        if (tree.isFocusOwner) return true
        if (isEzCodeMarksToolWindowActive()) return true
        if (!treeSpeedSearch.isPopupActive) return false
        val treeWindow = SwingUtilities.getWindowAncestor(tree)
        val componentWindow = SwingUtilities.getWindowAncestor(component)
        return treeWindow != null && treeWindow === componentWindow
    }

    private fun isEzCodeMarksToolWindowActive(): Boolean {
        return ToolWindowManager.getInstance(project).activeToolWindowId == TOOL_WINDOW_ID
    }

    private fun onSpeedSearchPrefixChanged(newPrefix: String?) {
        val normalized = newPrefix?.trim().orEmpty()
        if (speedSearchPrefix == normalized) return
        speedSearchPrefix = normalized
        val wasBlank = searchQuery.isBlank()
        if (normalized.isBlank()) {
            exitSearchMode()
            return
        }

        val effectiveMode = resolveTreeSearchModeForPrefix(searchMode, forceNextSearchFull)
        forceNextSearchFull = false
        pendingSearchMode = false
        searchMode = effectiveMode
        searchQuery = normalized
        searchResult = indexService.search(normalized)
        if (effectiveMode == TreeSearchMode.FULL) {
            bootstrapSearchExpansion(normalized)
        } else {
            searchBootstrapExpandIds = emptySet()
        }

        if (wasBlank || effectiveMode == TreeSearchMode.FULL) {
            refreshTreeFromCurrentState()
            installTreeNavigationKeyDispatcher()
        } else {
            tree.repaint()
        }
        syncRendererSearchHighlight()
        updateSearchModeLabel()
    }

    private fun syncRendererSearchHighlight() {
        renderer.speedSearchHighlightEnabled = searchQuery.isNotBlank()
    }

    private fun updateSearchModeLabel() {
        searchModeLabel.text = if (shouldShowSearchModeBadge()) {
            treeSearchModeBadgeText(searchMode)
        } else {
            ""
        }
        searchModeLabel.isVisible = shouldShowSearchModeBadge()
        (treeSpeedSearch as? AnchoredTreeSpeedSearch)?.moveToAnchor()
    }

    private fun toggleSearchModeFromFindShortcut() {
        if (searchQuery.isBlank() && !treeSpeedSearch.isPopupActive) {
            searchMode = treeSearchModeBeforeQueryInput()
            pendingSearchMode = true
            forceNextSearchFull = true
            focusTreeForSearch()
            installTreeNavigationKeyDispatcher()
            updateSearchModeLabel()
            return
        }

        val wasActive = isSearchInteractionActive()
        searchMode = nextTreeSearchModeOnFind(searchMode, wasActive)
        if (wasActive) {
            if (searchMode == TreeSearchMode.FULL) {
                bootstrapSearchExpansion(searchQuery)
            } else {
                searchBootstrapExpandIds = emptySet()
            }
            refreshTreeFromCurrentState()
            syncRendererSearchHighlight()
            updateSearchModeLabel()
        } else {
            pendingSearchMode = true
            forceNextSearchFull = true
            tree.requestFocusInWindow()
            installTreeNavigationKeyDispatcher()
            updateSearchModeLabel()
        }
    }

    /** ESC/清空前缀：清除搜索高亮与临时展开，恢复非搜索 ↑↓←→ 语义 */
    private fun exitSearchMode() {
        pendingSearchMode = false
        forceNextSearchFull = false
        speedSearchPrefix = ""
        searchQuery = ""
        searchResult = BookmarkIndexService.SearchResult.EMPTY
        searchBootstrapExpandIds = emptySet()
        lastBuiltSearchAutoIds = emptySet()
        isSearchAutoExpanding = false
        searchMode = treeSearchModeAfterExit()
        renderer.speedSearchHighlightEnabled = false
        refreshTreeAfterSearchExit()
        updateSearchModeLabel()
    }

    private fun refreshTreeAfterSearchExit() {
        val state = currentState
        val rootNode = state.rootNode ?: run {
            tree.repaint()
            return
        }
        val currentRootNode = treeModel.root as? DefaultMutableTreeNode
        if (currentRootNode == null) {
            refreshTreeFromCurrentState()
            tree.repaint()
            return
        }
        // 保存当前选中节点ID，退出搜索后恢复选中状态
        val selectedNodeId = lastAppliedSelectedNodeId
        mergeLiveTreeExpansionIntoPersistCache()
        val baseExpanded = effectiveExpandedNodeIds(state)
        val expandedNodeIds = expandedIdsWithAncestors(rootNode, baseExpanded)
        applyStructureDiff(rootNode, expandedNodeIds)
        val existingIds = BookmarkDomainTree.existingIds(rootNode)
        applyExpandedIdsToTree(expandedNodeIds.filterTo(mutableSetOf()) { it in existingIds }, collapseOthers = false)
        gutterPersistExpandIds.removeAll { it in state.expandedNodeIds }
        // 恢复选中状态
        if (selectedNodeId != null) {
            selectNodeById(selectedNodeId)
            renderer.highlightNodeId = selectedNodeId
            SelectionBus.getInstance(project).setLastSelectedNodeId(selectedNodeId)
        }
        tree.repaint()
    }

    private fun bootstrapSearchExpansion(query: String) {
        // 仅写入临时集（经 effectiveExpandedNodeIds 参与渲染），不持久化；退出搜索后回收
        searchBootstrapExpandIds = computeSearchFullExpansion(query)
    }

    private fun computeSearchFullExpansion(query: String): Set<String> {
        val root = currentRoot ?: return emptySet()
        val result = indexService.search(query)
        val directMatchIds = result.directMatchNodeIds + indexService.matchingNodeIdsByName(query)
        if (directMatchIds.isEmpty()) return emptySet()
        val toExpand = linkedSetOf<String>()
        directMatchIds.forEach { matchId ->
            val path = BookmarkDomainTree.pathFromRootTo(root, matchId) ?: return@forEach
            path.dropLast(1).forEach { ancestor ->
                if (ancestor.hasTreeChildren()) {
                    toExpand.add(ancestor.uuid)
                }
            }
        }
        return toExpand
    }

    private fun BookmarkNode.hasTreeChildren(): Boolean {
        return when (this) {
            is BookmarkNode.Group -> children.isNotEmpty()
            is BookmarkNode.Process -> steps.isNotEmpty()
            else -> false
        }
    }

    private fun effectiveExpandedNodeIds(state: BookmarkViewState): Set<String> {
        val base = if (searchQuery.isBlank()) {
            state.expandedNodeIds
        } else {
            state.expandedNodeIds + searchBootstrapExpandIds
        }
        return if (gutterPersistExpandIds.isEmpty()) base else base + gutterPersistExpandIds
    }

    /** 多 file root 时内层展开必须连带祖先（含顶层 file root），否则 builder/expand 无法落到可见路径 */
    private fun expandedIdsWithAncestors(root: BookmarkNode.Group, ids: Set<String>): Set<String> {
        if (ids.isEmpty()) return ids
        val out = linkedSetOf<String>()
        ids.forEach { id ->
            val path = BookmarkDomainTree.pathFromRootTo(root, id) ?: return@forEach
            path.forEach { out.add(it.uuid) }
        }
        return out
    }

    private fun refreshTreeFromCurrentState() {
        updateTree(currentState)
    }

    private fun isSpeedSearchActive(): Boolean {
        return searchQuery.isNotBlank()
    }

    private fun isSearchUiActive(): Boolean {
        return searchQuery.isNotBlank() || treeSpeedSearch.isPopupActive
    }

    private fun isSearchInteractionActive(): Boolean {
        return pendingSearchMode || isSearchUiActive()
    }

    private fun shouldShowSearchModeBadge(): Boolean {
        return shouldShowTreeSearchModeBadge(
            pendingSearchMode = pendingSearchMode,
            hasQuery = searchQuery.isNotBlank(),
            isPopupActive = treeSpeedSearch.isPopupActive
        )
    }

    /**
     * 搜索期 ↑↓ 落点：所有可见节点，但过滤掉展开的容器节点（仅作为路径）。
     */
    private fun searchNavigationStopIds(): Set<String> {
        if (searchQuery.isBlank()) return emptySet()
        val stops = linkedSetOf<String>()
        for (row in 0 until tree.rowCount) {
            val path = tree.getPathForRow(row) ?: continue
            val treeNode = path.lastPathComponent as? DefaultMutableTreeNode ?: continue
            val nodeView = treeNode.userObject as? NodeView ?: continue

            if (searchMode == TreeSearchMode.VISIBLE_ONLY) {
                // Only consider currently visible nodes, don't expand collapsed containers
                if (hasSpeedSearchHighlight(nodeView.displayName)) {
                    stops.add(nodeView.node.uuid)
                    logger.debug("[SEARCH_STOP] Added highlighted visible node '${nodeView.displayName}'")
                }
            } else {
                // Full mode: include collapsed containers with highlighted descendants
                if (hasSpeedSearchHighlight(nodeView.displayName)) {
                    stops.add(nodeView.node.uuid)
                    logger.debug("[SEARCH_STOP] Added highlighted visible node '${nodeView.displayName}'")
                } else if (isCollapsedContainerWithHighlightedDescendant(path, nodeView.node)) {
                    stops.add(nodeView.node.uuid)
                    logger.debug("[SEARCH_STOP] Added collapsed container proxy '${nodeView.displayName}'")
                }
            }
        }
        logger.debug("[SEARCH_STOP] Final stops count=${stops.size}")
        return stops
    }

    private fun hasSpeedSearchHighlight(displayName: String): Boolean {
        val speedSearch = SpeedSearchSupply.getSupply(tree, true) ?: return false
        val ranges = speedSearch.matchingFragments(displayName) ?: return false
        return ranges.iterator().hasNext()
    }

    private fun isCollapsedContainerWithHighlightedDescendant(path: TreePath, node: BookmarkNode): Boolean {
        if (tree.isExpanded(path)) return false
        val children = node.searchNavigationChildren()
        if (children.isEmpty()) return false
        return children.any { child -> hasHighlightedNodeInSubtree(child) }
    }

    private fun hasHighlightedNodeInSubtree(node: BookmarkNode): Boolean {
        val displayName = node.name.ifBlank { "(unnamed)" }
        if (hasSpeedSearchHighlight(displayName)) return true
        return node.searchNavigationChildren().any { child -> hasHighlightedNodeInSubtree(child) }
    }

    private fun BookmarkNode.searchNavigationChildren(): List<BookmarkNode> {
        return when (this) {
            is BookmarkNode.Group -> children
            is BookmarkNode.Process -> steps
            else -> emptyList()
        }
    }

    private fun updateTree(state: BookmarkViewState) {
        val prevState = currentState
        currentState = state
        currentRoot = state.rootNode
        currentReferenceCounts = state.referenceCounts
        currentReferenceTargets = state.referenceTargets
        currentTargetsBySource = state.referenceTargetsBySource
        currentSourcesByTarget = state.referenceSourcesByTarget
        searchResult = if (searchQuery.isBlank()) BookmarkIndexService.SearchResult.EMPTY else indexService.search(searchQuery)
        val rootNode = state.rootNode
        val currentRootNode = treeModel.root as? DefaultMutableTreeNode
        val forceRebuild = state.refreshEpoch != lastRefreshEpoch
        lastRefreshEpoch = state.refreshEpoch
        val refreshKind = if (forceRebuild) TreeRefreshKind.FULL else state.treeRefreshKind
        val expansionChanged = prevState.expandedNodeIds != state.expandedNodeIds ||
            searchBootstrapExpandIds != lastBuiltSearchAutoIds ||
            forceRebuild
        val referencesChanged = prevState.referenceCounts != state.referenceCounts ||
            prevState.referenceTargets != state.referenceTargets

        when (refreshKind) {
            TreeRefreshKind.SKIP -> {
                val expandedNodeIds = effectiveExpandedNodeIds(state)
                if (expansionChanged && currentRootNode != null) {
                    val existingIds = rootNode?.let { BookmarkDomainTree.existingIds(it) } ?: emptySet()
                    applyExpandedIdsToTree(expandedNodeIds.filterTo(mutableSetOf()) { it in existingIds }, collapseOthers = true)
                    lastBuiltSearchAutoIds = searchBootstrapExpandIds
                    gutterPersistExpandIds.removeAll { it in state.expandedNodeIds }
                } else if (referencesChanged && currentRootNode != null && rootNode != null) {
                    applyStructureDiff(rootNode, expandedNodeIds)
                }
            }
            TreeRefreshKind.DIFF -> {
                mergeLiveTreeExpansionIntoPersistCache()
                val baseExpanded = effectiveExpandedNodeIds(state)
                val expandedNodeIds = rootNode?.let { expandedIdsWithAncestors(it, baseExpanded) } ?: baseExpanded
                val fullReplace = applyStructureDiff(rootNode, expandedNodeIds)
                val existingIds = rootNode?.let { BookmarkDomainTree.existingIds(it) } ?: emptySet()
                val expandedInTree = expandedNodeIds.filterTo(mutableSetOf()) { it in existingIds }
                // applyDiff/setRoot 后 JTree 收拢；多 file root 时 invisible 根无 NodeView，必须 expand-only 且遍历子节点
                applyExpandedIdsToTree(expandedInTree, collapseOthers = false)
                lastBuiltSearchAutoIds = searchBootstrapExpandIds
                gutterPersistExpandIds.removeAll { it in state.expandedNodeIds }
            }
            TreeRefreshKind.FULL -> {
                mergeLiveTreeExpansionIntoPersistCache()
                val baseExpanded = effectiveExpandedNodeIds(state)
                val expandedNodeIds = rootNode?.let { expandedIdsWithAncestors(it, baseExpanded) } ?: baseExpanded
                applyStructureDiff(rootNode, expandedNodeIds, forceReplaceRoot = true)
                val existingIds = rootNode?.let { BookmarkDomainTree.existingIds(it) } ?: emptySet()
                applyExpandedIdsToTree(expandedNodeIds.filterTo(mutableSetOf()) { it in existingIds }, collapseOthers = true)
                lastBuiltSearchAutoIds = searchBootstrapExpandIds
                gutterPersistExpandIds.removeAll { it in state.expandedNodeIds }
            }
        }
        viewModel.ackTreeRefreshKind()

        val highlightNodeId = if (isSpeedSearchActive()) {
            selectedNode()?.uuid ?: state.selectedNodeId
        } else {
            state.selectedNodeId
        }
        highlightNodeId?.let { nodeId ->
            renderer.highlightNodeId = nodeId
            tree.repaint()
            if (isSpeedSearchActive()) {
                lastAppliedSelectedNodeId = nodeId
            } else if (lastAppliedSelectedNodeId != nodeId) {
                lastAppliedSelectedNodeId = nodeId
                javax.swing.SwingUtilities.invokeLater {
                    selectNodeWithRetry(nodeId, maxRetries = 5, delayMs = 100)
                    updateExpandCurrentButtonState()
                }
            }
        } ?: run {
            lastAppliedSelectedNodeId = null
        }
        if (highlightNodeId == null) {
            updateExpandCurrentButtonState()
        }
    }

    /** Repaint selected row without stealing focus (keep editor focus). */
    private fun highlightSelectedRow() {
        tree.selectionPath?.let { path ->
            tree.getPathBounds(path)?.let { tree.repaint(it) }
        }
    }

    /** Select node by file path and (optional) line number; expands path but does not steal focus. */
    private fun selectNodeByPathAndLine(filePath: String, line: Int?): Boolean {
        val normalized = FileUtil.toSystemIndependentName(filePath)
        val rootNode = treeModel.root as? DefaultMutableTreeNode ?: return false
        val target = BookmarkTreeUtil.findNode(rootNode) { view ->
            val node = view.node
            when (node) {
                is BookmarkNode.Bookmark -> FileUtil.toSystemIndependentName(node.filePath) == normalized && (line == null || node.line == line)
                is BookmarkNode.Process -> node.entryFilePath != null && FileUtil.toSystemIndependentName(node.entryFilePath) == normalized && (line == null || node.entryLine == line)
                else -> false
            }
        } ?: return false

        val path = TreePath(target.path)
        tree.selectionPath = path
        tree.expandPath(path)
        tree.scrollPathToVisible(path)
        highlightSelectedRow()
        return true
    }

    private fun selectedNode(): BookmarkNode? {
        val selected = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        if (selected == null) {
            // 没有选择是正常情况，使用 debug 级别
            logger.debug("selectedNode: tree.lastSelectedPathComponent is null or not DefaultMutableTreeNode")
            return null
        }
        val nodeView = selected.userObject as? NodeView
        if (nodeView == null) {
            // userObject 不是 NodeView 可能是异常情况，记录警告
            logger.warn("selectedNode: userObject is null or not NodeView, userObject=${selected.userObject?.javaClass?.simpleName}")
            return null
        }
        val node = nodeView.node
        logger.debug("selectedNode: found node ${node.uuid}, type=${node.javaClass.simpleName}")
        return node
    }

    private fun selectedNodes(): List<BookmarkNode> {
        val paths = tree.selectionPaths ?: return emptyList()
        return paths.mapNotNull { path ->
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return@mapNotNull null
            (node.userObject as? NodeView)?.node
        }.filter { it.uuid != BookmarkStore.SUPER_ROOT_UUID && it.uuid != "root" }
    }

    private fun insertionTarget(): Pair<String?, Int?> {
        val root = treeModel.root as? DefaultMutableTreeNode ?: return BookmarkStore.SUPER_ROOT_UUID to null
        val lastSelectedId = SelectionBus.getInstance(project).getLastSelectedNodeId()
        val selectedTreeNode = lastSelectedId?.let { BookmarkTreeUtil.findNodeById(root, it) }
        val selectedView = selectedTreeNode?.userObject as? NodeView
        val selectedNode = selectedView?.node

        return when (selectedNode) {
            is BookmarkNode.Group, is BookmarkNode.Process -> {
                val childCount = realChildCount(selectedTreeNode)
                selectedNode.uuid to childCount
            }
            is BookmarkNode.Bookmark, is BookmarkNode.DescriptiveBookmark -> {
                val parent = selectedTreeNode.parent as? DefaultMutableTreeNode
                val parentView = parent?.userObject as? NodeView
                val parentId = when (val pNode = parentView?.node) {
                    is BookmarkNode.Group -> pNode.uuid
                    is BookmarkNode.Process -> pNode.uuid
                    else -> BookmarkStore.SUPER_ROOT_UUID
                }
                val indexInParent = parent?.getIndex(selectedTreeNode)?.takeIf { it >= 0 }
                parentId to indexInParent?.plus(1)
            }
            else -> BookmarkStore.SUPER_ROOT_UUID to null
        }
    }

    private fun realChildCount(node: DefaultMutableTreeNode?): Int {
        if (node == null) return 0
        if (BookmarkTreeUtil.hasPlaceholder(node)) return 0
        return node.childCount
    }

    private fun currentContainerId(): String? {
        val selected = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        val node = (selected.userObject as? NodeView)?.node
        val containerId = when (node) {
            is BookmarkNode.Group -> node.uuid
            is BookmarkNode.Process -> node.uuid
            else -> {
                val parent = selected.parent as? DefaultMutableTreeNode ?: return null
                val parentNode = (parent.userObject as? NodeView)?.node
                when (parentNode) {
                    is BookmarkNode.Group -> parentNode.uuid
                    is BookmarkNode.Process -> parentNode.uuid
                    else -> null
                }
            }
        }
        return containerId
    }

    private fun selectNodeAt(event: MouseEvent): Boolean {
        logger.debug("selectNodeAt: x=${event.x}, y=${event.y}")
        val path = tree.getPathForLocation(event.x, event.y)
        logger.debug("selectNodeAt: path=${path != null}")
        if (path == null) return false
        BookmarkTreeUtil.selectPathForVerticalNavigation(tree, path)
        return true
    }

    /** 右键弹出前：与 ↑↓ 相同方式选中可导航行并同步 SelectionBus（不打开编辑器）。 */
    private fun prepareTreeContextMenu(event: MouseEvent?) {
        if (event == null) return
        if (!selectNodeAt(event)) return
        val path = tree.selectionPath ?: return
        val searchRelevantIds = if (isSpeedSearchActive()) searchNavigationStopIds() else null
        if (!BookmarkTreeUtil.isVerticalNavigationRow(tree, path, searchRelevantIds)) return
        val node = selectedNode() ?: return
        lastAppliedSelectedNodeId = node.uuid
        if (!isKeyboardTreeNavigating) {
            viewModel.processIntent(BookmarkIntent.SelectNode(node.uuid))
        }
        SelectionBus.getInstance(project).setCurrentContainerId(currentContainerId())
        SelectionBus.getInstance(project).setLastSelectedNodeId(node.uuid)
        renderer.highlightNodeId = node.uuid
        tree.repaint()
        updateExpandCurrentButtonState()
    }

    private fun isNodeClick(event: MouseEvent): Boolean {
        return tree.getPathForLocation(event.x, event.y) != null
    }

    private fun installBlankViewportFocus(scrollPane: JBScrollPane) {
        val blankFocusListener = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON1 || event.isPopupTrigger) {
                    markPanelInteractionForSearch()
                    focusTreeForSearch()
                }
            }

            override fun mouseClicked(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON1) {
                    markPanelInteractionForSearch()
                    focusTreeForSearch()
                }
            }
        }
        scrollPane.viewport.addMouseListener(blankFocusListener)
        scrollPane.addMouseListener(blankFocusListener)
    }

    private fun installFindShortcutBindings(vararg components: JComponent) {
        components.forEach { component ->
            treeFindShortcutStrokes().forEach { stroke ->
                val key = "ezCodeMarksFullSearch:${stroke}"
                component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, key)
                component.actionMap.put(key, object : AbstractAction() {
                    override fun actionPerformed(event: ActionEvent) {
                        markPanelInteractionForSearch()
                        toggleSearchModeFromFindShortcut()
                    }
                })
            }
        }
    }

    private fun markPanelInteractionForSearch() {
        lastPanelInteractionAtMillis = System.currentTimeMillis()
        installTreeNavigationKeyDispatcher()
    }

    private fun hasRecentPanelInteractionForSearch(): Boolean {
        return System.currentTimeMillis() - lastPanelInteractionAtMillis <= SEARCH_INTERACTION_GRACE_MS
    }

    private fun focusTreeForSearch() {
        if (!tree.hasFocus()) {
            IdeFocusManager.getInstance(project).requestFocus(tree, true)
            tree.requestFocusInWindow()
            tree.requestFocus()
        }
    }

    private fun selectNodeById(nodeId: String): Boolean {
        logger.debug("selectNodeById: searching for nodeId=$nodeId")
        val success = BookmarkTreeUtil.selectNodeById(tree, treeModel, nodeId)
        if (!success) {
            logger.warn("selectNodeById: node not found for nodeId=$nodeId")
        } else {
            logger.debug("selectNodeById: completed successfully")
        }
        return success
    }

    /**
     * 持久化目标节点沿域路径上各祖先容器的展开状态，使外部选中（如 Gutter）后刷新不会把它们收回。
     */
    private fun persistAncestorExpansion(nodeId: String) {
        val root = currentRoot ?: return
        val path = BookmarkDomainTree.pathFromRootTo(root, nodeId) ?: return
        path.dropLast(1).forEach { ancestor ->
            if (!ancestor.hasTreeChildren()) return@forEach
            gutterPersistExpandIds.add(ancestor.uuid)
            if (ancestor.uuid !in currentState.expandedNodeIds) {
                viewModel.processIntent(BookmarkIntent.ExpandNode(ancestor.uuid))
            }
        }
    }

    /**
     * Expand tree along domain path to [nodeId] and select that node (for gutter click).
     * Populates lazy nodes so deep bookmarks under collapsed groups are found.
     */
    private fun expandToNodeByDomainPath(nodeId: String): Boolean {
        val rootNode = currentRoot ?: return false
        val path = BookmarkDomainTree.pathFromRootTo(rootNode, nodeId) ?: return false
        if (path.isEmpty()) return false
        val treeRoot = treeModel.root as? DefaultMutableTreeNode ?: return false
        if (path.size == 1) {
            val pathTree = TreePath(treeRoot.path)
            tree.selectionPath = pathTree
            tree.expandPath(pathTree)
            tree.scrollPathToVisible(pathTree)
            highlightSelectedRow()
            isSelectingFromSideEffect = true
            javax.swing.SwingUtilities.invokeLater { isSelectingFromSideEffect = false }
            return true
        }
        var currentTreeNode = treeRoot
        for (i in 1 until path.size) {
            val domainNode = path[i]
            if (BookmarkTreeUtil.hasPlaceholder(currentTreeNode)) {
                populateChildren(currentTreeNode, path[i - 1])
            }
            val childTreeNode = BookmarkTreeUtil.nodeChildren(currentTreeNode).firstOrNull {
                BookmarkTreeUtil.getNodeView(it)?.node?.uuid == domainNode.uuid
            } ?: return false
            tree.expandPath(TreePath(childTreeNode.path))
            currentTreeNode = childTreeNode
        }
        tree.selectionPath = TreePath(currentTreeNode.path)
        tree.expandPath(tree.selectionPath)
        tree.scrollPathToVisible(tree.selectionPath)
        highlightSelectedRow()
        return true
    }
    
    /**
     * 带重试机制的节点选择
     * 因为树更新可能是异步的，需要等待树更新完成后再选择节点
     */
    private fun selectNodeWithRetry(nodeId: String, maxRetries: Int = 5, delayMs: Long = 100) {
        logger.debug("[SELECT_NODE_RETRY] Starting selectNodeWithRetry for nodeId=$nodeId, maxRetries=$maxRetries")
        var attempt = 0
        
        fun trySelect() {
            attempt++
            logger.debug("[SELECT_NODE_RETRY] Attempt $attempt/$maxRetries")
            
            // 设置标志，防止触发导航
            isSelectingFromSideEffect = true
            
            val success = selectNodeById(nodeId)
            
            if (success) {
                logger.debug("[SELECT_NODE_RETRY] Node selected successfully on attempt $attempt")
                
                // 确保展开到节点路径，包括所有父节点
                tree.selectionPath?.let { path ->
                    logger.debug("[SELECT_NODE_RETRY] Expanding path for nodeId=$nodeId")
                    // 展开路径上的所有节点，从当前节点向上到根节点
                    var currentPath: TreePath? = path
                    while (currentPath != null && currentPath.pathCount > 0) {
                        try {
                            tree.expandPath(currentPath)
                            val parent = currentPath.parentPath
                            if (parent == null || parent.pathCount == 0) {
                                // 到达根节点，停止展开
                                break
                            }
                            currentPath = parent
                        } catch (e: Exception) {
                            logger.warn("[SELECT_NODE_RETRY] Error expanding path: ${e.message}", e)
                            break
                        }
                    }
                    tree.scrollPathToVisible(path)
                } ?: logger.warn("[SELECT_NODE_RETRY] tree.selectionPath is null after selectNodeById")
                highlightSelectedRow()
                
                // 在下一个事件循环中清除标志
                javax.swing.SwingUtilities.invokeLater {
                    logger.debug("[SELECT_NODE_RETRY] Clearing isSelectingFromSideEffect flag")
                    isSelectingFromSideEffect = false
                }
            } else {
                if (attempt < maxRetries) {
                    logger.debug("[SELECT_NODE_RETRY] Node not found, will retry in ${delayMs}ms (attempt $attempt/$maxRetries)")
                    // 使用 Timer 延迟重试，避免阻塞 EDT
                    javax.swing.Timer(delayMs.toInt()) {
                        trySelect()
                    }.apply {
                        isRepeats = false
                        start()
                    }
                } else {
                    logger.warn("[SELECT_NODE_RETRY] Node not found after $maxRetries attempts, giving up")
                    isSelectingFromSideEffect = false
                }
            }
        }
        
        // 首次尝试延迟执行，确保树更新完成
        javax.swing.SwingUtilities.invokeLater {
            trySelect()
        }
    }

    /** 重建前把 JTree 当前展开并入缓存，避免 expandedNodeIds 滞后于 UI 时（如拖拽后）全树收缩。 */
    private fun mergeLiveTreeExpansionIntoPersistCache() {
        collectExpandedNodeIdsFromTree()
            .filterNot { it in searchBootstrapExpandIds }
            .forEach { gutterPersistExpandIds.add(it) }
    }

    private fun collectExpandedNodeIdsFromTree(): Set<String> {
        val root = treeModel.root as? DefaultMutableTreeNode ?: return emptySet()
        val ids = linkedSetOf<String>()
        collectExpandedNodeIdsRecursive(root, ids)
        return ids
    }

    private fun collectExpandedNodeIdsRecursive(node: DefaultMutableTreeNode, out: MutableSet<String>) {
        BookmarkTreeUtil.nodeChildren(node).forEach { child ->
            val childPath = TreePath(child.path)
            if (!tree.isExpanded(childPath)) return@forEach
            BookmarkTreeUtil.getNodeView(child)?.node?.uuid?.let { out.add(it) }
            collectExpandedNodeIdsRecursive(child, out)
        }
    }

    /**
     * @return true 表示已 setRoot（调用方应再 applyExpandedIdsToTree）
     */
    private fun applyStructureDiff(
        rootNode: BookmarkNode.Group?,
        expandedNodeIds: Set<String>,
        forceReplaceRoot: Boolean = false
    ): Boolean {
        val currentRootNode = treeModel.root as? DefaultMutableTreeNode ?: return true
        val newRootNode = BookmarkTreeModelBuilder(
            referenceCounts = currentReferenceCounts,
            referenceTargets = currentReferenceTargets,
            expandedNodeIds = expandedNodeIds,
            searchQuery = searchQuery,
            searchResult = searchResult,
            project = project
        ).build(rootNode)
        if (forceReplaceRoot) {
            treeModel.setRoot(newRootNode)
            return true
        }
        val updated = applyDiff(treeModel, currentRootNode, newRootNode)
        if (!updated) {
            treeModel.setRoot(newRootNode)
            return true
        }
        return false
    }

    private fun applyExpandedIdsToTree(expandedIds: Set<String>, collapseOthers: Boolean = true) {
        val root = treeModel.root as? DefaultMutableTreeNode ?: return
        isApplyingExpansion = true
        try {
            if (collapseOthers) {
                syncExpansionState(root, expandedIds)
            } else {
                syncExpansionStateExpandOnly(root, expandedIds)
            }
        } finally {
            isApplyingExpansion = false
        }
    }

    private fun syncExpansionState(node: DefaultMutableTreeNode, expandedIds: Set<String>) {
        val view = BookmarkTreeUtil.getNodeView(node)
        if (view == null) {
            BookmarkTreeUtil.nodeChildren(node).forEach { syncExpansionState(it, expandedIds) }
            return
        }
        val nodeId = view.node.uuid
        val path = TreePath(node.path)
        if (BookmarkTreeUtil.hasPlaceholder(node)) {
            if (nodeId in expandedIds) {
                populateChildren(node, view.node)
            } else {
                tree.collapsePath(path)
            }
        }
        if (node.childCount > 0 && !BookmarkTreeUtil.hasPlaceholder(node)) {
            if (nodeId in expandedIds) {
                tree.expandPath(path)
            } else {
                tree.collapsePath(path)
            }
            BookmarkTreeUtil.nodeChildren(node).forEach { syncExpansionState(it, expandedIds) }
        }
    }

    /** DIFF 后恢复展开：只 expand 目标节点，不 collapse 其它已展开行（避免拖拽后全量收缩） */
    private fun syncExpansionStateExpandOnly(node: DefaultMutableTreeNode, expandedIds: Set<String>) {
        val view = BookmarkTreeUtil.getNodeView(node)
        if (view == null) {
            BookmarkTreeUtil.nodeChildren(node).forEach { syncExpansionStateExpandOnly(it, expandedIds) }
            return
        }
        val nodeId = view.node.uuid
        val path = TreePath(node.path)
        if (nodeId !in expandedIds) {
            BookmarkTreeUtil.nodeChildren(node).forEach { syncExpansionStateExpandOnly(it, expandedIds) }
            return
        }
        if (BookmarkTreeUtil.hasPlaceholder(node)) {
            populateChildren(node, view.node)
        }
        if (node.childCount > 0 && !BookmarkTreeUtil.hasPlaceholder(node)) {
            tree.expandPath(path)
            BookmarkTreeUtil.nodeChildren(node).forEach { syncExpansionStateExpandOnly(it, expandedIds) }
        }
    }

    private fun populateChildren(treeNode: DefaultMutableTreeNode, node: BookmarkNode) {
        if (!BookmarkTreeUtil.hasPlaceholder(treeNode)) return
        treeNode.removeAllChildren()
        BookmarkTreeModelBuilder(
            referenceCounts = currentReferenceCounts,
            referenceTargets = currentReferenceTargets,
            expandedNodeIds = effectiveExpandedNodeIds(currentState),
            searchQuery = searchQuery,
            searchResult = searchResult,
            project = project
        ).buildChildren(node).forEach { treeNode.add(it) }
        treeModel.nodeStructureChanged(treeNode)
    }


    private fun applyDiff(
        model: DefaultTreeModel,
        current: DefaultMutableTreeNode,
        updated: DefaultMutableTreeNode
    ): Boolean {
        if (BookmarkTreeUtil.nodeKey(current) != BookmarkTreeUtil.nodeKey(updated)) return false

        // 更新当前节点的 userObject（NodeView），确保节点数据是最新的
        val currentView = current.userObject as? NodeView
        val updatedView = updated.userObject as? NodeView
        if (currentView != null && updatedView != null && currentView.node.uuid == updatedView.node.uuid) {
            // 如果节点数据已变化，更新 userObject
            if (currentView.node != updatedView.node || 
                currentView.referenceCount != updatedView.referenceCount ||
                currentView.isReferencedTarget != updatedView.isReferencedTarget) {
                current.userObject = updatedView
                model.nodeChanged(current)
            }
        }

        val existingChildren = BookmarkTreeUtil.nodeChildren(current)
        val updatedChildren = BookmarkTreeUtil.nodeChildren(updated)
        if (hasDuplicateNodeKeys(existingChildren) || hasDuplicateNodeKeys(updatedChildren)) {
            return false
        }
        if (hasMixedPlaceholderChildren(existingChildren) || hasMixedPlaceholderChildren(updatedChildren)) {
            return false
        }
        val existingMap = existingChildren.associateBy { BookmarkTreeUtil.nodeKey(it) }.toMutableMap()
        val desiredKeys = mutableListOf<String>()

        updatedChildren.forEachIndexed { index, updatedChild ->
            val key = BookmarkTreeUtil.nodeKey(updatedChild)
            desiredKeys.add(key)
            val existing = existingMap.remove(key)
            if (existing == null) {
                val copy = BookmarkTreeUtil.copyNode(updatedChild)
                model.insertNodeInto(copy, current, index.coerceAtMost(current.childCount))
            } else {
                if (!applyDiff(model, existing, updatedChild)) {
                    return false
                }
                val currentIndex = current.getIndex(existing)
                if (currentIndex != index) {
                    model.removeNodeFromParent(existing)
                    model.insertNodeInto(existing, current, index.coerceAtMost(current.childCount))
                }
            }
        }
        // remove extras
        existingMap.values.forEach { toRemove ->
            model.removeNodeFromParent(toRemove)
        }
        model.nodeStructureChanged(current)
        return true
    }

    private fun hasDuplicateNodeKeys(nodes: List<DefaultMutableTreeNode>): Boolean {
        val keys = nodes.map { BookmarkTreeUtil.nodeKey(it) }
        return keys.size != keys.toSet().size
    }

    private fun hasMixedPlaceholderChildren(nodes: List<DefaultMutableTreeNode>): Boolean {
        if (nodes.size <= 1) return false
        return nodes.any { it.userObject == "Loading..." }
    }

    private fun createRootFile() {
        val name = Messages.showInputDialog(project, "Root file name:", "New Root File", null) ?: return
        if (name.isBlank()) return
        viewModel.processIntent(BookmarkIntent.CreateRootFile(name))
    }

    private fun createGroup() {
        scope.launch {
            val (parentId, insertIndex) = viewModel.getInsertionTarget(SelectionBus.getInstance(project).getLastSelectedNodeId())
            withContext(Dispatchers.Main) {
                val group = BookmarkEditDialogUtil.editGroup(
                    project,
                    BookmarkNode.Group(name = "New Group")
                ) ?: return@withContext
                if (group.name.isBlank()) return@withContext
                viewModel.processIntent(BookmarkIntent.CreateGroup(parentId, group, insertIndex))
                SelectionBus.getInstance(project).requestSelect(group.uuid)
            }
        }
    }

    private fun createProcess() {
        scope.launch {
            val (parentId, insertIndex) = viewModel.getInsertionTarget(SelectionBus.getInstance(project).getLastSelectedNodeId())
            withContext(Dispatchers.Main) {
                val name = Messages.showInputDialog(project, "Process name:", "Create Process", null) ?: return@withContext
                if (name.isBlank()) return@withContext
                val description = Messages.showInputDialog(project, "Description:", "Create Process", null) ?: ""
                val entryPath = Messages.showInputDialog(project, "Entry file path (optional):", "Create Process", null)
                val entryLineText = Messages.showInputDialog(project, "Entry line (optional):", "Create Process", null)
                val entryLine = entryLineText?.toIntOrNull()
                if (!entryPath.isNullOrBlank() && !ensureFileExists(entryPath, "Create Process")) return@withContext
                val process = BookmarkNode.Process(
                    name = name.trim(),
                    description = description,
                    entryFilePath = entryPath?.takeIf { it.isNotBlank() },
                    entryLine = entryLine
                )
                viewModel.processIntent(BookmarkIntent.CreateProcess(parentId, process, insertIndex))
                SelectionBus.getInstance(project).requestSelect(process.uuid)
            }
        }
    }

    /** Default file path for new bookmark: same file as selected node or its container (so new bookmark is in that directory). */
    private fun defaultFilePathForNewBookmark(parentId: String? = null): String? {
        val selected = selectedNode()
        val fromSelected = when (selected) {
            is BookmarkNode.Bookmark -> selected.filePath
            is BookmarkNode.Process -> selected.entryFilePath
            else -> null
        }
        if (!fromSelected.isNullOrBlank()) return fromSelected
        val pid = parentId ?: insertionTarget().first ?: return null
        val parentNode = currentRoot?.let { BookmarkDomainTree.findNode(it, pid) } ?: return null
        return when (parentNode) {
            is BookmarkNode.Bookmark -> parentNode.filePath
            is BookmarkNode.Process -> parentNode.entryFilePath
            else -> null
        }
    }

    private fun createBookmark() {
        scope.launch {
            val (parentId, insertIndex) = viewModel.getInsertionTarget(SelectionBus.getInstance(project).getLastSelectedNodeId())
            withContext(Dispatchers.Main) {
                val defaultPath = defaultFilePathForNewBookmark(parentId)
                val bookmark = BookmarkEditDialogUtil.editBookmark(
                    project,
                    BookmarkNode.Bookmark(
                        name = "",
                        filePath = defaultPath.orEmpty(),
                        line = 0
                    )
                ) ?: return@withContext
                if (bookmark.name.isBlank()) return@withContext
                viewModel.processIntent(BookmarkIntent.CreateBookmark(parentId, bookmark, insertIndex))
                SelectionBus.getInstance(project).requestSelect(bookmark.uuid)
            }
        }
    }

    private fun createDescriptive() {
        scope.launch {
            val (parentId, insertIndex) = viewModel.getInsertionTarget(SelectionBus.getInstance(project).getLastSelectedNodeId())
            withContext(Dispatchers.Main) {
                val name = Messages.showInputDialog(project, "Note title:", "Create Note", null) ?: return@withContext
                if (name.isBlank()) return@withContext
                val description = Messages.showInputDialog(project, "Description:", "Create Note", null) ?: ""
                val markdown = Messages.showInputDialog(project, "Markdown:", "Create Note", null) ?: ""
                val note = BookmarkNode.DescriptiveBookmark(
                    name = name.trim(),
                    description = description.trim(),
                    markdownContent = markdown
                )
                viewModel.processIntent(BookmarkIntent.CreateDescriptive(parentId, note, insertIndex))
                SelectionBus.getInstance(project).requestSelect(note.uuid)
            }
        }
    }

    private fun editSelected() {
        val node = selectedNode() ?: return
        if (node.uuid == BookmarkStore.SUPER_ROOT_UUID) return
        // 禁止编辑文件根节点
        val superRoot = currentRoot
        if (superRoot is BookmarkNode.Group && superRoot.uuid == BookmarkStore.SUPER_ROOT_UUID
            && superRoot.children.any { it.uuid == node.uuid }) return
        // 从最新的 state 中获取节点数据，确保使用最新数据
        val latestNode = currentRoot?.let { BookmarkDomainTree.findNode(it, node.uuid) } ?: node
        if (latestNode is BookmarkNode.Bookmark || latestNode is BookmarkNode.Group) {
            viewModel.markPendingEditAnchor(latestNode.uuid)
        }
        val updated = when (latestNode) {
            is BookmarkNode.Bookmark -> BookmarkEditDialogUtil.editBookmark(project, latestNode)
            is BookmarkNode.DescriptiveBookmark -> BookmarkEditDialogUtil.editDescriptive(project, latestNode)
            is BookmarkNode.Group -> BookmarkEditDialogUtil.editGroup(project, latestNode)
            is BookmarkNode.Process -> BookmarkEditDialogUtil.editProcess(project, latestNode)
        } ?: return
        viewModel.processIntent(BookmarkIntent.EditNode(updated))
    }

    private fun editSelectedBookmarkOrGroup() {
        val node = selectedNode() ?: return
        val latestNode = currentRoot?.let { BookmarkDomainTree.findNode(it, node.uuid) } ?: node
        when (latestNode) {
            is BookmarkNode.Bookmark, is BookmarkNode.Group -> editSelected()
            else -> Unit
        }
    }
    private fun setProcessEntry() {
        val node = selectedNode() as? BookmarkNode.Process ?: return
        val entryPath = Messages.showInputDialog(
            project,
            "Entry file path:",
            "Set Process Entry",
            null,
            node.entryFilePath ?: "",
            null
        ) ?: return
        if (entryPath.isNotBlank() && !ensureFileExists(entryPath, "Set Process Entry")) return
        val entryLineText = Messages.showInputDialog(
            project,
            "Entry line:",
            "Set Process Entry",
            null,
            node.entryLine?.toString() ?: "",
            null
        ) ?: ""
        val entryLine = entryLineText.trim().toIntOrNull()
        val updated = node.copy(
            entryFilePath = entryPath.trim().ifBlank { null },
            entryLine = entryLine
        )
        viewModel.processIntent(BookmarkIntent.EditNode(updated))
    }

    private fun moveSelected() {
        val node = selectedNode() ?: return
        val root = currentRoot ?: return
        if (node.uuid == BookmarkStore.SUPER_ROOT_UUID) return
        val superRoot = root
        if (superRoot.uuid == BookmarkStore.SUPER_ROOT_UUID &&
            superRoot.children.any { it.uuid == node.uuid }
        ) {
            return
        }
        val displayRoot = BookmarkDomainTree.withoutNodeAndDescendants(root, node.uuid) as? BookmarkNode.Group
            ?: return
        BookmarkMoveTreePopup(
            project = project,
            displayRoot = displayRoot,
            movingNode = node,
            referenceCounts = currentReferenceCounts,
            referenceTargets = currentReferenceTargets,
            expandedNodeIds = currentState.expandedNodeIds,
            onConfirm = { parentId, index ->
                viewModel.processIntent(BookmarkIntent.MoveNode(node.uuid, parentId, index))
                SelectionBus.getInstance(project).requestSelect(node.uuid)
            }
        ).show(tree)
    }

    private fun moveSelectedSibling(offset: Int) {
        val node = selectedNode() ?: return
        val root = currentRoot ?: return
        val parent = BookmarkDomainTree.findParent(root, node.uuid) ?: return
        val siblings = when (parent) {
            is BookmarkNode.Group -> parent.children
            is BookmarkNode.Process -> parent.steps
            else -> return
        }
        val currentIndex = siblings.indexOfFirst { it.uuid == node.uuid }
        val targetIndex = currentIndex + offset
        if (currentIndex < 0 || targetIndex !in siblings.indices) return
        viewModel.processIntent(BookmarkIntent.MoveNode(node.uuid, parent.uuid, targetIndex))
        SelectionBus.getInstance(project).requestSelect(node.uuid)
    }

    private fun createReference() {
        val source = selectedNode() as? BookmarkNode.Bookmark ?: return
        val root = currentRoot ?: return
        val bookmarks = BookmarkDomainTree.collectBookmarks(root).filter { it.uuid != source.uuid }
        if (bookmarks.isEmpty()) return
        val labels = bookmarks.map { formatBookmarkLabel(it) }.toTypedArray()
        val choiceIndex = chooseIndex("Select target codemark:", "Create Reference", labels)
        val target = bookmarks.getOrNull(choiceIndex) ?: return
        viewModel.processIntent(BookmarkIntent.CreateReference(source.uuid, target.uuid))
    }

    private fun showReferenceOverview() {
        val root = currentRoot ?: return
        val all = BookmarkDomainTree.collectBookmarks(root).associateBy { it.uuid }
        val pathMap = BookmarkDomainTree.buildPathMap(root)
        val entries = mutableListOf<ReferenceEntry>()

        currentTargetsBySource.forEach { (sourceId, targets) ->
            targets.forEach { targetId ->
                val source = all[sourceId]
                val target = all[targetId]
                if (source != null && target != null) {
                    val sourcePath = pathMap[source.uuid] ?: source.name
                    val targetPath = pathMap[target.uuid] ?: target.name
                    entries.add(ReferenceEntry(source, target, sourcePath, targetPath))
                }
            }
        }
        if (entries.isEmpty()) {
            Messages.showMessageDialog(project, "No references", "References", null)
            return
        }
        val list = JBList(entries.map { it.label }.toTypedArray())
        list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        val dialog = object : DialogWrapper(project) {
            init {
                title = "References"
                init()
            }

            override fun createCenterPanel(): JComponent {
                return JBScrollPane(list)
            }
        }
        if (!dialog.showAndGet()) return
        val selected = list.selectedIndices.toList().mapNotNull { index -> entries.getOrNull(index) }
        if (selected.isEmpty()) return
        selected.forEach { entry ->
            selectNodeById(entry.target.uuid)
            selectNodeById(entry.source.uuid)
        }
    }

    private fun showReferenceGraph() {
        val root = currentRoot ?: return
        if (currentTargetsBySource.isEmpty()) {
            Messages.showMessageDialog(project, "No references", "Reference Graph", null)
            return
        }
        val all = BookmarkDomainTree.collectBookmarks(root).associateBy { it.uuid }
        val pathMap = BookmarkDomainTree.buildPathMap(root)
        val selected = selectedNode() as? BookmarkNode.Bookmark
        val selectedId = selected?.uuid
        val filterBox = javax.swing.JCheckBox("Only selected node chain", selectedId != null)
        val focusButton = JButton("Focus Selected")
        val showAllButton = JButton("Show All")

        fun buildVisualData(): Pair<List<VisualNode>, List<VisualEdge>> {
            val edges = mutableListOf<VisualEdge>()
            currentTargetsBySource.forEach { (sourceId, targets) ->
                targets.forEach { targetId ->
                    if (filterBox.isSelected && selectedId != null) {
                        if (sourceId != selectedId && targetId != selectedId) return@forEach
                    }
                    edges.add(VisualEdge(sourceId, targetId))
                }
            }
            val nodeIds = edges.flatMap { listOf(it.fromId, it.toId) }.toSet()
            val nodes = nodeIds.mapNotNull { id ->
                val node = all[id] ?: return@mapNotNull null
                VisualNode(id, formatGraphLabel(node, pathMap))
            }
            return nodes to edges
        }

        val graphPanel = ReferenceGraphPanel { nodeId ->
            selectNodeById(nodeId)
        }

        fun refreshGraph() {
            val (nodes, edges) = buildVisualData()
            graphPanel.updateData(nodes, edges, selectedId)
        }

        refreshGraph()
        filterBox.addActionListener { refreshGraph() }
        focusButton.addActionListener {
            if (selectedId != null) {
                filterBox.isSelected = true
                refreshGraph()
            }
        }
        showAllButton.addActionListener {
            filterBox.isSelected = false
            refreshGraph()
        }

        val dialog = object : DialogWrapper(project) {
            init {
                title = "Reference Graph"
                init()
            }

            override fun createCenterPanel(): JComponent {
                val controls = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                    add(filterBox)
                    add(focusButton)
                    add(showAllButton)
                }
                return FormBuilder.createFormBuilder()
                    .addComponent(controls)
                    .addComponent(graphPanel)
                    .panel
            }
        }
        dialog.show()
    }

    private data class ReferenceEntry(
        val source: BookmarkNode.Bookmark,
        val target: BookmarkNode.Bookmark,
        val sourcePath: String,
        val targetPath: String
    ) {
        val label: String = "$sourcePath -> $targetPath"
    }

    private data class VisualNode(
        val id: String,
        val label: String,
        var x: Int = 0,
        var y: Int = 0
    )

    private data class VisualEdge(val fromId: String, val toId: String)

    private class ReferenceGraphPanel(
        private val onNodeDoubleClick: (String) -> Unit
    ) : JPanel() {
        private var nodes: List<VisualNode> = emptyList()
        private var edges: List<VisualEdge> = emptyList()
        private var selectedId: String? = null

        init {
            preferredSize = Dimension(640, 480)
            background = Color(0xF8F8F8)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount != 2) return
                    val hit = nodes.minByOrNull { node ->
                        val dx = e.x - node.x
                        val dy = e.y - node.y
                        dx * dx + dy * dy
                    }
                    if (hit != null && distance(hit.x, hit.y, e.x, e.y) <= NODE_RADIUS * NODE_RADIUS) {
                        onNodeDoubleClick(hit.id)
                    }
                }
            })
            toolTipText = ""
            addMouseMotionListener(object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val hit = hitNode(e.x, e.y)
                    toolTipText = hit?.label
                }
            })
        }

        fun updateData(nodes: List<VisualNode>, edges: List<VisualEdge>, selectedId: String?) {
            this.nodes = nodes
            this.edges = edges
            this.selectedId = selectedId
            layoutNodes()
            repaint()
        }

        private fun layoutNodes() {
            if (nodes.isEmpty()) return
            val canvasWidth = width.takeIf { it > 0 } ?: preferredSize.width
            val canvasHeight = height.takeIf { it > 0 } ?: preferredSize.height
            val centerX = canvasWidth / 2
            val centerY = canvasHeight / 2
            val radius = (minOf(canvasWidth, canvasHeight) / 2.0 * 0.78).toInt().coerceAtLeast(90)
            val angleStep = 2 * Math.PI / nodes.size
            nodes.forEachIndexed { index, node ->
                val angle = index * angleStep
                node.x = (centerX + radius * Math.cos(angle)).toInt()
                node.y = (centerY + radius * Math.sin(angle)).toInt()
            }
        }

        override fun paintComponent(g: java.awt.Graphics) {
            super.paintComponent(g)
            val g2d = g as Graphics2D
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            edges.forEach { edge ->
                val from = nodes.find { it.id == edge.fromId }
                val to = nodes.find { it.id == edge.toId }
                if (from != null && to != null) {
                    drawArrow(g2d, from, to)
                }
            }
            nodes.forEach { node ->
                val isSelected = node.id == selectedId
                g2d.color = if (isSelected) Color(0x1976D2) else Color(0x2196F3)
                g2d.fillOval(node.x - NODE_RADIUS, node.y - NODE_RADIUS, NODE_DIAMETER, NODE_DIAMETER)
                g2d.color = Color.WHITE
                val text = node.label
                val metrics = g2d.fontMetrics
                val textWidth = metrics.stringWidth(text)
                val textX = node.x - textWidth / 2
                val textY = node.y + metrics.ascent / 2
                g2d.drawString(text, textX, textY)
            }
        }

        private fun drawArrow(g2d: Graphics2D, from: VisualNode, to: VisualNode) {
            val dx = (to.x - from.x).toDouble()
            val dy = (to.y - from.y).toDouble()
            val dist = Math.hypot(dx, dy).coerceAtLeast(1.0)
            val ux = dx / dist
            val uy = dy / dist
            val startX = from.x + (NODE_RADIUS * ux).toInt()
            val startY = from.y + (NODE_RADIUS * uy).toInt()
            val endX = to.x - (NODE_RADIUS * ux).toInt()
            val endY = to.y - (NODE_RADIUS * uy).toInt()
            g2d.color = Color(0x555555)
            g2d.stroke = BasicStroke(1.6f)
            val line = Line2D.Double(startX.toDouble(), startY.toDouble(), endX.toDouble(), endY.toDouble())
            g2d.draw(line)

            val arrowSize = 10.0
            val angle = Math.atan2(dy, dx)
            val arrowPath = Path2D.Double().apply {
                moveTo(0.0, 0.0)
                lineTo(-arrowSize, arrowSize / 1.6)
                lineTo(-arrowSize, -arrowSize / 1.6)
                closePath()
            }
            val transform = AffineTransform()
            transform.translate(endX.toDouble(), endY.toDouble())
            transform.rotate(angle)
            val arrowShape = transform.createTransformedShape(arrowPath)
            g2d.fill(arrowShape)
        }

        private fun hitNode(x: Int, y: Int): VisualNode? {
            return nodes.minByOrNull { node ->
                val dx = x - node.x
                val dy = y - node.y
                dx * dx + dy * dy
            }?.takeIf { distance(it.x, it.y, x, y) <= NODE_RADIUS * NODE_RADIUS }
        }

        private fun distance(x1: Int, y1: Int, x2: Int, y2: Int): Int {
            val dx = x1 - x2
            val dy = y1 - y2
            return dx * dx + dy * dy
        }

        companion object {
            private const val NODE_RADIUS = 22
            private const val NODE_DIAMETER = NODE_RADIUS * 2
        }
    }

    private fun copyToProcess() {
        val nodes = selectedNodes()
        if (nodes.isEmpty()) return
        val root = currentRoot ?: return
        val blockedTargets = nodes.filterIsInstance<BookmarkNode.Process>().map { it.uuid }.toSet()
        val processes = BookmarkDomainTree.collectProcesses(root).filter { it.uuid !in blockedTargets }
        if (processes.isEmpty()) return
        val labels = processes.map { it.name.ifBlank { "(unnamed)" } }.toTypedArray()
        val choiceIndex = chooseIndex("Select process:", "Copy To Process", labels)
        val target = processes.getOrNull(choiceIndex) ?: return
        nodes.forEach { node ->
            val clone = cloneForInsert(node)
            when (clone) {
                is BookmarkNode.Bookmark ->
                    viewModel.processIntent(BookmarkIntent.CreateBookmark(target.uuid, clone, null))
                is BookmarkNode.DescriptiveBookmark ->
                    viewModel.processIntent(BookmarkIntent.CreateDescriptive(target.uuid, clone, null))
                is BookmarkNode.Group ->
                    viewModel.processIntent(BookmarkIntent.CreateGroup(target.uuid, clone, null))
                is BookmarkNode.Process ->
                    viewModel.processIntent(BookmarkIntent.CreateProcess(target.uuid, clone, null))
            }
        }
    }

    private fun addToProcessStep() {
        val nodes = selectedNodes()
        if (nodes.isEmpty()) return
        val root = currentRoot ?: return
        val processes = BookmarkDomainTree.collectProcesses(root).filter { process ->
            nodes.none { it.uuid == process.uuid || BookmarkDomainTree.isDescendant(it, process.uuid) }
        }
        if (processes.isEmpty()) return
        val labels = processes.map { it.name.ifBlank { "(unnamed)" } }.toTypedArray()
        val choiceIndex = chooseIndex("Select process:", "Add Step", labels)
        val target = processes.getOrNull(choiceIndex) ?: return
        val steps = target.steps
        val positionOptions = mutableListOf("Append")
        steps.forEachIndexed { index, step ->
            positionOptions.add("Before ${index + 1}: ${step.name.ifBlank { "(unnamed)" }}")
        }
        val positionChoice = chooseIndex("Insert position:", "Add Step", positionOptions.toTypedArray())
        val baseIndex = if (positionChoice == 0) -1 else positionChoice - 1
        var insertIndex = baseIndex
        nodes.forEach { node ->
            val index = if (insertIndex < 0) -1 else insertIndex++
            viewModel.processIntent(BookmarkIntent.MoveNode(node.uuid, target.uuid, index))
        }
    }

    private fun cloneForInsert(node: BookmarkNode): BookmarkNode {
        return when (node) {
            is BookmarkNode.Bookmark -> BookmarkNode.Bookmark(
                name = node.name,
                description = node.description,
                filePath = node.filePath,
                line = node.line,
                column = node.column,
                iconPath = node.iconPath
            )
            is BookmarkNode.DescriptiveBookmark -> BookmarkNode.DescriptiveBookmark(
                name = node.name,
                description = node.description,
                markdownContent = node.markdownContent
            )
            is BookmarkNode.Group -> BookmarkNode.Group(
                name = node.name,
                description = node.description
            )
            is BookmarkNode.Process -> BookmarkNode.Process(
                name = node.name,
                description = node.description,
                entryFilePath = node.entryFilePath,
                entryLine = node.entryLine,
                markdownContent = node.markdownContent
            )
        }
    }

    private fun addToProcess() {
        val node = selectedNode() ?: return
        val root = currentRoot ?: return
        val processes = BookmarkDomainTree.collectProcesses(root).filter { it.uuid != node.uuid }
        if (processes.isEmpty()) return
        val labels = processes.map { it.name.ifBlank { "(unnamed)" } }.toTypedArray()
        val choiceIndex = chooseIndex("Select process:", "Add To Process", labels)
        val target = processes.getOrNull(choiceIndex) ?: return
        viewModel.processIntent(BookmarkIntent.MoveNode(node.uuid, target.uuid, -1))
    }

    private fun syncReferences() {
        val source = selectedNode() as? BookmarkNode.Bookmark ?: return
        viewModel.processIntent(BookmarkIntent.SyncReferences(source.uuid))
    }

    private fun deleteReferences() {
        val source = selectedNode() as? BookmarkNode.Bookmark ?: return
        val confirmed = Messages.showYesNoDialog(
            project,
            "Delete all references for this codemark?",
            "Delete References",
            Messages.getQuestionIcon()
        )
        if (confirmed == Messages.YES) {
            viewModel.processIntent(BookmarkIntent.DeleteReferences(source.uuid))
        }
    }

    private fun selectReferenceSource() {
        val target = selectedNode() as? BookmarkNode.Bookmark ?: return
        val root = currentRoot ?: return
        val sources = collectReferenceSources(target.uuid)
        if (sources.isEmpty()) return
        val labels = sources.map { formatBookmarkLabel(it) }.toTypedArray()
        val choiceIndex = chooseIndex("Select reference source:", "Reference Source", labels)
        val source = sources.getOrNull(choiceIndex) ?: return
        selectNodeById(source.uuid)
    }

    private fun selectReferenceTargets() {
        val source = selectedNode() as? BookmarkNode.Bookmark ?: return
        val root = currentRoot ?: return
        val targets = collectReferenceTargets(source.uuid)
        if (targets.isEmpty()) return
        val labels = targets.map { formatBookmarkLabel(it) }.toTypedArray()
        val choiceIndex = chooseIndex("Select reference target:", "Reference Targets", labels)
        val target = targets.getOrNull(choiceIndex) ?: return
        selectNodeById(target.uuid)
    }

    private fun chooseIndex(message: String, title: String, options: Array<String>): Int {
        return Messages.showDialog(project, message, title, options, 0, null)
    }

    private fun collectReferenceSources(targetId: String): List<BookmarkNode.Bookmark> {
        val root = currentRoot ?: return emptyList()
        val sourceIds = currentSourcesByTarget[targetId] ?: return emptyList()
        val all = BookmarkDomainTree.collectBookmarks(root)
        return all.filter { sourceIds.contains(it.uuid) }
    }

    private fun collectReferenceTargets(sourceId: String): List<BookmarkNode.Bookmark> {
        val root = currentRoot ?: return emptyList()
        val targetIds = currentTargetsBySource[sourceId] ?: return emptyList()
        val all = BookmarkDomainTree.collectBookmarks(root)
        return all.filter { targetIds.contains(it.uuid) }
    }

    private fun formatGraphLabel(
        bookmark: BookmarkNode.Bookmark,
        pathMap: Map<String, String>
    ): String {
        val path = pathMap[bookmark.uuid] ?: bookmark.name
        return "$path (${bookmark.filePath}:${bookmark.line + 1})"
    }

    private fun formatBookmarkLabel(bookmark: BookmarkNode.Bookmark): String {
        return "${bookmark.name} (${bookmark.filePath}:${bookmark.line + 1})"
    }

    private fun deleteSelected() {
        val node = selectedNode() ?: return
        if (node.uuid == BookmarkStore.SUPER_ROOT_UUID) return
        // 禁止删除文件根节点
        val superRoot = currentRoot
        if (superRoot is BookmarkNode.Group && superRoot.uuid == BookmarkStore.SUPER_ROOT_UUID
            && superRoot.children.any { it.uuid == node.uuid }) return
        val warning = when (node) {
            is BookmarkNode.Bookmark -> {
                val outgoing = currentReferenceCounts[node.uuid] ?: 0
                val incoming = currentSourcesByTarget[node.uuid]?.size ?: 0
                when {
                    outgoing > 0 && incoming > 0 ->
                        "This codemark has $outgoing outgoing references and is referenced by $incoming sources. Deleting will remove related references."
                    outgoing > 0 ->
                        "This codemark has $outgoing outgoing references. Deleting will remove related references."
                    incoming > 0 ->
                        "This codemark is referenced by $incoming sources. Deleting will remove related references."
                    else -> "Delete selected node?"
                }
            }
            else -> "Delete selected node?"
        }
        val confirmed = Messages.showYesNoDialog(
            warning,
            "Delete",
            "Yes",
            "No",
            Messages.getQuestionIcon()
        )
        if (confirmed == Messages.YES) {
            viewModel.processIntent(BookmarkIntent.DeleteNode(node.uuid))
        }
    }

    private fun collapseAll() {
        BookmarkTreeUtil.collapseVisibleRows(tree)
        gutterPersistExpandIds.clear()
        currentState.expandedNodeIds.forEach { nodeId ->
            viewModel.processIntent(BookmarkIntent.CollapseNode(nodeId))
        }
    }

    private fun expandToCurrent() {
        val nodeId = SelectionBus.getInstance(project).getLastSelectedNodeId() ?: return
        selectNodeById(nodeId)
        tree.selectionPath?.let { path ->
            tree.expandPath(path)
            tree.scrollPathToVisible(path)
        }
    }

    private fun expandAllNestedUnderSelectedGroup() {
        val group = selectedNode() as? BookmarkNode.Group ?: return
        val root = currentRoot ?: return
        val ids = BookmarkDomainTree.collectNestedContainerIds(group)
            .filter { BookmarkDomainTree.findNode(root, it) != null }
            .toSet()
        if (ids.isEmpty()) return
        viewModel.processIntent(BookmarkIntent.ExpandNodes(ids))
    }

    private fun updateExpandCurrentButtonState() {
        tree.repaint()
    }


    private fun handleSideEffect(effect: BookmarkSideEffect) {
        when (effect) {
            is BookmarkSideEffect.NavigateToFile -> {
                logger.debug("NavigateToFile side effect received: filePath=${effect.filePath}, line=${effect.line}, column=${effect.column}")
                navigateToFile(effect.filePath, effect.line, effect.column)
            }
            is BookmarkSideEffect.ShowNotification -> notify(effect.message, effect.type)
            is BookmarkSideEffect.ScrollToSelected -> Unit
            is BookmarkSideEffect.SelectNode -> {
                logger.debug("[SELECT_NODE] Side effect received: nodeId=${effect.nodeId}")
                persistAncestorExpansion(effect.nodeId)
                SelectionBus.getInstance(project).setLastSelectedNodeId(effect.nodeId)
                renderer.highlightNodeId = effect.nodeId
                tree.repaint()
                isSelectingFromSideEffect = true
                try {
                    if (!expandToNodeByDomainPath(effect.nodeId)) {
                        selectNodeWithRetry(effect.nodeId, maxRetries = 5, delayMs = 100)
                    } else {
                        lastAppliedSelectedNodeId = effect.nodeId
                    }
                } finally {
                    isSelectingFromSideEffect = false
                }
                updateExpandCurrentButtonState()
            }
            is BookmarkSideEffect.RefreshInlays -> {
                // Gutter + line-end painter: refresh highlighters and repaint (EditorLinePainter reads index on paint)
                BookmarkHighlighterService.getInstance(project).refreshGutterForFile(effect.filePath)
            }
            is BookmarkSideEffect.RefreshGutterAll -> {
                // Gutter 由 BookmarkHighlighterService 单源刷新（RangeHighlighter），不再使用 LineMarkerProvider/daemon
            }
            is BookmarkSideEffect.RefreshGutterForFile -> {
                BookmarkHighlighterService.getInstance(project).refreshGutterForFile(effect.filePath)
            }
            is BookmarkSideEffect.RefreshBookmarkxJson -> {
                // 刷新打开的 .codemark/*.json 文件编辑器
                val basePath = project.basePath ?: return
                val jsonFiles = BookmarkPersistentDataSource.listRootFiles(basePath)
                for (jsonPath in jsonFiles) {
                    val normalizedPath = FileUtil.toSystemIndependentName(jsonPath.toString())
                    val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(normalizedPath) ?: continue
                    ApplicationManager.getApplication().invokeLater {
                        val fileEditorManager = FileEditorManager.getInstance(project)
                        if (fileEditorManager.isFileOpen(file)) {
                            file.refresh(false, false)
                            val documentManager = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                            val document = documentManager.getDocument(file)
                            if (document != null) {
                                documentManager.reloadFiles(file)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun activateSelectedNode() {
        when (val node = selectedNode()) {
            is BookmarkNode.Bookmark -> viewModel.processIntent(BookmarkIntent.NavigateToBookmark(node))
            is BookmarkNode.Process -> {
                if (!node.entryFilePath.isNullOrBlank()) {
                    viewModel.processIntent(BookmarkIntent.NavigateToNode(node.uuid))
                }
            }
            else -> Unit
        }
    }

    private fun navigateToNextCodemarkGlobal() {
        val locator = ServiceLocator.get(project)
        val currentId = SelectionBus.getInstance(project).getLastSelectedNodeId()
        scope.launch {
            val entry = withContext(Dispatchers.IO) {
                locator.globalCodemarkNavigationUseCase.findNext(currentId)
            } ?: return@launch
            SelectionBus.getInstance(project).setLastSelectedNodeId(entry.nodeId)
            if (entry.hasEditorTarget()) {
                CodemarkNavigationHelper.navigateToEntry(project, entry.filePath!!, entry.line!!, entry.column)
            }
            viewModel.processIntent(BookmarkIntent.NavigateToNode(entry.nodeId))
        }
    }

    private fun navigateToPrevCodemarkGlobal() {
        val locator = ServiceLocator.get(project)
        val currentId = SelectionBus.getInstance(project).getLastSelectedNodeId()
        scope.launch {
            val entry = withContext(Dispatchers.IO) {
                locator.globalCodemarkNavigationUseCase.findPrevious(currentId)
            } ?: return@launch
            SelectionBus.getInstance(project).setLastSelectedNodeId(entry.nodeId)
            if (entry.hasEditorTarget()) {
                CodemarkNavigationHelper.navigateToEntry(project, entry.filePath!!, entry.line!!, entry.column)
            }
            viewModel.processIntent(BookmarkIntent.NavigateToNode(entry.nodeId))
        }
    }

    private fun navigateToFile(filePath: String, line: Int, column: Int) {
        logger.debug("navigateToFile called: filePath=$filePath, line=$line, column=$column")
        val lfs = LocalFileSystem.getInstance()
        val normalized = FileUtil.toSystemIndependentName(filePath)

        val candidates = buildList {
            val asFile = java.io.File(normalized)
            if (asFile.isAbsolute) {
                add(normalized)
            } else {
                project.basePath?.let { base ->
                    try {
                        val abs = java.io.File(base, normalized).canonicalFile
                        add(FileUtil.toSystemIndependentName(abs.absolutePath))
                    } catch (_: Exception) {
                        // fallback below
                    }
                }
                add(normalized)
            }
        }

        val file = candidates.asSequence()
            .mapNotNull { path -> lfs.refreshAndFindFileByPath(path) }
            .firstOrNull()

        if (file == null) {
            logger.error("navigateToFile: file not found for path=$filePath, candidates=$candidates")
            notify("Navigate failed: file not found: $filePath", NotificationType.WARNING)
            return
        }
        logger.debug("navigateToFile: file found, opening file and moving caret to line=$line")
        val fileEditorManager = FileEditorManager.getInstance(project)
        fileEditorManager.openFile(file, true)
        javax.swing.SwingUtilities.invokeLater {
            val targetLine = line.coerceAtLeast(0)
            val editors = fileEditorManager.getEditors(file)
            editors.forEach { editor ->
                (editor as? TextEditor)?.editor?.let { textEditor ->
                    val document = textEditor.document
                    if (targetLine < document.lineCount) {
                        val lineStart = document.getLineStartOffset(targetLine)
                        val lineEnd = document.getLineEndOffset(targetLine)
                        val col = column.coerceAtLeast(0).coerceAtMost((lineEnd - lineStart).coerceAtLeast(0))
                        val targetOffset = lineStart + col
                        textEditor.caretModel.primaryCaret.moveToOffset(targetOffset)
                        textEditor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                        logger.debug("navigateToFile: moved caret to line $targetLine and scrolled")
                    }
                }
            }
            BookmarkHighlighterService.getInstance(project).flashLineForFile(file.path, targetLine)
            logger.debug("navigateToFile: navigation completed")
        }
    }

    private fun ensureFileExists(path: String, title: String): Boolean {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return false
        val file = LocalFileSystem.getInstance().findFileByPath(trimmed)
        if (file == null) {
            Messages.showErrorDialog(project, "File not found: $trimmed", title)
            return false
        }
        return true
    }

    private fun notify(message: String, type: NotificationType) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("EzCodeMarks")
        if (group == null) {
            logger.warn("Notification group 'EzCodeMarks' is not registered; skip notification: $message")
            return
        }
        group.createNotification(message, type).notify(project)
    }

    fun showCurrentNodeDetails(): Boolean {
        val target = tree.currentDetailsTarget() ?: return false
        val nodeView = target.nodeView
        if (nodeView.location.isBlank() && nodeView.fullDescription.isBlank()) return false

        detailsPopup?.cancel()
        detailsPopup = null

        val accentColor = detailsAccentColor(nodeView)
        val iconBadge = IconBadge(NodeIcons.iconFor(nodeView.node, nodeView.isReferencedTarget), accentColor)
        val titleLabel = JLabel(nodeView.displayName).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 3f)
            foreground = JBColor.foreground()
        }
        val typeChip = ChipLabel(nodeKindLabel(nodeView.node), accentColor)
        val refChip = nodeView.suffix.takeIf { it.isNotBlank() }?.let {
            ChipLabel(it.removePrefix("[").removeSuffix("]"), JBColor(0x6F7A8A, 0xA8B0BE))
        }
        val metaRow = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(typeChip)
            refChip?.let {
                add(Box.createHorizontalStrut(6))
                add(it)
            }
            add(Box.createHorizontalStrut(6))
            add(ChipLabel(nodeView.location.ifBlank { "No file location" }, JBColor(0x596579, 0xAEB6C4), filled = false))
        }
        val topRow = JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false
            add(metaRow, BorderLayout.WEST)
            add(iconBadge, BorderLayout.EAST)
        }
        val titleRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(9)
            add(titleLabel, BorderLayout.WEST)
        }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(16, 18, 12, 18)
            add(topRow, BorderLayout.NORTH)
            add(titleRow, BorderLayout.SOUTH)
        }

        val descriptionPane = JEditorPane().apply {
            contentType = "text/html"
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.empty(10, 12)
            text = MarkdownDetailsRenderer.render(nodeView.fullDescription)
            addHyperlinkListener { event ->
                if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    openMarkdownLink(event)
                }
            }
        }
        val sectionLabel = JLabel("DESCRIPTION").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.BOLD, font.size2D - 1f)
            border = JBUI.Borders.empty(0, 0, 7, 0)
        }
        val body = JBScrollPane(descriptionPane).apply {
            border = BorderFactory.createLineBorder(JBColor.border())
            preferredSize = Dimension(500, 310)
        }
        val bodyWrap = RoundedPanel(JBColor(0xFFFFFF, 0x24272D), JBColor.border(), 10).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(11, 12, 12, 12)
            add(sectionLabel, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }
        val content = DetailsPopupPanel(accentColor).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(0, 0, 14, 0)
            add(header, BorderLayout.NORTH)
            add(bodyWrap, BorderLayout.CENTER)
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, descriptionPane)
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(false)
            .createPopup()

        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                if (detailsPopup === popup) detailsPopup = null
            }
        })
        detailsPopup = popup
        popup.show(RelativePoint(tree, target.point))
        return true
    }

    private fun openMarkdownLink(event: HyperlinkEvent) {
        event.url?.let { url ->
            if (url.protocol == "http" || url.protocol == "https") {
                BrowserUtil.browse(url)
                return
            }
        }
        val target = event.description ?: event.url?.toExternalForm() ?: return
        val projectLink = ProjectRelativeMarkdownLinkParser.parse(target)
        if (projectLink == null) {
            notify("Unsupported link: $target", NotificationType.WARNING)
            return
        }
        navigateToFile(projectLink.path, projectLink.line, projectLink.column)
    }

    private fun nodeKindLabel(node: BookmarkNode): String {
        return when (node) {
            is BookmarkNode.Bookmark -> "CODEMARK"
            is BookmarkNode.Group -> "GROUP"
            is BookmarkNode.Process -> "PROCESS"
            is BookmarkNode.DescriptiveBookmark -> "NOTE"
        }
    }

    private fun detailsAccentColor(nodeView: NodeView): JBColor {
        return when {
            nodeView.isReferencedTarget -> JBColor(0x7C5CFF, 0xA892FF)
            nodeView.node is BookmarkNode.Bookmark -> JBColor(0x3278F6, 0x78A8FF)
            nodeView.node is BookmarkNode.Group -> JBColor(0xD9822B, 0xE7A45D)
            nodeView.node is BookmarkNode.Process -> JBColor(0x2B9E6D, 0x5CC895)
            else -> JBColor(0x8B5CF6, 0xB79CFF)
        }
    }

    companion object {
        private const val TOOL_WINDOW_ID = "EzCodeMarks"
        private const val SEARCH_INTERACTION_GRACE_MS = 10_000L

        fun findActive(project: Project): BookmarkPanel? {
            val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            findAncestorPanel(focusOwner)?.let { return it }

            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return null
            return findDescendantPanel(toolWindow.component)
        }

        private fun findAncestorPanel(component: Component?): BookmarkPanel? {
            var current = component
            while (current != null) {
                if (current is BookmarkPanel) return current
                current = current.parent
            }
            return null
        }

        private fun findDescendantPanel(component: Component?): BookmarkPanel? {
            if (component == null) return null
            if (component is BookmarkPanel) return component
            if (component !is Container) return null
            for (child in component.components) {
                findDescendantPanel(child)?.let { return it }
            }
            return null
        }
    }

    data class NodeView(
        val node: BookmarkNode,
        val referenceCount: Int,
        val isReferencedTarget: Boolean,
        val pathLabel: String,
        val isFileBroken: Boolean = false,
        val project: Project? = null
    ) {
        val displayName: String = node.name.ifBlank { "(unnamed)" }
        val suffix: String = buildSuffix()
        val descriptionPreview: String = buildDescriptionPreview()
        val location: String = buildLocation()
        val fullDescription: String = node.description.trim()
        val tooltip: String = buildTooltip()
        val detailedTooltip: String = buildDetailedTooltip()

        constructor(
            node: BookmarkNode,
            referenceCount: Int,
            isReferencedTarget: Boolean,
            pathLabel: String
        ) : this(node, referenceCount, isReferencedTarget, pathLabel, false, null)

        constructor(
            node: BookmarkNode,
            referenceCount: Int,
            isReferencedTarget: Boolean,
            pathLabel: String,
            isFileBroken: Boolean
        ) : this(node, referenceCount, isReferencedTarget, pathLabel, isFileBroken, null)

        private fun buildSuffix(): String {
            val parts = mutableListOf<String>()
            if (referenceCount > 0) parts.add("*refs:*`$referenceCount`")
            if (isReferencedTarget) parts.add("*ref*")
            return if (parts.isEmpty()) "" else "[${parts.joinToString(", ")}]"
        }

        private fun buildDescriptionPreview(): String {
            if (node !is BookmarkNode.Bookmark && node !is BookmarkNode.Group) return ""
            return node.description
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
        }

        private fun buildLocation(): String {
            if (node is BookmarkNode.Bookmark) {
                val relativePath = project?.let { toRelativePath(it, node.filePath) } ?: node.filePath
                return "$relativePath:${node.line + 1}"
            }
            return ""
        }

        private fun buildTooltip(): String {
            return location
        }

        private fun buildDetailedTooltip(): String {
            val parts = mutableListOf<String>()
            if (location.isNotBlank()) parts.add(location)
            if (fullDescription.isNotBlank()) parts.add(fullDescription)
            return parts.joinToString("\n\n")
        }

        override fun toString(): String {
            return listOf(displayName, suffix, descriptionPreview)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }
    }

    private data class GraphNode(val label: String, val nodeId: String?) {
        override fun toString(): String = label
    }


    override fun dispose() {
        detailsPopup?.cancel()
        detailsPopup = null
        speedSearchKeyDispatcher?.let {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it)
        }
        speedSearchKeyDispatcher = null
        scope.cancel()
    }

}

private class BookmarkDropPreviewTree(model: DefaultTreeModel) : Tree(model) {
    var navigationKeyHandler: ((KeyEvent) -> Boolean)? = null
    private var lastTooltipPoint: Point? = null

    override fun getToolTipText(event: MouseEvent?): String? {
        if (event == null) return super.getToolTipText(event)
        val path = getPathForLocation(event.x, event.y) ?: return null
        val nodeView = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? BookmarkPanel.NodeView
            ?: return null
        val tooltip = nodeView.tooltip
        return tooltip.takeIf { it.isNotBlank() }?.toHtmlTooltip()
    }

    fun currentDetailsTarget(): DetailsTarget? {
        val pointerPoint = lastTooltipPoint ?: pointerLocationInTree()
        if (pointerPoint != null && contains(pointerPoint)) {
            nodeViewAt(pointerPoint)?.let { return DetailsTarget(it, pointerPoint) }
        }

        val path = selectionPath ?: return null
        val nodeView = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? BookmarkPanel.NodeView
            ?: return null
        val bounds = getPathBounds(path)
        val point = if (bounds != null) {
            Point((bounds.x + bounds.width / 2).coerceAtLeast(8), bounds.y + bounds.height)
        } else {
            Point(width / 2, height / 2)
        }
        return DetailsTarget(nodeView, point)
    }

    override fun processMouseEvent(event: MouseEvent) {
        when (event.id) {
            MouseEvent.MOUSE_ENTERED -> lastTooltipPoint = event.point
            MouseEvent.MOUSE_EXITED -> {
                lastTooltipPoint = null
            }
        }
        super.processMouseEvent(event)
    }

    override fun processMouseMotionEvent(event: MouseEvent) {
        lastTooltipPoint = event.point
        super.processMouseMotionEvent(event)
    }

    override fun processKeyEvent(event: KeyEvent) {
        if (event.isConsumed) return
        if (navigationKeyHandler?.invoke(event) == true) {
            return
        }
        super.processKeyEvent(event)
    }

    var dropPreview: BookmarkTreeDropSupport.DropPlacement? = null
        set(value) {
            if (field == value) return
            val oldBounds = field?.path?.let { getPathBounds(it) }
            val newBounds = value?.path?.let { getPathBounds(it) }
            field = value
            oldBounds?.let { repaintPreviewArea(it) }
            newBounds?.let { repaintPreviewArea(it) }
        }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val preview = dropPreview ?: return
        val bounds = getPathBounds(preview.path) ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val color = com.intellij.ui.JBColor(0x4B8DFF, 0x6EA6FF)
            g2.color = color
            g2.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            when (preview.zone) {
                BookmarkTreeDropSupport.DropZone.BEFORE ->
                    drawDropLine(g2, bounds.y)
                BookmarkTreeDropSupport.DropZone.AFTER ->
                    drawDropLine(g2, bounds.y + bounds.height)
                BookmarkTreeDropSupport.DropZone.INTO -> {
                    g2.color = Color(color.red, color.green, color.blue, 48)
                    g2.fillRoundRect(2, bounds.y + 1, width - 5, bounds.height - 2, 6, 6)
                    g2.color = color
                    g2.drawRoundRect(2, bounds.y + 1, width - 5, bounds.height - 2, 6, 6)
                }
            }
        } finally {
            g2.dispose()
        }
    }

    private fun drawDropLine(g: Graphics2D, y: Int) {
        val lineY = y.coerceIn(1, height - 2)
        g.drawLine(4, lineY, width - 6, lineY)
        g.fillOval(2, lineY - 3, 6, 6)
    }

    private fun repaintPreviewArea(bounds: Rectangle) {
        repaint(0, (bounds.y - 6).coerceAtLeast(0), width, bounds.height + 12)
    }

    private fun pointerLocationInTree(): Point? {
        val pointer = MouseInfo.getPointerInfo()?.location ?: return null
        SwingUtilities.convertPointFromScreen(pointer, this)
        return pointer
    }

    private fun nodeViewAt(point: Point): BookmarkPanel.NodeView? {
        val path = getPathForLocation(point.x, point.y) ?: return null
        return (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? BookmarkPanel.NodeView
    }

    private fun String.toHtmlTooltip(): String {
        return lineSequence()
            .joinToString("<br>") { it.escapeHtml() }
            .let { "<html>$it</html>" }
    }

    private fun String.escapeHtml(): String {
        return replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    data class DetailsTarget(
        val nodeView: BookmarkPanel.NodeView,
        val point: Point
    )
}

private open class RoundedPanel(
    private val fill: Color,
    private val stroke: Color,
    private val radius: Int
) : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = fill
            g2.fillRoundRect(0, 0, width - 1, height - 1, radius, radius)
            g2.color = stroke
            g2.drawRoundRect(0, 0, width - 1, height - 1, radius, radius)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

private class IconBadge(
    icon: Icon,
    private val accent: Color
) : JLabel(icon) {
    init {
        horizontalAlignment = CENTER
        verticalAlignment = CENTER
        preferredSize = Dimension(34, 34)
        minimumSize = preferredSize
        maximumSize = preferredSize
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val bg = Color(accent.red, accent.green, accent.blue, 36)
            g2.color = bg
            g2.fillRoundRect(0, 0, width - 1, height - 1, 10, 10)
            g2.color = Color(accent.red, accent.green, accent.blue, 120)
            g2.drawRoundRect(0, 0, width - 1, height - 1, 10, 10)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}
