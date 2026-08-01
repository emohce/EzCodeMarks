package emohce.data.commitmessage

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class CodexAppServerConnectionTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `closing transport terminates descendants even when the parent exits on stdin EOF`() {
        assumeFalse(System.getProperty("os.name").contains("windows", ignoreCase = true))
        val pidFile = tempDir.resolve("child.pid")
        val connection = CodexAppServerConnection.open(
            executable = "fake-codex",
            cwd = tempDir,
            environment = emptyMap(),
            listener = object : CodexAppServerConnectionListener {},
            processFactory = CodexAppServerConnectionProcessFactory { _, _, _ ->
                ProcessBuilder(
                    "/bin/sh",
                    "-c",
                    "sleep 60 & echo ${'$'}! > \"${pidFile}\"; read ignored || exit 0",
                ).start()
            },
        )
        await { Files.exists(pidFile) && Files.readString(pidFile).trim().isNotEmpty() }
        val childPid = Files.readString(pidFile).trim().toLong()
        assertTrue(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))

        try {
            connection.close()
            await { !ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false) }
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))
        } finally {
            ProcessHandle.of(childPid).ifPresent { if (it.isAlive) it.destroyForcibly() }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for process state")
    }
}
