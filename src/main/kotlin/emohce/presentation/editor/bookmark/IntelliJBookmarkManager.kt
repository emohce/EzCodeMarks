package emohce.presentation.editor.bookmark

import com.intellij.ide.bookmark.Bookmark
import com.intellij.ide.bookmark.BookmarkGroup
import com.intellij.ide.bookmark.BookmarkType
import com.intellij.ide.bookmark.BookmarksManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import emohce.domain.model.BookmarkNode

/**
 * 管理 IntelliJ 内置书签，确保 gutter 图标正常显示
 * 参考 CodeReadingMarkNotePro 的实现方式
 */
class IntelliJBookmarkManager(private val project: Project) {
    private val logger = Logger.getInstance(IntelliJBookmarkManager::class.java)
    private val groupName = "CodeMark"
    
    private val manager: BookmarksManager?
        get() = BookmarksManager.getInstance(project)
    
    private val group: BookmarkGroup
        get() {
            var bookmarkGroup = manager?.getGroup(groupName)
            if (bookmarkGroup == null) {
                manager?.addGroup(groupName, false)
                bookmarkGroup = manager?.getGroup(groupName)
            }
            return bookmarkGroup ?: throw IllegalStateException("Failed to create bookmark group: $groupName")
        }
    
    /**
     * 创建 IntelliJ 内置书签
     */
    fun createBookmark(bookmark: BookmarkNode.Bookmark): Bookmark? {
        try {
            val file = LocalFileSystem.getInstance().findFileByPath(
                FileUtil.toSystemIndependentName(bookmark.filePath)
            ) ?: run {
                logger.warn("[INTELLIJ_BOOKMARK] File not found: ${bookmark.filePath}")
                return null
            }
            
            // 使用 ReadAction 访问 Document
            val document = try {
                com.intellij.openapi.application.ReadAction.compute<com.intellij.openapi.editor.Document?, Throwable> {
                    FileDocumentManager.getInstance().getDocument(file)
                }
            } catch (e: Throwable) {
                logger.error("[INTELLIJ_BOOKMARK] Error getting document: ${e.message}", e)
                return null
            }
            
            if (document == null || bookmark.line >= document.lineCount) {
                logger.warn("[INTELLIJ_BOOKMARK] Invalid line: ${bookmark.line}, document has ${document?.lineCount} lines")
                return null
            }
            
            // 创建描述，包含 UUID 以便后续查找
            val description = "${bookmark.name.take(10)}$${bookmark.uuid}"
            
            // 使用反射创建 Bookmark 实例（因为构造函数是包私有的）
            val bookmarkInstance = createBookmarkInstance(project, file, bookmark.line, description)
            if (bookmarkInstance == null) {
                logger.error("[INTELLIJ_BOOKMARK] Failed to create bookmark instance")
                return null
            }
            
            // 添加到 BookmarksManager
            val createdBookmark = manager?.createBookmark(bookmarkInstance)
            if (createdBookmark == null) {
                logger.error("[INTELLIJ_BOOKMARK] Failed to create bookmark in manager")
                return null
            }
            
            // 添加到组
            group.add(createdBookmark, BookmarkType.DEFAULT, description)
            
            logger.debug("[INTELLIJ_BOOKMARK] Created bookmark: uuid=${bookmark.uuid}, line=${bookmark.line}, file=${file.path}")
            return createdBookmark
        } catch (e: Exception) {
            logger.error("[INTELLIJ_BOOKMARK] Error creating bookmark: ${e.message}", e)
            return null
        }
    }
    
    /**
     * 更新书签（删除旧书签并创建新书签，确保位置和描述正确）
     */
    fun updateBookmark(bookmark: BookmarkNode.Bookmark) {
        try {
            val existingBookmark = findBookmarkByUuid(bookmark.uuid)
            if (existingBookmark == null) {
                logger.warn("[INTELLIJ_BOOKMARK] Bookmark not found for update: ${bookmark.uuid}, creating new one")
                createBookmark(bookmark)
                return
            }
            
            // 简化逻辑：直接删除并重新创建，确保位置和描述正确
            logger.debug("[INTELLIJ_BOOKMARK] Updating bookmark: uuid=${bookmark.uuid}, deleting and recreating")
            deleteBookmark(bookmark.uuid)
            createBookmark(bookmark)
        } catch (e: Exception) {
            logger.error("[INTELLIJ_BOOKMARK] Error updating bookmark: ${e.message}", e)
        }
    }
    
    /**
     * 删除书签
     */
    fun deleteBookmark(uuid: String): Boolean {
        try {
            val mgr = manager ?: run {
                logger.warn("[INTELLIJ_BOOKMARK] BookmarksManager is null")
                return false
            }
            
            val bookmarkGroup = group ?: run {
                logger.warn("[INTELLIJ_BOOKMARK] BookmarkGroup is null")
                return false
            }
            
            val bookmark = findBookmarkByUuid(uuid)
            if (bookmark == null) {
                logger.warn("[INTELLIJ_BOOKMARK] Bookmark not found for deletion: $uuid")
                return false
            }
            
            // 从组中移除
            bookmarkGroup.remove(bookmark)
            // 从 manager 中移除
            mgr.remove(bookmark)
            
            logger.debug("[INTELLIJ_BOOKMARK] Deleted bookmark: uuid=$uuid")
            return true
        } catch (e: Exception) {
            logger.error("[INTELLIJ_BOOKMARK] Error deleting bookmark: ${e.message}", e)
            return false
        }
    }
    
    /**
     * 根据 UUID 查找书签
     */
    fun findBookmarkByUuid(uuid: String): Bookmark? {
        try {
            val bookmarkGroup = group ?: return null
            
            for (bookmark in bookmarkGroup.getBookmarks()) {
                val description = bookmarkGroup.getDescription(bookmark)
                val bookmarkUuid = extractUuid(description)
                if (bookmarkUuid == uuid) {
                    return bookmark
                }
            }
            return null
        } catch (e: Exception) {
            logger.error("[INTELLIJ_BOOKMARK] Error finding bookmark: ${e.message}", e)
            return null
        }
    }
    
    /**
     * 同步所有书签到 IntelliJ 内置书签系统
     * 用于初始化或修复不一致
     */
    fun syncAllBookmarks(bookmarks: List<BookmarkNode.Bookmark>) {
        try {
            val bookmarkGroup = group ?: run {
                logger.warn("[INTELLIJ_BOOKMARK] BookmarkGroup is null, cannot sync")
                return
            }
            
            logger.debug("[INTELLIJ_BOOKMARK] Syncing ${bookmarks.size} bookmarks")
            
            // 获取现有的书签 UUID
            val existingUuids = bookmarkGroup.getBookmarks().mapNotNull { bookmark ->
                extractUuid(bookmarkGroup.getDescription(bookmark))
            }.toSet()
            
            // 创建或更新书签
            val targetUuids = bookmarks.map { it.uuid }.toSet()
            for (bookmark in bookmarks) {
                if (bookmark.uuid in existingUuids) {
                    updateBookmark(bookmark)
                } else {
                    createBookmark(bookmark)
                }
            }
            
            // 删除不存在的书签
            for (uuid in existingUuids) {
                if (uuid !in targetUuids) {
                    deleteBookmark(uuid)
                }
            }
            
            logger.debug("[INTELLIJ_BOOKMARK] Sync completed")
        } catch (e: Exception) {
            logger.error("[INTELLIJ_BOOKMARK] Error syncing bookmarks: ${e.message}", e)
        }
    }
    
    /**
     * 使用反射创建 Bookmark 实例
     */
    private fun createBookmarkInstance(
        project: Project,
        file: VirtualFile,
        line: Int,
        description: String
    ): Any? {
        return try {
            val bookmarkClass = Class.forName("com.intellij.ide.bookmarks.Bookmark")
            val constructor = bookmarkClass.getDeclaredConstructor(
                    Project::class.java,
                    VirtualFile::class.java,
                    Int::class.java,
                    String::class.java
                )
            constructor.isAccessible = true
            constructor.newInstance(project, file, line, description)
        } catch (e: Exception) {
            logger.error("[INTELLIJ_BOOKMARK] Failed to create bookmark instance via reflection: ${e.message}", e)
            null
        }
    }
    
    /**
     * 从描述中提取 UUID
     * 格式: "name$uuid"
     */
    private fun extractUuid(description: String?): String? {
        if (description == null) return null
        val parts = description.split('$')
        return if (parts.size >= 2) parts.last() else null
    }
}
