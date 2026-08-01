package emohce.presentation.environmentaction

import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import com.intellij.openapi.vcs.CommitMessageI
import emohce.data.environmentaction.EnvironmentActionProjectStateService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import javax.swing.JPanel

class EnvironmentActionsDescriptorTest {
    @Test
    fun `descriptor keeps stable slots under the existing tools group and registers native commit provider`() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(Path.of("src/main/resources/META-INF/plugin.xml").toFile())
        val groups = document.getElementsByTagName("group").asElements()
        val environmentGroup = groups.single { it.getAttribute("id") == "EzCodeMarks.EnvironmentActions" }
        val toolsGroup = groups.single { it.getAttribute("id") == "EzCodeMarks.ToolsGroup" }
        val slotIds = environmentGroup.childNodes.asElements()
            .filter { it.tagName == "action" }
            .map { it.getAttribute("id") }

        assertEquals((1..10).map { "EzCodeMarks.EnvironmentAction.Slot$it" }, slotIds)
        assertEquals(0, environmentGroup.getElementsByTagName("add-to-group").length)
        assertEquals(
            1,
            toolsGroup.childNodes.asElements().count {
                it.tagName == "reference" && it.getAttribute("ref") == "EzCodeMarks.EnvironmentActions"
            },
        )

        val provider = document.getElementsByTagName("vcs.commitMessageProvider").asElements().single()
        assertEquals(
            "emohce.presentation.environmentaction.EnvironmentCommitMessageProvider",
            provider.getAttribute("implementation"),
        )
        assertEquals("first", provider.getAttribute("order"))

        val configurables = document.getElementsByTagName("applicationConfigurable").asElements()
            .associateBy { it.getAttribute("id") }
        assertNotNull(configurables["emohce.settings.environmentActions"])
        assertEquals(
            "emohce.presentation.commitmessage.settings.CodexSettingsConfigurable",
            configurables.getValue("emohce.settings.commitMessage.codex").getAttribute("instance"),
        )
    }

    @Test
    fun `environment project selection is explicitly non roaming`() {
        val state = EnvironmentActionProjectStateService::class.java.getAnnotation(State::class.java)

        assertEquals(RoamingType.DISABLED, state.storages.single().roamingType)
        assertTrue(state.storages.single().value.contains("workspace", ignoreCase = true))
    }

    @Test
    fun `all Environment Action locales contain the same keys`() {
        val baseKeys = loadBundle("").stringPropertyNames()

        listOf("_zh_CN", "_ja", "_ko").forEach { suffix ->
            assertEquals(baseKeys, loadBundle(suffix).stringPropertyNames(), "Bundle key drift in $suffix")
        }
    }

    @Test
    fun `environment implementation contains no raw git commit side effect or legacy dialog`() {
        val roots = listOf(
            Path.of("src/main/kotlin/emohce/data/environmentaction"),
            Path.of("src/main/kotlin/emohce/presentation/environmentaction"),
        )
        val source = roots.flatMap { root ->
            Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .map(Files::readString)
                    .toList()
            }
        }.joinToString("\n")

        assertFalse(source.contains("git add --all"))
        assertFalse(source.contains("git commit -m"))
        assertFalse(source.contains("CodexSettingsDialog"))
        assertFalse(
            Files.readString(Path.of("src/main/kotlin/emohce/presentation/environmentaction/EnvironmentActionsPanel.kt"))
                .contains("Messages.showDialog("),
        )
    }

    @Test
    fun `tool window opens settings by configurable class instead of treating ids as display names`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/emohce/presentation/environmentaction/EnvironmentActionsPanel.kt"),
        )

        assertTrue(source.contains("EnvironmentActionsConfigurable::class.java"))
        assertTrue(source.contains("CodexSettingsConfigurable::class.java"))
        assertFalse(source.contains("showSettingsDialog(project, \"emohce.settings."))
    }

    @Test
    fun `switching Environment requires a new conversation without changing the active session`() {
        assertFalse(EnvironmentActionsPanel.requiresNewConversation(false, null, "environment-b"))
        assertFalse(EnvironmentActionsPanel.requiresNewConversation(true, "environment-a", "environment-a"))
        assertTrue(EnvironmentActionsPanel.requiresNewConversation(true, "environment-a", "environment-b"))
    }

    @Test
    fun `native Commit message preparation handles empty replace append and cancel`() {
        assertEquals(
            "feat: prepared",
            preparedCommitMessageText("", "feat: prepared", PreparedCommitMessageChoice.CANCEL),
        )
        assertEquals(
            "feat: prepared",
            preparedCommitMessageText("old", "feat: prepared", PreparedCommitMessageChoice.REPLACE),
        )
        assertEquals(
            "old\n\nfeat: prepared",
            preparedCommitMessageText("old\n", "feat: prepared", PreparedCommitMessageChoice.APPEND),
        )
        assertEquals(
            null,
            preparedCommitMessageText("old", "feat: prepared", PreparedCommitMessageChoice.CANCEL),
        )
    }

    @Test
    fun `native Commit launcher discovers a visible editor even when another tool window owns focus`() {
        val visibleWriter = object : JPanel(), CommitMessageI {
            override fun isShowing(): Boolean = true
            override fun setCommitMessage(currentDescription: String) = Unit
        }
        val hiddenWriter = object : JPanel(), CommitMessageI {
            override fun setCommitMessage(currentDescription: String) = Unit
        }
        val root = JPanel().apply {
            add(hiddenWriter)
            add(JPanel().apply { add(visibleWriter) })
        }

        assertSame(visibleWriter, findVisibleCommitMessageControl(root))
        assertNull(findVisibleCommitMessageControl(JPanel().apply { add(hiddenWriter) }))
    }

    private fun org.w3c.dom.NodeList.asElements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }

    private fun loadBundle(suffix: String): Properties = Properties().apply {
        Path.of("src/main/resources/messages/EnvironmentActionsBundle$suffix.properties")
            .toFile().inputStream().use(::load)
    }
}
