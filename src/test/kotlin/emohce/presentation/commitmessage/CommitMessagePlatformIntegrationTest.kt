package emohce.presentation.commitmessage

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.credentialStore.Credentials
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.LocalChangeList
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.EditorTextField
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import com.intellij.vcs.commit.CommitMessageUi
import com.intellij.vcs.commit.CommitWorkflowUi
import emohce.data.commitmessage.CommitMessageCoordinatorService
import emohce.data.commitmessage.CommitMessageCredentialAccess
import emohce.data.commitmessage.CommitMessageCredentialSnapshot
import emohce.data.commitmessage.CommitMessageAiService
import emohce.data.commitmessage.CommitMessageSecretStore
import emohce.data.commitmessage.CommitMessageSettingsService
import emohce.data.commitmessage.CommitMessageSettingsState
import emohce.data.commitmessage.CommitProjectState
import emohce.data.commitmessage.CommitProjectStateService
import emohce.data.commitmessage.CommitProjectSharedSettingsService
import emohce.data.commitmessage.CommitProjectSharedSettingsState
import emohce.data.commitmessage.CodexAccountSettingsGateway
import emohce.data.commitmessage.CodexAppServerAccount
import emohce.data.commitmessage.CodexAppServerBrowserLogin
import emohce.data.commitmessage.CodexAppServerDeviceLogin
import emohce.data.commitmessage.CodexAppServerLoginCompleted
import emohce.data.commitmessage.CodexInstallationStatus
import emohce.data.commitmessage.GitContextRequest
import emohce.data.commitmessage.IntelliJGitContextRepository
import emohce.data.commitmessage.LlmProviderClient
import emohce.domain.commitmessage.CommitActionKind
import emohce.domain.commitmessage.CommitPromptOptimizationProposal
import emohce.domain.commitmessage.GitContextPolicy
import emohce.domain.commitmessage.CommitMessageDefaults
import emohce.domain.commitmessage.CommitStyleDefinition
import emohce.domain.commitmessage.CommitTemplateDefinition
import emohce.domain.commitmessage.CommitTemplateSnapshot
import emohce.domain.commitmessage.CommitTypeDefinition
import emohce.domain.commitmessage.LlmProfile
import emohce.domain.commitmessage.LlmProfileScope
import emohce.domain.commitmessage.LlmProviderType
import emohce.domain.commitmessage.LlmCompletion
import emohce.domain.commitmessage.LlmCompletionRequest
import emohce.domain.commitmessage.LlmPromptEnvelope
import emohce.domain.commitmessage.AiPreview
import emohce.domain.commitmessage.PreparedCommitRefinement
import emohce.domain.commitmessage.PreparedPromptOptimization
import emohce.domain.commitmessage.ProviderRequestBudget
import emohce.domain.commitmessage.ProviderRequestBudgetExceededException
import emohce.domain.commitmessage.ProjectInstructionMode
import emohce.presentation.commitmessage.action.BaseCommitMessageAction
import emohce.presentation.commitmessage.action.CreateCommitMessageAction
import emohce.presentation.commitmessage.action.GenerateCommitMessageAction
import emohce.presentation.commitmessage.action.FormatCommitMessageAction
import emohce.presentation.commitmessage.action.FormatInstructionsProvider
import emohce.presentation.commitmessage.action.SelectCommitStyleAction
import emohce.presentation.commitmessage.dialog.AdditionalRequirementsDialog
import emohce.presentation.commitmessage.dialog.AiCommitMessagePreviewDialog
import emohce.presentation.commitmessage.dialog.AiPromptEnvelopeConfirmationDialog
import emohce.presentation.commitmessage.dialog.CommitMessagePreviewRefiner
import emohce.presentation.commitmessage.dialog.CommitMessageRefinementAttempt
import emohce.presentation.commitmessage.dialog.CommitRefinementHistoryDialog
import emohce.presentation.commitmessage.dialog.CommitRefinementDialogResult
import emohce.presentation.commitmessage.dialog.CommitRefinementInteraction
import emohce.presentation.commitmessage.dialog.CommitRefinementProgressRunner
import emohce.presentation.commitmessage.dialog.CommitRefinementPromptDialog
import emohce.presentation.commitmessage.dialog.DefaultCommitMessagePreviewRefiner
import emohce.presentation.commitmessage.settings.CodexSettingsPanel
import emohce.presentation.commitmessage.settings.CommitMessageSettingsConfigurable
import emohce.presentation.commitmessage.settings.CommitMessageKeymapSupport
import emohce.presentation.commitmessage.settings.CommitProjectDefaultsConfigurable
import emohce.presentation.commitmessage.settings.CommitProjectProvidersConfigurable
import emohce.presentation.commitmessage.settings.CommitProjectSharedConfigurable
import emohce.presentation.commitmessage.settings.CommitProvidersConfigurable
import emohce.presentation.commitmessage.settings.CommitTemplatesConfigurable
import emohce.presentation.commitmessage.settings.FilterableModelComboBox
import emohce.presentation.commitmessage.settings.ProviderSettingsRequestRunner
import emohce.presentation.commitmessage.settings.ProviderProfileDialog
import emohce.presentation.commitmessage.settings.ProviderProfileEditInput
import emohce.presentation.commitmessage.settings.ProviderProfileEditResult
import emohce.presentation.commitmessage.settings.ProviderProfileEditor
import emohce.presentation.commitmessage.settings.ProviderModelFetcher
import emohce.presentation.commitmessage.settings.ProviderModelRequest
import emohce.presentation.environmentaction.EnvironmentCommitMessageProvider
import emohce.presentation.environmentaction.NativeCommitWorkflowLauncher
import emohce.presentation.environmentaction.PendingCommitMessageService
import emohce.data.environmentaction.InteractiveCodexSessionFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.swing.KeyStroke
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JSpinner
import javax.swing.JSplitPane
import javax.swing.JTextField
import javax.swing.SwingUtilities
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CommitMessagePlatformIntegrationTest {
    private lateinit var fixture: CodeInsightTestFixture
    private lateinit var originalSettings: CommitMessageSettingsState
    private lateinit var originalProjectState: CommitProjectState
    private lateinit var originalProjectSharedState: CommitProjectSharedSettingsState

    @BeforeEach
    fun setUp() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val builder = factory.createLightFixtureBuilder(
            LightProjectDescriptor.EMPTY_PROJECT_DESCRIPTOR,
            "commit-message-integration",
        )
        fixture = factory.createCodeInsightFixture(builder.fixture)
        val token = LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
            override fun processError(
                category: String,
                message: String,
                details: Array<out String>,
                error: Throwable?,
            ): MutableSet<Action> = if (message.contains("SolutionExplorerPopupMenu")) {
                Action.NONE
            } else {
                super.processError(category, message, details, error)
            }
        })
        try {
            fixture.setUp()
        } finally {
            token.finish()
        }
        originalSettings = CommitMessageSettingsService.getInstance().state.deepCopy()
        originalProjectState = CommitProjectStateService.getInstance(fixture.project).state.copy(
            profiles = CommitProjectStateService.getInstance(fixture.project).state.profiles.map { it.copy() }.toMutableList(),
            sourceConsentGrants = CommitProjectStateService.getInstance(fixture.project).state.sourceConsentGrants
                .map { it.copy() }
                .toMutableList(),
            draft = CommitProjectStateService.getInstance(fixture.project).state.draft?.copy(),
        )
        originalProjectSharedState = CommitProjectSharedSettingsService.getInstance(fixture.project).state.deepCopy()
    }

    @AfterEach
    fun tearDown() {
        runInEdtAndWait {
            CommitMessageSettingsService.getInstance().replaceState(originalSettings)
            CommitProjectStateService.getInstance(fixture.project).loadState(originalProjectState)
            CommitProjectSharedSettingsService.getInstance(fixture.project).loadState(originalProjectSharedState)
        }
        fixture.tearDown()
    }

    @Test
    fun `four stable actions are registered once and remain in order`() {
        val manager = ActionManager.getInstance()
        val ids = listOf(
            CommitMessageSettingsConfigurable.ACTION_CREATE,
            CommitMessageSettingsConfigurable.ACTION_GENERATE,
            CommitMessageSettingsConfigurable.ACTION_GENERATE_WITH_CONTEXT,
            CommitMessageSettingsConfigurable.ACTION_FORMAT,
        )

        ids.forEach { assertNotNull(manager.getAction(it), it) }
        val group = manager.getAction("EzCodeMarks.CommitMessage.Actions") as DefaultActionGroup
        assertFalse(group.isPopup)
        assertEquals(ids, group.getChildren(null).filterIsInstance<AnAction>().take(4).map(manager::getId))
    }

    @Test
    fun `toolbar hiding does not hide action from non toolbar invocation`() {
        runInEdtAndWait {
            val state = CommitMessageSettingsService.getInstance().state.deepCopy().apply {
                showCreateInToolbar = false
            }
            CommitMessageSettingsService.getInstance().replaceState(state)
            val action = CreateCommitMessageAction()
            val context = commitContext("draft")

            listOf(
                BaseCommitMessageAction.COMMIT_MESSAGE_PLACE,
                BaseCommitMessageAction.CHANGES_VIEW_COMMIT_TOOLBAR_PLACE,
            ).forEach { place ->
                val toolbarEvent = event(action, context, place, ActionUiKind.TOOLBAR)
                action.update(toolbarEvent)
                assertFalse(toolbarEvent.presentation.isVisible, place)
            }

            val keymapEvent = event(action, context, "Keymap", ActionUiKind.NONE)
            action.update(keymapEvent)
            assertTrue(keymapEvent.presentation.isVisible)
            assertTrue(keymapEvent.presentation.isEnabled)
        }
    }

    @Test
    fun `running action shows stop icon and disables sibling action for same document`() {
        runInEdtAndWait {
            val document = EditorFactory.getInstance().createDocument("draft")
            val context = commitContext(document, arrayOf(mockk(relaxed = true)))
            val coordinator = CommitMessageCoordinatorService.getInstance(fixture.project)
            val handle = coordinator.start(document, CommitActionKind.CREATE)!!

            val create = CreateCommitMessageAction()
            val createEvent = event(create, context, "Keymap", ActionUiKind.NONE)
            create.update(createEvent)
            assertEquals(AllIcons.Actions.StopRefresh, createEvent.presentation.icon)
            assertTrue(createEvent.presentation.isEnabled)

            val generate = GenerateCommitMessageAction()
            val generateEvent = event(generate, context, "Keymap", ActionUiKind.NONE)
            generate.update(generateEvent)
            assertFalse(generateEvent.presentation.isEnabled)
            coordinator.finish(handle)
        }
    }

    @Test
    fun `cancelling format instructions stops before coordinator and provider work`() {
        runInEdtAndWait {
            val document = EditorFactory.getInstance().createDocument("fix: keep original")
            val action = FormatCommitMessageAction(FormatInstructionsProvider { null })

            action.actionPerformed(event(action, commitContext(document), "Keymap", ActionUiKind.NONE))

            assertNull(CommitMessageCoordinatorService.getInstance(fixture.project).status(document))
        }
    }

    @Test
    fun `running format action cancels without reopening instructions`() {
        runInEdtAndWait {
            val document = EditorFactory.getInstance().createDocument("fix: keep original")
            val coordinator = CommitMessageCoordinatorService.getInstance(fixture.project)
            val handle = coordinator.start(document, CommitActionKind.FORMAT)!!
            var promptCount = 0
            val action = FormatCommitMessageAction(FormatInstructionsProvider {
                promptCount += 1
                ""
            })

            action.actionPerformed(event(action, commitContext(document), "Keymap", ActionUiKind.NONE))

            assertEquals(0, promptCount)
            assertTrue(handle.isCancelled())
            coordinator.finish(handle)
        }
    }

    @Test
    fun `workflow text and included changes take precedence over fallback keys`() {
        runInEdtAndWait {
            val workflowChange = mockk<Change>(relaxed = true)
            val fallbackChange = mockk<Change>(relaxed = true)
            val commitUi = mockk<CommitMessageUi>(relaxed = true)
            every { commitUi.text } returns "workflow text"
            val workflow = mockk<CommitWorkflowUi>(relaxed = true)
            every { workflow.commitMessageUi } returns commitUi
            every { workflow.getIncludedChanges() } returns listOf(workflowChange)
            every { workflow.getIncludedUnversionedFiles() } returns emptyList()
            val writer = mockk<CommitMessageI>(relaxed = true)
            val document = EditorFactory.getInstance().createDocument("document text")
            val data = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, fixture.project)
                .add(VcsDataKeys.COMMIT_MESSAGE_CONTROL, writer)
                .add(VcsDataKeys.COMMIT_MESSAGE_DOCUMENT, document)
                .add(VcsDataKeys.COMMIT_WORKFLOW_UI, workflow)
                .add(VcsDataKeys.SELECTED_CHANGES, arrayOf(fallbackChange))
                .build()
            val action = CreateCommitMessageAction()
            val snapshot = IntelliJCommitActionContextAdapter.snapshot(
                event(action, data, "Keymap", ActionUiKind.NONE),
            )!!

            assertEquals("workflow text", snapshot.currentText)
            assertEquals(listOf(workflowChange), snapshot.changes)
        }
    }

    @Test
    fun `snapshot rejects writeback after commit document changes`() {
        runInEdtAndWait {
            val document = EditorFactory.getInstance().createDocument("original")
            val action = CreateCommitMessageAction()
            val snapshot = IntelliJCommitActionContextAdapter.snapshot(
                event(action, commitContext(document), "Keymap", ActionUiKind.NONE),
            )!!

            assertTrue(IntelliJCommitActionContextAdapter.isUnchanged(snapshot))
            WriteAction.run<RuntimeException> { document.setText("user edit") }
            assertFalse(IntelliJCommitActionContextAdapter.isUnchanged(snapshot))
        }
    }

    @Test
    fun `AI result writes directly and atomically when preview is disabled`() {
        runInEdtAndWait {
            assertFalse(CommitMessageSettingsState().previewAiResultBeforeApply)
            val document = EditorFactory.getInstance().createDocument("original")
            val writer = mockk<CommitMessageI>(relaxed = true)
            val snapshot = CommitActionSnapshot(
                project = fixture.project,
                writer = writer,
                commitUi = null,
                document = document,
                documentModificationStamp = document.modificationStamp,
                currentText = "original",
                changes = emptyList(),
                unversionedFiles = emptyList(),
                revision = null,
            )
            val action = object : BaseCommitMessageAction(CommitActionKind.FORMAT, AllIcons.Actions.ReformatCode) {
                override fun toolbarVisible(settings: CommitMessageSettingsState): Boolean = true
                override fun supports(context: CommitActionAvailability): Boolean = true
                override fun perform(snapshot: CommitActionSnapshot) = Unit
                fun deliver(snapshot: CommitActionSnapshot, preview: AiPreview) = applyAiResult(snapshot, preview, false)
            }

            action.deliver(
                snapshot,
                AiPreview(
                    original = "original",
                    result = "fix: write directly",
                    provider = "Test",
                    endpoint = "https://example.test/v1",
                    template = "Default",
                    templateId = "default",
                    filteredFiles = emptyList(),
                ),
            )

            verify(exactly = 1) { writer.setCommitMessage("fix: write directly") }
            assertEquals("fix: write directly", document.text)
        }
    }

    @Test
    fun `refinement uses the exact confirmed envelopes and one shared three request budget`() {
        val profile = LlmProfile(
            id = "refinement-profile",
            name = "Refinement",
            baseUrl = "https://example.test/v1",
            model = "test-model",
            responseLanguage = "English",
            streaming = true,
        )
        CommitMessageSettingsService.getInstance().replaceState(
            CommitMessageSettingsService.getInstance().state.deepCopy().apply {
                profiles = mutableListOf(profile.copy())
                activeProfileId = profile.id
            },
        )
        val client = mockk<LlmProviderClient>()
        val secretStore = mockk<CommitMessageSecretStore>()
        val requests = mutableListOf<LlmCompletionRequest>()
        every { secretStore.credentialSnapshotWithinTransaction(profile.id) } returns
            CommitMessageCredentialSnapshot("test-secret", "test-generation")
        every { client.complete(any(), any(), any(), any(), any()) } answers {
            val request = arg<LlmCompletionRequest>(2)
            arg<ProviderRequestBudget>(3).acquire()
            requests += request
            when (requests.size) {
                1 -> LlmCompletion(
                    """{"optimizedInstruction":"Use one imperative subject under 50 characters","explanation":"Removes repetition"}""",
                )
                2 -> LlmCompletion("invalid structured response")
                else -> LlmCompletion(
                    """{"type":"feat","scope":"","subject":"add refinement history","body":"","breakingChanges":"","closes":"","skipCi":false}""",
                )
            }
        }
        val service = CommitMessageAiService(fixture.project)
        setPrivateField(service, "providerClient", client)
        setPrivateField(service, "secretStore", secretStore)
        val template = CommitTemplateSnapshot(
            candidates = CommitMessageDefaults.templates().map { it.copy() },
            allowedTypes = listOf("feat", "fix"),
            style = CommitStyleDefinition(
                id = "concise-test",
                name = "Concise",
                prompt = "Avoid routine detail and repetition.",
            ),
        )
        val currentCommit = "feat: initial AI result"
        val originalBaseline = "ORIGINAL-BASELINE-MUST-STAY-LOCAL"
        val rawPrompt = "make it shorter"
        val budget = ProviderRequestBudget(3)

        val promptPlan = service.preparePromptOptimization(currentCommit, rawPrompt, profile)
        val proposal = service.optimizePreparedPrompt(promptPlan, profile, budget, EmptyProgressIndicator())
        val commitPlan = service.prepareCommitRefinement(
            currentCommit,
            proposal.optimizedInstruction,
            profile,
            template,
        )
        val result = service.refinePreparedCommit(commitPlan, profile, budget, EmptyProgressIndicator())

        assertEquals("feat: add refinement history", result)
        assertEquals(3, budget.usedRequests())
        assertThrows(ProviderRequestBudgetExceededException::class.java) { budget.acquire() }
        assertEquals(promptPlan.envelope.systemPrompt, requests[0].systemPrompt)
        assertEquals(promptPlan.envelope.userPrompt, requests[0].userPrompt)
        assertEquals(commitPlan.envelope.systemPrompt, requests[1].systemPrompt)
        assertEquals(commitPlan.envelope.userPrompt, requests[1].userPrompt)
        assertEquals(commitPlan.envelope.systemPrompt, requests[2].systemPrompt)
        assertTrue(requests[2].userPrompt.contains(proposal.optimizedInstruction))
        assertTrue(requests[2].userPrompt.contains(commitPlan.envelope.userPrompt))
        assertTrue(requests[2].userPrompt.contains(currentCommit))
        assertTrue(requests.all { it.structured })
        assertTrue(requests.all { !it.streaming && !it.reasoningCompatibility })
        requests.forEach { request ->
            assertFalse(request.systemPrompt.contains(originalBaseline))
            assertFalse(request.userPrompt.contains(originalBaseline))
            listOf("## Status\n", "## Diff\n", "## Unversioned files\n", "## Recent commits\n", "## Historical revision\n")
                .forEach { heading -> assertFalse(request.userPrompt.contains(heading), heading) }
        }
        verify(exactly = 3) { client.complete(any(), "test-secret", any(), budget, any()) }
    }

    @Test
    fun `prepared refinement rejects a changed provider profile before any request`() {
        val profile = LlmProfile(
            id = "stable-profile",
            name = "Stable",
            baseUrl = "https://example.test/v1",
            model = "model-a",
        )
        val service = CommitMessageAiService(fixture.project)
        val prepared = service.prepareCommitRefinement(
            "fix: preserve current result",
            "Use one line",
            profile,
            CommitTemplateSnapshot(CommitMessageDefaults.templates()),
        )

        assertThrows(emohce.data.commitmessage.StaleProviderProfileException::class.java) {
            service.refinePreparedCommit(
                prepared,
                profile.copy(model = "model-b"),
                ProviderRequestBudget(3),
                EmptyProgressIndicator(),
            )
        }
    }

    @Test
    fun `default preview refiner rejects cancelled finished and response-stale coordinator handles`() {
        runInEdtAndWait {
            val profile = LlmProfile(
                id = "coordinator-refinement",
                name = "Coordinator refinement",
                baseUrl = "https://example.test/v1",
                model = "test-model",
                streaming = false,
            )
            CommitMessageSettingsService.getInstance().replaceState(
                CommitMessageSettingsService.getInstance().state.deepCopy().apply {
                    profiles = mutableListOf(profile.copy())
                    activeProfileId = profile.id
                },
            )
            val storedProfile = requireNotNull(CommitMessageSettingsService.getInstance().profile(profile.id)).copy()
            val template = CommitTemplateSnapshot(CommitMessageDefaults.templates().map { it.copy() })
            val service = CommitMessageAiService.getInstance(fixture.project)
            val originalClient = getPrivateField(service, "providerClient")
            val originalSecretStore = getPrivateField(service, "secretStore")
            val client = mockk<LlmProviderClient>()
            val secretStore = mockk<CommitMessageSecretStore>()
            every { secretStore.credentialSnapshotWithinTransaction(storedProfile.id) } returns
                CommitMessageCredentialSnapshot("test-secret", "test-generation")
            val coordinator = CommitMessageCoordinatorService.getInstance(fixture.project)
            try {
                setPrivateField(service, "providerClient", client)
                setPrivateField(service, "secretStore", secretStore)

                val cancelledDocument = EditorFactory.getInstance().createDocument("feat: current result")
                val cancelledHandle = requireNotNull(
                    coordinator.start(cancelledDocument, CommitActionKind.GENERATE),
                )
                val cancelledAttempt = DefaultCommitMessagePreviewRefiner(
                    fixture.project,
                    storedProfile,
                    template,
                    cancelledHandle,
                ).newAttempt()
                val cancelledPlan = cancelledAttempt.preparePromptOptimization(
                    "feat: current result",
                    "Make it concise",
                )
                assertTrue(coordinator.cancel(cancelledDocument, CommitActionKind.GENERATE))
                val cancelledIndicator = EmptyProgressIndicator()
                assertThrows(ProcessCanceledException::class.java) {
                    cancelledAttempt.optimizePrompt(cancelledPlan, cancelledIndicator)
                }
                assertTrue(cancelledIndicator.isCanceled)
                verify(exactly = 0) { client.complete(any(), any(), any(), any(), any()) }
                coordinator.finish(cancelledHandle)

                val finishedDocument = EditorFactory.getInstance().createDocument("fix: current result")
                val finishedHandle = requireNotNull(coordinator.start(finishedDocument, CommitActionKind.FORMAT))
                val finishedAttempt = DefaultCommitMessagePreviewRefiner(
                    fixture.project,
                    storedProfile,
                    template,
                    finishedHandle,
                ).newAttempt()
                coordinator.finish(finishedHandle)
                assertThrows(ProcessCanceledException::class.java) {
                    finishedAttempt.prepareCommitRefinement("fix: current result", "Use one line")
                }
                verify(exactly = 0) { client.complete(any(), any(), any(), any(), any()) }

                val staleDocument = EditorFactory.getInstance().createDocument("docs: current result")
                val staleHandle = requireNotNull(coordinator.start(staleDocument, CommitActionKind.GENERATE))
                val staleAttempt = DefaultCommitMessagePreviewRefiner(
                    fixture.project,
                    storedProfile,
                    template,
                    staleHandle,
                ).newAttempt()
                val stalePlan = staleAttempt.preparePromptOptimization(
                    "docs: current result",
                    "Make it concise",
                )
                every { client.complete(any(), any(), any(), any(), any()) } answers {
                    arg<ProviderRequestBudget>(3).acquire()
                    coordinator.finish(staleHandle)
                    LlmCompletion(
                        """{"optimizedInstruction":"Use one concise subject","explanation":"Removes detail"}""",
                    )
                }
                val staleIndicator = EmptyProgressIndicator()
                assertThrows(ProcessCanceledException::class.java) {
                    staleAttempt.optimizePrompt(stalePlan, staleIndicator)
                }
                assertTrue(staleIndicator.isCanceled)
                verify(exactly = 1) { client.complete(any(), "test-secret", any(), any(), any()) }
            } finally {
                setPrivateField(service, "providerClient", originalClient)
                setPrivateField(service, "secretStore", originalSecretStore)
            }
        }
    }

    @Test
    fun `preview refinement keeps cancelled attempts out of the session and records accepted evidence`() {
        runInEdtAndWait {
            val refiner = mockk<CommitMessagePreviewRefiner>()
            val envelope = LlmPromptEnvelope("confirmed system", "confirmed user")
            var interactionCount = 0
            val dialog = AiCommitMessagePreviewDialog(
                fixture.project,
                AiPreview(
                    original = "draft before generation",
                    result = "feat: initial AI result",
                    provider = "Test",
                    endpoint = "https://example.test/v1",
                    template = "Default",
                    templateId = CommitMessageDefaults.DEFAULT_TEMPLATE_ID,
                    filteredFiles = emptyList(),
                ),
                refiner,
                CommitRefinementInteraction { _, currentCommit, _ ->
                    interactionCount += 1
                    if (interactionCount == 1) {
                        null
                    } else {
                        CommitRefinementDialogResult(
                            beforeCommit = currentCommit,
                            afterCommit = "feat: concise final result",
                            rawPrompt = "Make it shorter",
                            aiOptimizedPrompt = "Use one imperative subject",
                            confirmedPrompt = "Use one imperative subject under 50 characters",
                            promptEnvelope = envelope,
                            promptOptimizationEnvelope = LlmPromptEnvelope("optimizer system", "optimizer user"),
                            promptOptimizationExplanation = "Removes repetition",
                        )
                    }
                },
            )
            val actions = dialog.actionsForTest()
            val refine = actions.first { it.getValue(javax.swing.Action.NAME) == CommitMessageBundle.message("dialog.preview.refine") }
            assertTrue(actions.any {
                it.getValue(javax.swing.Action.NAME) == CommitMessageBundle.message("dialog.preview.history", 0)
            })

            refine.actionPerformed(ActionEvent(dialog, ActionEvent.ACTION_PERFORMED, "refine"))
            assertEquals(0, dialog.sessionForTest().operations.size)
            assertEquals("feat: initial AI result", dialog.result)

            refine.actionPerformed(ActionEvent(dialog, ActionEvent.ACTION_PERFORMED, "refine"))
            val session = dialog.sessionForTest()
            assertEquals("draft before generation", session.originalCommit)
            assertEquals("feat: initial AI result", session.initialAiResult)
            assertEquals("feat: concise final result", session.finalCommit)
            assertEquals(1, session.operations.size)
            assertEquals("Make it shorter", session.operations.single().rawPrompt)
            assertEquals("Use one imperative subject", session.operations.single().aiOptimizedPrompt)
            assertEquals("Use one imperative subject under 50 characters", session.operations.single().confirmedPrompt)
            assertEquals(envelope, session.operations.single().promptEnvelope)
            assertEquals("optimizer user", session.operations.single().promptOptimizationEnvelope?.userPrompt)
            assertEquals("Removes repetition", session.operations.single().promptOptimizationExplanation)
            assertTrue(dialog.actionsForTest().any {
                it.getValue(javax.swing.Action.NAME) == CommitMessageBundle.message("dialog.preview.history", 1)
            })
            val historyDialog = CommitRefinementHistoryDialog(fixture.project, session)
            val copiedHistory = historyDialog.copyTextForTest()
            listOf(
                session.originalCommit,
                session.initialAiResult,
                session.finalCommit,
                session.operations.single().rawPrompt,
                session.operations.single().confirmedPrompt,
                session.operations.single().promptEnvelope.systemPrompt,
                requireNotNull(session.operations.single().promptOptimizationEnvelope).userPrompt,
            ).forEach { expected -> assertTrue(copiedHistory.contains(expected), expected) }
            historyDialog.disposeIfNeeded()
            dialog.disposeIfNeeded()
        }
    }

    @Test
    fun `refinement dialog confirms exact optimizer and commit prompts before applying`() {
        runInEdtAndWait {
            val optimizerEnvelope = LlmPromptEnvelope("optimizer system", "optimizer user")
            val commitEnvelope = LlmPromptEnvelope("commit system", "commit user with confirmed instruction")
            val template = CommitTemplateDefinition("test", "Test", "${'$'}{type}: ${'$'}{subject}")
            var usedRequests = 0
            var preparedCommitInstruction = ""
            val attempt = object : CommitMessageRefinementAttempt {
                override fun preparePromptOptimization(
                    currentCommit: String,
                    rawPrompt: String,
                ): PreparedPromptOptimization {
                    assertEquals("feat: initial AI result", currentCommit)
                    assertEquals("Make it concise", rawPrompt)
                    return PreparedPromptOptimization(optimizerEnvelope, rawPrompt, "profile")
                }

                override fun optimizePrompt(
                    prepared: PreparedPromptOptimization,
                    indicator: ProgressIndicator,
                ): CommitPromptOptimizationProposal {
                    assertEquals(optimizerEnvelope, prepared.envelope)
                    usedRequests += 1
                    return CommitPromptOptimizationProposal(
                        "Use one imperative subject under 50 characters",
                        "Makes the instruction precise",
                    )
                }

                override fun prepareCommitRefinement(
                    currentCommit: String,
                    confirmedPrompt: String,
                ): PreparedCommitRefinement {
                    assertEquals("feat: initial AI result", currentCommit)
                    preparedCommitInstruction = confirmedPrompt
                    return PreparedCommitRefinement(
                        commitEnvelope,
                        template,
                        listOf("feat"),
                        "concise",
                        confirmedPrompt,
                        "profile",
                    )
                }

                override fun refineCommit(prepared: PreparedCommitRefinement, indicator: ProgressIndicator): String {
                    assertEquals(commitEnvelope, prepared.envelope)
                    usedRequests += 1
                    return "feat: concise final result"
                }

                override fun usedRequests(): Int = usedRequests
            }
            val refiner = object : CommitMessagePreviewRefiner {
                override fun newAttempt(): CommitMessageRefinementAttempt = attempt
            }
            val runner = object : CommitRefinementProgressRunner {
                override fun <T : Any> run(
                    project: com.intellij.openapi.project.Project,
                    title: String,
                    operation: (ProgressIndicator) -> T,
                ): Result<T> = runCatching { operation(EmptyProgressIndicator()) }
            }
            val confirmedEnvelopes = mutableListOf<Pair<String, LlmPromptEnvelope>>()
            val dialog = CommitRefinementPromptDialog(
                fixture.project,
                "feat: initial AI result",
                refiner,
                runner,
            ) { _, titleKey, envelope ->
                confirmedEnvelopes += titleKey to envelope
                true
            }
            dialog.componentForTest()
            dialog.setRawPromptForTest("Make it concise")

            dialog.optimizeActionForTest().actionPerformed(
                ActionEvent(dialog, ActionEvent.ACTION_PERFORMED, "optimize"),
            )
            assertEquals("Use one imperative subject under 50 characters", dialog.optimizedPromptForTest())
            dialog.confirmForTest()

            val accepted = requireNotNull(dialog.acceptedResultForTest())
            assertEquals("feat: initial AI result", accepted.beforeCommit)
            assertEquals("feat: concise final result", accepted.afterCommit)
            assertEquals("Make it concise", accepted.rawPrompt)
            assertEquals("Use one imperative subject under 50 characters", accepted.aiOptimizedPrompt)
            assertEquals("Use one imperative subject under 50 characters", accepted.confirmedPrompt)
            assertEquals(accepted.confirmedPrompt, preparedCommitInstruction)
            assertEquals(commitEnvelope, accepted.promptEnvelope)
            assertEquals(optimizerEnvelope, accepted.promptOptimizationEnvelope)
            assertEquals("Makes the instruction precise", accepted.promptOptimizationExplanation)
            assertEquals(
                listOf(
                    "dialog.promptEnvelope.optimizeTitle" to optimizerEnvelope,
                    "dialog.promptEnvelope.commitTitle" to commitEnvelope,
                ),
                confirmedEnvelopes,
            )
            assertEquals(2, usedRequests)
            dialog.disposeIfNeeded()
        }
    }

    @Test
    fun `prompt confirmation dialog displays the exact system and user envelopes`() {
        runInEdtAndWait {
            val envelope = LlmPromptEnvelope(
                "exact system prompt\nwith policy",
                "exact user prompt\nwith current commit and template",
            )
            val dialog = AiPromptEnvelopeConfirmationDialog(
                fixture.project,
                envelope,
                "dialog.promptEnvelope.commitTitle",
            )
            val prompts = descendants(dialog.componentForTest())
                .filterIsInstance<EditorTextField>()
                .map { it.text }
                .toSet()

            assertEquals(setOf(envelope.systemPrompt, envelope.userPrompt), prompts)
            dialog.disposeIfNeeded()
        }
    }

    @Test
    fun `json repair request retains the configured style and one-off instructions`() {
        val client = mockk<LlmProviderClient>()
        val repairRequest = slot<LlmCompletionRequest>()
        every {
            client.complete(any(), any(), capture(repairRequest), any(), any())
        } returns LlmCompletion(
            """{"type":"feat","scope":"","subject":"keep style","body":"","breakingChanges":"","closes":"","skipCi":false}""",
        )
        val service = CommitMessageAiService(fixture.project)
        CommitMessageAiService::class.java.getDeclaredField("providerClient").apply {
            isAccessible = true
            set(service, client)
        }
        val repair = CommitMessageAiService::class.java.declaredMethods.single { it.name == "parseOrRepair" }.apply {
            isAccessible = true
        }
        val stylePrompt = "Use one imperative line and omit redundant background."

        val result = repair.invoke(
            service,
            LlmProfile(responseLanguage = "English"),
            "test-key",
            "not valid json",
            listOf("feat", "fix"),
            stylePrompt,
            ProviderRequestBudget(3),
            EmptyProgressIndicator(),
            "Keep the subject below 50 characters.",
            null,
            "",
            null,
        ) as emohce.domain.commitmessage.CommitDraft

        assertEquals("feat", result.type)
        assertTrue(repairRequest.captured.systemPrompt.contains("Follow this configured commit style"))
        assertTrue(repairRequest.captured.systemPrompt.contains(stylePrompt))
        assertTrue(repairRequest.captured.userPrompt.startsWith("Repair this invalid response"))
        assertTrue(repairRequest.captured.userPrompt.contains("Keep the subject below 50 characters."))
    }

    @Test
    fun `fallback commit document preserves text across snapshots`() {
        runInEdtAndWait {
            val writer = mockk<CommitMessageI>(relaxed = true)
            val data = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, fixture.project)
                .add(VcsDataKeys.COMMIT_MESSAGE_CONTROL, writer)
                .build()
            val action = CreateCommitMessageAction()
            val first = IntelliJCommitActionContextAdapter.snapshot(
                event(action, data, "Keymap", ActionUiKind.NONE),
            )!!
            IntelliJCommitActionContextAdapter.write(first, "first message")
            val second = IntelliJCommitActionContextAdapter.snapshot(
                event(action, data, "Keymap", ActionUiKind.NONE),
            )!!

            assertEquals("first message", second.currentText)
            assertTrue(IntelliJCommitActionContextAdapter.isUnchanged(second))
        }
    }

    @Test
    fun `unversioned context has an aggregate budget`() {
        val files = (1..5).map { index ->
            LightVirtualFile("unversioned-$index.txt", "x".repeat(GitContextPolicy.UNVERSIONED_FILE_LIMIT))
        }
        val context = IntelliJGitContextRepository(fixture.project).collect(
            GitContextRequest(emptyList(), files),
            EmptyProgressIndicator(),
        )

        assertTrue(context.unversionedFiles.length <= GitContextPolicy.UNVERSIONED_TOTAL_LIMIT)
        assertTrue(context.truncated)
        assertTrue(context.filteredFiles.isNotEmpty())
    }

    @Test
    fun `unversioned binary content is filtered before prompting`() {
        val binary = LightVirtualFile("looks-like-text.txt", "prefix\u0000binary")
        val context = IntelliJGitContextRepository(fixture.project).collect(
            GitContextRequest(emptyList(), listOf(binary)),
            EmptyProgressIndicator(),
        )

        assertTrue(context.unversionedFiles.isBlank())
        assertEquals(listOf("looks-like-text.txt"), context.filteredFiles)
    }

    @Test
    fun `keymap conflict detection reports another action and can be restored`() {
        runInEdtAndWait {
            val keymap = KeymapManager.getInstance().activeKeymap
            val shortcut = KeyboardShortcut(KeyStroke.getKeyStroke("control alt shift F12"), null)
            val first = CommitMessageSettingsConfigurable.ACTION_CREATE
            val second = CommitMessageSettingsConfigurable.ACTION_FORMAT
            keymap.addShortcut(first, shortcut)
            keymap.addShortcut(second, shortcut)
            try {
                assertTrue(second in CommitMessageKeymapSupport.conflictingActionIds(first))
            } finally {
                keymap.removeShortcut(first, shortcut)
                keymap.removeShortcut(second, shortcut)
            }
        }
    }

    @Test
    fun `mac keymaps receive the cross-system generate shortcut`() {
        runInEdtAndWait {
            val createShortcut = KeyboardShortcut(KeyStroke.getKeyStroke("meta alt shift M"), null)
            val generateShortcut = KeyboardShortcut(KeyStroke.getKeyStroke("control alt X"), null)
            listOf(KeymapManager.MAC_OS_X_KEYMAP, KeymapManager.MAC_OS_X_10_5_PLUS_KEYMAP).forEach { name ->
                val macKeymap = KeymapManager.getInstance().getKeymap(name)
                assertNotNull(macKeymap, name)
                assertTrue(
                    macKeymap!!.getShortcuts(CommitMessageSettingsConfigurable.ACTION_CREATE).contains(createShortcut),
                    name,
                )
                assertTrue(
                    macKeymap.getShortcuts(CommitMessageSettingsConfigurable.ACTION_GENERATE).contains(generateShortcut),
                    name,
                )
            }
        }
    }

    @Test
    fun `format instructions dialog uses dedicated optional prompt`() {
        runInEdtAndWait {
            val dialog = AdditionalRequirementsDialog(
                fixture.project,
                AdditionalRequirementsDialog.Purpose.FORMAT,
            )
            val root = dialog.componentForTest()
            val input = descendants(root).filterIsInstance<JBTextArea>().single()
            input.text = "  Use one concise line.  "

            assertEquals(CommitMessageBundle.message("dialog.formatInstructions.title"), dialog.title)
            assertTrue(descendants(root).filterIsInstance<javax.swing.JLabel>().any {
                it.text == CommitMessageBundle.message("dialog.formatInstructions.prompt")
            })
            assertEquals("Use one concise line.", dialog.requirements)
            dialog.disposeIfNeeded()
        }
    }

    @Test
    fun `settings pages support clean lifecycle`() {
        runInEdtAndWait {
            val pages = listOf(
                CommitMessageSettingsConfigurable(),
                CommitTemplatesConfigurable(),
                CommitProvidersConfigurable(),
                CommitProjectDefaultsConfigurable(fixture.project),
                CommitProjectProvidersConfigurable(fixture.project),
                CommitProjectSharedConfigurable(fixture.project),
            )
            pages.forEach { page ->
                assertNotNull(page.createComponent())
                page.reset()
                assertFalse(page.isModified, page.displayName)
                page.apply()
                page.disposeUIResources()
            }
        }
    }

    @Test
    fun `shared project settings page preserves instructions definitions and defaults`() {
        runInEdtAndWait {
            val template = CommitTemplateDefinition("project.shared.template", "Shared template", "${'$'}{type}: ${'$'}{subject}")
            val style = CommitStyleDefinition("project.shared.style", "Shared style", prompt = "Use project terminology")
            val shared = CommitProjectSharedSettingsService.getInstance(fixture.project)
            shared.loadState(
                CommitProjectSharedSettingsState(
                    instructionMode = ProjectInstructionMode.APPEND,
                    extraInstructions = "Mention the issue identifier when present.",
                    templates = mutableListOf(template),
                    styles = mutableListOf(style),
                    defaultTemplateId = template.id,
                    defaultStyleId = style.id,
                ),
            )
            val page = CommitProjectSharedConfigurable(fixture.project)

            assertNotNull(page.createComponent())
            assertFalse(page.isModified)
            page.apply()

            assertEquals(ProjectInstructionMode.APPEND, shared.state.instructionMode)
            assertEquals(template.id, shared.state.defaultTemplateId)
            assertEquals(style.id, shared.state.defaultStyleId)
            assertEquals("Global\n\nMention the issue identifier when present.", shared.effectiveExtraInstructions("Global"))
            page.disposeUIResources()
        }
    }

    @Test
    fun `shared project settings page loads an error message for unsupported schema instead of crashing`() {
        runInEdtAndWait {
            val shared = CommitProjectSharedSettingsService.getInstance(fixture.project)
            shared.loadState(CommitProjectSharedSettingsState(schemaVersion = 999))
            assertFalse(shared.isSchemaSupported())

            val page = CommitProjectSharedConfigurable(fixture.project)
            val root = page.createComponent()

            assertNotNull(root)
            assertTrue(descendants(root).filterIsInstance<JBTabbedPane>().none())
            assertFalse(page.isModified)
            page.disposeUIResources()
        }
    }

    @Test
    fun `project provider page stores private profile metadata and scoped credential without codex account controls`() {
        runInEdtAndWait {
            val projectState = CommitProjectStateService.getInstance(fixture.project)
            projectState.loadState(CommitProjectState())
            val passwordSafe = mockk<PasswordSafe>(relaxed = true)
            val editor = ProviderProfileEditor { input, _ ->
                input.sessionKey?.fill('\u0000')
                ProviderProfileEditResult(
                    profile = input.profile.copy(name = "Project model", model = "project-model"),
                    apiKey = "project-secret".toCharArray(),
                )
            }
            val page = CommitProjectProvidersConfigurable(
                project = fixture.project,
                secretStore = CommitMessageSecretStore(passwordSafe),
                profileEditor = editor,
            )
            val root = page.createComponent()

            page.addProfileForTest()
            assertTrue(page.isModified)
            assertTrue(descendants(root).filterIsInstance<JButton>().none {
                it.text == CommitMessageBundle.message("settings.providers.codex.signIn") ||
                    it.text == CommitMessageBundle.message("settings.providers.codex.logout")
            })
            page.apply()

            val profile = projectState.state.profiles.single()
            assertEquals("Project model", profile.name)
            assertEquals("project-model", profile.model)
            assertEquals(LlmProfileScope.PROJECT, projectState.state.activeProfileScope)
            assertEquals(profile.id, projectState.state.activeProfileId)
            assertTrue(projectState.projectCredentialId(profile.id).startsWith("project:"))
            assertFalse(projectState.projectCredentialId(profile.id) == profile.id)
            verify(exactly = 1) { passwordSafe.set(any(), any<Credentials>()) }
            page.disposeUIResources()
        }
    }

    @Test
    fun `project profile edit stages consent invalidation until apply and reset preserves consent`() {
        runInEdtAndWait {
            val profile = LlmProfile(
                id = "project-consent-profile",
                name = "Project consent",
                baseUrl = "https://before.example/v1",
                model = "model",
            )
            val projectState = CommitProjectStateService.getInstance(fixture.project)
            projectState.loadState(
                CommitProjectState(
                    activeProfileScope = LlmProfileScope.PROJECT,
                    activeProfileId = profile.id,
                    profiles = mutableListOf(profile),
                ),
            )
            projectState.grantSourceContextConsent(profile, "")
            val page = CommitProjectProvidersConfigurable(
                project = fixture.project,
                secretStore = CommitMessageSecretStore(mockk(relaxed = true)),
                profileEditor = ProviderProfileEditor { input, _ ->
                    input.sessionKey?.fill('\u0000')
                    ProviderProfileEditResult(input.profile.copy(baseUrl = "https://after.example/v1"))
                },
            )
            page.createComponent()

            page.editProfileForTest(profile.id)
            assertTrue(projectState.hasSourceContextConsent(profile, ""))
            page.reset()

            assertTrue(projectState.hasSourceContextConsent(profile, ""))
            assertEquals("https://before.example/v1", projectState.state.profiles.single().baseUrl)

            page.editProfileForTest(profile.id)
            assertTrue(projectState.hasSourceContextConsent(profile, ""))
            page.apply()

            assertFalse(projectState.hasSourceContextConsent(profile, ""))
            assertEquals("https://after.example/v1", projectState.state.profiles.single().baseUrl)
            page.disposeUIResources()
        }
    }

    @Test
    fun `project profile keeps the applied session key while settings remain open`() {
        runInEdtAndWait {
            val profile = LlmProfile(id = "project-session-profile", name = "Project session", model = "model")
            val projectState = CommitProjectStateService.getInstance(fixture.project)
            projectState.loadState(
                CommitProjectState(
                    activeProfileScope = LlmProfileScope.PROJECT,
                    activeProfileId = profile.id,
                    profiles = mutableListOf(profile),
                ),
            )
            var editCount = 0
            var reopenedKey = ""
            val page = CommitProjectProvidersConfigurable(
                project = fixture.project,
                secretStore = CommitMessageSecretStore(mockk(relaxed = true)),
                profileEditor = ProviderProfileEditor { input, _ ->
                    editCount += 1
                    if (editCount == 1) {
                        input.sessionKey?.fill('\u0000')
                        ProviderProfileEditResult(input.profile, "project-session-secret".toCharArray())
                    } else {
                        reopenedKey = input.sessionKey?.concatToString().orEmpty()
                        input.sessionKey?.fill('\u0000')
                        null
                    }
                },
            )
            page.createComponent()

            page.editProfileForTest(profile.id)
            page.apply()
            page.editProfileForTest(profile.id)

            assertEquals("project-session-secret", reopenedKey)
            page.disposeUIResources()
        }
    }

    @Test
    fun `private default pages preserve selections that reference shared definitions`() {
        runInEdtAndWait {
            val sharedTemplate = CommitTemplateDefinition(
                "project.shared.selected.template",
                "Shared selected template",
                "${'$'}{subject}",
            )
            val sharedStyle = CommitStyleDefinition(
                "project.shared.selected.style",
                "Shared selected style",
                prompt = "Use a terse project tone",
            )
            CommitProjectSharedSettingsService.getInstance(fixture.project).loadState(
                CommitProjectSharedSettingsState(
                    templates = mutableListOf(sharedTemplate),
                    styles = mutableListOf(sharedStyle),
                ),
            )
            val projectState = CommitProjectStateService.getInstance(fixture.project)
            projectState.setTemplateId(sharedTemplate.id)
            projectState.setStyleId(sharedStyle.id)
            val templatesPage = CommitTemplatesConfigurable(fixture.project)
            val defaultsPage = CommitProjectDefaultsConfigurable(fixture.project)

            assertNotNull(templatesPage.createComponent())
            assertFalse(templatesPage.isModified)
            templatesPage.apply()
            assertNotNull(defaultsPage.createComponent())
            assertFalse(defaultsPage.isModified)
            defaultsPage.apply()

            assertEquals(sharedTemplate.id, projectState.state.templateId)
            assertEquals(sharedStyle.id, projectState.state.styleId)
            templatesPage.disposeUIResources()
            defaultsPage.disposeUIResources()
        }
    }

    @Test
    fun `sorted project provider and shared template tables act on the visible row`() {
        runInEdtAndWait {
            val projectState = CommitProjectStateService.getInstance(fixture.project)
            projectState.loadState(
                CommitProjectState(
                    profiles = mutableListOf(
                        LlmProfile(id = "project-zulu", name = "Zulu"),
                        LlmProfile(id = "project-alpha", name = "Alpha"),
                    ),
                ),
            )
            val edited = mutableListOf<String>()
            val providers = CommitProjectProvidersConfigurable(
                project = fixture.project,
                secretStore = CommitMessageSecretStore(mockk(relaxed = true)),
                profileEditor = ProviderProfileEditor { input, _ ->
                    edited += input.profile.id
                    input.sessionKey?.fill('\u0000')
                    null
                },
            )
            val providerRoot = providers.createComponent()
            val providerTable = descendants(providerRoot).filterIsInstance<JBTable>().single { it.columnCount == 4 }
            providerTable.autoCreateRowSorter = true
            providerTable.rowSorter.toggleSortOrder(0)
            providerTable.setRowSelectionInterval(0, 0)
            requireNotNull(ToolbarDecorator.findEditButton(providerRoot)).actionPerformed(mockk(relaxed = true))
            assertEquals(listOf("project-alpha"), edited)

            val alphaTemplate = CommitTemplateDefinition("shared-alpha", "Alpha", "${'$'}{subject}")
            val zuluTemplate = CommitTemplateDefinition("shared-zulu", "Zulu", "${'$'}{subject}")
            val sharedState = CommitProjectSharedSettingsService.getInstance(fixture.project)
            sharedState.loadState(
                CommitProjectSharedSettingsState(templates = mutableListOf(zuluTemplate, alphaTemplate)),
            )
            val shared = CommitProjectSharedConfigurable(fixture.project)
            val sharedRoot = shared.createComponent()
            val templateTable = descendants(sharedRoot).filterIsInstance<JBTable>().first()
            templateTable.autoCreateRowSorter = true
            templateTable.rowSorter.toggleSortOrder(0)
            templateTable.setRowSelectionInterval(0, 0)
            shared.removeSelectedTemplateForTest()
            shared.apply()
            assertEquals(listOf("shared-zulu"), sharedState.state.templates.map { it.id })

            providers.disposeUIResources()
            shared.disposeUIResources()
        }
    }

    @Test
    fun `settings layout follows the upstream table and split pane structure`() {
        runInEdtAndWait {
            val providers = CommitProvidersConfigurable()
            val providerRoot = providers.createComponent()
            val providerTable = descendants(providerRoot).filterIsInstance<JBTable>().single()
            assertEquals(4, providerTable.columnCount)
            assertEquals(
                listOf("Name", "Provider", "Base URL", "Model"),
                (0 until providerTable.columnCount).map(providerTable::getColumnName),
            )
            assertEquals(0.5, descendants(providerRoot).filterIsInstance<JSpinner>().single().value)
            assertTrue(descendants(providerRoot).none { it is JBPasswordField })

            val templates = CommitTemplatesConfigurable(fixture.project)
            val templateRoot = templates.createComponent()
            val tabs = descendants(templateRoot).filterIsInstance<JBTabbedPane>().single()
            assertEquals(3, tabs.tabCount)
            assertEquals(CommitMessageBundle.message("settings.templates.tab"), tabs.getTitleAt(0))
            assertEquals(CommitMessageBundle.message("settings.types.tab"), tabs.getTitleAt(1))
            assertEquals(CommitMessageBundle.message("settings.styles.tab"), tabs.getTitleAt(2))
            assertTrue(descendants(templateRoot).any { it is JSplitPane })
            assertTrue(descendants(templateRoot).filterIsInstance<JBTable>().any { it.columnCount == 2 })

            providers.disposeUIResources()
            templates.disposeUIResources()
        }
    }

    @Test
    fun `native commit message provider consumes a prepared draft once and expires stale drafts`() {
        val service = PendingCommitMessageService.getInstance(fixture.project)
        val provider = EnvironmentCommitMessageProvider()
        val changeList = mockk<LocalChangeList>(relaxed = true)

        service.offer("feat: native workflow")

        assertEquals("feat: native workflow", provider.getCommitMessage(changeList, fixture.project))
        assertNull(provider.getCommitMessage(changeList, fixture.project))

        service.offer("stale", lifetimeMillis = -1)
        assertNull(provider.getCommitMessage(changeList, fixture.project))
    }

    @Test
    fun `project scoped Environment Action services accept platform coroutine scope injection`() {
        assertNotNull(NativeCommitWorkflowLauncher.getInstance(fixture.project))
        assertNotNull(InteractiveCodexSessionFactory.getInstance(fixture.project))
    }

    @Test
    fun `codex executable remains transactional and disables login until applied`() {
        runInEdtAndWait {
            var executable = "codex"
            var confirmations = 0
            val gateway = mockk<CodexAccountSettingsGateway>(relaxed = true)
            every { gateway.executablePath() } answers { executable }
            every { gateway.resolvedExecutablePath() } answers { executable }
            every { gateway.setExecutablePath(any(), any()) } answers {
                assertEquals(executable, secondArg<String>())
                executable = firstArg<String>()
            }
            val panel = CodexSettingsPanel(
                gateway = gateway,
                requestRunner = ProviderSettingsRequestRunner { _, operation ->
                    operation(EmptyProgressIndicator())
                },
                confirmCodexLogin = {
                    confirmations += 1
                    true
                },
            )
            panel.reset()
            val field = descendants(panel.component).filterIsInstance<JBTextField>().single()
            val signIn = descendants(panel.component).filterIsInstance<JButton>()
                .single { it.text == CommitMessageBundle.message("settings.providers.codex.signIn") }

            field.text = "/opt/custom-codex"
            assertTrue(panel.isModified())
            assertFalse(signIn.isEnabled)
            signIn.doClick()
            assertEquals(0, confirmations)
            assertEquals("codex", executable)

            panel.reset()
            assertEquals("codex", field.text)
            assertFalse(panel.isModified())

            field.text = "/opt/custom-codex"
            panel.applySettings()
            assertEquals("/opt/custom-codex", executable)
            assertFalse(panel.isModified())
            assertTrue(signIn.isEnabled)
            panel.dispose()
        }
    }

    @Test
    fun `codex executable apply rejects a concurrent external change`() {
        runInEdtAndWait {
            var executable = "codex"
            val gateway = mockk<CodexAccountSettingsGateway>(relaxed = true)
            every { gateway.executablePath() } answers { executable }
            every { gateway.resolvedExecutablePath() } answers { executable }
            val panel = CodexSettingsPanel(gateway = gateway)
            panel.reset()
            descendants(panel.component).filterIsInstance<JBTextField>().single().text = "/opt/local-codex"
            executable = "/opt/remote-codex"

            assertThrows(ConfigurationException::class.java) { panel.applySettings() }

            assertTrue(panel.isModified())
            verify(exactly = 0) { gateway.setExecutablePath(any(), any()) }
            panel.dispose()
        }
    }

    @Test
    fun `codex settings panel account controls use the isolated gateway without external browser or account writes`() {
        runInEdtAndWait {
            var executable = "codex"
            var signedIn = true
            var logoutCount = 0
            val openedUrls = mutableListOf<String>()
            val deviceCodes = mutableListOf<String>()
            val gateway = object : CodexAccountSettingsGateway {
                override fun executablePath(): String = executable
                override fun resolvedExecutablePath(): String = executable
                override fun setExecutablePath(value: String, expectedValue: String) {
                    check(executable == expectedValue)
                    executable = value.trim()
                }

                override fun installationStatus(): CodexInstallationStatus =
                    CodexInstallationStatus(true, executable, "0.144.5")

                override fun account(indicator: ProgressIndicator?): CodexAppServerAccount = if (signedIn) {
                    CodexAppServerAccount("chatgpt", "developer@example.test", "plus", false)
                } else {
                    CodexAppServerAccount(null, null, null, true)
                }

                override fun browserLogin(
                    indicator: ProgressIndicator,
                    started: (CodexAppServerBrowserLogin) -> Unit,
                ): CodexAppServerLoginCompleted {
                    val login = CodexAppServerBrowserLogin("browser-login", "https://example.test/login")
                    started(login)
                    signedIn = true
                    return CodexAppServerLoginCompleted(login.loginId, true, null)
                }

                override fun deviceLogin(
                    indicator: ProgressIndicator,
                    started: (CodexAppServerDeviceLogin) -> Unit,
                ): CodexAppServerLoginCompleted {
                    val login = CodexAppServerDeviceLogin(
                        "device-login",
                        "https://example.test/device",
                        "ABCD-EFGH",
                    )
                    started(login)
                    signedIn = true
                    return CodexAppServerLoginCompleted(login.loginId, true, null)
                }

                override fun logout() {
                    logoutCount += 1
                    signedIn = false
                }
            }
            val panel = CodexSettingsPanel(
                gateway = gateway,
                requestRunner = ProviderSettingsRequestRunner { _, operation ->
                    operation(EmptyProgressIndicator())
                },
                openCodexUrl = { openedUrls.add(it) },
                showCodexDeviceCode = { deviceCodes.add(it) },
                confirmCodexLogin = { true },
                confirmCodexLogout = { true },
            )
            panel.reset()
            val component = panel.component
            val buttons = descendants(component).filterIsInstance<JButton>()

            buttons.single { it.text == CommitMessageBundle.message("settings.providers.codex.refresh") }.doClick()
            assertTrue(descendants(component).filterIsInstance<JBLabel>().any {
                it.text.contains("developer@example.test") && it.text.contains("plus")
            })

            buttons.single { it.text == CommitMessageBundle.message("settings.providers.codex.logout") }.doClick()
            assertEquals(1, logoutCount)
            assertTrue(descendants(component).filterIsInstance<JBLabel>().any {
                it.text == CommitMessageBundle.message("settings.providers.codex.signedOut")
            })

            buttons.single { it.text == CommitMessageBundle.message("settings.providers.codex.signIn") }.doClick()
            assertEquals(listOf("https://example.test/login"), openedUrls)
            assertTrue(signedIn)
            assertTrue(deviceCodes.isEmpty())
            assertFalse(panel.isModified())
            panel.dispose()
        }
    }

    @Test
    fun `disposing codex settings panel cancels queued account work before gateway access`() {
        runInEdtAndWait {
            val gateway = mockk<CodexAccountSettingsGateway>(relaxed = true)
            every { gateway.executablePath() } returns "codex"
            val queued = mutableListOf<(ProgressIndicator) -> Unit>()
            val panel = CodexSettingsPanel(
                gateway = gateway,
                requestRunner = ProviderSettingsRequestRunner { _, operation -> queued += operation },
            )
            panel.reset()
            descendants(panel.component).filterIsInstance<JButton>()
                .single { it.text == CommitMessageBundle.message("settings.providers.codex.refresh") }
                .doClick()
            assertEquals(1, queued.size)

            panel.dispose()
            val indicator = EmptyProgressIndicator()
            queued.single()(indicator)

            assertTrue(indicator.isCanceled)
            verify(exactly = 0) { gateway.installationStatus() }
            verify(exactly = 0) { gateway.account(any()) }
        }
    }

    @Test
    fun `stale queued codex panel work cannot clear a replacement handle and apply restores controls`() {
        runInEdtAndWait {
            val gateway = mockk<CodexAccountSettingsGateway>(relaxed = true)
            every { gateway.executablePath() } returns "codex"
            every { gateway.installationStatus() } returns CodexInstallationStatus(true, "codex", "0.144.5")
            every { gateway.account(any()) } returns CodexAppServerAccount(null, null, null, true)
            val queued = mutableListOf<(ProgressIndicator) -> Unit>()
            val panel = CodexSettingsPanel(
                gateway = gateway,
                requestRunner = ProviderSettingsRequestRunner { _, operation -> queued += operation },
            )
            panel.reset()
            val refresh = descendants(panel.component).filterIsInstance<JButton>()
                .single { it.text == CommitMessageBundle.message("settings.providers.codex.refresh") }
            val signIn = descendants(panel.component).filterIsInstance<JButton>()
                .single { it.text == CommitMessageBundle.message("settings.providers.codex.signIn") }

            refresh.doClick()
            panel.reset()
            refresh.doClick()
            assertEquals(2, queued.size)
            queued[1](EmptyProgressIndicator())
            val staleIndicator = EmptyProgressIndicator()
            queued[0](staleIndicator)

            assertTrue(staleIndicator.isCanceled)
            verify(exactly = 1) { gateway.installationStatus() }
            verify(exactly = 1) { gateway.account(any()) }

            refresh.doClick()
            assertEquals(3, queued.size)
            assertFalse(refresh.isEnabled)
            panel.applySettings()
            assertTrue(refresh.isEnabled)
            panel.dispose()
        }
    }

    @Test
    fun `codex profile dialog omits endpoint and API key and keeps reasoning effort`() {
        runInEdtAndWait {
            val dialog = ProviderProfileDialog(
                ProviderProfileEditInput(
                    title = "Edit",
                    profile = LlmProfile(
                        id = "codex-profile",
                        name = "ChatGPT",
                        provider = LlmProviderType.CHATGPT_CODEX,
                        model = "gpt-5.1-codex",
                        reasoningEffort = "high",
                    ),
                ),
                ProviderModelFetcher { _, _, completed ->
                    completed(Result.success(listOf("gpt-5.1-codex")))
                    ProviderModelRequest { }
                },
            )
            val root = dialog.componentForTest()
            val password = descendants(root).filterIsInstance<JBPasswordField>().single()
            val fields = descendants(root).filterIsInstance<javax.swing.JTextField>()

            assertFalse(password.isEnabled)
            assertTrue(fields.any { !it.isEnabled && it.text.isBlank() })
            dialog.confirmForTest()
            val result = requireNotNull(dialog.acceptedResultForTest()).profile
            assertEquals(LlmProviderType.CHATGPT_CODEX, result.provider)
            assertEquals("", result.baseUrl)
            assertEquals("high", result.reasoningEffort)
            dialog.disposeIfNeeded()
        }
    }

    @Test
    fun `provider edit toolbar and row double click open the selected profile`() {
        runInEdtAndWait {
            val service = CommitMessageSettingsService.getInstance()
            service.replaceState(service.state.deepCopy().apply {
                profiles = mutableListOf(
                    LlmProfile(id = "profile-edit-a", name = "Zulu"),
                    LlmProfile(id = "profile-edit-b", name = "Alpha"),
                )
                activeProfileId = "profile-edit-a"
            })
            val editedProfiles = mutableListOf<String>()
            val page = CommitProvidersConfigurable(
                profileEditor = ProviderProfileEditor { input, _ ->
                    editedProfiles += input.profile.id
                    ProviderProfileEditResult(input.profile.copy(name = "${input.profile.name} updated"))
                },
            )
            val root = page.createComponent()
            val table = descendants(root).filterIsInstance<JBTable>().single { it.columnCount == 4 }
            table.autoCreateRowSorter = true
            table.rowSorter.toggleSortOrder(0)

            table.setRowSelectionInterval(0, 0)
            requireNotNull(ToolbarDecorator.findEditButton(root)).actionPerformed(mockk(relaxed = true))
            assertEquals(listOf("profile-edit-b"), editedProfiles)
            assertEquals(0, table.selectedRow)
            assertEquals("Alpha updated", table.getValueAt(0, 0))

            val secondRow = table.getCellRect(1, 0, true)
            table.dispatchEvent(
                MouseEvent(
                    table,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    secondRow.x + 2,
                    secondRow.y + 2,
                    2,
                    false,
                    MouseEvent.BUTTON1,
                ),
            )
            assertEquals(listOf("profile-edit-b", "profile-edit-a"), editedProfiles)
            assertEquals(1, table.selectedRow)
            assertEquals("Zulu updated", table.getValueAt(1, 0))

            val blankY = table.rowHeight * table.rowCount + 4
            table.dispatchEvent(
                MouseEvent(
                    table,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    2,
                    blankY,
                    2,
                    false,
                    MouseEvent.BUTTON1,
                ),
            )
            table.dispatchEvent(
                MouseEvent(
                    table,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    secondRow.x + 2,
                    secondRow.y + 2,
                    2,
                    false,
                    MouseEvent.BUTTON3,
                ),
            )
            assertEquals(listOf("profile-edit-b", "profile-edit-a"), editedProfiles)
            page.disposeUIResources()
        }
    }

    @Test
    fun `model editor filters a long list and preserves the selected model through confirmation`() {
        lateinit var dialog: ProviderProfileDialog
        lateinit var combo: FilterableModelComboBox
        val models = List(344) { index -> "provider/model-${index.toString().padStart(3, '0')}" }
        runInEdtAndWait {
            dialog = ProviderProfileDialog(
                ProviderProfileEditInput(
                    title = "Edit",
                    profile = LlmProfile(id = "profile-filter", name = "Filter"),
                ),
                ProviderModelFetcher { _, _, completed ->
                    completed(Result.success(models))
                    ProviderModelRequest { }
                },
            )
            val root = dialog.componentForTest()
            combo = descendants(root).filterIsInstance<FilterableModelComboBox>().single()
            assertTrue(SwingUtilities.isDescendingFrom(combo.editor.editorComponent, combo))
            descendants(root).filterIsInstance<JButton>()
                .first { it.text == CommitMessageBundle.message("settings.providers.fetchModels") }
                .doClick()
        }
        runInEdtAndWait {
            (combo.editor.editorComponent as JTextField).text = "model-12"
            assertEquals("model-12", combo.selectedItem)
        }

        TimeUnit.MILLISECONDS.sleep(200)
        val selectedModel = "provider/model-127"
        runInEdtAndWait {
            val filteredModels = (0 until combo.itemCount).map(combo::getItemAt)
            assertEquals(models.subList(120, 130), filteredModels.take(10))
            assertTrue(filteredModels.size > 10, "fuzzy subsequence matches should remain available after prefixes")
            assertEquals("model-12", combo.currentText())
            combo.selectedItem = selectedModel
        }
        TimeUnit.MILLISECONDS.sleep(200)
        runInEdtAndWait {
            assertEquals(selectedModel, combo.selectedItem)
            assertEquals(selectedModel, combo.currentText())
            dialog.confirmForTest()
            assertEquals(selectedModel, dialog.acceptedResultForTest()?.profile?.model)
            dialog.disposeIfNeeded()
        }
    }

    @Test
    fun `custom model survives an empty filter result and confirmation`() {
        runInEdtAndWait {
            val dialog = ProviderProfileDialog(
                ProviderProfileEditInput(
                    title = "Edit",
                    profile = LlmProfile(id = "profile-custom-model", name = "Custom model"),
                    modelSuggestions = listOf("provider/model-a", "provider/model-b"),
                ),
                ProviderModelFetcher { _, _, _ -> ProviderModelRequest { } },
            )
            val root = dialog.componentForTest()
            val combo = descendants(root).filterIsInstance<FilterableModelComboBox>().single()

            (combo.editor.editorComponent as JTextField).text = "custom/private-model"
            combo.filterNow()

            assertEquals(0, combo.itemCount)
            assertEquals("custom/private-model", combo.currentText())
            dialog.confirmForTest()
            assertEquals("custom/private-model", dialog.acceptedResultForTest()?.profile?.model)
            dialog.disposeIfNeeded()
        }
    }

    @Test
    fun `models fetched after typing are filtered by the current query`() {
        runInEdtAndWait {
            val queued = AtomicReference<((Result<List<String>>) -> Unit)?>()
            val dialog = ProviderProfileDialog(
                ProviderProfileEditInput(
                    title = "Edit",
                    profile = LlmProfile(id = "profile-late-models", name = "Late models"),
                ),
                ProviderModelFetcher { _, _, completed ->
                    queued.set(completed)
                    ProviderModelRequest { }
                },
            )
            val root = dialog.componentForTest()
            val combo = descendants(root).filterIsInstance<FilterableModelComboBox>().single()
            val fetch = descendants(root).filterIsInstance<JButton>()
                .first { it.text == CommitMessageBundle.message("settings.providers.fetchModels") }

            fetch.doClick()
            (combo.editor.editorComponent as JTextField).text = "claude"
            queued.get()!!(
                Result.success(
                    listOf("openai/gpt-5", "anthropic/claude-sonnet", "google/gemini-pro"),
                ),
            )

            assertEquals(listOf("anthropic/claude-sonnet"), (0 until combo.itemCount).map(combo::getItemAt))
            assertEquals("claude", combo.selectedItem)
            assertEquals("claude", combo.currentText())
            dialog.disposeIfNeeded()
        }
    }

    @Test
    fun `selecting a provider table row does not change the active profile`() {
        runInEdtAndWait {
            val service = CommitMessageSettingsService.getInstance()
            service.replaceState(service.state.deepCopy().apply {
                profiles = mutableListOf(
                    LlmProfile(id = "active-profile", name = "Active", model = "active-model"),
                    LlmProfile(id = "inspected-profile", name = "Inspected", model = "inspected-model"),
                )
                activeProfileId = "active-profile"
            })
            val page = CommitProvidersConfigurable()
            val root = page.createComponent()
            val table = descendants(root).filterIsInstance<JBTable>().single { it.columnCount == 4 }
            val activeCombo = descendants(root).filterIsInstance<JComboBox<*>>().single()

            table.setRowSelectionInterval(1, 1)

            assertEquals(
                "Active (${CommitMessageBundle.message("provider.openaiCompatible")})",
                activeCombo.selectedItem.toString(),
            )
            assertFalse(page.isModified)
            page.apply()
            assertEquals("active-profile", service.state.activeProfileId)
            page.disposeUIResources()
        }
    }

    @Test
    fun `project defaults reset preserves existing template and style overrides`() {
        runInEdtAndWait {
            val projectState = CommitProjectStateService.getInstance(fixture.project)
            projectState.setTemplateId(CommitMessageDefaults.DEFAULT_TEMPLATE_ID)
            projectState.setStyleId(CommitMessageDefaults.CONCISE_STYLE_ID)
            val templatesPage = CommitTemplatesConfigurable(fixture.project)
            val page = CommitProjectDefaultsConfigurable(fixture.project)

            assertNotNull(templatesPage.createComponent())
            templatesPage.reset()
            assertFalse(templatesPage.isModified)
            templatesPage.apply()
            assertEquals(CommitMessageDefaults.DEFAULT_TEMPLATE_ID, projectState.state.templateId)
            assertEquals(CommitMessageDefaults.CONCISE_STYLE_ID, projectState.state.styleId)
            assertNotNull(page.createComponent())
            page.reset()
            assertFalse(page.isModified)
            page.apply()

            assertEquals(CommitMessageDefaults.DEFAULT_TEMPLATE_ID, projectState.state.templateId)
            assertEquals(CommitMessageDefaults.CONCISE_STYLE_ID, projectState.state.styleId)
            templatesPage.disposeUIResources()
            page.disposeUIResources()
        }
    }

    @Test
    fun `missing project template and style overrides remain explicit until the user changes them`() {
        runInEdtAndWait {
            val projectState = CommitProjectStateService.getInstance(fixture.project)
            projectState.setTemplateId("missing-template")
            projectState.setStyleId("missing-style")
            val defaultsPage = CommitProjectDefaultsConfigurable(fixture.project)
            val templatesPage = CommitTemplatesConfigurable(fixture.project)

            assertNotNull(defaultsPage.createComponent())
            assertFalse(defaultsPage.isModified)
            defaultsPage.apply()
            assertEquals("missing-template", projectState.state.templateId)
            assertEquals("missing-style", projectState.state.styleId)

            assertNotNull(templatesPage.createComponent())
            assertFalse(templatesPage.isModified)
            templatesPage.apply()
            assertEquals("missing-template", projectState.state.templateId)
            assertEquals("missing-style", projectState.state.styleId)

            defaultsPage.disposeUIResources()
            templatesPage.disposeUIResources()
        }
    }

    @Test
    fun `project defaults resnapshot disjoint external changes after apply`() {
        runInEdtAndWait {
            val projectState = CommitProjectStateService.getInstance(fixture.project)
            projectState.loadState(
                CommitProjectState(
                    templateId = CommitMessageDefaults.DEFAULT_TEMPLATE_ID,
                    styleId = CommitMessageDefaults.STANDARD_STYLE_ID,
                ),
            )
            val page = CommitProjectDefaultsConfigurable(fixture.project)
            val root = page.createComponent()
            val combos = descendants(root).filterIsInstance<ComboBox<*>>()
            val conciseName = CommitMessageBundle.message("style.concise.name")
            val styleCombo = combos.single { combo ->
                (0 until combo.itemCount).any { combo.getItemAt(it).toString() == conciseName }
            }
            val templateCombo = combos.single { it !== styleCombo }
            val concise = (0 until styleCombo.itemCount)
                .map(styleCombo::getItemAt)
                .first { it.toString() == conciseName }

            projectState.setTemplateId("")
            styleCombo.selectedItem = concise
            page.apply()

            assertEquals("", projectState.state.templateId)
            assertEquals(CommitMessageDefaults.CONCISE_STYLE_ID, projectState.state.styleId)
            assertEquals(
                CommitMessageBundle.message("settings.project.inheritDefault"),
                templateCombo.selectedItem.toString(),
            )
            assertEquals(conciseName, styleCombo.selectedItem.toString())
            assertFalse(page.isModified)
            page.disposeUIResources()
        }
    }

    @Test
    fun `project defaults reject a conflicting external edit and remain modified`() {
        runInEdtAndWait {
            val projectState = CommitProjectStateService.getInstance(fixture.project)
            projectState.loadState(CommitProjectState(styleId = CommitMessageDefaults.STANDARD_STYLE_ID))
            val page = CommitProjectDefaultsConfigurable(fixture.project)
            val root = page.createComponent()
            val conciseName = CommitMessageBundle.message("style.concise.name")
            val styleCombo = descendants(root).filterIsInstance<ComboBox<*>>().single { combo ->
                (0 until combo.itemCount).any { combo.getItemAt(it).toString() == conciseName }
            }
            styleCombo.selectedItem = (0 until styleCombo.itemCount)
                .map(styleCombo::getItemAt)
                .first { it.toString() == conciseName }
            projectState.setStyleId("external-style")

            assertThrows(ConfigurationException::class.java) { page.apply() }
            assertTrue(page.isModified)
            assertEquals("external-style", projectState.state.styleId)
            page.disposeUIResources()
        }
    }

    @Test
    fun `project shared settings reject a conflicting external edit and remain modified`() {
        runInEdtAndWait {
            val shared = CommitProjectSharedSettingsService.getInstance(fixture.project)
            shared.loadState(CommitProjectSharedSettingsState())
            val page = CommitProjectSharedConfigurable(fixture.project)
            val root = page.createComponent()
            descendants(root).filterIsInstance<ComboBox<*>>()
                .single { it.itemCount == ProjectInstructionMode.entries.size }
                .selectedIndex = ProjectInstructionMode.APPEND.ordinal
            descendants(root).filterIsInstance<JBTextArea>().single().text = "Local instructions"
            shared.loadState(
                CommitProjectSharedSettingsState(
                    instructionMode = ProjectInstructionMode.REPLACE,
                    extraInstructions = "External instructions",
                ),
            )

            assertThrows(ConfigurationException::class.java) { page.apply() }
            assertTrue(page.isModified)
            assertEquals("External instructions", shared.state.extraInstructions)
            page.disposeUIResources()
        }
    }

    @Test
    fun `project providers reject a conflicting external edit and remain modified`() {
        runInEdtAndWait {
            val projectState = CommitProjectStateService.getInstance(fixture.project)
            projectState.loadState(CommitProjectState())
            val page = CommitProjectProvidersConfigurable(
                project = fixture.project,
                secretStore = CommitMessageSecretStore(mockk(relaxed = true)),
                profileEditor = ProviderProfileEditor { input, _ ->
                    input.sessionKey?.fill('\u0000')
                    ProviderProfileEditResult(input.profile.copy(name = "Local profile"))
                },
            )
            page.createComponent()
            page.addProfileForTest()
            projectState.replaceProfiles(listOf(LlmProfile(id = "external-profile", name = "External profile")))

            assertThrows(ConfigurationException::class.java) { page.apply() }
            assertTrue(page.isModified)
            assertEquals("external-profile", projectState.state.profiles.single().id)
            page.disposeUIResources()
        }
    }

    @Test
    fun `failed main settings apply remains modified after a concurrent change`() {
        runInEdtAndWait {
            val service = CommitMessageSettingsService.getInstance()
            val page = CommitMessageSettingsConfigurable()
            val root = page.createComponent()
            val visible = descendants(root).filterIsInstance<javax.swing.JCheckBox>()
                .first { it.text == CommitMessageBundle.message("settings.action.visible") }
            visible.isSelected = !visible.isSelected
            service.replaceState(service.state.deepCopy().apply {
                showFormatInToolbar = !showFormatInToolbar
            })

            assertTrue(page.isModified)
            assertThrows(ConfigurationException::class.java) { page.apply() }
            assertTrue(page.isModified)

            page.disposeUIResources()
        }
    }

    @Test
    fun `template list type table and style list expose accessible names`() {
        runInEdtAndWait {
            val page = CommitTemplatesConfigurable()
            val root = page.createComponent()

            descendants(root).filterIsInstance<JBList<*>>().forEach {
                assertTrue(it.accessibleContext.accessibleName.isNotBlank())
            }
            descendants(root).filterIsInstance<JBTable>().single { it.columnCount == 2 }.also {
                assertTrue(it.accessibleContext.accessibleName.isNotBlank())
            }

            page.disposeUIResources()
        }
    }

    @Test
    fun `select style remains enabled with a project-only data context`() {
        runInEdtAndWait {
            val action = SelectCommitStyleAction()
            val data = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, fixture.project)
                .build()
            val actionEvent = event(action, data, "Keymap", ActionUiKind.NONE)

            action.update(actionEvent)

            assertTrue(actionEvent.presentation.isEnabled)
        }
    }

    @Test
    fun `provider deletion does not overwrite the next profile`() {
        runInEdtAndWait {
            val service = CommitMessageSettingsService.getInstance()
            service.replaceState(service.state.deepCopy().apply {
                profiles = mutableListOf(
                    LlmProfile(id = "profile-a", name = "A", baseUrl = "https://a.example/v1"),
                    LlmProfile(id = "profile-b", name = "B", baseUrl = "https://b.example/v1"),
                )
                activeProfileId = "profile-a"
            })
            val page = CommitProvidersConfigurable()
            val root = page.createComponent()
            val table = descendants(root).filterIsInstance<JBTable>().single { it.columnCount == 4 }
            table.setRowSelectionInterval(0, 0)
            requireNotNull(ToolbarDecorator.findRemoveButton(root)).actionPerformed(mockk(relaxed = true))
            page.apply()

            val remaining = service.state.profiles.single()
            assertEquals("profile-b", remaining.id)
            assertEquals("B", remaining.name)
            assertEquals("https://b.example/v1", remaining.baseUrl)
            page.disposeUIResources()
        }
    }

    @Test
    fun `type move preserves ids and descriptions`() {
        runInEdtAndWait {
            val service = CommitMessageSettingsService.getInstance()
            service.replaceState(service.state.deepCopy().apply {
                types = mutableListOf(
                    CommitTypeDefinition("alpha", "Alpha description"),
                    CommitTypeDefinition("beta", "Beta description"),
                    CommitTypeDefinition("gamma", "Gamma description"),
                )
            })
            val page = CommitTemplatesConfigurable()
            val root = page.createComponent()
            val table = descendants(root).filterIsInstance<JBTable>().single { it.columnCount == 2 }
            table.setRowSelectionInterval(1, 1)
            page.moveSelectedTypeForTest(-1)
            page.apply()

            assertEquals(
                listOf("beta" to "Beta description", "alpha" to "Alpha description", "gamma" to "Gamma description"),
                service.state.types.map { it.id to it.description },
            )
            page.disposeUIResources()
        }
    }

    @Test
    fun `template settings can recreate editors after disposal`() {
        runInEdtAndWait {
            val page = CommitTemplatesConfigurable()
            assertNotNull(page.createComponent())
            page.disposeUIResources()
            assertNotNull(page.createComponent())
            page.reset()
            assertFalse(page.isModified)
            page.disposeUIResources()
        }
    }

    @Test
    fun `provider dialog keeps a wide API key field and clears its document on dispose`() {
        runInEdtAndWait {
            val sessionKey = "temporary-secret".toCharArray()
            val dialog = ProviderProfileDialog(
                ProviderProfileEditInput(
                    title = "Edit",
                    profile = LlmProfile(id = "profile-secret", name = "Secret profile"),
                    sessionKey = sessionKey,
                ),
                ProviderModelFetcher { _, _, _ -> ProviderModelRequest { } },
            )
            val providerRoot = dialog.componentForTest()
            val password = descendants(providerRoot).filterIsInstance<JBPasswordField>().single()
            assertEquals(42, password.columns)
            assertTrue(password.preferredSize.width >= 360)
            assertPasswordEquals("temporary-secret", password)
            dialog.disposeIfNeeded()
            val remainingPassword = password.password
            try {
                assertEquals(0, remainingPassword.size)
            } finally {
                remainingPassword.fill('\u0000')
            }

            val settingsPage = CommitMessageSettingsConfigurable()
            val settingsRoot = settingsPage.createComponent()
            val displayCombo = descendants(settingsRoot).filterIsInstance<JComboBox<*>>().single()
            assertEquals(CommitMessageBundle.message("settings.typeDisplay.combo"), displayCombo.getItemAt(0).toString())
            settingsPage.disposeUIResources()
        }
    }

    @Test
    fun `pending clear in the profile dialog passes an explicit empty key to model fetch`() {
        runInEdtAndWait {
            val receivedKey = AtomicReference<CharArray?>()
            val dialog = ProviderProfileDialog(
                ProviderProfileEditInput(
                    title = "Edit",
                    profile = LlmProfile(id = "profile-dialog-clear", name = "Clear in dialog"),
                    hasStoredKey = true,
                ),
                ProviderModelFetcher { _, key, completed ->
                    receivedKey.set(key?.copyOf())
                    completed(Result.failure(emohce.data.commitmessage.MissingApiKeyException()))
                    ProviderModelRequest { }
                },
            )
            val root = dialog.componentForTest()
            descendants(root).filterIsInstance<JButton>()
                .first { it.text == CommitMessageBundle.message("settings.providers.clearApiKey") }
                .doClick()
            descendants(root).filterIsInstance<JButton>()
                .first { it.text == CommitMessageBundle.message("settings.providers.fetchModels") }
                .doClick()

            val key = requireNotNull(receivedKey.get())
            try {
                assertEquals(0, key.size)
            } finally {
                key.fill('\u0000')
            }
            dialog.disposeIfNeeded()
        }
    }

    @Test
    fun `credential generation rotates without storing a key fingerprint`() {
        val passwordSafe = mockk<PasswordSafe>(relaxed = true)
        val stored = AtomicReference<Credentials?>()
        every { passwordSafe.get(any()) } answers { stored.get() }
        every { passwordSafe.getPassword(any()) } returns "stored-secret"
        every { passwordSafe.set(any(), any<Credentials>()) } answers {
            stored.set(secondArg())
        }
        val store = CommitMessageSecretStore(passwordSafe)
        val firstKey = "first-secret".toCharArray()
        val secondKey = "second-secret".toCharArray()
        try {
            store.setApiKey("credential-profile", firstKey)
            val firstGeneration = store.credentialGeneration("credential-profile")
            store.setApiKey("credential-profile", secondKey)
            val secondGeneration = store.credentialGeneration("credential-profile")
            val providerGeneration = CommitMessageCredentialAccess.transaction {
                store.credentialSnapshotWithinTransaction("credential-profile").generation
            }

            assertNotEquals(firstGeneration, secondGeneration)
            assertEquals(secondGeneration, providerGeneration)
            assertFalse(firstGeneration.contains("secret"))
            assertFalse(secondGeneration.contains("secret"))
        } finally {
            firstKey.fill('\u0000')
            secondKey.fill('\u0000')
        }
    }

    @Test
    fun `provider key remains masked and fetch models uses the unsaved session key`() {
        runInEdtAndWait {
            val service = CommitMessageSettingsService.getInstance()
            service.replaceState(service.state.deepCopy().apply {
                profiles = mutableListOf(
                    LlmProfile(
                        id = "profile-session-key",
                        name = "OpenRouter",
                        baseUrl = "https://openrouter.ai/api/v1",
                    ),
                )
                activeProfileId = "profile-session-key"
            })
            val passwordSafe = mockk<PasswordSafe>(relaxed = true)
            val apiKey = slot<String>()
            val client = mockk<LlmProviderClient>()
            every {
                client.fetchModels(any(), capture(apiKey), any(), any())
            } returns listOf("openai/gpt-5", "anthropic/claude-sonnet")
            var editCount = 0
            var reopenedSessionKey = ""
            val editor = ProviderProfileEditor { input, fetcher ->
                editCount += 1
                if (editCount > 1) {
                    reopenedSessionKey = input.sessionKey?.concatToString().orEmpty()
                    input.sessionKey?.fill('\u0000')
                    null
                } else {
                    val key = "session-secret".toCharArray()
                    val fetchKey = key.copyOf()
                    var loaded = emptyList<String>()
                    fetcher.fetch(input.profile, fetchKey) { result ->
                        loaded = result.getOrThrow()
                        fetchKey.fill('\u0000')
                    }
                    ProviderProfileEditResult(
                        profile = input.profile.copy(name = "or-czz", model = loaded.first()),
                        apiKey = key,
                        modelSuggestions = loaded,
                    )
                }
            }
            val page = CommitProvidersConfigurable(
                CommitMessageSecretStore(passwordSafe),
                client,
                ProviderSettingsRequestRunner { _, operation -> operation(EmptyProgressIndicator()) },
                profileEditor = editor,
            )
            val root = page.createComponent()
            val table = descendants(root).filterIsInstance<JBTable>().single { it.columnCount == 4 }
            table.setRowSelectionInterval(0, 0)
            page.editProfileForTest("profile-session-key")
            assertEquals(1, editCount)
            repeat(3) {
                assertTrue(page.isModified)
            }
            assertEquals("session-secret", apiKey.captured)
            assertEquals("or-czz", table.getValueAt(0, 0))
            page.editProfileForTest("profile-session-key")
            assertEquals("session-secret", reopenedSessionKey)

            page.apply()
            assertFalse(page.isModified)
            page.disposeUIResources()
        }
    }

    @Test
    fun `pending API key clear never falls back to the stored credential`() {
        runInEdtAndWait {
            val service = CommitMessageSettingsService.getInstance()
            service.replaceState(service.state.deepCopy().apply {
                profiles = mutableListOf(LlmProfile(id = "profile-clear", name = "Clear key"))
                activeProfileId = "profile-clear"
            })
            val passwordSafe = mockk<PasswordSafe>(relaxed = true)
            every { passwordSafe.getPassword(any()) } returns "stored-secret"
            val client = mockk<LlmProviderClient>(relaxed = true)
            var missingWarnings = 0
            val editor = ProviderProfileEditor { input, _ ->
                input.sessionKey?.fill('\u0000')
                ProviderProfileEditResult(profile = input.profile, clearApiKey = true)
            }
            val page = CommitProvidersConfigurable(
                CommitMessageSecretStore(passwordSafe),
                client,
                ProviderSettingsRequestRunner { _, operation -> operation(EmptyProgressIndicator()) },
                confirmApiKeyClear = { true },
                notifyMissingApiKey = { missingWarnings++ },
                profileEditor = editor,
            )
            val root = page.createComponent()
            val table = descendants(root).filterIsInstance<JBTable>().single { it.columnCount == 4 }
            table.setRowSelectionInterval(0, 0)
            page.editProfileForTest("profile-clear")
            assertTrue(page.isModified)
            descendants(root).filterIsInstance<JButton>()
                .first { it.text == CommitMessageBundle.message("settings.providers.test") }
                .doClick()

            assertEquals(1, missingWarnings)
            assertTrue(page.isModified)
            verify(exactly = 0) { client.fetchModels(any(), any(), any(), any()) }
            page.disposeUIResources()
        }
    }

    @Test
    fun `stale model response is ignored after endpoint changes in profile dialog`() {
        runInEdtAndWait {
            val queued = AtomicReference<((Result<List<String>>) -> Unit)?>()
            val dialog = ProviderProfileDialog(
                ProviderProfileEditInput(
                    title = "Edit",
                    profile = LlmProfile(id = "profile-stale", name = "Stale", baseUrl = "https://old.example/v1"),
                    sessionKey = "session-secret".toCharArray(),
                ),
                ProviderModelFetcher { _, _, completed ->
                    queued.set(completed)
                    ProviderModelRequest { }
                },
            )
            val root = dialog.componentForTest()
            val model = descendants(root).filterIsInstance<ComboBox<*>>().single { it.isEditable }
            val fetch = descendants(root).filterIsInstance<JButton>()
                .first { it.text == CommitMessageBundle.message("settings.providers.fetchModels") }

            fetch.doClick()
            descendants(root).filterIsInstance<javax.swing.JTextField>()
                .first { it.text == "https://old.example/v1" }
                .text = "https://new.example/v1"
            queued.get()!!(Result.success(listOf("stale/model")))

            assertEquals(0, model.itemCount)
            assertTrue(fetch.isEnabled)
            assertTrue(descendants(root).filterIsInstance<JBLabel>().any {
                it.text == CommitMessageBundle.message("settings.providers.staleModelsIgnored")
            })
            dialog.disposeIfNeeded()
        }
    }

    @Test
    fun `persisted model fetch rejects a profile changed by another settings instance`() {
        runInEdtAndWait {
            val profile = LlmProfile(
                id = "profile-concurrent-fetch",
                name = "Concurrent fetch",
                baseUrl = "https://old.example/v1",
            )
            val service = CommitMessageSettingsService.getInstance()
            service.replaceState(service.state.deepCopy().apply {
                profiles = mutableListOf(profile)
                activeProfileId = profile.id
            })
            val passwordSafe = mockk<PasswordSafe>(relaxed = true)
            every { passwordSafe.getPassword(any()) } returns "stored-secret"
            every { passwordSafe.get(any()) } returns Credentials("generation", "stored-secret")
            val provider = mockk<LlmProviderClient>(relaxed = true)
            val outcome = AtomicReference<Result<List<String>>?>()
            val page = CommitProvidersConfigurable(
                secretStore = CommitMessageSecretStore(passwordSafe),
                providerClient = provider,
                requestRunner = ProviderSettingsRequestRunner { _, operation -> operation(EmptyProgressIndicator()) },
                profileEditor = ProviderProfileEditor { input, fetcher ->
                    fetcher.fetch(input.profile, null) { outcome.set(it) }
                    input.sessionKey?.fill('\u0000')
                    null
                },
            )
            page.createComponent()
            service.replaceState(service.state.deepCopy().apply {
                profiles.single().baseUrl = "https://new.example/v1"
            })

            page.editProfileForTest(profile.id)

            assertTrue(outcome.get()?.exceptionOrNull() is emohce.data.commitmessage.StaleProviderProfileException)
            verify(exactly = 0) { provider.fetchModels(any(), any(), any(), any()) }
            page.disposeUIResources()
        }
    }

    @Test
    fun `cancelled model request does not update the profile dialog selector`() {
        runInEdtAndWait {
            val dialog = ProviderProfileDialog(
                ProviderProfileEditInput(
                    title = "Edit",
                    profile = LlmProfile(id = "profile-cancel", name = "Cancel"),
                    sessionKey = "session-secret".toCharArray(),
                ),
                ProviderModelFetcher { _, _, completed ->
                    completed(Result.failure(ProcessCanceledException()))
                    ProviderModelRequest { }
                },
            )
            val root = dialog.componentForTest()
            val model = descendants(root).filterIsInstance<ComboBox<*>>().single { it.isEditable }
            val fetch = descendants(root).filterIsInstance<JButton>()
                .first { it.text == CommitMessageBundle.message("settings.providers.fetchModels") }
            fetch.doClick()

            assertEquals(0, model.itemCount)
            assertTrue(fetch.isEnabled)
            dialog.disposeIfNeeded()
        }
    }

    @Test
    fun `fetch models button cancels the active request and ignores its late callback`() {
        runInEdtAndWait {
            val queued = AtomicReference<((Result<List<String>>) -> Unit)?>()
            var cancellations = 0
            val dialog = ProviderProfileDialog(
                ProviderProfileEditInput(
                    title = "Edit",
                    profile = LlmProfile(id = "profile-fetch-cancel", name = "Fetch cancel"),
                    sessionKey = "session-secret".toCharArray(),
                ),
                ProviderModelFetcher { _, _, completed ->
                    queued.set(completed)
                    ProviderModelRequest { cancellations += 1 }
                },
            )
            val root = dialog.componentForTest()
            val model = descendants(root).filterIsInstance<ComboBox<*>>().single { it.isEditable }
            val fetch = descendants(root).filterIsInstance<JButton>()
                .first { it.text == CommitMessageBundle.message("settings.providers.fetchModels") }

            fetch.doClick()
            assertEquals(CommitMessageBundle.message("settings.providers.cancelFetchModels"), fetch.text)
            fetch.doClick()
            assertEquals(1, cancellations)
            assertEquals(CommitMessageBundle.message("settings.providers.fetchModels"), fetch.text)
            queued.get()!!(Result.success(listOf("late/model")))

            assertEquals(0, model.itemCount)
            assertTrue(descendants(root).filterIsInstance<JBLabel>().any {
                it.text == CommitMessageBundle.message("settings.providers.testCancelled")
            })
            dialog.disposeIfNeeded()
        }
    }

    @Test
    fun `disposing the provider dialog cancels an active model request and ignores completion`() {
        runInEdtAndWait {
            val queued = AtomicReference<((Result<List<String>>) -> Unit)?>()
            var cancellations = 0
            val dialog = ProviderProfileDialog(
                ProviderProfileEditInput(
                    title = "Edit",
                    profile = LlmProfile(id = "profile-fetch-dispose", name = "Fetch dispose"),
                    sessionKey = "session-secret".toCharArray(),
                ),
                ProviderModelFetcher { _, _, completed ->
                    queued.set(completed)
                    ProviderModelRequest { cancellations += 1 }
                },
            )
            val root = dialog.componentForTest()
            val model = descendants(root).filterIsInstance<ComboBox<*>>().single { it.isEditable }
            val fetch = descendants(root).filterIsInstance<JButton>()
                .first { it.text == CommitMessageBundle.message("settings.providers.fetchModels") }

            fetch.doClick()
            dialog.disposeIfNeeded()
            assertEquals(1, cancellations)
            queued.get()!!(Result.success(listOf("late/model")))

            assertEquals(0, model.itemCount)
        }
    }

    @Test
    fun `provider apply failure preserves configuration and pending key`() {
        runInEdtAndWait {
            val service = CommitMessageSettingsService.getInstance()
            service.replaceState(service.state.deepCopy().apply {
                profiles = mutableListOf(LlmProfile(id = "profile-failure", name = "Failure profile"))
                activeProfileId = "profile-failure"
            })
            val before = service.state.deepCopy()
            val passwordSafe = mockk<PasswordSafe>(relaxed = true)
            every { passwordSafe.set(any(), any<Credentials>()) } throws IllegalStateException("store unavailable")
            var editCount = 0
            var reopenedSessionKey = ""
            val editor = ProviderProfileEditor { input, _ ->
                editCount += 1
                if (editCount == 1) {
                    ProviderProfileEditResult(input.profile, "new-secret".toCharArray())
                } else {
                    reopenedSessionKey = input.sessionKey?.concatToString().orEmpty()
                    input.sessionKey?.fill('\u0000')
                    null
                }
            }
            val page = CommitProvidersConfigurable(
                CommitMessageSecretStore(passwordSafe),
                profileEditor = editor,
            )
            val root = page.createComponent()
            val table = descendants(root).filterIsInstance<JBTable>().single { it.columnCount == 4 }
            table.setRowSelectionInterval(0, 0)
            page.editProfileForTest("profile-failure")
            assertEquals(1, editCount)

            assertThrows(ConfigurationException::class.java) { page.apply() }
            assertEquals(before, service.state)
            assertTrue(page.isModified)
            page.editProfileForTest("profile-failure")
            assertEquals("new-secret", reopenedSessionKey)
            page.disposeUIResources()
        }
    }

    @Test
    fun `test connection checks models then performs a minimal inference`() {
        runInEdtAndWait {
            val service = CommitMessageSettingsService.getInstance()
            service.replaceState(service.state.deepCopy().apply {
                profiles = mutableListOf(
                    LlmProfile(id = "profile-test", name = "Test", model = "test-model"),
                )
                activeProfileId = "profile-test"
            })
            val passwordSafe = mockk<PasswordSafe>(relaxed = true)
            every { passwordSafe.getPassword(any()) } returns "stored-secret"
            val client = mockk<LlmProviderClient>()
            val completionRequest = slot<LlmCompletionRequest>()
            val completionProfile = slot<LlmProfile>()
            every { client.fetchModels(any(), any(), any(), any()) } returns listOf("test-model")
            every {
                client.complete(capture(completionProfile), any(), capture(completionRequest), any(), any())
            } returns LlmCompletion("OK")
            val page = CommitProvidersConfigurable(
                CommitMessageSecretStore(passwordSafe),
                client,
                ProviderSettingsRequestRunner { _, operation -> operation(EmptyProgressIndicator()) },
            )
            val root = page.createComponent()

            descendants(root).filterIsInstance<JButton>()
                .first { it.text == CommitMessageBundle.message("settings.providers.test") }
                .doClick()

            assertEquals(8, completionRequest.captured.maxOutputTokens)
            assertFalse(completionRequest.captured.streaming)
            assertFalse(completionRequest.captured.reasoningCompatibility)
            assertTrue(completionRequest.captured.userPrompt.contains("OK only"))
            assertEquals(0.0, completionProfile.captured.temperature)
            assertFalse(completionProfile.captured.streaming)
            assertFalse(completionProfile.captured.reasoningCompatibility)
            verifyOrder {
                client.fetchModels(any(), any(), any(), any())
                client.complete(any(), any(), any(), any(), any())
            }
            assertTrue(descendants(root).filterIsInstance<JBLabel>().any {
                it.text.contains("ms") || it.text.contains("毫秒")
            })
            page.disposeUIResources()
        }
    }

    @Test
    fun `test connection with a blank model stops after models and asks for selection`() {
        runInEdtAndWait {
            val service = CommitMessageSettingsService.getInstance()
            service.replaceState(service.state.deepCopy().apply {
                profiles = mutableListOf(LlmProfile(id = "profile-models-only", name = "Models only", model = ""))
                activeProfileId = "profile-models-only"
            })
            val passwordSafe = mockk<PasswordSafe>(relaxed = true)
            every { passwordSafe.getPassword(any()) } returns "stored-secret"
            val client = mockk<LlmProviderClient>()
            every { client.fetchModels(any(), any(), any(), any()) } returns listOf("model-a", "model-b")
            val page = CommitProvidersConfigurable(
                CommitMessageSecretStore(passwordSafe),
                client,
                ProviderSettingsRequestRunner { _, operation -> operation(EmptyProgressIndicator()) },
            )
            val root = page.createComponent()

            descendants(root).filterIsInstance<JButton>()
                .first { it.text == CommitMessageBundle.message("settings.providers.test") }
                .doClick()

            verify(exactly = 1) { client.fetchModels(any(), any(), any(), any()) }
            verify(exactly = 0) { client.complete(any(), any(), any(), any(), any()) }
            assertTrue(descendants(root).filterIsInstance<JBLabel>().any {
                it.text.contains("Select") || it.text.contains("选择")
            })
            page.disposeUIResources()
        }
    }

    @Test
    fun `test connection button cancels a queued request before provider access`() {
        runInEdtAndWait {
            val service = CommitMessageSettingsService.getInstance()
            service.replaceState(service.state.deepCopy().apply {
                profiles = mutableListOf(LlmProfile(id = "profile-cancel-test", name = "Cancel test", model = "model"))
                activeProfileId = "profile-cancel-test"
            })
            val passwordSafe = mockk<PasswordSafe>(relaxed = true)
            every { passwordSafe.getPassword(any()) } returns "stored-secret"
            val client = mockk<LlmProviderClient>(relaxed = true)
            val queued = AtomicReference<((ProgressIndicator) -> Unit)?>()
            val page = CommitProvidersConfigurable(
                CommitMessageSecretStore(passwordSafe),
                client,
                ProviderSettingsRequestRunner { _, operation -> queued.set(operation) },
            )
            val root = page.createComponent()
            val testButton = descendants(root).filterIsInstance<JButton>()
                .first { it.text == CommitMessageBundle.message("settings.providers.test") }

            testButton.doClick()
            assertEquals(CommitMessageBundle.message("settings.providers.cancelTest"), testButton.text)
            testButton.doClick()
            assertEquals(CommitMessageBundle.message("settings.providers.test"), testButton.text)
            queued.get()!!(EmptyProgressIndicator())

            verify(exactly = 0) { client.fetchModels(any(), any(), any(), any()) }
            verify(exactly = 0) { client.complete(any(), any(), any(), any(), any()) }
            assertTrue(descendants(root).filterIsInstance<JBLabel>().any {
                it.text == CommitMessageBundle.message("settings.providers.testCancelled")
            })
            page.disposeUIResources()
        }
    }

    @Test
    fun `restarting a queued connection test keeps the cancelled task away from the provider`() {
        runInEdtAndWait {
            val service = CommitMessageSettingsService.getInstance()
            service.replaceState(service.state.deepCopy().apply {
                profiles = mutableListOf(
                    LlmProfile(id = "profile-restart-test", name = "Restart test", model = "model"),
                )
                activeProfileId = "profile-restart-test"
            })
            val passwordSafe = mockk<PasswordSafe>(relaxed = true)
            every { passwordSafe.getPassword(any()) } returns "stored-secret"
            val client = mockk<LlmProviderClient>()
            every { client.fetchModels(any(), any(), any(), any()) } returns listOf("model")
            every { client.complete(any(), any(), any(), any(), any()) } returns LlmCompletion("OK")
            val queued = mutableListOf<(ProgressIndicator) -> Unit>()
            val page = CommitProvidersConfigurable(
                CommitMessageSecretStore(passwordSafe),
                client,
                ProviderSettingsRequestRunner { _, operation -> queued += operation },
            )
            val root = page.createComponent()
            val testButton = descendants(root).filterIsInstance<JButton>()
                .first { it.text == CommitMessageBundle.message("settings.providers.test") }

            testButton.doClick()
            testButton.doClick()
            testButton.doClick()
            assertEquals(2, queued.size)

            val cancelledIndicator = EmptyProgressIndicator()
            queued[0](cancelledIndicator)
            assertTrue(cancelledIndicator.isCanceled)
            verify(exactly = 0) { client.fetchModels(any(), any(), any(), any()) }
            verify(exactly = 0) { client.complete(any(), any(), any(), any(), any()) }

            queued[1](EmptyProgressIndicator())
            verify(exactly = 1) { client.fetchModels(any(), any(), any(), any()) }
            verify(exactly = 1) { client.complete(any(), any(), any(), any(), any()) }
            assertTrue(descendants(root).filterIsInstance<JBLabel>().any {
                it.text.contains("ms") || it.text.contains("毫秒")
            })
            page.disposeUIResources()
        }
    }

    @Test
    fun `cancelling an in-flight connection test cancels its indicator and never completes`() {
        runInEdtAndWait {
            val service = CommitMessageSettingsService.getInstance()
            service.replaceState(service.state.deepCopy().apply {
                profiles = mutableListOf(
                    LlmProfile(id = "profile-in-flight", name = "In flight", model = "model"),
                )
                activeProfileId = "profile-in-flight"
            })
            val passwordSafe = mockk<PasswordSafe>(relaxed = true)
            every { passwordSafe.getPassword(any()) } returns "stored-secret"
            val client = mockk<LlmProviderClient>()
            val indicatorRef = AtomicReference<ProgressIndicator>()
            val buttonRef = AtomicReference<JButton>()
            every { client.fetchModels(any(), any(), any(), any()) } answers {
                buttonRef.get().doClick()
                listOf("model")
            }
            val page = CommitProvidersConfigurable(
                CommitMessageSecretStore(passwordSafe),
                client,
                ProviderSettingsRequestRunner { _, operation ->
                    val indicator = EmptyProgressIndicator()
                    indicatorRef.set(indicator)
                    operation(indicator)
                },
            )
            val root = page.createComponent()
            val testButton = descendants(root).filterIsInstance<JButton>()
                .first { it.text == CommitMessageBundle.message("settings.providers.test") }
            buttonRef.set(testButton)

            testButton.doClick()

            assertTrue(indicatorRef.get().isCanceled)
            verify(exactly = 1) { client.fetchModels(any(), any(), any(), any()) }
            verify(exactly = 0) { client.complete(any(), any(), any(), any(), any()) }
            assertEquals(CommitMessageBundle.message("settings.providers.test"), testButton.text)
            assertTrue(descendants(root).filterIsInstance<JBLabel>().any {
                it.text == CommitMessageBundle.message("settings.providers.testCancelled")
            })
            page.disposeUIResources()
        }
    }

    private fun commitContext(text: String) = commitContext(EditorFactory.getInstance().createDocument(text))

    private fun commitContext(document: com.intellij.openapi.editor.Document, changes: Array<Change> = emptyArray()) =
        SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, fixture.project)
            .add(VcsDataKeys.COMMIT_MESSAGE_CONTROL, mockk<CommitMessageI>(relaxed = true))
            .add(VcsDataKeys.COMMIT_MESSAGE_DOCUMENT, document)
            .add(VcsDataKeys.CHANGES, changes)
            .build()

    private fun event(
        action: AnAction,
        context: com.intellij.openapi.actionSystem.DataContext,
        place: String,
        uiKind: ActionUiKind,
    ): AnActionEvent = AnActionEvent.createEvent(
        action,
        context,
        action.templatePresentation.clone(),
        place,
        uiKind,
        null,
    )

    private fun descendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        if (component is Container) {
            component.components.forEach { child -> yieldAll(descendants(child)) }
        }
    }

    private fun assertPasswordEquals(expected: String, field: JBPasswordField) {
        val password = field.password
        try {
            assertEquals(expected, password.concatToString())
        } finally {
            password.fill('\u0000')
        }
    }

    private fun setPrivateField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun getPrivateField(target: Any, name: String): Any = target.javaClass.getDeclaredField(name).let { field ->
        field.isAccessible = true
        requireNotNull(field.get(target))
    }
}
