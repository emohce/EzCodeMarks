package emohce.presentation.commitmessage

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.commit.CommitMessageUi
import java.util.WeakHashMap

data class CommitActionSnapshot(
    val project: Project,
    val writer: CommitMessageI,
    val commitUi: CommitMessageUi?,
    val document: Document,
    val documentModificationStamp: Long,
    val currentText: String,
    val changes: List<Change>,
    val unversionedFiles: List<VirtualFile>,
    val revision: VcsRevisionNumber?,
)

data class CommitActionAvailability(
    val project: Project,
    val document: Document?,
    val currentText: String,
    val hasChanges: Boolean,
    val hasRevision: Boolean,
)

internal interface CommitActionContextAdapter {
    fun availability(event: AnActionEvent): CommitActionAvailability?
    fun snapshot(event: AnActionEvent): CommitActionSnapshot?
    fun startLoading(snapshot: CommitActionSnapshot)
    fun stopLoading(snapshot: CommitActionSnapshot)
    fun isUnchanged(snapshot: CommitActionSnapshot): Boolean
    fun write(snapshot: CommitActionSnapshot, message: String)
}

internal object IntelliJCommitActionContextAdapter : CommitActionContextAdapter {
    private val fallbackDocuments = WeakHashMap<CommitMessageI, Document>()

    override fun availability(event: AnActionEvent): CommitActionAvailability? {
        val project = event.project ?: return null
        val writer = event.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return null
        val workflowUi = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        val commitUi = workflowUi?.commitMessageUi ?: (writer as? CommitMessageUi)
        val publishedDocument = event.getData(VcsDataKeys.COMMIT_MESSAGE_DOCUMENT)
        val fallbackDocument = synchronized(fallbackDocuments) { fallbackDocuments[writer] }
        val document = publishedDocument ?: fallbackDocument
        val currentText = commitUi?.text ?: document?.text.orEmpty()
        val changes = workflowUi?.getIncludedChanges()
            ?: event.getData(VcsDataKeys.SELECTED_CHANGES)?.toList()
            ?: event.getData(VcsDataKeys.CHANGES)?.toList()
            ?: emptyList()
        val hasUnversioned = workflowUi?.getIncludedUnversionedFiles()?.isNotEmpty() == true
        return CommitActionAvailability(
            project = project,
            document = document,
            currentText = currentText,
            hasChanges = changes.isNotEmpty() || hasUnversioned,
            hasRevision = event.getData(VcsDataKeys.VCS_REVISION_NUMBER) != null,
        )
    }

    override fun snapshot(event: AnActionEvent): CommitActionSnapshot? {
        val project = event.project ?: return null
        val writer = event.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return null
        val workflowUi = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        val commitUi = workflowUi?.commitMessageUi ?: (writer as? CommitMessageUi)
        val publishedDocument = event.getData(VcsDataKeys.COMMIT_MESSAGE_DOCUMENT)
        val document = publishedDocument ?: synchronized(fallbackDocuments) {
            fallbackDocuments.getOrPut(writer) {
                EditorFactory.getInstance().createDocument(commitUi?.text.orEmpty())
            }
        }
        val currentText = commitUi?.text ?: document.text
        val changes = workflowUi?.getIncludedChanges()?.toList()
            ?: event.getData(VcsDataKeys.SELECTED_CHANGES)?.toList()
            ?: event.getData(VcsDataKeys.CHANGES)?.toList()
            ?: emptyList()
        val unversioned = workflowUi?.getIncludedUnversionedFiles()
            ?.mapNotNull { it.virtualFile }
            .orEmpty()
        return CommitActionSnapshot(
            project = project,
            writer = writer,
            commitUi = commitUi,
            document = document,
            documentModificationStamp = document.modificationStamp,
            currentText = currentText,
            changes = changes,
            unversionedFiles = unversioned,
            revision = event.getData(VcsDataKeys.VCS_REVISION_NUMBER),
        )
    }

    override fun startLoading(snapshot: CommitActionSnapshot) {
        snapshot.commitUi?.startLoading()
    }

    override fun stopLoading(snapshot: CommitActionSnapshot) {
        snapshot.commitUi?.stopLoading()
    }

    override fun isUnchanged(snapshot: CommitActionSnapshot): Boolean {
        val currentText = snapshot.commitUi?.text ?: snapshot.document.text
        return snapshot.document.modificationStamp == snapshot.documentModificationStamp &&
            currentText == snapshot.currentText
    }

    override fun write(snapshot: CommitActionSnapshot, message: String) {
        snapshot.writer.setCommitMessage(message)
        if (snapshot.document.text != message && snapshot.commitUi == null) {
            WriteAction.run<RuntimeException> { snapshot.document.setText(message) }
        }
    }
}
