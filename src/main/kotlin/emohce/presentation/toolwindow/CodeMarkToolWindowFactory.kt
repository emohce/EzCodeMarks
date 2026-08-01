package emohce.presentation.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import emohce.core.di.ServiceLocator
import emohce.presentation.editor.BookmarkDocumentListener
import emohce.presentation.editor.bookmark.BookmarkNavigationListener
import emohce.environmentaction.EnvironmentActionsBundle
import emohce.presentation.environmentaction.EnvironmentActionsPanel
import emohce.presentation.toolwindow.panel.BookmarkPanel

class CodeMarksToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val locator = ServiceLocator.get(project)
        val viewModel = BookmarkViewModel(
            project = project,
            bookmarkRepository = locator.bookmarkRepository,
            referenceRepository = locator.referenceRepository,
            processNavigationUseCase = locator.processNavigationUseCase,
            syncReferencesUseCase = locator.syncReferencesUseCase,
            detectCircularRefUseCase = locator.detectCircularRefUseCase,
            dispatchers = locator.dispatchers
        )

        // 初始化文档监听器
        val documentListener = BookmarkDocumentListener(project)
        documentListener.setViewModel(viewModel)
        viewModel.setDocumentListener(documentListener)
        
        // 初始化书签导航监听器（监听 gutter 图标点击）
        val bookmarkNavigationListener = BookmarkNavigationListener(project)

        val panel = BookmarkPanel(project, viewModel)
        val content = ContentFactory.getInstance().createContent(
            panel,
            EnvironmentActionsBundle.message("panel.bookmarksTab"),
            false,
        )
        val environmentPanel = EnvironmentActionsPanel(project)
        val environmentContent = ContentFactory.getInstance().createContent(
            environmentPanel,
            EnvironmentActionsBundle.message("panel.tab"),
            false,
        ).apply { setDisposer(environmentPanel) }
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.addContent(environmentContent)
    }
}
