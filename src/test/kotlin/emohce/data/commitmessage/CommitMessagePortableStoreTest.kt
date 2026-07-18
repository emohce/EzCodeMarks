package emohce.data.commitmessage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.UUID

class CommitMessagePortableStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `round trip preserves the canonical payload without interpreting it`() {
        val path = tempDir.resolve("nested/portable-settings.json")
        val store = CommitMessagePortableStore(path)
        val payload = """{"apiKey":"opaque-not-interpreted","styles":["a","b"]}"""
        val envelope = CommitMessagePortableEnvelope.create(payload)

        val write = store.write(envelope)
        val read = store.read()

        assertFalse(write.replacedExisting)
        assertTrue(read is CommitMessagePortableReadResult.Found)
        assertEquals(envelope, (read as CommitMessagePortableReadResult.Found).envelope)
        assertEquals(payload, read.envelope.payload)
        assertFalse(envelope.toString().contains("opaque-not-interpreted"))
    }

    @Test
    fun `atomic replacement reports replacement and leaves no sibling temp`() {
        val path = tempDir.resolve("portable-settings.json")
        val store = CommitMessagePortableStore(path)
        val first = CommitMessagePortableEnvelope.create("""{"value":1}""")
        val second = CommitMessagePortableEnvelope.successor("""{"value":2}""", first)

        assertFalse(store.write(first).replacedExisting)
        assertTrue(store.write(second).replacedExisting)

        val found = store.read() as CommitMessagePortableReadResult.Found
        assertEquals(second, found.envelope)
        val siblingNames = Files.list(tempDir).use { paths ->
            paths.map { it.fileName.toString() }.toList()
        }
        assertEquals(setOf("portable-settings.json", "portable-settings.json.lock"), siblingNames.toSet())
    }

    @Test
    fun `equivalent payloads converge without conflict when revisions differ`() {
        val common = CommitMessagePortableEnvelope.create("""{"same":true}""")
        val incoming = CommitMessagePortableEnvelope.create("""{"same":true}""")

        val outcome = CommitMessagePortableReconciler.reconcile(common, incoming)

        assertTrue(outcome is CommitMessagePortableReconciliation.EquivalentPayload)
        val converged = (outcome as CommitMessagePortableReconciliation.EquivalentPayload).converged
        assertEquals(common.payload, converged.payload)
        assertNotEquals(common.revision, converged.revision)
        assertNotEquals(incoming.revision, converged.revision)
        assertTrue(converged.ancestors.containsAll(listOf(common.revision, incoming.revision)))
    }

    @Test
    fun `both ancestry directions recognize multi generation lineage`() {
        val root = CommitMessagePortableEnvelope.create("root")
        val child = CommitMessagePortableEnvelope.successor("child", root)
        val grandchild = CommitMessagePortableEnvelope.successor("grandchild", child)

        val incomingOutcome = CommitMessagePortableReconciler.reconcile(root, grandchild)
        val commonOutcome = CommitMessagePortableReconciler.reconcile(grandchild, root)

        assertEquals(
            CommitMessagePortableReconciliation.IncomingDescendant(grandchild),
            incomingOutcome,
        )
        assertEquals(
            CommitMessagePortableReconciliation.CommonDescendant(grandchild),
            commonOutcome,
        )
        assertTrue(root.revision in grandchild.ancestors)
    }

    @Test
    fun `shared ancestor with different branch payloads is a divergence conflict`() {
        val root = CommitMessagePortableEnvelope.create("root")
        val common = CommitMessagePortableEnvelope.successor("common branch", root)
        val incoming = CommitMessagePortableEnvelope.successor("incoming branch", root)

        val outcome = CommitMessagePortableReconciler.reconcile(common, incoming)

        assertEquals(
            CommitMessagePortableReconciliation.DivergenceConflict(common, incoming),
            outcome,
        )
    }

    @Test
    fun `conditional write preserves the winning sibling instead of overwriting it`() {
        val store = CommitMessagePortableStore(tempDir.resolve("conditional-settings.json"))
        val root = CommitMessagePortableEnvelope.create("root")
        store.write(root)
        val first = CommitMessagePortableEnvelope.successor("first", root)
        val second = CommitMessagePortableEnvelope.successor("second", root)

        val firstResult = store.compareAndWrite(root.revision, first)
        val secondResult = store.compareAndWrite(root.revision, second)

        assertEquals(CommitMessagePortableConditionalWriteResult.Written(true), firstResult)
        assertEquals(CommitMessagePortableConditionalWriteResult.RevisionMismatch(first), secondResult)
        assertEquals(first, (store.read() as CommitMessagePortableReadResult.Found).envelope)
    }

    @Test
    fun `exclusive lock wait is cancellable and bounded within the same process`() {
        val path = tempDir.resolve("bounded-lock.json")
        val store = CommitMessagePortableStore(path)
        store.write(CommitMessagePortableEnvelope.create("initial"))
        FileChannel.open(
            path.resolveSibling("bounded-lock.json.lock"),
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use {
                var checks = 0
                assertThrows(IllegalStateException::class.java) {
                    store.withExclusiveLock(
                        timeoutMillis = 1_000,
                        checkCancelled = {
                            checks += 1
                            if (checks > 1) throw IllegalStateException("cancelled")
                        },
                    ) { error("lock must not be acquired") }
                }
                val timeout = assertThrows(CommitMessagePortableStoreException::class.java) {
                    store.withExclusiveLock(
                        timeoutMillis = 25,
                        checkCancelled = {},
                    ) { error("lock must not be acquired") }
                }
                assertEquals(CommitMessagePortableStoreFailure.IO, timeout.failure)
            }
        }
    }

    @Test
    fun `concurrent processes preserve one conditional write winner`() {
        val path = tempDir.resolve("cross-process-settings.json")
        val store = CommitMessagePortableStore(path)
        val root = CommitMessagePortableEnvelope.create("root")
        store.write(root)
        val go = tempDir.resolve("race-go")
        val firstReady = tempDir.resolve("first-ready")
        val secondReady = tempDir.resolve("second-ready")
        val first = startRaceWorker(path, root.revision, uuid(), "first", firstReady, go)
        val second = startRaceWorker(path, root.revision, uuid(), "second", secondReady, go)

        try {
            val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while ((!Files.exists(firstReady) || !Files.exists(secondReady)) && System.nanoTime() < readyDeadline) {
                Thread.sleep(10)
            }
            assertTrue(Files.exists(firstReady) && Files.exists(secondReady), "Race workers did not become ready")
            Files.writeString(go, "go", StandardCharsets.UTF_8)

            assertTrue(first.waitFor(20, TimeUnit.SECONDS), "First race worker did not exit")
            assertTrue(second.waitFor(20, TimeUnit.SECONDS), "Second race worker did not exit")
            val outputs = listOf(first, second).map { process ->
                val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                assertEquals(0, process.exitValue(), output)
                output.lineSequence().firstOrNull { it.startsWith("RESULT:") }
                    ?: throw AssertionError("Race worker returned no result: $output")
            }

            assertEquals(setOf("RESULT:WRITTEN", "RESULT:MISMATCH"), outputs.toSet())
            val found = store.read() as CommitMessagePortableReadResult.Found
            assertTrue(found.envelope.payload.startsWith("first") || found.envelope.payload.startsWith("second"))
        } finally {
            if (first.isAlive) first.destroyForcibly()
            if (second.isAlive) second.destroyForcibly()
        }
    }

    @Test
    fun `missing side identifies common incoming and both`() {
        val envelope = CommitMessagePortableEnvelope.create("payload")

        assertEquals(
            CommitMessagePortableReconciliation.MissingSide(
                CommitMessagePortableMissingSide.COMMON,
                envelope,
            ),
            CommitMessagePortableReconciler.reconcile(null, envelope),
        )
        assertEquals(
            CommitMessagePortableReconciliation.MissingSide(
                CommitMessagePortableMissingSide.INCOMING,
                envelope,
            ),
            CommitMessagePortableReconciler.reconcile(envelope, null),
        )
        assertEquals(
            CommitMessagePortableReconciliation.MissingSide(
                CommitMessagePortableMissingSide.BOTH,
                null,
            ),
            CommitMessagePortableReconciler.reconcile(null, null),
        )
        assertEquals(
            CommitMessagePortableReadResult.Missing,
            CommitMessagePortableStore(tempDir.resolve("missing.json")).read(),
        )
    }

    @Test
    fun `successor unions both lineages deduplicates and applies the bound`() {
        val shared = uuids(8)
        val commonOnly = uuids(24)
        val incomingOnly = uuids(24)
        val common = CommitMessagePortableEnvelope(
            schemaVersion = 1,
            revision = uuid(),
            ancestors = (shared + commonOnly).take(CommitMessagePortableEnvelope.MAX_ANCESTORS),
            payload = "common",
        )
        val incoming = CommitMessagePortableEnvelope(
            schemaVersion = 1,
            revision = uuid(),
            ancestors = (shared + incomingOnly).take(CommitMessagePortableEnvelope.MAX_ANCESTORS),
            payload = "incoming",
        )

        val successor = CommitMessagePortableEnvelope.successor("merged", common, incoming)

        assertEquals(CommitMessagePortableEnvelope.MAX_ANCESTORS, successor.ancestors.size)
        assertEquals(successor.ancestors.size, successor.ancestors.distinct().size)
        assertTrue(common.revision in successor.ancestors)
        assertTrue(incoming.revision in successor.ancestors)
        assertTrue(successor.ancestors.any(commonOnly::contains))
        assertTrue(successor.ancestors.any(incomingOnly::contains))
        assertFalse(successor.revision in successor.ancestors)
    }

    @Test
    fun `history beyond the ancestry horizon remains an explicit conflict`() {
        val root = CommitMessagePortableEnvelope.create("root")
        var latest = root
        repeat(CommitMessagePortableEnvelope.MAX_ANCESTORS + 1) { index ->
            latest = CommitMessagePortableEnvelope.successor("version-$index", latest)
        }

        assertFalse(root.revision in latest.ancestors)
        assertEquals(
            CommitMessagePortableReconciliation.DivergenceConflict(root, latest),
            CommitMessagePortableReconciler.reconcile(root, latest),
        )
    }

    @Test
    fun `malformed content surfaces a compact payload free failure`() {
        val path = tempDir.resolve("portable-settings.json")
        val secretMarker = "must-not-appear"
        Files.writeString(
            path,
            """{"schemaVersion":1,"revision":"${uuid()}","ancestors":[],"payload":"$secretMarker"""",
            StandardCharsets.UTF_8,
        )

        val exception = assertThrows(CommitMessagePortableStoreException::class.java) {
            CommitMessagePortableStore(path).read()
        }

        assertEquals(CommitMessagePortableStoreFailure.MALFORMED_CONTENT, exception.failure)
        assertEquals(CommitMessagePortableStoreOperation.READ, exception.operation)
        assertFalse(exception.message.orEmpty().contains(secretMarker))
        assertNull(exception.cause)
    }

    @Test
    fun `symbolic link store lock and parent hazards are refused where supported`() {
        val target = tempDir.resolve("target.json")
        Files.writeString(target, "unchanged", StandardCharsets.UTF_8)
        val linkedStore = tempDir.resolve("linked-store.json")
        createSymbolicLinkOrSkip(linkedStore, target)

        assertUnsafePath {
            CommitMessagePortableStore(linkedStore).write(CommitMessagePortableEnvelope.create("replacement"))
        }
        assertEquals("unchanged", Files.readString(target, StandardCharsets.UTF_8))

        val lockTarget = tempDir.resolve("lock-target")
        Files.writeString(lockTarget, "unchanged", StandardCharsets.UTF_8)
        val lockHazardStore = tempDir.resolve("lock-hazard.json")
        Files.createSymbolicLink(tempDir.resolve("lock-hazard.json.lock"), lockTarget)
        assertUnsafePath {
            CommitMessagePortableStore(lockHazardStore).write(CommitMessagePortableEnvelope.create("payload"))
        }
        assertEquals("unchanged", Files.readString(lockTarget, StandardCharsets.UTF_8))

        val realParent = tempDir.resolve("real-parent")
        Files.createDirectory(realParent)
        val linkedParent = tempDir.resolve("linked-parent")
        Files.createSymbolicLink(linkedParent, realParent)
        assertUnsafePath {
            CommitMessagePortableStore(linkedParent.resolve("settings.json"))
                .write(CommitMessagePortableEnvelope.create("payload"))
        }
        assertFalse(Files.exists(realParent.resolve("settings.json")))

        val nestedRealParent = tempDir.resolve("nested-real-parent")
        Files.createDirectory(nestedRealParent)
        val nestedLinkedComponent = tempDir.resolve("nested-linked-component")
        Files.createSymbolicLink(nestedLinkedComponent, nestedRealParent)
        assertUnsafePath {
            CommitMessagePortableStore(
                nestedLinkedComponent.resolve("child/settings.json"),
                trustedRoot = tempDir,
            ).write(CommitMessagePortableEnvelope.create("payload"))
        }
        assertFalse(Files.exists(nestedRealParent.resolve("child/settings.json")))
    }

    @Test
    fun `codex isolation root rejects a symbolic link below the trusted root`() {
        val real = tempDir.resolve("real-codex-root")
        Files.createDirectory(real)
        val linked = tempDir.resolve("linked-codex-root")
        createSymbolicLinkOrSkip(linked, real)

        assertThrows(IllegalArgumentException::class.java) {
            prepareCodexIsolationRoot(tempDir, linked.resolve("provider"))
        }
        assertFalse(Files.exists(real.resolve("provider")))
    }

    private fun assertUnsafePath(action: () -> Unit) {
        val exception = assertThrows(CommitMessagePortableStoreException::class.java, action)
        assertEquals(CommitMessagePortableStoreFailure.UNSAFE_PATH, exception.failure)
    }

    private fun createSymbolicLinkOrSkip(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "Symbolic links are unsupported by this file system")
        } catch (_: FileSystemException) {
            assumeTrue(false, "Symbolic links are unavailable in this environment")
        } catch (_: SecurityException) {
            assumeTrue(false, "Symbolic links are forbidden in this environment")
        } catch (_: IOException) {
            assumeTrue(false, "Symbolic links could not be created in this environment")
        }
    }

    private fun startRaceWorker(
        path: Path,
        expectedRevision: String,
        candidateRevision: String,
        label: String,
        ready: Path,
        go: Path,
    ): Process = ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp",
        System.getProperty("java.class.path"),
        CommitMessagePortableStoreRaceWorker::class.java.name,
        path.toString(),
        expectedRevision,
        candidateRevision,
        label,
        ready.toString(),
        go.toString(),
    ).redirectErrorStream(true).start()

    private fun uuids(count: Int): List<String> = List(count) { uuid() }

    private fun uuid(): String = UUID.randomUUID().toString()
}

internal object CommitMessagePortableStoreRaceWorker {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val path = Path.of(arguments[0])
        val expectedRevision = arguments[1]
        val candidate = CommitMessagePortableEnvelope(
            schemaVersion = CommitMessagePortableEnvelope.DEFAULT_SCHEMA_VERSION,
            revision = arguments[2],
            ancestors = listOf(expectedRevision),
            payload = arguments[3].repeat(500_000),
        )
        val ready = Path.of(arguments[4])
        val go = Path.of(arguments[5])
        Files.writeString(ready, "ready", StandardCharsets.UTF_8)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!Files.exists(go) && System.nanoTime() < deadline) Thread.sleep(5)
        check(Files.exists(go)) { "Race start was not released" }

        val result = CommitMessagePortableStore(path).compareAndWrite(expectedRevision, candidate)
        when (result) {
            is CommitMessagePortableConditionalWriteResult.Written -> println("RESULT:WRITTEN")
            is CommitMessagePortableConditionalWriteResult.RevisionMismatch -> println("RESULT:MISMATCH")
        }
    }
}
