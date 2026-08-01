package emohce.data.environmentaction

import emohce.data.commitmessage.codexPlatformCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EnvironmentActionSettingsStateTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `normalization creates ten stable slots and preserves action order`() {
        val environment = EnvironmentDefinition(
            name = "Local",
            actions = mutableListOf(
                EnvironmentActionDefinition(slot = 1, name = "First"),
                EnvironmentActionDefinition(slot = 2, name = "Second"),
            ),
            actionOrder = mutableListOf(2, 1),
        )
        val state = EnvironmentActionSettingsState(environments = mutableListOf(environment))

        state.normalize()

        val normalizedEnvironment = state.environments.single()
        val actions = normalizedEnvironment.actions
        assertEquals((1..ENVIRONMENT_ACTION_SLOT_COUNT).toList(), actions.map { it.slot })
        assertEquals(listOf(2, 1) + (3..ENVIRONMENT_ACTION_SLOT_COUNT), normalizedEnvironment.actionOrder)
        assertEquals(listOf(2, 1) + (3..ENVIRONMENT_ACTION_SLOT_COUNT), normalizedEnvironment.orderedActions().map { it.slot })
        assertTrue(actions.drop(2).all { it.name == "Action ${it.slot}" })
    }

    @Test
    fun `normalization drops secret-like variables and redaction hides emitted values`() {
        val state = EnvironmentActionSettingsState(
            environments = mutableListOf(
                EnvironmentDefinition(
                    variables = linkedMapOf(
                        "SAFE_MODE" to "on",
                        "API_TOKEN" to "plain-secret",
                        "PASSWORD" to "plain-password",
                    ),
                ),
            ),
        )

        state.normalize()

        assertEquals(mapOf("SAFE_MODE" to "on"), state.environments.single().variables)
        assertEquals(
            "token=<redacted> password: <redacted>",
            EnvironmentActionSecurity.redact("token=abc password: xyz"),
        )
    }

    @Test
    fun `v1 state migrates active environment and legacy commit without losing message`() {
        val environmentId = UUID.randomUUID().toString()
        val actionId = UUID.randomUUID().toString()
        val path = tempDir.resolve("legacy.json")
        Files.writeString(
            path,
            """
                {
                  "schemaVersion": 1,
                  "activeEnvironmentId": "$environmentId",
                  "environments": [{
                    "id": "$environmentId",
                    "name": "Legacy",
                    "actions": [{
                      "id": "$actionId",
                      "slot": 1,
                      "name": "Commit",
                      "type": "GIT_COMMIT",
                      "command": "feat: preserve me"
                    }]
                  }]
                }
            """.trimIndent(),
        )

        val migrated = EnvironmentActionStore(path).read()!!

        assertEquals(2, migrated.schemaVersion)
        assertEquals(environmentId, migrated.defaultEnvironmentId)
        assertEquals(EnvironmentActionType.PREPARE_COMMIT, migrated.environments.single().actions.first().type)
        assertEquals("feat: preserve me", migrated.environments.single().actions.first().command)
        assertEquals((1..ENVIRONMENT_ACTION_SLOT_COUNT).toList(), migrated.environments.single().actionOrder)
    }

    @Test
    fun `v1 state has a stable migration revision and can be upgraded through CAS`() {
        val environmentId = UUID.randomUUID().toString()
        val path = tempDir.resolve("legacy-cas.json")
        Files.writeString(
            path,
            """
                {
                  "schemaVersion": 1,
                  "activeEnvironmentId": "$environmentId",
                  "environments": [{
                    "id": "$environmentId",
                    "name": "Legacy",
                    "actions": []
                  }]
                }
            """.trimIndent(),
        )
        val store = EnvironmentActionStore(path)
        val firstRead = store.read()!!
        val secondRead = store.read()!!
        assertEquals(firstRead.revision, secondRead.revision)

        val result = assertInstanceOf(
            EnvironmentActionStoreWriteResult.Success::class.java,
            store.compareAndWrite(
                firstRead.revision,
                firstRead.copyForSnapshot().apply { environments.single().name = "Migrated" },
            ),
        )

        assertEquals(2, result.state.schemaVersion)
        assertEquals("Migrated", store.read()?.environments?.single()?.name)
        assertTrue(result.state.revision != firstRead.revision)
    }

    @Test
    fun `store uses revision CAS and never overwrites a stale writer`() {
        val store = EnvironmentActionStore(tempDir.resolve("settings.json"))
        val initial = EnvironmentActionSettingsState(
            environments = mutableListOf(EnvironmentDefinition(name = "Initial")),
        ).apply { normalize() }
        val written = assertInstanceOf(
            EnvironmentActionStoreWriteResult.Success::class.java,
            store.compareAndWrite(null, initial),
        ).state
        val next = written.copyForSnapshot().apply { environments.single().name = "Remote" }
        val remote = assertInstanceOf(
            EnvironmentActionStoreWriteResult.Success::class.java,
            store.compareAndWrite(written.revision, next),
        ).state

        val stale = written.copyForSnapshot().apply { environments.single().workingDirectory = tempDir.toString() }
        val conflict = assertInstanceOf(
            EnvironmentActionStoreWriteResult.Conflict::class.java,
            store.compareAndWrite(written.revision, stale),
        )

        assertEquals(remote.revision, conflict.current?.revision)
        assertEquals("Remote", store.read()?.environments?.single()?.name)
    }

    @Test
    fun `two store instances serialize competing writes without corrupting the file`() {
        val path = tempDir.resolve("contended.json")
        val firstStore = EnvironmentActionStore(path)
        val secondStore = EnvironmentActionStore(path)
        val initial = EnvironmentActionSettingsState(
            environments = mutableListOf(EnvironmentDefinition(name = "Initial")),
        ).apply { normalize() }
        val base = assertInstanceOf(
            EnvironmentActionStoreWriteResult.Success::class.java,
            firstStore.compareAndWrite(null, initial),
        ).state
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val results = CopyOnWriteArrayList<EnvironmentActionStoreWriteResult>()
        val executor = Executors.newFixedThreadPool(2)
        try {
            listOf(firstStore to "First", secondStore to "Second").forEach { (store, name) ->
                executor.submit {
                    ready.countDown()
                    start.await()
                    results += store.compareAndWrite(
                        base.revision,
                        base.copyForSnapshot().apply { environments.single().name = name },
                    )
                }
            }
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            start.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, results.count { it is EnvironmentActionStoreWriteResult.Success })
        assertEquals(1, results.count { it is EnvironmentActionStoreWriteResult.Conflict })
        assertTrue(firstStore.read()?.environments?.single()?.name in setOf("First", "Second"))
    }

    @Test
    fun `independent JVMs preserve one CAS winner`() {
        val path = tempDir.resolve("cross-process.json")
        val store = EnvironmentActionStore(path)
        val base = assertInstanceOf(
            EnvironmentActionStoreWriteResult.Success::class.java,
            store.compareAndWrite(
                null,
                EnvironmentActionSettingsState(
                    environments = mutableListOf(EnvironmentDefinition(name = "Initial")),
                ).apply { normalize() },
            ),
        ).state
        val go = tempDir.resolve("race-go")
        val firstReady = tempDir.resolve("first-ready")
        val secondReady = tempDir.resolve("second-ready")
        val first = startRaceWorker(path, base.revision, "First", firstReady, go)
        val second = startRaceWorker(path, base.revision, "Second", secondReady, go)
        try {
            val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while ((!Files.exists(firstReady) || !Files.exists(secondReady)) && System.nanoTime() < readyDeadline) {
                Thread.sleep(10)
            }
            assertTrue(Files.exists(firstReady) && Files.exists(secondReady), "Race workers did not become ready")
            Files.writeString(go, "go", StandardCharsets.UTF_8)
            assertTrue(first.waitFor(20, TimeUnit.SECONDS), "First race worker did not exit")
            assertTrue(second.waitFor(20, TimeUnit.SECONDS), "Second race worker did not exit")
            val results = listOf(first, second).map { process ->
                val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                assertEquals(0, process.exitValue(), output)
                output.lineSequence().first { it.startsWith("RESULT:") }
            }
            assertEquals(setOf("RESULT:SUCCESS", "RESULT:CONFLICT"), results.toSet())
            assertTrue(store.read()?.environments?.single()?.name in setOf("First", "Second"))
        } finally {
            if (first.isAlive) first.destroyForcibly()
            if (second.isAlive) second.destroyForcibly()
        }
    }

    @Test
    fun `action display order survives a store round trip`() {
        val store = EnvironmentActionStore(tempDir.resolve("order.json"))
        val initial = EnvironmentActionSettingsState(
            environments = mutableListOf(
                EnvironmentDefinition(
                    name = "Ordered",
                    actionOrder = mutableListOf(3, 1, 2, 10, 9, 8, 7, 6, 5, 4),
                ),
            ),
        ).apply { normalize() }
        val written = assertInstanceOf(
            EnvironmentActionStoreWriteResult.Success::class.java,
            store.compareAndWrite(null, initial),
        )

        assertEquals(
            listOf(3, 1, 2, 10, 9, 8, 7, 6, 5, 4),
            EnvironmentActionStore(tempDir.resolve("order.json")).read()?.environments?.single()?.actionOrder,
        )
        assertTrue(written.state.revision.isNotBlank())
    }

    @Test
    fun `services auto merge different slots and require explicit same-field resolution`() {
        val path = tempDir.resolve("services.json")
        val first = EnvironmentActionSettingsService(EnvironmentActionStore(path))
        val empty = first.snapshot()
        first.replaceState(
            empty,
            EnvironmentActionSettingsState(
                revision = empty.revision,
                environments = mutableListOf(EnvironmentDefinition(name = "Shared")),
            ),
        )
        val second = EnvironmentActionSettingsService(EnvironmentActionStore(path))
        val firstBase = first.snapshot()
        val secondBase = second.snapshot()
        first.replaceState(
            firstBase,
            firstBase.copyForSnapshot().apply { environments.single().actions[0].name = "First slot" },
        )

        second.replaceState(
            secondBase,
            secondBase.copyForSnapshot().apply { environments.single().actions[1].name = "Second slot" },
        )

        val merged = first.snapshot()
        assertEquals("First slot", merged.environments.single().actions[0].name)
        assertEquals("Second slot", merged.environments.single().actions[1].name)

        val nextFirstBase = first.snapshot()
        val nextSecondBase = second.snapshot()
        first.replaceState(
            nextFirstBase,
            nextFirstBase.copyForSnapshot().apply { environments.single().actions[0].name = "Remote choice" },
        )
        val localChoice = nextSecondBase.copyForSnapshot().apply {
            environments.single().actions[0].name = "Local choice"
        }
        val conflict = assertThrows(EnvironmentActionSettingsConflictException::class.java) {
            second.replaceState(nextSecondBase, localChoice)
        }
        assertTrue(conflict.conflictingPaths.any { it.endsWith("actions.1.name") })

        second.replaceState(nextSecondBase, localChoice, EnvironmentActionConflictResolution.LOCAL)
        assertEquals("Local choice", first.snapshot().environments.single().actions[0].name)
    }

    @Test
    fun `deleted common store requires a choice and supports local or remote resolution`() {
        val path = tempDir.resolve("deleted.json")
        val service = EnvironmentActionSettingsService(EnvironmentActionStore(path))
        val empty = service.snapshot()
        service.replaceState(
            empty,
            EnvironmentActionSettingsState(
                revision = empty.revision,
                environments = mutableListOf(EnvironmentDefinition(name = "Shared")),
            ),
        )
        val localBase = service.snapshot()
        val local = localBase.copyForSnapshot().apply { environments.single().name = "Keep local" }
        Files.delete(path)

        assertThrows(EnvironmentActionSettingsConflictException::class.java) {
            service.replaceState(localBase, local)
        }
        val restored = service.replaceState(localBase, local, EnvironmentActionConflictResolution.LOCAL)
        assertEquals("Keep local", restored.environments.single().name)

        val remoteBase = service.snapshot()
        Files.delete(path)
        val cleared = service.replaceState(
            remoteBase,
            remoteBase.copyForSnapshot().apply { environments.single().name = "Discard me" },
            EnvironmentActionConflictResolution.REMOTE,
        )
        assertTrue(cleared.environments.isEmpty())
        assertTrue(EnvironmentActionStore(path).read()?.environments?.isEmpty() == true)
    }

    @Test
    fun `three way merge combines different slots and flags same-field conflicts`() {
        val base = normalizedState()
        val local = base.copyForSnapshot().apply { environments.single().actions[0].name = "Local slot 1" }
        val remote = base.copyForSnapshot().apply {
            revision = UUID.randomUUID().toString()
            environments.single().actions[1].name = "Remote slot 2"
        }

        val merged = EnvironmentActionSettingsMerger.merge(base, local, remote)

        assertTrue(merged.conflicts.isEmpty())
        assertEquals("Local slot 1", merged.state.environments.single().actions[0].name)
        assertEquals("Remote slot 2", merged.state.environments.single().actions[1].name)

        val conflictingRemote = remote.copyForSnapshot().apply { environments.single().actions[0].name = "Remote slot 1" }
        val conflict = EnvironmentActionSettingsMerger.merge(base, local, conflictingRemote)
        assertTrue(conflict.conflicts.any { it.endsWith("actions.1.name") })
        assertEquals("Remote slot 1", conflict.state.environments.single().actions[0].name)

        val keepLocal = EnvironmentActionSettingsMerger.merge(
            base,
            local,
            conflictingRemote,
            EnvironmentActionConflictResolution.LOCAL,
        )
        assertEquals("Local slot 1", keepLocal.state.environments.single().actions[0].name)
    }

    @Test
    fun `project selection is isolated and falls back to shared default`() {
        val settings = normalizedState()
        val first = settings.environments.single()
        val second = EnvironmentDefinition(name = "Second").also(settings.environments::add)
        settings.normalize()
        val projectA = EnvironmentActionProjectStateService()
        val projectB = EnvironmentActionProjectStateService()

        assertTrue(projectA.select(second.id, settings))

        assertEquals(second.id, projectA.activeEnvironment(settings)?.id)
        assertEquals(first.id, projectB.activeEnvironment(settings)?.id)
        assertFalse(projectB.select("missing", settings))
    }

    @Test
    fun `script actions resolve relative paths after applying working directory`() {
        val workDirectory = Files.createDirectory(tempDir.resolve("workspace"))
        val scriptDirectory = Files.createDirectories(workDirectory.resolve("tools"))
        val script = Files.writeString(scriptDirectory.resolve("check.py"), "print('ok')")

        val command = EnvironmentActionCommandResolver.resolve(
            EnvironmentDefinition(workingDirectory = workDirectory.toString()),
            EnvironmentActionDefinition(
                slot = 1,
                type = EnvironmentActionType.SCRIPT,
                scriptPath = "tools/check.py",
                arguments = "--flag value",
            ),
        )

        assertEquals("python3", command.exePath)
        assertEquals(listOf(script.toString(), "--flag", "value"), command.parametersList.parameters)
        assertEquals(workDirectory.toFile(), command.workDirectory)
    }

    @Test
    fun `classifier maps commit-like input to native prepare commit`() {
        assertEquals(EnvironmentActionType.SCRIPT, EnvironmentActionClassifier.classify("tools/check.py"))
        assertEquals(EnvironmentActionType.CODEX, EnvironmentActionClassifier.classify("skill: review the current change"))
        assertEquals(
            EnvironmentActionType.PREPARE_COMMIT,
            EnvironmentActionClassifier.classify("git commit release environment actions"),
        )
        assertEquals(EnvironmentActionType.SHELL, EnvironmentActionClassifier.classify("./gradlew test"))
    }

    @Test
    fun `prepare commit cannot resolve to a process command`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            EnvironmentActionCommandResolver.resolve(
                EnvironmentDefinition(),
                EnvironmentActionDefinition(
                    slot = 1,
                    type = EnvironmentActionType.PREPARE_COMMIT,
                    command = "feat: native commit",
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("IDE Commit workflow"))
    }

    @Test
    fun `codex one shot job uses ephemeral json mode and sends prompt on stdin`() {
        val spec = EnvironmentActionCommandResolver.resolveSpec(
            EnvironmentDefinition(),
            EnvironmentActionDefinition(slot = 1, type = EnvironmentActionType.CODEX, command = "run configured skill"),
        )

        assertEquals("codex", spec.commandLine.exePath)
        assertEquals(listOf("exec", "--json", "--ephemeral"), spec.commandLine.parametersList.parameters)
        assertEquals("run configured skill\n", spec.stdin)
        assertFalse(spec.commandLine.commandLineString.contains("--skip-git-repo-check"))
    }

    @Test
    fun `codex one shot job only skips git check after explicit opt in`() {
        val spec = EnvironmentActionCommandResolver.resolveSpec(
            EnvironmentDefinition(workingDirectory = tempDir.toString()),
            EnvironmentActionDefinition(
                slot = 1,
                type = EnvironmentActionType.CODEX,
                command = "inspect",
                allowNonGitDirectory = true,
            ),
            codexExecutable = "/opt/codex",
        )

        assertEquals("/opt/codex", spec.commandLine.exePath)
        assertEquals(
            listOf("exec", "--json", "--ephemeral", "--cd", tempDir.toString(), "--skip-git-repo-check"),
            spec.commandLine.parametersList.parameters,
        )
    }

    @Test
    fun `Windows command scripts are launched through cmd without changing stable Codex arguments`() {
        assertEquals(
            listOf("cmd.exe", "/d", "/s", "/c", "C:\\Tools\\codex.cmd", "exec", "--json"),
            codexPlatformCommand("C:\\Tools\\codex.cmd", listOf("exec", "--json"), windows = true),
        )
        assertEquals(
            listOf("C:\\Tools\\codex.exe", "app-server", "--stdio"),
            codexPlatformCommand("C:\\Tools\\codex.exe", listOf("app-server", "--stdio"), windows = true),
        )
    }

    @Test
    fun `store rejects corrupted and oversized definitions`() {
        val corrupted = tempDir.resolve("corrupted.json")
        Files.writeString(corrupted, "{not-json")
        assertThrows(Exception::class.java) { EnvironmentActionStore(corrupted).read() }

        val oversized = tempDir.resolve("oversized.json")
        Files.writeString(oversized, "x".repeat(2_000_001))
        assertThrows(IllegalArgumentException::class.java) { EnvironmentActionStore(oversized).read() }
    }

    private fun normalizedState(): EnvironmentActionSettingsState = EnvironmentActionSettingsState(
        environments = mutableListOf(EnvironmentDefinition(name = "Default")),
    ).apply { normalize() }

    private fun startRaceWorker(
        path: Path,
        expectedRevision: String,
        name: String,
        ready: Path,
        go: Path,
    ): Process = ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp",
        System.getProperty("java.class.path"),
        EnvironmentActionStoreRaceWorker::class.java.name,
        path.toString(),
        expectedRevision,
        name,
        ready.toString(),
        go.toString(),
    ).redirectErrorStream(true).start()
}

internal object EnvironmentActionStoreRaceWorker {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val path = Path.of(arguments[0])
        val expectedRevision = arguments[1]
        val name = arguments[2]
        val ready = Path.of(arguments[3])
        val go = Path.of(arguments[4])
        val base = EnvironmentActionStore(path).read() ?: error("Missing Environment Action base state")
        val candidate = base.copyForSnapshot().apply {
            revision = expectedRevision
            environments.single().name = name
        }
        Files.writeString(ready, "ready", StandardCharsets.UTF_8)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!Files.exists(go) && System.nanoTime() < deadline) Thread.sleep(5)
        check(Files.exists(go)) { "Race start was not released" }
        when (EnvironmentActionStore(path).compareAndWrite(expectedRevision, candidate)) {
            is EnvironmentActionStoreWriteResult.Success -> println("RESULT:SUCCESS")
            is EnvironmentActionStoreWriteResult.Conflict -> println("RESULT:CONFLICT")
        }
    }
}
