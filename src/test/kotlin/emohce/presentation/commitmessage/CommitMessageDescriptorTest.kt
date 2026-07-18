package emohce.presentation.commitmessage

import com.intellij.openapi.components.State
import emohce.data.commitmessage.CommitMessageSettingsState
import emohce.data.commitmessage.CommitProjectSharedSettingsService
import emohce.data.commitmessage.CommitProjectSharedSettingsState
import emohce.data.commitmessage.CommitProjectState
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
        assertEquals(
            setOf("control alt X"),
            generateShortcuts.map { it.getAttribute("first-keystroke") }.toSet(),
        )
        assertEquals(0, actions[2].getElementsByTagName("keyboard-shortcut").length)
        assertEquals(0, actions[3].getElementsByTagName("keyboard-shortcut").length)
        val selectStyle = document.getElementsByTagName("action").asElements()
            .single { it.getAttribute("id") == "EzCodeMarks.CommitMessage.SelectStyle" }
        assertEquals(0, selectStyle.getElementsByTagName("keyboard-shortcut").length)
        assertTrue(selectStyle.getElementsByTagName("add-to-group").asElements().any {
            it.getAttribute("group-id") == "VcsActions.KeymapGroup"
        })
        val toolsGroup = document.getElementsByTagName("group").asElements()
            .single { it.getAttribute("id") == "EzCodeMarks.CommitMessage.Tools" }
        val styleReferences = toolsGroup.childNodes.asElements()
            .filter { it.tagName == "reference" && it.getAttribute("ref") == selectStyle.getAttribute("id") }
        assertEquals(1, styleReferences.size)
        assertEquals(4, actions.size)
    }

    @Test
    fun `all locales contain every bundle key`() {
        val baseKeys = loadBundle("").stringPropertyNames()
        listOf("_zh_CN", "_ja", "_ko").forEach { suffix ->
            assertEquals(baseKeys, loadBundle(suffix).stringPropertyNames(), "Bundle key drift in $suffix")
        }
    }

    @Test
    fun `project private and shared settings pages have stable project scope registration`() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(Path.of("src/main/resources/META-INF/plugin.xml").toFile())
        val projectPages = document.getElementsByTagName("projectConfigurable").asElements()
            .associateBy { it.getAttribute("id") }

        assertEquals(
            "emohce.presentation.commitmessage.settings.CommitProjectProvidersConfigurable",
            projectPages.getValue("emohce.settings.commitMessage.projectProviders").getAttribute("instance"),
        )
        assertEquals(
            "emohce.presentation.commitmessage.settings.CommitProjectSharedConfigurable",
            projectPages.getValue("emohce.settings.commitMessage.projectShared").getAttribute("instance"),
        )
        listOf(
            "emohce.settings.commitMessage.templates",
            "emohce.settings.commitMessage.project",
            "emohce.settings.commitMessage.projectProviders",
            "emohce.settings.commitMessage.projectShared",
        ).forEach { id ->
            val page = projectPages.getValue(id)
            assertEquals("emohce.settings.commitMessage", page.getAttribute("parentId"))
            assertEquals("true", page.getAttribute("nonDefaultProject"))
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

    @Test
    fun `shared and private project persistence boundaries remain explicit and commit eligible`() {
        val sharedFields = CommitProjectSharedSettingsState::class.java.declaredFields.map { it.name }.toSet()
        val privateFields = CommitProjectState::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(sharedFields.containsAll(setOf("extraInstructions", "templates", "styles", "defaultTemplateId", "defaultStyleId")))
        assertFalse(sharedFields.any { it.contains("profile", ignoreCase = true) || it.contains("draft", ignoreCase = true) })
        assertTrue(privateFields.containsAll(setOf("profiles", "sourceConsentGrants", "draft", "credentialNamespace")))
        assertFalse(privateFields.any { it == "extraInstructions" || it == "templates" || it == "styles" })

        val state = CommitProjectSharedSettingsService::class.java.getAnnotation(State::class.java)
        assertEquals("ezCodeMarkCommitMessage.xml", state.storages.single().value)
        val ignoreRules = Path.of(".gitignore").toFile().readText()
        assertTrue(ignoreRules.contains(".idea/*"))
        assertTrue(ignoreRules.contains("!.idea/ezCodeMarkCommitMessage.xml"))
    }

    private fun org.w3c.dom.NodeList.asElements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }

    private fun loadBundle(suffix: String): Properties = Properties().apply {
        Path.of("src/main/resources/messages/CommitMessageBundle$suffix.properties")
            .toFile().inputStream().use(::load)
    }
}
