package emohce.data.commitmessage

import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.openapi.util.JDOMUtil
import emohce.domain.commitmessage.CommitActionKind
import emohce.domain.commitmessage.CommitMessageDefaults
import emohce.domain.commitmessage.LlmProfile
import emohce.domain.commitmessage.LlmProfileRef
import emohce.domain.commitmessage.LlmProfileScope
import emohce.domain.commitmessage.ProjectInstructionMode
import emohce.domain.commitmessage.CommitStyleDefinition
import emohce.domain.commitmessage.CommitTemplateDefinition
import emohce.domain.commitmessage.CommitTemplateRenderer
import emohce.domain.commitmessage.CommitTypeDefinition
import emohce.domain.commitmessage.TemplateValidation
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

class CommitMessageStateAndCoordinatorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `new state restores upstream LLM template type and writeback defaults`() {
        val state = CommitMessageSettingsState()

        assertEquals(3, state.schemaVersion)
        assertEquals(0.5, state.llmTemperature)
        assertEquals("English", state.llmResponseLanguage)
        assertTrue(state.llmStreaming)
        assertFalse(state.smartEcho)
        assertFalse(state.previewAiResultBeforeApply)
        assertEquals(CommitMessageDefaults.DEFAULT_PROFILE_ID, state.activeProfileId)
        assertEquals("Default", state.profiles.single().name)
        assertEquals("https://api.openai.com/v1", state.profiles.single().baseUrl)
        assertEquals("", state.profiles.single().model)
        assertEquals("Default", state.templates.single().name)
        assertEquals("A new feature", state.types.first().description)
        assertEquals(
            listOf(CommitMessageDefaults.STANDARD_STYLE_ID, CommitMessageDefaults.CONCISE_STYLE_ID),
            state.styles.map { it.id },
        )

        val service = CommitMessageSettingsService()
        assertEquals(CommitMessageDefaults.DEFAULT_PROFILE_ID, service.activeProfileSnapshot()?.id)
    }

    @Test
    fun `state migration restores builtin template and a valid active profile`() {
        val profile = LlmProfile(name = "Configured")
        val service = CommitMessageSettingsService()
        service.loadState(
            CommitMessageSettingsState(
                schemaVersion = 0,
                templates = mutableListOf(),
                types = mutableListOf(),
                activeProfileId = "missing",
                profiles = mutableListOf(profile),
            ),
        )

        assertEquals(3, service.state.schemaVersion)
        assertNotNull(service.template(CommitMessageDefaults.DEFAULT_TEMPLATE_ID))
        assertEquals(profile.id, service.state.activeProfileId)
        assertTrue(service.state.types.isNotEmpty())
        assertEquals(0.5, service.state.llmTemperature)
        assertEquals(CommitMessageDefaults.STANDARD_STYLE_ID, service.state.defaultStyleId)
    }

    @Test
    fun `project template resolution falls back to global default`() {
        val settings = CommitMessageSettingsService()
        val projectState = CommitProjectStateService()
        projectState.loadState(CommitProjectState(templateId = "missing"))

        assertEquals(CommitMessageDefaults.DEFAULT_TEMPLATE_ID, projectState.resolveTemplate(settings).id)
    }

    @Test
    fun `invalid persisted project template falls through style global and builtin candidates in order`() {
        val projectTemplate = CommitTemplateDefinition("project", "Project", "project-content")
        val globalTemplate = CommitTemplateDefinition("global", "Global", "global-content")
        val style = CommitStyleDefinition(
            id = "team-style",
            name = "Team style",
            prompt = "Keep it focused",
            templateContent = "style-content",
        )
        val settings = CommitMessageSettingsService().apply {
            replaceState(state.deepCopy().apply {
                templates.addAll(listOf(projectTemplate, globalTemplate))
                defaultTemplateId = globalTemplate.id
                styles.add(style)
                defaultStyleId = style.id
            })
        }
        val projectState = CommitProjectStateService().apply {
            loadState(CommitProjectState(templateId = projectTemplate.id, styleId = style.id))
        }
        var acceptedContent = style.templateContent
        val renderer = object : CommitTemplateRenderer {
            override fun render(template: CommitTemplateDefinition, draft: emohce.domain.commitmessage.CommitDraft): String =
                template.content

            override fun validate(template: CommitTemplateDefinition): TemplateValidation =
                TemplateValidation(template.content == acceptedContent)
        }

        assertEquals(
            listOf(projectTemplate.id, "${style.id}.template", globalTemplate.id, CommitMessageDefaults.DEFAULT_TEMPLATE_ID),
            projectState.templateCandidates(settings).map { it.id },
        )
        assertEquals("${style.id}.template", projectState.resolveValidTemplate(settings, renderer).id)
        acceptedContent = globalTemplate.content
        assertEquals(globalTemplate.id, projectState.resolveValidTemplate(settings, renderer).id)
        acceptedContent = CommitMessageDefaults.defaultTemplateContent
        assertEquals(CommitMessageDefaults.DEFAULT_TEMPLATE_ID, projectState.resolveValidTemplate(settings, renderer).id)
    }

    @Test
    fun `schema one migration updates untouched defaults without overwriting custom values`() {
        val service = CommitMessageSettingsService()
        service.loadState(
            CommitMessageSettingsState(
                schemaVersion = 1,
                templates = mutableListOf(
                    CommitTemplateDefinition(
                        CommitMessageDefaults.DEFAULT_TEMPLATE_ID,
                        "Conventional Commit",
                        CommitMessageDefaults.legacyDefaultTemplateContent,
                        true,
                    ),
                    CommitTemplateDefinition("custom", "Custom", "${'$'}{subject} custom"),
                ),
                types = mutableListOf(
                    CommitTypeDefinition("feat", "Feature"),
                    CommitTypeDefinition("fix", "Team-specific fix"),
                ),
                profiles = mutableListOf(),
                activeProfileId = "",
            ),
        )

        assertEquals(3, service.state.schemaVersion)
        assertEquals(CommitMessageDefaults.defaultTemplateContent, service.template(CommitMessageDefaults.DEFAULT_TEMPLATE_ID)?.content)
        assertEquals("${'$'}{subject} custom", service.template("custom")?.content)
        assertEquals("A new feature", service.state.types.first { it.id == "feat" }.description)
        assertEquals("Team-specific fix", service.state.types.first { it.id == "fix" }.description)
        assertEquals(CommitMessageDefaults.DEFAULT_PROFILE_ID, service.state.activeProfileId)
    }

    @Test
    fun `project style resolves prompt and template while explicit project template wins`() {
        val settings = CommitMessageSettingsService()
        val style = CommitStyleDefinition(
            id = "custom-style",
            name = "Team concise",
            prompt = "Keep it short",
            templateContent = "${'$'}{type}: ${'$'}{subject}",
        )
        val explicit = CommitTemplateDefinition("project-template", "Project", "${'$'}{subject}")
        settings.replaceState(settings.state.deepCopy().apply {
            styles.add(style)
            templates.add(explicit)
            defaultStyleId = style.id
        })
        val projectState = CommitProjectStateService()

        assertEquals(style.id, projectState.resolveStyle(settings).id)
        assertEquals("${style.id}.template", projectState.resolveTemplate(settings).id)

        projectState.setTemplateId(explicit.id)
        assertEquals(explicit.id, projectState.resolveTemplate(settings).id)

        projectState.setStyleId("missing")
        assertEquals(style.id, projectState.resolveStyle(settings).id)
    }

    @Test
    fun `coordinator isolates operation cancels and rejects stale handles`() {
        val document = mockk<Document>()
        val indicator = mockk<ProgressIndicator>(relaxed = true)
        val coordinator = CommitMessageCoordinatorService()
        val handle = coordinator.start(document, CommitActionKind.GENERATE)!!

        assertNull(coordinator.start(document, CommitActionKind.FORMAT))
        coordinator.attachIndicator(handle, indicator)
        assertFalse(coordinator.cancel(document, CommitActionKind.FORMAT))
        assertTrue(coordinator.cancel(document, CommitActionKind.GENERATE))
        assertFalse(coordinator.isCurrent(handle))
        verify { indicator.cancel() }

        coordinator.finish(handle)
        assertNull(coordinator.status(document))
        assertNotNull(coordinator.start(document, CommitActionKind.FORMAT))
        coordinator.dispose()
    }

    @Test
    fun `settings xml round trip contains profile metadata but no secret`() {
        val state = CommitMessageSettingsState(
            activeProfileId = "profile-1",
            profiles = mutableListOf(
                LlmProfile(id = "profile-1", name = "Local", baseUrl = "https://example.test/v1"),
            ),
        )

        val element = XmlSerializer.serialize(state)
        val serialized = JDOMUtil.writeElement(element)
        val restored = XmlSerializer.deserialize(element, CommitMessageSettingsState::class.java)

        assertTrue(serialized.contains("profile-1"))
        assertFalse(serialized.contains("apiKey", ignoreCase = true))
        assertEquals("Local", restored.profiles.single().name)
    }

    @Test
    fun `portable settings are shared across application services and reject stale apply`() {
        val store = CommitMessagePortableStore(tempDir.resolve("global-settings.json"))
        val first = CommitMessageSettingsService(store)
        val second = CommitMessageSettingsService(store)
        val staleSecond = second.state.deepCopy()

        first.replaceState(first.state.deepCopy().apply {
            persistentExtraInstructions = "Use issue identifiers when present."
        })

        assertEquals("Use issue identifiers when present.", second.snapshot().persistentExtraInstructions)
        assertFalse(second.hasPortableConflict())
        assertThrows(CommitMessageSettingsConflictException::class.java) {
            second.replaceState(staleSecond.apply { persistentExtraInstructions = "Stale edit" })
        }
        assertEquals("Use issue identifiers when present.", second.snapshot().persistentExtraInstructions)
    }

    @Test
    fun `divergent synced portable state is explicit and resolvable`() {
        val store = CommitMessagePortableStore(tempDir.resolve("global-settings.json"))
        val common = CommitMessageSettingsService(store)
        common.replaceState(common.state.deepCopy().apply { persistentExtraInstructions = "Common" })
        val incoming = CommitMessageSettingsState(persistentExtraInstructions = "Incoming")

        val receiving = CommitMessageSettingsService(store)
        receiving.loadState(incoming)

        assertTrue(receiving.hasPortableConflict())
        assertEquals("Common", receiving.snapshot(refreshPortable = false).persistentExtraInstructions)
        assertEquals("Incoming", receiving.state.persistentExtraInstructions)
        receiving.resolvePortableConflict(useIncoming = true)
        assertFalse(receiving.hasPortableConflict())
        assertEquals("Incoming", receiving.snapshot(refreshPortable = false).persistentExtraInstructions)
        assertEquals("Incoming", CommitMessageSettingsService(store).state.persistentExtraInstructions)
    }

    @Test
    fun `versioned reset to defaults remains a valid incoming descendant`() {
        val store = CommitMessagePortableStore(tempDir.resolve("global-settings.json"))
        val common = CommitMessageSettingsService(store)
        common.replaceState(common.snapshot().apply { persistentExtraInstructions = "Configured" })
        val commonRevision = common.snapshot(refreshPortable = false).portableRevision
        val reset = CommitMessageSettingsState().apply {
            portableRevision = UUID.randomUUID().toString()
            portableAncestors = mutableListOf(commonRevision)
        }

        val receiving = CommitMessageSettingsService(store)
        receiving.loadState(reset)

        assertFalse(receiving.hasPortableConflict())
        assertEquals("", receiving.snapshot(refreshPortable = false).persistentExtraInstructions)
        assertEquals("", CommitMessageSettingsService(store).snapshot().persistentExtraInstructions)
    }

    @Test
    fun `versioned divergent defaults remain explicit conflict`() {
        val store = CommitMessagePortableStore(tempDir.resolve("global-settings.json"))
        val common = CommitMessageSettingsService(store)
        common.replaceState(common.snapshot().apply { persistentExtraInstructions = "Configured" })
        val resetBranch = CommitMessageSettingsState().apply {
            portableRevision = UUID.randomUUID().toString()
        }

        val receiving = CommitMessageSettingsService(store)
        receiving.loadState(resetBranch)

        assertTrue(receiving.hasPortableConflict())
        assertEquals("Configured", receiving.snapshot(refreshPortable = false).persistentExtraInstructions)
        assertEquals("", receiving.state.persistentExtraInstructions)
    }

    @Test
    fun `unresolved incoming branch survives roaming serialization and restart`() {
        val store = CommitMessagePortableStore(tempDir.resolve("global-settings.json"))
        val common = CommitMessageSettingsService(store)
        common.replaceState(common.snapshot().apply { persistentExtraInstructions = "Common" })
        val receiving = CommitMessageSettingsService(store)
        receiving.loadState(CommitMessageSettingsState(persistentExtraInstructions = "Incoming"))
        val serializedIncoming = receiving.state.deepCopy()

        val restarted = CommitMessageSettingsService(store)
        restarted.loadState(serializedIncoming)

        assertTrue(restarted.hasPortableConflict())
        assertEquals("Common", restarted.snapshot(refreshPortable = false).persistentExtraInstructions)
        assertEquals("Incoming", restarted.state.persistentExtraInstructions)
    }

    @Test
    fun `persistent state read does not create or refresh the common carrier`() {
        val store = CommitMessagePortableStore(tempDir.resolve("global-settings.json"))
        val service = CommitMessageSettingsService(store)

        service.state

        assertEquals(CommitMessagePortableReadResult.Missing, store.read())
    }

    @Test
    fun `portable payload excludes credentials account material and source consent`() {
        val store = CommitMessagePortableStore(tempDir.resolve("global-settings.json"))
        val service = CommitMessageSettingsService(store)
        service.replaceState(service.snapshot().apply {
            profiles = mutableListOf(
                LlmProfile(
                    id = "portable-profile",
                    name = "Portable",
                    sourceConsentFingerprint = "consent-secret-marker",
                ),
            )
            activeProfileId = "portable-profile"
        })

        val payload = (store.read() as CommitMessagePortableReadResult.Found).envelope.payload

        listOf("apiKey", "oauth", "accountEmail", "authorizationCode", "executablePath", "consent-secret-marker")
            .forEach { forbidden -> assertFalse(payload.contains(forbidden, ignoreCase = true), forbidden) }
    }

    @Test
    fun `unsupported or malformed portable payload fails closed without breaking service startup`() {
        val futureStore = CommitMessagePortableStore(tempDir.resolve("future-settings.json"))
        futureStore.write(
            CommitMessagePortableEnvelope.create(
                payload = """{"schemaVersion":99,"futureSecret":"must-not-load"}""",
            ),
        )

        val future = CommitMessageSettingsService(futureStore)

        assertNotNull(future.portableFailureMessage())
        assertEquals(CommitMessageDefaults.DEFAULT_PROFILE_ID, future.state.activeProfileId)
        assertEquals(CommitMessageDefaults.DEFAULT_PROFILE_ID, future.snapshot().activeProfileId)
        assertThrows(CommitMessageSettingsStoreException::class.java) {
            future.replaceState(future.snapshot(refreshPortable = false))
        }

        val malformedStore = CommitMessagePortableStore(tempDir.resolve("malformed-payload.json"))
        malformedStore.write(CommitMessagePortableEnvelope.create("not-json"))
        val malformed = CommitMessageSettingsService(malformedStore)

        assertNotNull(malformed.portableFailureMessage())
        assertEquals(CommitMessageDefaults.DEFAULT_PROFILE_ID, malformed.state.activeProfileId)
        assertEquals(CommitMessageDefaults.DEFAULT_PROFILE_ID, malformed.snapshot().activeProfileId)
    }

    @Test
    fun `portable state recovers when valid content replaces a quarantined revision`() {
        val store = CommitMessagePortableStore(tempDir.resolve("recoverable-settings.json"))
        val writer = CommitMessageSettingsService(store)
        writer.replaceState(writer.snapshot().apply { persistentExtraInstructions = "Recovered" })
        val valid = (store.read() as CommitMessagePortableReadResult.Found).envelope
        store.write(valid.copy(payload = "not-json"))
        val recovering = CommitMessageSettingsService(store)
        assertNotNull(recovering.portableFailureMessage())

        store.write(valid)
        val recovered = recovering.snapshot()

        assertEquals("Recovered", recovered.persistentExtraInstructions)
        assertNull(recovering.portableFailureMessage())
        recovering.replaceState(recovered.apply { persistentExtraInstructions = "Writable again" })
        assertEquals("Writable again", recovering.snapshot().persistentExtraInstructions)

        store.write(valid.copy(payload = "not-json"))
        val loading = CommitMessageSettingsService(store)
        store.write(valid)
        loading.loadState(CommitMessageSettingsState())
        assertEquals("Recovered", loading.snapshot(refreshPortable = false).persistentExtraInstructions)
        assertNull(loading.portableFailureMessage())
    }

    @Test
    fun `invalid or missing Codex auth generation is repaired durably`() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val payloads = listOf(
            json.encodeToString(CodexMachineSettings("  /custom/codex  ", "invalid-generation")),
            """{"executablePath":"  /custom/codex  ","futurePolicy":{"enabled":true}}""",
        )

        payloads.forEachIndexed { index, payload ->
            val root = tempDir.resolve("codex-machine-$index")
            val store = CommitMessagePortableStore(root.resolve("machine-settings.json"), trustedRoot = root)
            val original = CommitMessagePortableEnvelope.create(payload)
            store.write(original)

            val service = CodexAppServerService(root, store, null)
            val repairedGeneration = service.authGeneration()
            val repaired = (store.read() as CommitMessagePortableReadResult.Found).envelope
            val persisted = json.decodeFromString<CodexMachineSettings>(repaired.payload)

            assertTrue(runCatching { UUID.fromString(repairedGeneration) }.isSuccess)
            assertFalse(original.revision == repaired.revision)
            assertEquals(repairedGeneration, persisted.authGeneration)
            assertEquals("  /custom/codex  ", persisted.executablePath)
            if (index == 1) {
                assertTrue(json.parseToJsonElement(repaired.payload).jsonObject.containsKey("futurePolicy"))
            }
            assertEquals("/custom/codex", service.executablePath())
            service.dispose()

            val restarted = CodexAppServerService(root, store, null)
            assertEquals(repairedGeneration, restarted.authGeneration())
            assertEquals(repaired.revision, (store.read() as CommitMessagePortableReadResult.Found).envelope.revision)
            restarted.dispose()
        }
    }

    @Test
    fun `future Codex machine envelope schema is not downgraded during repair`() {
        val root = tempDir.resolve("codex-future-machine")
        val store = CommitMessagePortableStore(root.resolve("machine-settings.json"), trustedRoot = root)
        val future = CommitMessagePortableEnvelope.create(
            payload = """{"executablePath":"","authGeneration":""}""",
            schemaVersion = CommitMessagePortableEnvelope.DEFAULT_SCHEMA_VERSION + 1,
        )
        store.write(future)

        val error = assertThrows(CodexAppServerException::class.java) {
            CodexAppServerService(root, store, null)
        }

        assertEquals(CodexAppServerErrorKind.PROCESS, error.kind)
        assertEquals(future, (store.read() as CommitMessagePortableReadResult.Found).envelope)
    }

    @Test
    fun `Codex executable is auto-detected from candidate list`() {
        val root = tempDir.resolve("codex-autodetect")
        val fakeCodex = root.resolve("fake-codex")
        Files.createDirectories(fakeCodex.parent)
        Files.writeString(fakeCodex, "#!/bin/sh\necho \"codex-cli 0.144.5\"\n")
        Files.getFileAttributeView(fakeCodex, java.nio.file.attribute.PosixFileAttributeView::class.java)
            ?.setPermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))

        val service = CodexAppServerService(
            root,
            null,
            null,
            executableCandidates = { listOf(fakeCodex.toString(), "codex") },
        )

        assertEquals(fakeCodex.toString(), service.resolvedExecutablePath())
        val status = service.installationStatus()
        assertEquals(fakeCodex.toString(), status.executable)
        assertTrue(status.available)
        assertEquals("0.144.5", status.version)
        service.dispose()
    }

    @Test
    fun `Codex executable falls back to PATH when no candidate exists`() {
        val root = tempDir.resolve("codex-fallback")
        val service = CodexAppServerService(
            root,
            null,
            null,
            executableCandidates = { listOf(root.resolve("missing-codex").toString()) },
        )

        assertEquals("codex", service.resolvedExecutablePath())
        service.dispose()
    }

    @Test
    fun `configured Codex executable overrides auto-detection`() {
        val root = tempDir.resolve("codex-override")
        val autoDetected = root.resolve("auto-codex")
        val configured = root.resolve("configured-codex")
        Files.createDirectories(autoDetected.parent)
        Files.writeString(autoDetected, "#!/bin/sh\necho \"codex-cli 0.144.5\"\n")
        Files.getFileAttributeView(autoDetected, java.nio.file.attribute.PosixFileAttributeView::class.java)
            ?.setPermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))

        val service = CodexAppServerService(
            root,
            null,
            null,
            executableCandidates = { listOf(autoDetected.toString()) },
        )
        service.setExecutablePath(configured.toString(), "")

        assertEquals(configured.toString(), service.resolvedExecutablePath())
        service.dispose()
    }

    @Test
    fun `Codex auth generation repair adopts a concurrent CAS winner`() {
        val invalid = CommitMessagePortableEnvelope.create(
            """{"executablePath":"","authGeneration":"invalid"}""",
        )
        val winningGeneration = UUID.randomUUID().toString()
        val winner = CommitMessagePortableEnvelope.successor(
            """{"executablePath":"","authGeneration":"$winningGeneration"}""",
            invalid,
        )
        val store = mockk<CommitMessagePortableStore>()
        every { store.read() } returnsMany listOf(
            CommitMessagePortableReadResult.Found(invalid),
            CommitMessagePortableReadResult.Found(winner),
        )
        every { store.compareAndWrite(invalid.revision, any()) } returns
            CommitMessagePortableConditionalWriteResult.RevisionMismatch(winner)

        val service = CodexAppServerService(tempDir, store, null)

        assertEquals(winningGeneration, service.authGeneration())
        verify(exactly = 1) { store.compareAndWrite(invalid.revision, any()) }
        service.dispose()
    }

    @Test
    fun `semantic no-op keeps the portable revision and unsafe endpoints are not serialized`() {
        val store = CommitMessagePortableStore(tempDir.resolve("global-settings.json"))
        val service = CommitMessageSettingsService(store)
        val initial = service.snapshot()
        service.replaceState(initial.deepCopy())

        assertEquals(initial.portableRevision, service.snapshot(refreshPortable = false).portableRevision)

        service.replaceState(service.snapshot().apply {
            profiles = mutableListOf(
                LlmProfile(
                    id = "unsafe-endpoint",
                    name = "Unsafe endpoint",
                    baseUrl = "https://user:token@example.test/v1?api_key=secret",
                ),
            )
            activeProfileId = "unsafe-endpoint"
        })

        val state = service.snapshot(refreshPortable = false)
        val payload = (store.read() as CommitMessagePortableReadResult.Found).envelope.payload
        assertEquals("", state.profiles.single().baseUrl)
        assertFalse(payload.contains("token"))
        assertFalse(payload.contains("api_key"))
    }

    @Test
    fun `project shared defaults instructions and private profile resolve before global defaults`() {
        val settings = CommitMessageSettingsService(null)
        settings.replaceState(settings.state.deepCopy().apply {
            persistentExtraInstructions = "Global instruction"
            profiles += LlmProfile(id = "global-profile", name = "Global profile", model = "global-model")
            activeProfileId = "global-profile"
        })
        val sharedTemplate = CommitTemplateDefinition("project.template", "Project", "${'$'}{subject}")
        val sharedStyle = CommitStyleDefinition("project.style", "Project style", prompt = "Project tone")
        val shared = CommitProjectSharedSettingsService().apply {
            loadState(
                CommitProjectSharedSettingsState(
                    instructionMode = ProjectInstructionMode.APPEND,
                    extraInstructions = "Project instruction",
                    templates = mutableListOf(sharedTemplate),
                    styles = mutableListOf(sharedStyle),
                    defaultTemplateId = sharedTemplate.id,
                    defaultStyleId = sharedStyle.id,
                ),
            )
        }
        val projectProfile = LlmProfile(id = "project-profile", name = "Project profile", model = "project-model")
        val projectState = CommitProjectStateService().apply {
            loadState(
                CommitProjectState(
                    activeProfileScope = LlmProfileScope.PROJECT,
                    activeProfileId = projectProfile.id,
                    profiles = mutableListOf(projectProfile),
                ),
            )
        }

        assertEquals("Global instruction\n\nProject instruction", shared.effectiveExtraInstructions(settings.state.persistentExtraInstructions))
        assertEquals(sharedStyle.id, projectState.resolveStyle(settings, shared).id)
        assertEquals(sharedTemplate.id, projectState.templateCandidates(settings, shared).first().id)
        assertEquals("project-model", projectState.resolveProfileSnapshot(settings)?.model)

        projectState.setActiveProfile(LlmProfileRef(LlmProfileScope.GLOBAL, "global-profile"))
        assertEquals("global-model", projectState.resolveProfileSnapshot(settings)?.model)
    }

    @Test
    fun `project instructions preserve all modes and the full bounded append`() {
        val global = "g".repeat(CommitProjectSharedSettingsState.MAX_EXTRA_INSTRUCTIONS)
        val project = "p".repeat(CommitProjectSharedSettingsState.MAX_EXTRA_INSTRUCTIONS)
        val shared = CommitProjectSharedSettingsService()

        shared.loadState(CommitProjectSharedSettingsState(instructionMode = ProjectInstructionMode.INHERIT, extraInstructions = project))
        assertEquals(global, shared.effectiveExtraInstructions(global))

        shared.loadState(CommitProjectSharedSettingsState(instructionMode = ProjectInstructionMode.APPEND, extraInstructions = project))
        val appended = shared.effectiveExtraInstructions(global)
        assertEquals(CommitProjectSharedSettingsState.MAX_EFFECTIVE_INSTRUCTIONS, appended.length)
        assertTrue(appended.endsWith(project))
        val prompt = structuredCommitSystemPrompt(
            LlmProfile(responseLanguage = "English"),
            listOf("feat"),
            persistentInstructions = appended,
        )
        assertTrue(prompt.endsWith(appended))

        shared.loadState(CommitProjectSharedSettingsState(instructionMode = ProjectInstructionMode.REPLACE, extraInstructions = ""))
        assertEquals("", shared.effectiveExtraInstructions(global))
    }

    @Test
    fun `future project schemas remain marked and cannot be overwritten through current services`() {
        val privateState = CommitProjectStateService()
        privateState.loadState(
            CommitProjectState(
                schemaVersion = 99,
                activeProfileId = "future-profile",
                profiles = mutableListOf(LlmProfile(id = "future-profile", name = "Future")),
            ),
        )
        val sharedState = CommitProjectSharedSettingsService()
        sharedState.loadState(
            CommitProjectSharedSettingsState(
                schemaVersion = 99,
                extraInstructions = "future instructions",
            ),
        )

        assertEquals(99, privateState.state.schemaVersion)
        assertEquals(99, sharedState.state.schemaVersion)
        assertThrows(IllegalStateException::class.java) { privateState.setTemplateId("replacement") }
        assertThrows(IllegalStateException::class.java) { privateState.activeProfileRef() }
        assertThrows(IllegalStateException::class.java) {
            sharedState.replaceState(CommitProjectSharedSettingsState())
        }
        assertThrows(IllegalStateException::class.java) {
            sharedState.effectiveExtraInstructions("global")
        }
    }

    @Test
    fun `project private profiles discard credential-bearing endpoints at the state boundary`() {
        val projectState = CommitProjectStateService()
        projectState.loadState(
            CommitProjectState(
                profiles = mutableListOf(
                    LlmProfile(
                        id = "unsafe-project-profile",
                        name = "Unsafe",
                        baseUrl = "https://user:token@example.test/v1?api_key=secret",
                    ),
                ),
            ),
        )

        assertEquals("", projectState.state.profiles.single().baseUrl)
    }

    @Test
    fun `private project state migrates a stable credential namespace`() {
        val service = CommitProjectStateService()
        service.loadState(
            CommitProjectState(
                schemaVersion = 1,
                credentialNamespace = "invalid",
                profiles = mutableListOf(LlmProfile(id = "same-id", name = "Project")),
            ),
        )

        assertEquals(2, service.state.schemaVersion)
        assertTrue(runCatching { UUID.fromString(service.state.credentialNamespace) }.isSuccess)
        assertTrue(service.projectCredentialId("same-id").startsWith("project:"))
        assertFalse(service.projectCredentialId("same-id") == "same-id")
    }
}
