package emohce.data.commitmessage

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import emohce.domain.commitmessage.CommitMessageDefaults
import emohce.domain.commitmessage.CommitTemplateDefinition
import emohce.domain.commitmessage.CommitTypeDefinition
import emohce.domain.commitmessage.CommitStyleDefinition
import emohce.domain.commitmessage.LlmProfile
import emohce.domain.commitmessage.TypeDisplayMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class CommitMessageSettingsState(
    var schemaVersion: Int = 3,
    var portableRevision: String = "",
    var portableAncestors: MutableList<String> = mutableListOf(),
    var showCreateInToolbar: Boolean = true,
    var showGenerateInToolbar: Boolean = true,
    var showGenerateWithContextInToolbar: Boolean = true,
    var showFormatInToolbar: Boolean = false,
    var showType: Boolean = true,
    var showScope: Boolean = true,
    var showBody: Boolean = true,
    var showBreakingChanges: Boolean = true,
    var showCloses: Boolean = true,
    var showSkipCi: Boolean = true,
    var typeDisplayMode: TypeDisplayMode = TypeDisplayMode.COMBO,
    var defaultSkipCi: Boolean = false,
    var smartEcho: Boolean = false,
    var previewAiResultBeforeApply: Boolean = false,
    var llmTemperature: Double = 0.5,
    var llmResponseLanguage: String = "English",
    var llmStreaming: Boolean = true,
    var persistentExtraInstructions: String = "",
    var defaultTemplateId: String = CommitMessageDefaults.DEFAULT_TEMPLATE_ID,
    var templates: MutableList<CommitTemplateDefinition> = CommitMessageDefaults.templates(),
    var types: MutableList<CommitTypeDefinition> = CommitMessageDefaults.types(),
    var defaultStyleId: String = CommitMessageDefaults.STANDARD_STYLE_ID,
    var styles: MutableList<CommitStyleDefinition> = CommitMessageDefaults.styles(),
    var activeProfileId: String = CommitMessageDefaults.DEFAULT_PROFILE_ID,
    var profiles: MutableList<LlmProfile> = mutableListOf(CommitMessageDefaults.defaultProfile()),
) {
    fun deepCopy(): CommitMessageSettingsState = copy(
        templates = templates.map { it.copy() }.toMutableList(),
        types = types.map { it.copy() }.toMutableList(),
        styles = styles.map { it.copy() }.toMutableList(),
        profiles = profiles.map { it.copy() }.toMutableList(),
        portableAncestors = portableAncestors.toMutableList(),
    )

    fun normalize() {
        val previousSchema = schemaVersion
        require(previousSchema in 0..CURRENT_SCHEMA_VERSION) { "Unsupported commit-message settings schema" }
        if (previousSchema < 2) migrateV1Defaults()
        schemaVersion = CURRENT_SCHEMA_VERSION
        if (templates.none { it.id == CommitMessageDefaults.DEFAULT_TEMPLATE_ID }) {
            templates.add(0, CommitMessageDefaults.templates().single())
        }
        templates.first { it.id == CommitMessageDefaults.DEFAULT_TEMPLATE_ID }.apply {
            name = "Default"
            builtIn = true
            if (content.isBlank()) content = CommitMessageDefaults.defaultTemplateContent
        }
        if (types.isEmpty()) types = CommitMessageDefaults.types()
        if (templates.none { it.id == defaultTemplateId }) {
            defaultTemplateId = CommitMessageDefaults.DEFAULT_TEMPLATE_ID
        }
        ensureBuiltInStyles()
        if (styles.none { it.id == defaultStyleId }) defaultStyleId = CommitMessageDefaults.STANDARD_STYLE_ID
        profiles = profiles.filterNot { it.id.startsWith(RESERVED_PROJECT_CREDENTIAL_PREFIX) }.toMutableList()
        if (profiles.isEmpty()) profiles.add(CommitMessageDefaults.defaultProfile())
        if (profiles.none { it.id == activeProfileId }) activeProfileId = profiles.firstOrNull()?.id.orEmpty()
        llmTemperature = llmTemperature.coerceIn(0.0, 2.0)
        llmResponseLanguage = llmResponseLanguage.trim().ifBlank { "English" }
        persistentExtraInstructions = persistentExtraInstructions.trim().take(MAX_PERSISTENT_INSTRUCTIONS)
        profiles.forEach {
            it.baseUrl = if (it.provider == emohce.domain.commitmessage.LlmProviderType.CHATGPT_CODEX) {
                ""
            } else {
                it.baseUrl.trim().trimEnd('/').takeIf(::isSafeProviderEndpoint).orEmpty()
            }
            it.reasoningEffort = it.reasoningEffort.trim()
            it.temperature = llmTemperature
            it.responseLanguage = llmResponseLanguage
            it.streaming = llmStreaming
        }
    }

    private fun migrateV1Defaults() {
        templates.firstOrNull { it.id == CommitMessageDefaults.DEFAULT_TEMPLATE_ID }?.let { builtIn ->
            if (builtIn.content == CommitMessageDefaults.legacyDefaultTemplateContent) {
                builtIn.content = CommitMessageDefaults.defaultTemplateContent
            }
        }
        val active = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
        if (active != null) {
            if (active.temperature != 0.2) llmTemperature = active.temperature
            llmResponseLanguage = active.responseLanguage.ifBlank { "English" }
            llmStreaming = active.streaming
        }
        val oldDescriptions = mapOf(
            "feat" to "Feature",
            "fix" to "Bug fix",
            "docs" to "Documentation",
            "style" to "Style",
            "refactor" to "Refactor",
            "perf" to "Performance",
            "test" to "Tests",
            "build" to "Build",
            "ci" to "Continuous integration",
            "chore" to "Chore",
            "revert" to "Revert",
        )
        val restored = CommitMessageDefaults.types().associateBy { it.id }
        types.forEach { type ->
            if (oldDescriptions[type.id] == type.description) {
                type.description = restored[type.id]?.description ?: type.description
            }
        }
    }

    private fun ensureBuiltInStyles() {
        val defaults = CommitMessageDefaults.styles()
        defaults.forEachIndexed { index, default ->
            val existing = styles.firstOrNull { it.id == default.id }
            if (existing == null) {
                styles.add(index.coerceAtMost(styles.size), default)
            } else {
                existing.builtIn = true
                existing.name = default.name
                existing.description = default.description
                existing.prompt = default.prompt
                existing.templateContent = default.templateContent
            }
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 3
        const val MAX_PERSISTENT_INSTRUCTIONS: Int = 4_000
        private const val RESERVED_PROJECT_CREDENTIAL_PREFIX = "project:"
    }
}

@Service(Service.Level.APP)
@State(
    name = "EzCodeMarksCommitMessageSettings",
    storages = [Storage("ezCodeMarksCommitMessage.xml", roamingType = RoamingType.DEFAULT)],
    category = SettingsCategory.TOOLS,
)
class CommitMessageSettingsService internal constructor(
    private val portableStore: CommitMessagePortableStore?,
) : PersistentStateComponent<CommitMessageSettingsState> {
    constructor() : this(defaultPortableStore())

    private val stateLock = Any()
    @Volatile
    private var currentState = CommitMessageSettingsState()
    @Volatile
    private var roamingState = currentState
    @Volatile
    private var initialized = portableStore == null
    @Volatile
    private var pendingPortableConflict: CommitMessagePortableReconciliation.DivergenceConflict? = null
    @Volatile
    private var portableStoreFailure: String? = null
    @Volatile
    private var quarantinedPortableRevision: String? = null
    @Volatile
    private var unsupportedRoamingSchema = false

    init {
        synchronized(stateLock) {
            loadCommonAtStartupLocked()
        }
    }

    override fun getState(): CommitMessageSettingsState = roamingState

    override fun loadState(state: CommitMessageSettingsState) {
        val migrated = CommitMessageSettingsState()
        XmlSerializerUtil.copyBean(state, migrated)
        try {
            migrated.normalize()
        } catch (_: IllegalArgumentException) {
            synchronized(stateLock) {
                roamingState = state
                unsupportedRoamingSchema = true
                portableStoreFailure = "Roaming commit-message settings use an unsupported schema"
                initialized = true
            }
            return
        }
        synchronized(stateLock) {
            unsupportedRoamingSchema = false
            if (portableStore == null) {
                currentState = migrated
                roamingState = migrated
                initialized = true
            } else {
                reconcileLocked(
                    incoming = envelopeFromState(migrated),
                    ignoreUnversionedDefault = state.portableRevision.isBlank() &&
                        encodePortablePayload(migrated) == defaultPortablePayload(),
                )
                initialized = true
            }
        }
    }

    fun replaceState(state: CommitMessageSettingsState) {
        synchronized(stateLock) {
            val expectedRevision = state.portableRevision
            val cachedRevision = currentState.portableRevision
            val cachedPayload = encodePortablePayload(currentState)
            ensurePortableInitializedLocked()
            refreshFromCommonLocked()
            if (unsupportedRoamingSchema || quarantinedPortableRevision != null) {
                throw CommitMessageSettingsStoreException(
                    portableStoreFailure ?: "Portable commit-message settings are unavailable",
                )
            }
            if (pendingPortableConflict != null) throw CommitMessageSettingsConflictException()
            val upgradesInitialState = expectedRevision.isBlank() &&
                cachedRevision.isBlank() &&
                cachedPayload == encodePortablePayload(currentState)
            if (expectedRevision != currentState.portableRevision && !upgradesInitialState) {
                throw CommitMessageSettingsConflictException()
            }
            val replacement = state.deepCopy().apply { normalize() }
            if (portableStore == null) {
                currentState = replacement
                roamingState = replacement
                return
            }
            val predecessor = envelopeFromState(currentState)
            val replacementPayload = encodePortablePayload(replacement)
            if (replacementPayload == predecessor.payload) {
                replacement.portableRevision = predecessor.revision
                replacement.portableAncestors = predecessor.ancestors.toMutableList()
                currentState = replacement
                roamingState = replacement
                return
            }
            val successor = CommitMessagePortableEnvelope.successor(replacementPayload, predecessor)
            acceptEnvelopeLocked(writePortableLocked(successor, predecessor.revision))
        }
    }

    fun snapshot(refreshPortable: Boolean = true): CommitMessageSettingsState = synchronized(stateLock) {
        if (refreshPortable) {
            ensurePortableInitializedLocked()
            refreshFromCommonLocked()
        }
        currentState.deepCopy()
    }

    private fun activeProfile(): LlmProfile? =
        currentState.profiles.firstOrNull { it.id == currentState.activeProfileId }
            ?: currentState.profiles.firstOrNull()

    fun activeProfileSnapshot(): LlmProfile? = CommitMessageCredentialAccess.read {
        synchronized(stateLock) {
            ensurePortableInitializedLocked()
            refreshFromCommonLocked()
            activeProfile()?.copy(
                temperature = currentState.llmTemperature,
                responseLanguage = currentState.llmResponseLanguage,
                streaming = currentState.llmStreaming,
            )
        }
    }

    fun profileSnapshot(profileId: String): LlmProfile? = CommitMessageCredentialAccess.read {
        synchronized(stateLock) {
            ensurePortableInitializedLocked()
            refreshFromCommonLocked()
            currentState.profiles.firstOrNull { it.id == profileId }?.copy(
                temperature = currentState.llmTemperature,
                responseLanguage = currentState.llmResponseLanguage,
                streaming = currentState.llmStreaming,
            )
        }
    }

    fun grantSourceContextConsent(expected: LlmProfile): LlmProfile? = CommitMessageCredentialAccess.write {
        synchronized(stateLock) {
            ensurePortableInitializedLocked()
            refreshFromCommonLocked()
            val current = currentState.profiles.firstOrNull { it.id == expected.id } ?: return@synchronized null
            if (current.provider != expected.provider || normalizedEndpoint(current.baseUrl) != normalizedEndpoint(expected.baseUrl)) {
                return@synchronized null
            }
            SourceContextConsent.grant(current)
            current.copy()
        }
    }

    internal fun profile(profileId: String): LlmProfile? = synchronized(stateLock) {
        ensurePortableInitializedLocked()
        refreshFromCommonLocked()
        currentState.profiles.firstOrNull { it.id == profileId }
    }

    fun hasPortableConflict(): Boolean = pendingPortableConflict != null

    fun portableFailureMessage(): String? = portableStoreFailure

    fun resolvePortableConflict(useIncoming: Boolean) {
        synchronized(stateLock) {
            val conflict = pendingPortableConflict ?: return
            val selected = if (useIncoming) conflict.incoming else conflict.common
            val resolved = CommitMessagePortableEnvelope.successor(
                payload = selected.payload,
                common = conflict.common,
                incoming = conflict.incoming,
            )
            acceptEnvelopeLocked(writePortableLocked(resolved, conflict.common.revision))
            pendingPortableConflict = null
        }
    }

    fun template(id: String): CommitTemplateDefinition? = snapshot().templates.firstOrNull { it.id == id }

    fun defaultTemplate(): CommitTemplateDefinition {
        val snapshot = snapshot()
        return snapshot.templates.firstOrNull { it.id == snapshot.defaultTemplateId }
            ?: snapshot.templates.firstOrNull { it.id == CommitMessageDefaults.DEFAULT_TEMPLATE_ID }
            ?: CommitMessageDefaults.templates().single()
    }

    fun style(id: String): CommitStyleDefinition? = snapshot().styles.firstOrNull { it.id == id }

    fun defaultStyle(): CommitStyleDefinition {
        val snapshot = snapshot()
        return snapshot.styles.firstOrNull { it.id == snapshot.defaultStyleId }
            ?: snapshot.styles.firstOrNull { it.id == CommitMessageDefaults.STANDARD_STYLE_ID }
            ?: CommitMessageDefaults.standardStyle()
    }

    private fun normalizedEndpoint(value: String): String = value.trim().trimEnd('/')

    private fun ensurePortableInitializedLocked() {
        if (initialized) return
        val normalized = currentState.deepCopy().apply { normalize() }
        reconcileLocked(
            incoming = envelopeFromState(normalized),
            ignoreUnversionedDefault = currentState.portableRevision.isBlank() &&
                encodePortablePayload(normalized) == defaultPortablePayload(),
        )
        initialized = true
    }

    private fun loadCommonAtStartupLocked() {
        val store = portableStore ?: return
        try {
            when (val result = store.read()) {
                is CommitMessagePortableReadResult.Found -> {
                    acceptEnvelopeLocked(validateCommonEnvelopeLocked(result.envelope))
                    quarantinedPortableRevision = null
                    portableStoreFailure = null
                    initialized = true
                }
                CommitMessagePortableReadResult.Missing -> Unit
            }
        } catch (error: CommitMessagePortableStoreException) {
            portableStoreFailure = error.message
            initialized = true
        } catch (error: CommitMessageSettingsStoreException) {
            val revision = runCatching {
                (store.read() as? CommitMessagePortableReadResult.Found)?.envelope?.revision
            }.getOrNull()
            quarantinedPortableRevision = revision
            portableStoreFailure = error.message
            initialized = true
        }
    }

    private fun refreshFromCommonLocked() {
        val store = portableStore ?: return
        if (pendingPortableConflict != null) return
        val rawCommon = try {
            when (val result = store.read()) {
                is CommitMessagePortableReadResult.Found -> result.envelope
                CommitMessagePortableReadResult.Missing -> null
            }
        } catch (error: CommitMessagePortableStoreException) {
            portableStoreFailure = error.message
            return
        }
        val common = try {
            rawCommon?.let(::validateCommonEnvelopeLocked)
        } catch (error: CommitMessageSettingsStoreException) {
            quarantinedPortableRevision = rawCommon?.revision
            portableStoreFailure = error.message
            return
        }
        quarantinedPortableRevision = null
        portableStoreFailure = null
        val current = envelopeFromState(currentState)
        if (currentState.portableRevision.isBlank() && current.payload == defaultPortablePayload() && common != null) {
            acceptEnvelopeLocked(common)
            pendingPortableConflict = null
            return
        }
        when (val outcome = CommitMessagePortableReconciler.reconcile(common, current)) {
            is CommitMessagePortableReconciliation.EquivalentPayload -> {
                val accepted = if (common?.revision != outcome.converged.revision) {
                    writePortableLocked(outcome.converged, common?.revision)
                } else {
                    outcome.converged
                }
                acceptEnvelopeLocked(accepted)
                pendingPortableConflict = null
            }
            is CommitMessagePortableReconciliation.IncomingDescendant -> {
                acceptEnvelopeLocked(writePortableLocked(outcome.incoming, common?.revision))
                pendingPortableConflict = null
            }
            is CommitMessagePortableReconciliation.CommonDescendant -> {
                acceptEnvelopeLocked(outcome.common)
                pendingPortableConflict = null
            }
            is CommitMessagePortableReconciliation.DivergenceConflict -> {
                preserveConflictLocked(outcome)
            }
            is CommitMessagePortableReconciliation.MissingSide -> when (outcome.side) {
                CommitMessagePortableMissingSide.COMMON -> {
                    acceptEnvelopeLocked(writePortableLocked(current, expectedRevision = null))
                    pendingPortableConflict = null
                }
                CommitMessagePortableMissingSide.INCOMING -> {
                    outcome.available?.let(::acceptEnvelopeLocked)
                    pendingPortableConflict = null
                }
                CommitMessagePortableMissingSide.BOTH -> Unit
            }
        }
    }

    private fun reconcileLocked(
        incoming: CommitMessagePortableEnvelope,
        ignoreUnversionedDefault: Boolean = false,
    ) {
        val store = portableStore
        if (store == null) {
            acceptEnvelopeLocked(incoming)
            return
        }
        val rawCommon = try {
            when (val result = store.read()) {
                is CommitMessagePortableReadResult.Found -> result.envelope
                CommitMessagePortableReadResult.Missing -> null
            }
        } catch (error: CommitMessagePortableStoreException) {
            portableStoreFailure = error.message
            acceptEnvelopeLocked(incoming)
            return
        }
        val common = try {
            rawCommon?.let(::validateCommonEnvelopeLocked)
        } catch (error: CommitMessageSettingsStoreException) {
            quarantinedPortableRevision = rawCommon?.revision
            portableStoreFailure = error.message
            acceptEnvelopeLocked(incoming)
            return
        }
        quarantinedPortableRevision = null
        portableStoreFailure = null
        if (common != null && ignoreUnversionedDefault) {
            acceptEnvelopeLocked(common)
            pendingPortableConflict = null
            return
        }
        when (val outcome = CommitMessagePortableReconciler.reconcile(common, incoming)) {
            is CommitMessagePortableReconciliation.EquivalentPayload -> {
                acceptEnvelopeLocked(writePortableLocked(outcome.converged, common?.revision))
                pendingPortableConflict = null
            }
            is CommitMessagePortableReconciliation.IncomingDescendant -> {
                acceptEnvelopeLocked(writePortableLocked(outcome.incoming, common?.revision))
                pendingPortableConflict = null
            }
            is CommitMessagePortableReconciliation.CommonDescendant -> {
                acceptEnvelopeLocked(outcome.common)
                pendingPortableConflict = null
            }
            is CommitMessagePortableReconciliation.DivergenceConflict -> {
                preserveConflictLocked(outcome)
            }
            is CommitMessagePortableReconciliation.MissingSide -> when (outcome.side) {
                CommitMessagePortableMissingSide.COMMON -> {
                    acceptEnvelopeLocked(writePortableLocked(incoming, expectedRevision = null))
                    pendingPortableConflict = null
                }
                CommitMessagePortableMissingSide.INCOMING -> {
                    outcome.available?.let(::acceptEnvelopeLocked)
                    pendingPortableConflict = null
                }
                CommitMessagePortableMissingSide.BOTH -> {
                    acceptEnvelopeLocked(writePortableLocked(incoming, expectedRevision = null))
                    pendingPortableConflict = null
                }
            }
        }
    }

    private fun acceptEnvelopeLocked(envelope: CommitMessagePortableEnvelope) {
        val accepted = stateFromEnvelope(envelope)
        currentState = accepted
        roamingState = accepted
    }

    private fun preserveConflictLocked(conflict: CommitMessagePortableReconciliation.DivergenceConflict) {
        pendingPortableConflict = conflict
        currentState = stateFromEnvelope(conflict.common)
        roamingState = stateFromEnvelope(conflict.incoming)
    }

    private fun envelopeFromState(state: CommitMessageSettingsState): CommitMessagePortableEnvelope {
        val payload = encodePortablePayload(state)
        val restored = runCatching {
            CommitMessagePortableEnvelope(
                schemaVersion = PORTABLE_SCHEMA_VERSION,
                revision = state.portableRevision,
                ancestors = state.portableAncestors.toList(),
                payload = payload,
            )
        }.getOrNull()
        return restored ?: CommitMessagePortableEnvelope.create(payload, PORTABLE_SCHEMA_VERSION)
    }

    private fun stateFromEnvelope(envelope: CommitMessagePortableEnvelope): CommitMessageSettingsState {
        if (envelope.schemaVersion != PORTABLE_SCHEMA_VERSION) {
            throw CommitMessageSettingsStoreException("Portable commit-message settings use an unsupported schema")
        }
        val restored = try {
            PORTABLE_JSON.decodeFromString<CommitMessageSettingsState>(envelope.payload)
        } catch (_: Exception) {
            throw CommitMessageSettingsStoreException("Portable commit-message settings are invalid")
        }
        restored.portableRevision = envelope.revision
        restored.portableAncestors = envelope.ancestors.toMutableList()
        try {
            restored.normalize()
        } catch (_: IllegalArgumentException) {
            throw CommitMessageSettingsStoreException("Portable commit-message settings use an unsupported schema")
        }
        return restored
    }

    private fun validateCommonEnvelopeLocked(envelope: CommitMessagePortableEnvelope): CommitMessagePortableEnvelope {
        val restored = stateFromEnvelope(envelope)
        val canonicalPayload = encodePortablePayload(restored)
        if (canonicalPayload == envelope.payload) return envelope
        val migrated = CommitMessagePortableEnvelope.successor(canonicalPayload, envelope)
        return writePortableLocked(migrated, envelope.revision)
    }

    private fun encodePortablePayload(state: CommitMessageSettingsState): String = PORTABLE_JSON.encodeToString(
        state.deepCopy().apply {
            portableRevision = ""
            portableAncestors.clear()
        },
    )

    private fun defaultPortablePayload(): String = encodePortablePayload(
        CommitMessageSettingsState().apply { normalize() },
    )

    private fun writePortableLocked(
        envelope: CommitMessagePortableEnvelope,
        expectedRevision: String?,
    ): CommitMessagePortableEnvelope {
        val store = portableStore ?: return envelope
        var candidate = envelope
        var expected = expectedRevision
        repeat(MAX_CONDITIONAL_WRITE_ATTEMPTS) {
            val result = try {
                store.compareAndWrite(expected, candidate)
            } catch (error: CommitMessagePortableStoreException) {
                portableStoreFailure = error.message
                throw CommitMessageSettingsStoreException(error.message ?: "Portable settings write failed", error)
            }
            when (result) {
                is CommitMessagePortableConditionalWriteResult.Written -> {
                    portableStoreFailure = null
                    return candidate
                }
                is CommitMessagePortableConditionalWriteResult.RevisionMismatch -> {
                    val current = result.current
                    if (current == null) {
                        expected = null
                        return@repeat
                    }
                    try {
                        stateFromEnvelope(current)
                    } catch (error: CommitMessageSettingsStoreException) {
                        quarantinedPortableRevision = current.revision
                        portableStoreFailure = error.message
                        throw error
                    }
                    when (val reconciliation = CommitMessagePortableReconciler.reconcile(current, candidate)) {
                        is CommitMessagePortableReconciliation.EquivalentPayload -> {
                            candidate = reconciliation.converged
                            expected = current.revision
                        }
                        is CommitMessagePortableReconciliation.IncomingDescendant -> {
                            candidate = reconciliation.incoming
                            expected = current.revision
                        }
                        is CommitMessagePortableReconciliation.CommonDescendant -> {
                            portableStoreFailure = null
                            return reconciliation.common
                        }
                        is CommitMessagePortableReconciliation.DivergenceConflict -> {
                            preserveConflictLocked(reconciliation)
                            throw CommitMessageSettingsConflictException()
                        }
                        is CommitMessagePortableReconciliation.MissingSide -> error("both conditional branches are present")
                    }
                }
            }
        }
        throw CommitMessageSettingsConflictException()
    }

    companion object {
        private const val PORTABLE_SCHEMA_VERSION: Int = 1
        private const val MAX_CONDITIONAL_WRITE_ATTEMPTS: Int = 4
        private val PORTABLE_JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
        }

        private fun defaultPortableStore(): CommitMessagePortableStore? {
            val application = runCatching { ApplicationManager.getApplication() }.getOrNull()
            if (application == null || application.isUnitTestMode) return null
            val commonDataRoot = PathManager.getCommonDataPath()
            return CommitMessagePortableStore(
                commonDataRoot
                    .resolve("EzCodeMarks")
                    .resolve("commit-message")
                    .resolve("global-settings.json"),
                trustedRoot = commonDataRoot,
            )
        }

        fun getInstance(): CommitMessageSettingsService =
            ApplicationManager.getApplication().getService(CommitMessageSettingsService::class.java)
    }
}

class CommitMessageSettingsConflictException : IllegalStateException(
    "Portable commit-message settings changed in another IDE",
)

class CommitMessageSettingsStoreException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
