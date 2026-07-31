package emohce.presentation.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import emohce.core.di.ServiceLocator
import emohce.domain.model.BookmarkNode
import emohce.presentation.index.BookmarkIndexService
import emohce.presentation.selection.SelectionBus
import emohce.presentation.toolwindow.BookmarkEditDialogUtil
import emohce.presentation.toolwindow.BookmarkIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class CreateBookmarkAtCaretAction : AnAction() {
    private val logger = Logger.getInstance(CreateBookmarkAtCaretAction::class.java)
    
    override fun actionPerformed(e: AnActionEvent) {
        logger.debug("[ACTION_CREATE_BOOKMARK] Action triggered")
        val project = e.project ?: return logger.warn("[ACTION_CREATE_BOOKMARK] No project")
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return logger.warn("[ACTION_CREATE_BOOKMARK] No editor")
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return logger.warn("[ACTION_CREATE_BOOKMARK] No file")

        val caret = editor.caretModel.primaryCaret
        val line = caret.logicalPosition.line
        val column = caret.logicalPosition.column

        val indexService = BookmarkIndexService.getInstance(project)
        val existingEntry = indexService.entriesForFile(file.path).firstOrNull { it.line == line }
        if (existingEntry != null) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val locator = ServiceLocator.get(project)
                val existingNode = runBlocking {
                    withContext(Dispatchers.IO) {
                        locator.bookmarkRepository.findByUuid(existingEntry.nodeId)
                    }
                } ?: return@executeOnPooledThread

                ApplicationManager.getApplication().invokeLater {
                    val edited = when (existingNode) {
                        is BookmarkNode.Bookmark -> BookmarkEditDialogUtil.editBookmark(project, existingNode)
                        is BookmarkNode.DescriptiveBookmark -> BookmarkEditDialogUtil.editDescriptive(project, existingNode)
                        is BookmarkNode.Group -> BookmarkEditDialogUtil.editGroup(project, existingNode)
                        is BookmarkNode.Process -> BookmarkEditDialogUtil.editProcess(project, existingNode)
                    } ?: return@invokeLater
                    locator.bookmarkViewModel.processIntent(BookmarkIntent.EditNode(edited))
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("EzCodeMarks")
                        .createNotification("CodeMark updated", NotificationType.INFORMATION)
                        .notify(project)
                }
            }
            return
        }

        logger.debug("[ACTION_CREATE_BOOKMARK] Showing create bookmark dialog...")
        val bookmark = BookmarkEditDialogUtil.editBookmark(
            project,
            BookmarkNode.Bookmark(
                name = "",
                filePath = file.path,
                line = line,
                column = column
            ),
            "Add Bookmark"
        ) ?: return logger.debug("[ACTION_CREATE_BOOKMARK] User cancelled create dialog")
        if (bookmark.name.isBlank()) return logger.warn("[ACTION_CREATE_BOOKMARK] Name is blank")
        logger.debug("[ACTION_CREATE_BOOKMARK] Bookmark created with uuid=${bookmark.uuid}")

        ApplicationManager.getApplication().executeOnPooledThread {
            runBlocking {
                val locator = ServiceLocator.get(project)
                val (parentId, insertIndex) = locator.bookmarkViewModel.getInsertionTarget(
                    SelectionBus.getInstance(project).getLastSelectedNodeId()
                )
                logger.debug("[ACTION_CREATE_BOOKMARK] ParentId=$parentId, insertIndex=$insertIndex, calling processIntent...")
                locator.bookmarkViewModel.processIntent(
                    BookmarkIntent.CreateBookmark(parentId, bookmark, insertIndex)
                )
            }
        }
        logger.debug("[ACTION_CREATE_BOOKMARK] processIntent scheduled, SelectNode side effect will handle tree selection")
        NotificationGroupManager.getInstance()
            .getNotificationGroup("EzCodeMarks")
            .createNotification("CodeMark created", NotificationType.INFORMATION)
            .notify(project)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val hasEditor = editor != null
        val hasFile = file != null
        e.presentation.isEnabledAndVisible = hasEditor && hasFile && project != null
        if (hasEditor && hasFile && project != null) {
            val indexService = BookmarkIndexService.getInstance(project)
            val caretLine = editor.caretModel.primaryCaret.logicalPosition.line
            val hasCodemark = indexService.entriesForFile(file.path).any { it.line == caretLine }
            e.presentation.text = if (hasCodemark) "Edit CodeMark" else "Add CodeMark Here"
        }
    }
}
