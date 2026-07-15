package emohce.presentation.commitmessage

import emohce.data.commitmessage.CommitMessageSettingsState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.nio.file.Path
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

class CommitMessageDescriptorTest {
    @Test
    fun `descriptor has stable action order groups and default shortcuts`() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(Path.of("src/main/resources/META-INF/plugin.xml").toFile())
        val group = document.getElementsByTagName("group").asElements()
            .first { it.getAttribute("id") == "EzCodeMarks.CommitMessage.Actions" }
        val actions = group.childNodes.asElements().filter { it.tagName == "action" }

        assertEquals("false", group.getAttribute("popup"))
        assertEquals(
            listOf(
                "EzCodeMarks.CommitMessage.Create",
                "EzCodeMarks.CommitMessage.Generate",
                "EzCodeMarks.CommitMessage.GenerateWithContext",
                "EzCodeMarks.CommitMessage.Format",
            ),
            actions.map { it.getAttribute("id") },
        )
        val groupTargets = group.getElementsByTagName("add-to-group").asElements()
        assertTrue(groupTargets.any {
            it.getAttribute("group-id") == "Vcs.MessageActionGroup" &&
                it.getAttribute("relative-to-action") == "Vcs.ShowMessageHistory"
        })
        assertTrue(groupTargets.any { it.getAttribute("group-id") == "VcsActions.KeymapGroup" })

        val createShortcuts = actions[0].getElementsByTagName("keyboard-shortcut").asElements()
        val generateShortcuts = actions[1].getElementsByTagName("keyboard-shortcut").asElements()
        val expectedKeymaps = setOf("\$default", "Mac OS X", "Mac OS X 10.5+")
        assertEquals(expectedKeymaps, createShortcuts.map { it.getAttribute("keymap") }.toSet())
        assertEquals(expectedKeymaps, generateShortcuts.map { it.getAttribute("keymap") }.toSet())
        assertEquals(0, actions[2].getElementsByTagName("keyboard-shortcut").length)
        assertEquals(0, actions[3].getElementsByTagName("keyboard-shortcut").length)
    }

    @Test
    fun `all locales contain every bundle key`() {
        val baseKeys = loadBundle("").stringPropertyNames()
        listOf("_zh_CN", "_ja", "_ko").forEach { suffix ->
            assertEquals(baseKeys, loadBundle(suffix).stringPropertyNames(), "Bundle key drift in $suffix")
        }
    }

    @Test
    fun `persistent state has no api key field`() {
        val names = CommitMessageSettingsState::class.java.declaredFields.map { it.name }
        assertFalse(names.any { it.contains("key", ignoreCase = true) })
        assertFalse(emohce.domain.commitmessage.LlmProfile::class.java.declaredFields.any {
            it.name.contains("apiKey", ignoreCase = true)
        })
    }

    private fun org.w3c.dom.NodeList.asElements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }

    private fun loadBundle(suffix: String): Properties = Properties().apply {
        Path.of("src/main/resources/messages/CommitMessageBundle$suffix.properties")
            .toFile().inputStream().use(::load)
    }
}
