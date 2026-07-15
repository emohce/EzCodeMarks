package emohce.data.commitmessage

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.diff.fragments.LineFragment
import com.intellij.diff.tools.util.text.LineOffsetsUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.GeneratedSourcesFilter
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.BinaryContentRevision
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import emohce.domain.commitmessage.GitContext
import emohce.domain.commitmessage.GitContextPolicy
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class GitContextRequest(
    val changes: List<Change>,
    val unversionedFiles: List<VirtualFile>,
    val revision: VcsRevisionNumber? = null,
)

interface GitContextRepository {
    fun collect(request: GitContextRequest, indicator: ProgressIndicator): GitContext
}

@Service(Service.Level.PROJECT)
class IntelliJGitContextRepository(private val project: Project) : GitContextRepository {
    override fun collect(request: GitContextRequest, indicator: ProgressIndicator): GitContext {
        val repositories = repositoriesFor(request)
        val filtered = linkedSetOf<String>()
        var truncated = false

        val statusRaw = buildStatus(request, repositories, filtered, indicator)
        val (status, statusTruncated) = GitContextPolicy.truncate(statusRaw, GitContextPolicy.STATUS_LIMIT)
        truncated = truncated || statusTruncated

        val diffResult = buildDiff(request.changes, repositories, filtered, indicator)
        truncated = truncated || diffResult.truncated

        val unversionedResult = buildUnversioned(request.unversionedFiles, repositories, filtered, indicator)
        truncated = truncated || unversionedResult.truncated

        val recentRaw = buildRecentCommits(repositories, indicator)
        val (recent, recentTruncated) = GitContextPolicy.truncate(
            recentRaw,
            GitContextPolicy.RECENT_COMMITS_LIMIT,
        )
        truncated = truncated || recentTruncated

        val revision = buildRevision(request.revision, repositories, indicator)
        return GitContext(
            status = status,
            diff = diffResult.text,
            unversionedFiles = unversionedResult.text,
            recentCommits = recent,
            revision = revision,
            filteredFiles = filtered.toList().sorted(),
            truncated = truncated,
        )
    }

    private fun repositoriesFor(request: GitContextRequest): List<GitRepository> {
        val manager = GitRepositoryManager.getInstance(project)
        val resolved = buildList {
            request.changes.forEach { change ->
                val path = change.afterRevision?.file ?: change.beforeRevision?.file
                path?.let(manager::getRepositoryForFileQuick)?.let(::add)
            }
            request.unversionedFiles.forEach { manager.getRepositoryForFileQuick(it)?.let(::add) }
        }.distinctBy { it.root.path }
        return resolved.ifEmpty { manager.repositories }
    }

    private fun buildStatus(
        request: GitContextRequest,
        repositories: List<GitRepository>,
        filtered: MutableSet<String>,
        indicator: ProgressIndicator,
    ): String = buildString {
        request.changes.forEach { change ->
            indicator.checkCanceled()
            val file = change.afterRevision?.file ?: change.beforeRevision?.file ?: return@forEach
            val path = relativePath(repositories, file)
            if (shouldFilter(path, change.virtualFile)) {
                filtered += path
                return@forEach
            }
            append(change.type.name.lowercase()).append(' ').append(path).append('\n')
        }
        request.unversionedFiles.forEach { file ->
            indicator.checkCanceled()
            val path = relativePath(repositories, file)
            if (shouldFilter(path, file)) {
                filtered += path
            } else {
                append("unversioned ").append(path).append('\n')
            }
        }
    }.trim()

    private fun buildDiff(
        changes: List<Change>,
        repositories: List<GitRepository>,
        filtered: MutableSet<String>,
        indicator: ProgressIndicator,
    ): LimitedSection {
        val output = StringBuilder()
        var truncated = false
        for (change in changes) {
            indicator.checkCanceled()
            val revision = change.afterRevision ?: change.beforeRevision ?: continue
            val path = relativePath(repositories, revision.file)
            if (shouldFilter(path, change.virtualFile) ||
                revision.file.fileType.isBinary ||
                change.beforeRevision is BinaryContentRevision ||
                change.afterRevision is BinaryContentRevision ||
                (change.virtualFile?.length ?: 0L) > MAX_REVISION_FILE_BYTES
            ) {
                filtered += path
                continue
            }

            val fileDiff = try {
                val before = when {
                    change.beforeRevision == null && change.type == Change.Type.NEW -> ""
                    else -> change.beforeRevision?.content ?: throw VcsException("Before revision unavailable")
                }
                val after = when {
                    change.afterRevision == null && change.type == Change.Type.DELETED -> ""
                    else -> change.afterRevision?.content ?: throw VcsException("After revision unavailable")
                }
                if (GitContextPolicy.isProbablyBinary(before.toByteArray(StandardCharsets.UTF_8)) ||
                    GitContextPolicy.isProbablyBinary(after.toByteArray(StandardCharsets.UTF_8)) ||
                    GitContextPolicy.containsLikelySecret(before) ||
                    GitContextPolicy.containsLikelySecret(after)
                ) {
                    filtered += path
                    continue
                }
                unifiedDiff(path, before, after, indicator)
            } catch (e: DiffTooBigException) {
                filtered += path
                continue
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (_: VcsException) {
                filtered += path
                continue
            }

            if (fileDiff.isBlank()) continue
            val separator = if (output.isEmpty()) "" else "\n"
            val remaining = GitContextPolicy.DIFF_LIMIT - output.length - separator.length
            if (remaining <= 0) {
                filtered += path
                truncated = true
                break
            }
            output.append(separator)
            if (fileDiff.length <= remaining) {
                output.append(fileDiff)
            } else {
                output.append(GitContextPolicy.truncate(fileDiff, remaining).first)
                filtered += path
                truncated = true
                break
            }
        }
        return LimitedSection(output.toString(), truncated)
    }

    private fun unifiedDiff(
        path: String,
        before: String,
        after: String,
        indicator: ProgressIndicator,
    ): String {
        val fragments = ComparisonManager.getInstance().compareLines(
            before,
            after,
            ComparisonPolicy.DEFAULT,
            indicator,
        )
        if (fragments.isEmpty()) return ""
        val oldOffsets = LineOffsetsUtil.create(before)
        val newOffsets = LineOffsetsUtil.create(after)
        return buildString {
            append("diff --git a/").append(path).append(" b/").append(path).append('\n')
            append("--- a/").append(path).append('\n')
            append("+++ b/").append(path).append('\n')
            fragments.forEach { fragment ->
                indicator.checkCanceled()
                appendFragmentHeader(fragment)
                for (line in fragment.startLine1 until fragment.endLine1) {
                    append('-')
                    append(before, oldOffsets.getLineStart(line), oldOffsets.getLineEnd(line))
                    append('\n')
                }
                for (line in fragment.startLine2 until fragment.endLine2) {
                    append('+')
                    append(after, newOffsets.getLineStart(line), newOffsets.getLineEnd(line))
                    append('\n')
                }
            }
        }.trimEnd()
    }

    private fun StringBuilder.appendFragmentHeader(fragment: LineFragment) {
        val oldCount = fragment.endLine1 - fragment.startLine1
        val newCount = fragment.endLine2 - fragment.startLine2
        append("@@ -").append(range(fragment.startLine1, oldCount))
            .append(" +").append(range(fragment.startLine2, newCount)).append(" @@\n")
    }

    private fun range(start: Int, count: Int): String = "${if (count == 0) start else start + 1},$count"

    private fun buildUnversioned(
        files: List<VirtualFile>,
        repositories: List<GitRepository>,
        filtered: MutableSet<String>,
        indicator: ProgressIndicator,
    ): LimitedSection {
        val output = StringBuilder()
        var truncated = false
        for (file in files) {
            indicator.checkCanceled()
            val path = relativePath(repositories, file)
            if (!file.isValid || file.isDirectory || file.fileType.isBinary || shouldFilter(path, file)) {
                filtered += path
                continue
            }
            val separator = if (output.isEmpty()) "" else "\n\n"
            val header = "### $path\n"
            val remaining = GitContextPolicy.UNVERSIONED_TOTAL_LIMIT -
                output.length - separator.length - header.length
            if (remaining <= 0) {
                filtered += path
                truncated = true
                continue
            }
            val limited = try {
                readLimitedText(
                    file,
                    minOf(GitContextPolicy.UNVERSIONED_FILE_LIMIT, remaining),
                    indicator,
                )
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (_: Exception) {
                filtered += path
                continue
            }
            if (limited.binary || GitContextPolicy.containsLikelySecret(limited.text)) {
                filtered += path
                continue
            }
            output.append(separator).append(header).append(limited.text)
            if (limited.truncated) {
                filtered += path
                truncated = true
            }
        }
        return LimitedSection(output.toString(), truncated)
    }

    private fun readLimitedText(
        file: VirtualFile,
        limit: Int,
        indicator: ProgressIndicator,
    ): LimitedTextRead {
        val byteLimit = (limit * MAX_UTF8_BYTES_PER_CHAR).coerceAtLeast(1)
        VfsUtilCore.inputStreamSkippingBOM(file.inputStream, file).use { input ->
            val result = ByteArrayOutputStream(byteLimit + 1)
            val buffer = ByteArray(1_024)
            while (result.size() <= byteLimit) {
                indicator.checkCanceled()
                val wanted = minOf(buffer.size, byteLimit + 1 - result.size())
                if (wanted <= 0) break
                val count = input.read(buffer, 0, wanted)
                if (count < 0) break
                result.write(buffer, 0, count)
            }
            val raw = result.toByteArray()
            if (GitContextPolicy.isProbablyBinary(raw)) {
                return LimitedTextRead("", truncated = raw.size > byteLimit, binary = true)
            }
            val text = String(raw.take(byteLimit).toByteArray(), file.charset)
            return LimitedTextRead(
                text = text.take(limit),
                truncated = raw.size > byteLimit || text.length > limit,
                binary = false,
            )
        }
    }

    private fun buildRecentCommits(
        repositories: List<GitRepository>,
        indicator: ProgressIndicator,
    ): String = repositories
        .flatMap { repository ->
            indicator.checkCanceled()
            try {
                GitHistoryUtils.history(project, repository.root, "--max-count=5")
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (_: VcsException) {
                emptyList()
            }
        }
        .sortedByDescending { it.commitTime }
        .distinctBy { it.id.asString() }
        .filterNot { GitContextPolicy.containsLikelySecret(it.subject) }
        .take(5)
        .joinToString("\n") { "${it.id.toShortString()} ${it.subject}" }

    private fun buildRevision(
        revision: VcsRevisionNumber?,
        repositories: List<GitRepository>,
        indicator: ProgressIndicator,
    ): String {
        if (revision == null) return ""
        for (repository in repositories) {
            indicator.checkCanceled()
            try {
                val metadata = GitHistoryUtils.collectCommitsMetadata(
                    project,
                    repository.root,
                    revision.asString(),
                )?.firstOrNull()
                if (metadata != null) {
                    val id = metadata.id.toShortString()
                    return if (GitContextPolicy.containsLikelySecret(metadata.subject)) {
                        id
                    } else {
                        "$id ${metadata.subject}".take(1_000)
                    }
                }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (_: VcsException) {
                // Try the next associated repository.
            }
        }
        return revision.asString().take(1_000)
    }

    private fun shouldFilter(path: String, file: VirtualFile?): Boolean =
        GitContextPolicy.shouldExclude(path) ||
            (file != null && GeneratedSourcesFilter.isGeneratedSourceByAnyFilter(file, project))

    private fun relativePath(repositories: List<GitRepository>, file: FilePath): String {
        val repository = repositories.firstOrNull { FileUtil.isAncestor(it.root.path, file.path, false) }
        return repository?.let { FileUtil.getRelativePath(it.root.path, file.path, '/') } ?: file.name
    }

    private fun relativePath(repositories: List<GitRepository>, file: VirtualFile): String {
        val repository = repositories.firstOrNull { FileUtil.isAncestor(it.root.path, file.path, false) }
        return repository?.let { FileUtil.getRelativePath(it.root.path, file.path, '/') } ?: file.name
    }

    private data class LimitedSection(val text: String, val truncated: Boolean)
    private data class LimitedTextRead(val text: String, val truncated: Boolean, val binary: Boolean)

    companion object {
        private const val MAX_REVISION_FILE_BYTES = 2L * 1024L * 1024L
        private const val MAX_UTF8_BYTES_PER_CHAR = 4
    }
}
