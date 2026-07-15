package emohce.data.commitmessage

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import emohce.domain.commitmessage.CommitActionKind
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class CommitOperationStatus(
    val id: String,
    val action: CommitActionKind,
    val cancelled: Boolean,
)

class CommitOperationHandle internal constructor(
    val id: String,
    val document: Document,
    val action: CommitActionKind,
    private val cancellation: AtomicBoolean,
) {
    fun isCancelled(): Boolean = cancellation.get()
    internal fun cancel() = cancellation.set(true)
}

interface CommitMessageCoordinator {
    fun status(document: Document): CommitOperationStatus?
    fun start(document: Document, action: CommitActionKind): CommitOperationHandle?
    fun attachIndicator(handle: CommitOperationHandle, indicator: ProgressIndicator)
    fun cancel(document: Document, action: CommitActionKind): Boolean
    fun isCurrent(handle: CommitOperationHandle): Boolean
    fun finish(handle: CommitOperationHandle)
}

@Service(Service.Level.PROJECT)
class CommitMessageCoordinatorService : CommitMessageCoordinator, Disposable {
    private data class ActiveOperation(
        val handle: CommitOperationHandle,
        var indicator: ProgressIndicator? = null,
    )

    private val operations = WeakHashMap<Document, ActiveOperation>()

    @Synchronized
    override fun status(document: Document): CommitOperationStatus? = operations[document]?.handle?.let {
        CommitOperationStatus(it.id, it.action, it.isCancelled())
    }

    @Synchronized
    override fun start(document: Document, action: CommitActionKind): CommitOperationHandle? {
        if (operations.containsKey(document)) return null
        val handle = CommitOperationHandle(
            id = UUID.randomUUID().toString(),
            document = document,
            action = action,
            cancellation = AtomicBoolean(false),
        )
        operations[document] = ActiveOperation(handle)
        return handle
    }

    @Synchronized
    override fun attachIndicator(handle: CommitOperationHandle, indicator: ProgressIndicator) {
        val active = operations[handle.document]
        if (active?.handle?.id != handle.id) {
            indicator.cancel()
            return
        }
        active.indicator = indicator
        if (handle.isCancelled()) indicator.cancel()
    }

    @Synchronized
    override fun cancel(document: Document, action: CommitActionKind): Boolean {
        val active = operations[document] ?: return false
        if (active.handle.action != action) return false
        active.handle.cancel()
        active.indicator?.cancel()
        return true
    }

    @Synchronized
    override fun isCurrent(handle: CommitOperationHandle): Boolean =
        !handle.isCancelled() && operations[handle.document]?.handle?.id == handle.id

    @Synchronized
    override fun finish(handle: CommitOperationHandle) {
        if (operations[handle.document]?.handle?.id == handle.id) operations.remove(handle.document)
    }

    @Synchronized
    override fun dispose() {
        operations.values.forEach {
            it.handle.cancel()
            it.indicator?.cancel()
        }
        operations.clear()
    }

    companion object {
        fun getInstance(project: Project): CommitMessageCoordinatorService =
            project.getService(CommitMessageCoordinatorService::class.java)
    }
}
