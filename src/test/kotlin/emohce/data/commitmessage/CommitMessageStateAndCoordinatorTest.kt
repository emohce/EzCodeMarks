package emohce.data.commitmessage

import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.openapi.util.JDOMUtil
import emohce.domain.commitmessage.CommitActionKind
import emohce.domain.commitmessage.CommitMessageDefaults
import emohce.domain.commitmessage.LlmProfile
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommitMessageStateAndCoordinatorTest {
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

        assertEquals(1, service.state.schemaVersion)
        assertNotNull(service.template(CommitMessageDefaults.DEFAULT_TEMPLATE_ID))
        assertEquals(profile.id, service.state.activeProfileId)
        assertTrue(service.state.types.isNotEmpty())
    }

    @Test
    fun `project template resolution falls back to global default`() {
        val settings = CommitMessageSettingsService()
        val projectState = CommitProjectStateService()
        projectState.loadState(CommitProjectState(templateId = "missing"))

        assertEquals(CommitMessageDefaults.DEFAULT_TEMPLATE_ID, projectState.resolveTemplate(settings).id)
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
}
