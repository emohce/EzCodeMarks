package emohce.presentation.environmentaction

import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import emohce.data.environmentaction.EnvironmentActionSettingsService
import emohce.data.environmentaction.EnvironmentActionSettingsState
import emohce.data.environmentaction.EnvironmentActionStore
import emohce.data.environmentaction.EnvironmentDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Component
import java.awt.Container
import java.nio.file.Path

class EnvironmentActionsConfigurableTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `reset discards edits while apply persists a deep-copied settings snapshot`() {
        runInEdtAndWait {
            val service = serviceWithEnvironment()
            val configurable = EnvironmentActionsConfigurable(service)
            val root = configurable.createComponent()
            val nameField = descendants(root).filterIsInstance<JBTextField>().single { it.text == "Local" }

            nameField.text = "Discarded"
            assertTrue(configurable.isModified())
            configurable.reset()
            assertEquals("Local", nameField.text)
            assertFalse(configurable.isModified())
            assertEquals("Local", service.snapshot().environments.single().name)

            nameField.text = "Applied"
            configurable.apply()
            assertEquals("Applied", service.snapshot().environments.single().name)
            assertFalse(configurable.isModified())
            configurable.disposeUIResources()
        }
    }

    @Test
    fun `apply rejects secret-like environment variables`() {
        runInEdtAndWait {
            val service = serviceWithEnvironment()
            val configurable = EnvironmentActionsConfigurable(service)
            val root = configurable.createComponent()
            val variables = descendants(root).filterIsInstance<JBTextArea>().single()
            variables.text = "API_TOKEN=plain-text"

            assertThrows(ConfigurationException::class.java) { configurable.apply() }
            assertTrue(service.snapshot().environments.single().variables.isEmpty())
            configurable.disposeUIResources()
        }
    }

    private fun serviceWithEnvironment(): EnvironmentActionSettingsService {
        val service = EnvironmentActionSettingsService(EnvironmentActionStore(tempDir.resolve("settings.json")))
        val base = service.snapshot()
        service.replaceState(
            base,
            EnvironmentActionSettingsState(
                revision = base.revision,
                environments = mutableListOf(EnvironmentDefinition(name = "Local")),
            ),
        )
        return service
    }

    private fun descendants(component: Component): List<Component> = buildList {
        add(component)
        if (component is Container) component.components.forEach { addAll(descendants(it)) }
    }
}
