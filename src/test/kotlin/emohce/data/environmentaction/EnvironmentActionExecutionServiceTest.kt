package emohce.data.environmentaction

import emohce.environmentaction.EnvironmentActionsBundle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EnvironmentActionExecutionServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val service = EnvironmentActionExecutionService()
    private val executor = Executors.newSingleThreadExecutor()

    @AfterEach
    fun tearDown() {
        service.dispose()
        executor.shutdownNow()
    }

    @Test
    fun `stdout and stderr stream independently while retaining bounded results`() {
        assumeFalse(isWindows())
        val streamed = CopyOnWriteArrayList<Pair<String, Boolean>>()

        val result = service.runAction(
            EnvironmentDefinition(workingDirectory = tempDir.toString()),
            EnvironmentActionDefinition(
                slot = 1,
                type = EnvironmentActionType.SHELL,
                command = "printf alpha; printf beta >&2",
            ),
            onOutput = { text, stderr -> streamed += text to stderr },
        )

        assertEquals(0, result.exitCode)
        assertEquals("alpha", result.stdout)
        assertEquals("beta", result.stderr)
        assertTrue(streamed.any { (text, stderr) -> text.contains("alpha") && !stderr })
        assertTrue(streamed.any { (text, stderr) -> text.contains("beta") && stderr })
    }

    @Test
    fun `captured output keeps only the latest sixty four thousand characters`() {
        assumeFalse(isWindows())
        val script = tempDir.resolve("large.py")
        Files.writeString(script, "print('x' * 70000)")

        val result = service.runAction(
            EnvironmentDefinition(workingDirectory = tempDir.toString()),
            EnvironmentActionDefinition(
                slot = 1,
                type = EnvironmentActionType.SCRIPT,
                scriptPath = script.fileName.toString(),
            ),
        )

        assertEquals(0, result.exitCode)
        assertEquals(64_000, result.stdout.length)
        assertTrue(result.stdout.endsWith("\n"))
    }

    @Test
    fun `cancelling an action terminates its spawned process tree`() {
        assumeFalse(isWindows())
        val handle = EnvironmentActionExecutionService.ExecutionHandle()
        val pidOutput = StringBuilder()
        val pidReady = CountDownLatch(1)
        val future = executor.submit<EnvironmentActionExecutionResult> {
            service.runAction(
                EnvironmentDefinition(workingDirectory = tempDir.toString()),
                EnvironmentActionDefinition(
                    slot = 1,
                    type = EnvironmentActionType.SHELL,
                    command = "sleep 60 & echo ${'$'}!; wait",
                ),
                externalHandle = handle,
                onOutput = { text, stderr ->
                    if (!stderr) {
                        synchronized(pidOutput) { pidOutput.append(text) }
                        if ('\n' in text) pidReady.countDown()
                    }
                },
            )
        }
        assertTrue(pidReady.await(5, TimeUnit.SECONDS), "child PID was not emitted")
        val childPid = synchronized(pidOutput) { pidOutput.toString().lineSequence().first().trim().toLong() }

        try {
            handle.cancel()
            val result = future.get(5, TimeUnit.SECONDS)
            assertTrue(result.cancelled)
            awaitProcessExit(childPid)
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))
        } finally {
            ProcessHandle.of(childPid).ifPresent { if (it.isAlive) it.destroyForcibly() }
        }
    }

    @Test
    fun `one shot approval failure directs the user to Codex Chat`() {
        assumeFalse(isWindows())
        val fakeCodex = tempDir.resolve("fake-codex")
        Files.writeString(fakeCodex, "#!/bin/sh\necho 'interactive approval required' >&2\nexit 2\n")
        Files.setPosixFilePermissions(
            fakeCodex,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )

        val result = service.runAction(
            EnvironmentDefinition(workingDirectory = tempDir.toString()),
            EnvironmentActionDefinition(slot = 1, type = EnvironmentActionType.CODEX, command = "inspect"),
            codexExecutable = fakeCodex.toString(),
        )

        assertEquals(2, result.exitCode)
        assertEquals(EnvironmentActionsBundle.message("action.error.codexApprovalRequired"), result.failureMessage)
    }

    @Test
    fun `shared running handle notifies both original and duplicate callers once`() {
        val handle = EnvironmentActionExecutionService.ExecutionHandle()
        val observed = mutableListOf<String>()
        val result = EnvironmentActionExecutionResult("action", 0, "", "", false, false)
        handle.addCompletionListener { observed += "original:${it.actionId}" }
        handle.addCompletionListener { observed += "duplicate:${it.actionId}" }

        handle.finish(result)
        handle.finish(result)
        handle.addCompletionListener { observed += "late:${it.actionId}" }

        assertEquals(
            listOf("original:action", "duplicate:action", "late:action"),
            observed,
        )
        assertTrue(handle.isDone)
    }

    private fun awaitProcessExit(pid: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) return
            Thread.sleep(20)
        }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").contains("windows", ignoreCase = true)
}
