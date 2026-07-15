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
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.vcs.commit.CommitMessageUi
import com.intellij.vcs.commit.CommitWorkflowUi
import emohce.data.commitmessage.CommitMessageCoordinatorService
import emohce.data.commitmessage.CommitMessageSecretStore
import emohce.data.commitmessage.CommitMessageSettingsService
import emohce.data.commitmessage.CommitMessageSettingsState
import emohce.data.commitmessage.CommitProjectState
import emohce.data.commitmessage.CommitProjectStateService
import emohce.data.commitmessage.GitContextRequest
import emohce.data.commitmessage.IntelliJGitContextRepository
import emohce.domain.commitmessage.CommitActionKind
import emohce.domain.commitmessage.GitContextPolicy
import emohce.domain.commitmessage.CommitTypeDefinition
import emohce.domain.commitmessage.LlmProfile
import emohce.presentation.commitmessage.action.BaseCommitMessageAction
import emohce.presentation.commitmessage.action.CreateCommitMessageAction
import emohce.presentation.commitmessage.action.GenerateCommitMessageAction
import emohce.presentation.commitmessage.settings.CommitMessageSettingsConfigurable
import emohce.presentation.commitmessage.settings.CommitMessageKeymapSupport
import emohce.presentation.commitmessage.settings.CommitProjectDefaultsConfigurable
import emohce.presentation.commitmessage.settings.CommitProvidersConfigurable
import emohce.presentation.commitmessage.settings.CommitTemplatesConfigurable
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.swing.KeyStroke
import javax.swing.JButton
import javax.swing.JComboBox
import java.awt.Component
import java.awt.Container

class CommitMessagePlatformIntegrationTest {
    private lateinit var fixture: CodeInsightTestFixture
    private lateinit var originalSettings: CommitMessageSettingsState
    private lateinit var originalProjectState: CommitProjectState

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
            draft = CommitProjectStateService.getInstance(fixture.project).state.draft?.copy(),
        )
    }

    @AfterEach
    fun tearDown() {
        runInEdtAndWait {
            CommitMessageSettingsService.getInstance().replaceState(originalSettings)
            CommitProjectStateService.getInstance(fixture.project).loadState(originalProjectState)
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
    fun `mac keymap receives the declared commit message shortcuts`() {
        runInEdtAndWait {
            val createShortcut = KeyboardShortcut(KeyStroke.getKeyStroke("meta alt shift M"), null)
            val generateShortcut = KeyboardShortcut(KeyStroke.getKeyStroke("meta alt shift G"), null)
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
    fun `settings pages support clean lifecycle`() {
        runInEdtAndWait {
            val pages = listOf(
                CommitMessageSettingsConfigurable(),
                CommitTemplatesConfigurable(),
                CommitProvidersConfigurable(),
                CommitProjectDefaultsConfigurable(fixture.project),
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
            val list = descendants(root).filterIsInstance<JBList<*>>().first { candidate ->
                candidate.model.size > 0 && candidate.model.getElementAt(0) is LlmProfile
            }
            list.selectedIndex = 0
            descendants(root).filterIsInstance<JButton>()
                .first { it.text == CommitMessageBundle.message("settings.providers.delete") }
                .doClick()
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
            val list = descendants(root).filterIsInstance<JBList<*>>().first { candidate ->
                candidate.model.size > 0 && candidate.model.getElementAt(0) is CommitTypeDefinition
            }
            list.selectedIndex = 1
            descendants(root).filterIsInstance<JButton>()
                .first { it.text == CommitMessageBundle.message("settings.types.up") }
                .doClick()
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
    fun `provider disposal clears password document and type display is localized`() {
        runInEdtAndWait {
            val service = CommitMessageSettingsService.getInstance()
            service.replaceState(service.state.deepCopy().apply {
                profiles = mutableListOf(LlmProfile(id = "profile-secret", name = "Secret profile"))
                activeProfileId = "profile-secret"
            })
            val providerPage = CommitProvidersConfigurable()
            val providerRoot = providerPage.createComponent()
            val password = descendants(providerRoot).filterIsInstance<JBPasswordField>().single()
            password.text = "temporary-secret"
            providerPage.disposeUIResources()
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
            val page = CommitProvidersConfigurable(CommitMessageSecretStore(passwordSafe))
            val root = page.createComponent()
            descendants(root).filterIsInstance<JBPasswordField>().single().text = "new-secret"

            assertThrows(ConfigurationException::class.java) { page.apply() }
            assertEquals(before, service.state)
            assertTrue(page.isModified)
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
}
