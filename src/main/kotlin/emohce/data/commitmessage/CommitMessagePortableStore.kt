package emohce.data.commitmessage

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

@Serializable
internal data class CommitMessagePortableEnvelope(
    val schemaVersion: Int,
    val revision: String,
    val ancestors: List<String>,
    val payload: String,
) {
    init {
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        require(isCanonicalUuid(revision)) { "revision must be a canonical UUID" }
        require(ancestors.size <= MAX_ANCESTORS) { "ancestor lineage exceeds its bound" }
        require(ancestors.all(::isCanonicalUuid)) { "ancestor lineage contains an invalid UUID" }
        require(ancestors.distinct().size == ancestors.size) { "ancestor lineage contains duplicates" }
        require(revision !in ancestors) { "ancestor lineage contains the current revision" }
    }

    internal fun descendsFrom(other: CommitMessagePortableEnvelope): Boolean =
        revision != other.revision && other.revision in ancestors

    override fun toString(): String =
        "CommitMessagePortableEnvelope(schemaVersion=$schemaVersion, revision=$revision, " +
            "ancestors=$ancestors, payload=<redacted>)"

    companion object {
        const val DEFAULT_SCHEMA_VERSION: Int = 1
        const val MAX_ANCESTORS: Int = 32

        fun create(
            payload: String,
            schemaVersion: Int = DEFAULT_SCHEMA_VERSION,
            revision: String = UUID.randomUUID().toString(),
        ): CommitMessagePortableEnvelope = CommitMessagePortableEnvelope(
            schemaVersion = schemaVersion,
            revision = revision,
            ancestors = emptyList(),
            payload = payload,
        )

        fun successor(
            payload: String,
            predecessor: CommitMessagePortableEnvelope,
            revision: String = UUID.randomUUID().toString(),
        ): CommitMessagePortableEnvelope = successor(payload, listOf(predecessor), revision)

        fun successor(
            payload: String,
            common: CommitMessagePortableEnvelope,
            incoming: CommitMessagePortableEnvelope,
            revision: String = UUID.randomUUID().toString(),
        ): CommitMessagePortableEnvelope {
            require(common.schemaVersion == incoming.schemaVersion) {
                "successor predecessors must use the same schema"
            }
            return successor(payload, listOf(common, incoming), revision)
        }

        private fun successor(
            payload: String,
            predecessors: List<CommitMessagePortableEnvelope>,
            revision: String,
        ): CommitMessagePortableEnvelope {
            require(isCanonicalUuid(revision)) { "revision must be a canonical UUID" }
            require(predecessors.none { revision == it.revision || revision in it.ancestors }) {
                "successor revision already exists in its lineage"
            }

            val lineage = linkedSetOf<String>()
            predecessors.forEach { lineage += it.revision }
            var ancestorIndex = 0
            while (lineage.size < MAX_ANCESTORS && predecessors.any { ancestorIndex < it.ancestors.size }) {
                predecessors.forEach { predecessor ->
                    predecessor.ancestors.getOrNull(ancestorIndex)?.let(lineage::add)
                }
                ancestorIndex++
            }

            return CommitMessagePortableEnvelope(
                schemaVersion = predecessors.first().schemaVersion,
                revision = revision,
                ancestors = lineage.take(MAX_ANCESTORS),
                payload = payload,
            )
        }

        private fun isCanonicalUuid(value: String): Boolean =
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}

internal enum class CommitMessagePortableMissingSide {
    COMMON,
    INCOMING,
    BOTH,
}

internal sealed interface CommitMessagePortableReconciliation {
    data class EquivalentPayload(
        val converged: CommitMessagePortableEnvelope,
    ) : CommitMessagePortableReconciliation

    data class IncomingDescendant(
        val incoming: CommitMessagePortableEnvelope,
    ) : CommitMessagePortableReconciliation

    data class CommonDescendant(
        val common: CommitMessagePortableEnvelope,
    ) : CommitMessagePortableReconciliation

    data class DivergenceConflict(
        val common: CommitMessagePortableEnvelope,
        val incoming: CommitMessagePortableEnvelope,
    ) : CommitMessagePortableReconciliation

    data class MissingSide(
        val side: CommitMessagePortableMissingSide,
        val available: CommitMessagePortableEnvelope?,
    ) : CommitMessagePortableReconciliation
}

internal object CommitMessagePortableReconciler {
    fun reconcile(
        common: CommitMessagePortableEnvelope?,
        incoming: CommitMessagePortableEnvelope?,
    ): CommitMessagePortableReconciliation {
        if (common == null || incoming == null) {
            return CommitMessagePortableReconciliation.MissingSide(
                side = when {
                    common == null && incoming == null -> CommitMessagePortableMissingSide.BOTH
                    common == null -> CommitMessagePortableMissingSide.COMMON
                    else -> CommitMessagePortableMissingSide.INCOMING
                },
                available = common ?: incoming,
            )
        }

        val incomingDescends = incoming.descendsFrom(common)
        val commonDescends = common.descendsFrom(incoming)
        if (common.payload == incoming.payload) {
            val converged = when {
                common.revision == incoming.revision -> common
                incomingDescends && !commonDescends -> incoming
                commonDescends && !incomingDescends -> common
                common.schemaVersion == incoming.schemaVersion ->
                    CommitMessagePortableEnvelope.successor(common.payload, common, incoming)
                else -> incoming
            }
            return CommitMessagePortableReconciliation.EquivalentPayload(converged)
        }

        return when {
            incomingDescends && !commonDescends -> CommitMessagePortableReconciliation.IncomingDescendant(incoming)
            commonDescends && !incomingDescends -> CommitMessagePortableReconciliation.CommonDescendant(common)
            else -> CommitMessagePortableReconciliation.DivergenceConflict(common, incoming)
        }
    }
}

internal sealed interface CommitMessagePortableReadResult {
    data object Missing : CommitMessagePortableReadResult

    data class Found(
        val envelope: CommitMessagePortableEnvelope,
    ) : CommitMessagePortableReadResult
}

internal data class CommitMessagePortableWriteResult(
    val replacedExisting: Boolean,
)

internal sealed interface CommitMessagePortableConditionalWriteResult {
    data class Written(
        val replacedExisting: Boolean,
    ) : CommitMessagePortableConditionalWriteResult

    data class RevisionMismatch(
        val current: CommitMessagePortableEnvelope?,
    ) : CommitMessagePortableConditionalWriteResult
}

internal enum class CommitMessagePortableStoreOperation {
    READ,
    WRITE,
}

internal enum class CommitMessagePortableStoreFailure(val description: String) {
    MALFORMED_CONTENT("malformed content"),
    UNSAFE_PATH("unsafe path"),
    IO("I/O error"),
}

internal class CommitMessagePortableStoreException(
    val failure: CommitMessagePortableStoreFailure,
    val operation: CommitMessagePortableStoreOperation,
    val storePath: Path,
    cause: Throwable? = null,
) : IOException(
    "Portable settings ${operation.name.lowercase()} failed: ${failure.description} (${storePath.fileName ?: storePath})",
    cause,
)

internal class CommitMessagePortableStore(
    path: Path,
    trustedRoot: Path = requireNotNull(path.toAbsolutePath().normalize().parent),
) {
    private val storePath = path.toAbsolutePath().normalize()
    private val fileName = requireNotNull(storePath.fileName) { "store path must name a file" }.toString()
    private val parentPath = requireNotNull(storePath.parent) { "store path must have a parent" }
    private val trustedRootPath = trustedRoot.toAbsolutePath().normalize()
    private val lockPath = storePath.resolveSibling("$fileName.lock")

    fun read(): CommitMessagePortableReadResult {
        val operation = CommitMessagePortableStoreOperation.READ
        try {
            ensureSafeParent(operation, create = false)
            ensureSafeRegularFile(storePath, operation, allowMissing = true)
            if (!Files.exists(storePath, LinkOption.NOFOLLOW_LINKS)) {
                return CommitMessagePortableReadResult.Missing
            }

            val serialized = try {
                readUtf8()
            } catch (_: CharacterCodingException) {
                throw failure(CommitMessagePortableStoreFailure.MALFORMED_CONTENT, operation)
            }
            val envelope = try {
                JSON.decodeFromString<CommitMessagePortableEnvelope>(serialized)
            } catch (_: SerializationException) {
                throw failure(CommitMessagePortableStoreFailure.MALFORMED_CONTENT, operation)
            } catch (_: IllegalArgumentException) {
                throw failure(CommitMessagePortableStoreFailure.MALFORMED_CONTENT, operation)
            }
            return CommitMessagePortableReadResult.Found(envelope)
        } catch (exception: CommitMessagePortableStoreException) {
            throw exception
        } catch (exception: IOException) {
            throw failure(CommitMessagePortableStoreFailure.IO, operation, exception)
        } catch (exception: SecurityException) {
            throw failure(CommitMessagePortableStoreFailure.IO, operation, exception)
        }
    }

    fun write(envelope: CommitMessagePortableEnvelope): CommitMessagePortableWriteResult {
        return when (val result = writeLocked(envelope, expectedRevision = null, compareRevision = false)) {
            is CommitMessagePortableConditionalWriteResult.Written ->
                CommitMessagePortableWriteResult(result.replacedExisting)
            is CommitMessagePortableConditionalWriteResult.RevisionMismatch -> error("unconditional write cannot conflict")
        }
    }

    fun compareAndWrite(
        expectedRevision: String?,
        envelope: CommitMessagePortableEnvelope,
    ): CommitMessagePortableConditionalWriteResult = writeLocked(envelope, expectedRevision, compareRevision = true)

    fun <T> withExclusiveLock(action: () -> T): T =
        withExclusiveLock(DEFAULT_EXCLUSIVE_LOCK_TIMEOUT_MS, {}, action)

    fun <T> withExclusiveLock(
        timeoutMillis: Long,
        checkCancelled: () -> Unit,
        action: () -> T,
    ): T {
        val operation = CommitMessagePortableStoreOperation.WRITE
        return try {
            ensureSafeParent(operation, create = true)
            ensureSafeRegularFile(lockPath, operation, allowMissing = true)
            FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { lockChannel ->
                withTimedFileLock(lockChannel, timeoutMillis, checkCancelled) {
                    ensureSafeParent(operation, create = false)
                    ensureSafeRegularFile(lockPath, operation, allowMissing = false)
                    action()
                }
            }
        } catch (exception: CommitMessagePortableStoreException) {
            throw exception
        } catch (exception: IOException) {
            throw failure(CommitMessagePortableStoreFailure.IO, operation, exception)
        } catch (exception: SecurityException) {
            throw failure(CommitMessagePortableStoreFailure.IO, operation, exception)
        }
    }

    private fun writeLocked(
        envelope: CommitMessagePortableEnvelope,
        expectedRevision: String?,
        compareRevision: Boolean,
    ): CommitMessagePortableConditionalWriteResult {
        val operation = CommitMessagePortableStoreOperation.WRITE
        try {
            val bytes = JSON.encodeToString(envelope).toByteArray(StandardCharsets.UTF_8)
            ensureSafeParent(operation, create = true)
            ensureSafeRegularFile(storePath, operation, allowMissing = true)
            ensureSafeRegularFile(lockPath, operation, allowMissing = true)

            return FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { lockChannel ->
                withTimedFileLock(lockChannel, DEFAULT_EXCLUSIVE_LOCK_TIMEOUT_MS, {}) {
                    ensureSafeParent(operation, create = false)
                    ensureSafeRegularFile(lockPath, operation, allowMissing = false)
                    ensureSafeRegularFile(storePath, operation, allowMissing = true)
                    val current = when (val result = read()) {
                        is CommitMessagePortableReadResult.Found -> result.envelope
                        CommitMessagePortableReadResult.Missing -> null
                    }
                    if (compareRevision && current?.revision != expectedRevision) {
                        return CommitMessagePortableConditionalWriteResult.RevisionMismatch(current)
                    }
                    val replacedExisting = current != null
                    replaceAtomically(bytes, operation)
                    CommitMessagePortableConditionalWriteResult.Written(replacedExisting)
                }
            }
        } catch (exception: CommitMessagePortableStoreException) {
            throw exception
        } catch (exception: SerializationException) {
            throw failure(CommitMessagePortableStoreFailure.MALFORMED_CONTENT, operation)
        } catch (exception: IOException) {
            throw failure(CommitMessagePortableStoreFailure.IO, operation, exception)
        } catch (exception: SecurityException) {
            throw failure(CommitMessagePortableStoreFailure.IO, operation, exception)
        }
    }

    private fun readUtf8(): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return FileChannel.open(storePath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            Channels.newReader(channel, decoder, -1).use { reader -> reader.readText() }
        }
    }

    private inline fun <T> withTimedFileLock(
        channel: FileChannel,
        timeoutMillis: Long,
        checkCancelled: () -> Unit,
        action: () -> T,
    ): T {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var acquired: java.nio.channels.FileLock? = null
        while (acquired == null) {
            checkCancelled()
            acquired = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (acquired == null) {
                if (System.nanoTime() >= deadline) throw IOException("Portable settings lock timed out")
                try {
                    Thread.sleep(EXCLUSIVE_LOCK_POLL_MS)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Portable settings lock was interrupted", error)
                }
            }
        }
        return acquired.use { action() }
    }

    private fun replaceAtomically(bytes: ByteArray, operation: CommitMessagePortableStoreOperation) {
        val tempPath = Files.createTempFile(parentPath, ".$fileName.", ".tmp")
        var moved = false
        try {
            ensureSafeRegularFile(tempPath, operation, allowMissing = false)
            FileChannel.open(
                tempPath,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }

            ensureSafeParent(operation, create = false)
            ensureSafeRegularFile(tempPath, operation, allowMissing = false)
            ensureSafeRegularFile(storePath, operation, allowMissing = true)
            Files.move(
                tempPath,
                storePath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            moved = true
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(tempPath)
                } catch (_: IOException) {
                    // Preserve the original write failure; the random sibling temp is never reused.
                }
            }
        }
    }

    private fun ensureSafeParent(operation: CommitMessagePortableStoreOperation, create: Boolean) {
        rejectParentSymbolicLinks(operation)
        if (create) Files.createDirectories(parentPath)
        rejectParentSymbolicLinks(operation)
        if (Files.isSymbolicLink(parentPath) ||
            (Files.exists(parentPath, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isDirectory(parentPath, LinkOption.NOFOLLOW_LINKS))
        ) {
            throw failure(CommitMessagePortableStoreFailure.UNSAFE_PATH, operation)
        }
    }

    private fun rejectParentSymbolicLinks(operation: CommitMessagePortableStoreOperation) {
        if (!parentPath.startsWith(trustedRootPath) ||
            Files.isSymbolicLink(trustedRootPath) ||
            (Files.exists(trustedRootPath, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isDirectory(trustedRootPath, LinkOption.NOFOLLOW_LINKS))
        ) {
            throw failure(CommitMessagePortableStoreFailure.UNSAFE_PATH, operation)
        }
        var candidate = trustedRootPath
        trustedRootPath.relativize(parentPath).forEach { component ->
            candidate = candidate.resolve(component)
            if (Files.isSymbolicLink(candidate) ||
                (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS))
            ) {
                throw failure(CommitMessagePortableStoreFailure.UNSAFE_PATH, operation)
            }
        }
    }

    private fun ensureSafeRegularFile(
        path: Path,
        operation: CommitMessagePortableStoreOperation,
        allowMissing: Boolean,
    ) {
        if (Files.isSymbolicLink(path)) {
            throw failure(CommitMessagePortableStoreFailure.UNSAFE_PATH, operation)
        }
        val exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        if ((!allowMissing && !exists) || (exists && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
            throw failure(CommitMessagePortableStoreFailure.UNSAFE_PATH, operation)
        }
    }

    private fun failure(
        failure: CommitMessagePortableStoreFailure,
        operation: CommitMessagePortableStoreOperation,
        cause: Throwable? = null,
    ): CommitMessagePortableStoreException = CommitMessagePortableStoreException(
        failure = failure,
        operation = operation,
        storePath = storePath,
        cause = cause,
    )

    private companion object {
        const val DEFAULT_EXCLUSIVE_LOCK_TIMEOUT_MS = 30_000L
        const val EXCLUSIVE_LOCK_POLL_MS = 50L
        val JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
            isLenient = false
        }
    }
}
