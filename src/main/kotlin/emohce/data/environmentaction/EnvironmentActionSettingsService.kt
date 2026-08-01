package emohce.data.environmentaction

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import emohce.environmentaction.EnvironmentActionsBundle
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

const val ENVIRONMENT_ACTION_SLOT_COUNT: Int = 10

@Serializable
enum class EnvironmentActionType {
    SHELL,
    SCRIPT,
    CODEX,
    PREPARE_COMMIT,

    /** Schema-v1 compatibility only. [EnvironmentActionSettingsState.normalize] replaces it. */
    @Deprecated("Migrated to PREPARE_COMMIT")
    GIT_COMMIT,
}

@Serializable
data class EnvironmentActionDefinition(
    var id: String = UUID.randomUUID().toString(),
    var slot: Int = 1,
    var name: String = "",
    var type: EnvironmentActionType = EnvironmentActionType.SHELL,
    var command: String = "",
    var scriptPath: String = "",
    var arguments: String = "",
    var enabled: Boolean = true,
    var allowNonGitDirectory: Boolean = false,
) {
    fun copyForSnapshot(): EnvironmentActionDefinition = copy()
}

@Serializable
data class EnvironmentDefinition(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var workingDirectory: String = "",
    var variables: MutableMap<String, String> = linkedMapOf(),
    var actions: MutableList<EnvironmentActionDefinition> = mutableListOf(),
    var actionOrder: MutableList<Int> = mutableListOf(),
) {
    fun copyForSnapshot(): EnvironmentDefinition = copy(
        variables = LinkedHashMap(variables),
        actions = actions.map(EnvironmentActionDefinition::copyForSnapshot).toMutableList(),
        actionOrder = actionOrder.toMutableList(),
    )

    fun orderedActions(): List<EnvironmentActionDefinition> {
        val bySlot = actions.associateBy(EnvironmentActionDefinition::slot)
        return actionOrder.mapNotNull(bySlot::get)
    }

    override fun toString(): String = name.ifBlank { EnvironmentActionsBundle.message("environment.fallback") }
}

@Serializable
data class EnvironmentActionSettingsState(
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    var revision: String = UUID.randomUUID().toString(),
    var defaultEnvironmentId: String = "",
    var environments: MutableList<EnvironmentDefinition> = mutableListOf(),
) {
    fun copyForSnapshot(): EnvironmentActionSettingsState = copy(
        environments = environments.map(EnvironmentDefinition::copyForSnapshot).toMutableList(),
    )

    fun normalize(legacyActiveEnvironmentId: String? = null) {
        require(schemaVersion in 1..CURRENT_SCHEMA_VERSION) { "Unsupported environment-action settings schema" }
        if (!isUuid(revision)) revision = UUID.randomUUID().toString()

        val environmentIds = mutableSetOf<String>()
        environments = environments.take(MAX_ENVIRONMENTS).map { source ->
            source.copyForSnapshot().apply {
                id = id.takeIf(::isUuid)
                    ?.takeIf(environmentIds::add)
                    ?: generateUniqueUuid(environmentIds)
                name = name.trim().take(MAX_ENVIRONMENT_NAME_LENGTH)
                workingDirectory = workingDirectory.trim().take(MAX_WORKING_DIRECTORY_LENGTH)
                variables = variables.entries
                    .mapNotNull { (key, value) ->
                        val normalizedKey = key.trim()
                        normalizedKey.takeIf {
                            VARIABLE_NAME.matches(it) && !EnvironmentActionSecurity.isSensitiveVariableName(it)
                        }
                            ?.let { it to value.take(MAX_VARIABLE_VALUE_LENGTH) }
                    }
                    .take(MAX_VARIABLES)
                    .toMap(LinkedHashMap())

                val actionIds = mutableSetOf<String>()
                val originalOrder = actions.map(EnvironmentActionDefinition::slot)
                val existing = actions
                    .asSequence()
                    .filter { it.slot in 1..ENVIRONMENT_ACTION_SLOT_COUNT }
                    .distinctBy(EnvironmentActionDefinition::slot)
                    .associateBy(EnvironmentActionDefinition::slot)
                actions = (1..ENVIRONMENT_ACTION_SLOT_COUNT).map { slot ->
                    (existing[slot]?.copyForSnapshot() ?: EnvironmentActionDefinition(slot = slot)).apply {
                        this.slot = slot
                        id = id.takeIf(::isUuid)
                            ?.takeIf(actionIds::add)
                            ?: generateUniqueUuid(actionIds)
                        name = name.trim().take(MAX_ACTION_NAME_LENGTH).ifBlank {
                            EnvironmentActionsBundle.message("settings.defaultAction", slot)
                        }
                        @Suppress("DEPRECATION")
                        if (type == EnvironmentActionType.GIT_COMMIT) type = EnvironmentActionType.PREPARE_COMMIT
                        command = command.trim().take(MAX_ACTION_CONTENT_LENGTH)
                        scriptPath = scriptPath.trim().take(MAX_SCRIPT_PATH_LENGTH)
                        arguments = arguments.trim().take(MAX_ACTION_CONTENT_LENGTH)
                        if (type != EnvironmentActionType.CODEX) allowNonGitDirectory = false
                    }
                }.toMutableList()

                val preferredOrder = (actionOrder.ifEmpty { originalOrder })
                    .filter { it in 1..ENVIRONMENT_ACTION_SLOT_COUNT }
                    .distinct()
                actionOrder = preferredOrder
                    .plus((1..ENVIRONMENT_ACTION_SLOT_COUNT).filterNot { it in preferredOrder })
                    .toMutableList()
            }
        }.toMutableList()

        val migratedDefault = defaultEnvironmentId.ifBlank { legacyActiveEnvironmentId.orEmpty() }
        defaultEnvironmentId = migratedDefault.takeIf { candidate -> environments.any { it.id == candidate } }
            ?: environments.firstOrNull()?.id.orEmpty()
        schemaVersion = CURRENT_SCHEMA_VERSION
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 2
        private const val MAX_ENVIRONMENTS = 100
        private const val MAX_ENVIRONMENT_NAME_LENGTH = 120
        private const val MAX_WORKING_DIRECTORY_LENGTH = 2_048
        private const val MAX_ACTION_NAME_LENGTH = 120
        private const val MAX_ACTION_CONTENT_LENGTH = 16_000
        private const val MAX_SCRIPT_PATH_LENGTH = 2_048
        private const val MAX_VARIABLE_VALUE_LENGTH = 8_192
        private const val MAX_VARIABLES = 128
        private val VARIABLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        internal fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

        private fun generateUniqueUuid(existing: MutableSet<String>): String {
            while (true) {
                val candidate = UUID.randomUUID().toString()
                if (existing.add(candidate)) return candidate
            }
        }
    }
}

internal sealed interface EnvironmentActionStoreWriteResult {
    data class Success(val state: EnvironmentActionSettingsState) : EnvironmentActionStoreWriteResult
    data class Conflict(val current: EnvironmentActionSettingsState?) : EnvironmentActionStoreWriteResult
}

internal enum class EnvironmentActionConflictResolution {
    LOCAL,
    REMOTE,
}

internal data class EnvironmentActionMergeResult(
    val state: EnvironmentActionSettingsState,
    val conflicts: List<String>,
)

/** Field-level three-way merge used after a cross-IDE CAS miss. */
internal object EnvironmentActionSettingsMerger {
    fun merge(
        base: EnvironmentActionSettingsState,
        local: EnvironmentActionSettingsState,
        remote: EnvironmentActionSettingsState,
        resolution: EnvironmentActionConflictResolution? = null,
    ): EnvironmentActionMergeResult {
        val conflicts = linkedSetOf<String>()
        fun <T> choose(path: String, before: T, ours: T, theirs: T): T = when {
            ours == theirs -> ours
            ours == before -> theirs
            theirs == before -> ours
            else -> {
                conflicts += path
                if (resolution == EnvironmentActionConflictResolution.LOCAL) ours else theirs
            }
        }

        fun mergeVariables(
            path: String,
            before: Map<String, String>,
            ours: Map<String, String>,
            theirs: Map<String, String>,
        ): MutableMap<String, String> {
            val keys = linkedSetOf<String>().apply {
                addAll(before.keys)
                addAll(theirs.keys)
                addAll(ours.keys)
            }
            return keys.mapNotNull { key ->
                choose("$path.$key", before[key], ours[key], theirs[key])?.let { key to it }
            }.toMap(LinkedHashMap())
        }

        fun mergeAction(
            path: String,
            before: EnvironmentActionDefinition?,
            ours: EnvironmentActionDefinition?,
            theirs: EnvironmentActionDefinition?,
        ): EnvironmentActionDefinition? {
            if (ours == theirs) return ours?.copyForSnapshot()
            if (ours == before) return theirs?.copyForSnapshot()
            if (theirs == before) return ours?.copyForSnapshot()
            if (before == null || ours == null || theirs == null) {
                conflicts += path
                return when (resolution) {
                    EnvironmentActionConflictResolution.LOCAL -> ours?.copyForSnapshot()
                    else -> theirs?.copyForSnapshot()
                }
            }
            return EnvironmentActionDefinition(
                id = choose("$path.id", before.id, ours.id, theirs.id),
                slot = before.slot,
                name = choose("$path.name", before.name, ours.name, theirs.name),
                type = choose("$path.type", before.type, ours.type, theirs.type),
                command = choose("$path.command", before.command, ours.command, theirs.command),
                scriptPath = choose("$path.scriptPath", before.scriptPath, ours.scriptPath, theirs.scriptPath),
                arguments = choose("$path.arguments", before.arguments, ours.arguments, theirs.arguments),
                enabled = choose("$path.enabled", before.enabled, ours.enabled, theirs.enabled),
                allowNonGitDirectory = choose(
                    "$path.allowNonGitDirectory",
                    before.allowNonGitDirectory,
                    ours.allowNonGitDirectory,
                    theirs.allowNonGitDirectory,
                ),
            )
        }

        fun mergeEnvironment(
            id: String,
            before: EnvironmentDefinition?,
            ours: EnvironmentDefinition?,
            theirs: EnvironmentDefinition?,
        ): EnvironmentDefinition? {
            val path = "environments.$id"
            if (ours == theirs) return ours?.copyForSnapshot()
            if (ours == before) return theirs?.copyForSnapshot()
            if (theirs == before) return ours?.copyForSnapshot()
            if (before == null || ours == null || theirs == null) {
                conflicts += path
                return when (resolution) {
                    EnvironmentActionConflictResolution.LOCAL -> ours?.copyForSnapshot()
                    else -> theirs?.copyForSnapshot()
                }
            }
            val beforeActions = before.actions.associateBy(EnvironmentActionDefinition::slot)
            val ourActions = ours.actions.associateBy(EnvironmentActionDefinition::slot)
            val theirActions = theirs.actions.associateBy(EnvironmentActionDefinition::slot)
            val slots = linkedSetOf<Int>().apply {
                addAll(beforeActions.keys)
                addAll(theirActions.keys)
                addAll(ourActions.keys)
            }
            val actions = slots.mapNotNull { slot ->
                mergeAction("$path.actions.$slot", beforeActions[slot], ourActions[slot], theirActions[slot])
            }.toMutableList()
            return EnvironmentDefinition(
                id = id,
                name = choose("$path.name", before.name, ours.name, theirs.name),
                workingDirectory = choose(
                    "$path.workingDirectory",
                    before.workingDirectory,
                    ours.workingDirectory,
                    theirs.workingDirectory,
                ),
                variables = mergeVariables("$path.variables", before.variables, ours.variables, theirs.variables),
                actions = actions,
                actionOrder = choose(
                    "$path.actionOrder",
                    before.actionOrder,
                    ours.actionOrder,
                    theirs.actionOrder,
                ).toMutableList(),
            )
        }

        val baseById = base.environments.associateBy(EnvironmentDefinition::id)
        val localById = local.environments.associateBy(EnvironmentDefinition::id)
        val remoteById = remote.environments.associateBy(EnvironmentDefinition::id)
        val environmentIds = linkedSetOf<String>().apply {
            addAll(base.environments.map(EnvironmentDefinition::id))
            addAll(remote.environments.map(EnvironmentDefinition::id))
            addAll(local.environments.map(EnvironmentDefinition::id))
        }
        val merged = EnvironmentActionSettingsState(
            schemaVersion = EnvironmentActionSettingsState.CURRENT_SCHEMA_VERSION,
            revision = remote.revision,
            defaultEnvironmentId = choose(
                "defaultEnvironmentId",
                base.defaultEnvironmentId,
                local.defaultEnvironmentId,
                remote.defaultEnvironmentId,
            ),
            environments = environmentIds.mapNotNull { id ->
                mergeEnvironment(id, baseById[id], localById[id], remoteById[id])
            }.toMutableList(),
        ).apply { normalize() }
        return EnvironmentActionMergeResult(merged, conflicts.toList())
    }
}

internal class EnvironmentActionStore(private val path: Path) {
    fun read(): EnvironmentActionSettingsState? = withFileLock { readUnlocked() }

    fun compareAndWrite(
        expectedRevision: String?,
        replacement: EnvironmentActionSettingsState,
    ): EnvironmentActionStoreWriteResult = withFileLock {
        val current = readUnlocked()
        if (current?.revision != expectedRevision) {
            return@withFileLock EnvironmentActionStoreWriteResult.Conflict(current?.copyForSnapshot())
        }
        val successor = replacement.copyForSnapshot().apply {
            normalize()
            revision = UUID.randomUUID().toString()
        }
        writeUnlocked(successor)
        EnvironmentActionStoreWriteResult.Success(successor.copyForSnapshot())
    }

    private fun readUnlocked(): EnvironmentActionSettingsState? {
        if (!Files.isRegularFile(path)) return null
        val size = Files.size(path)
        require(size in 1..MAX_STATE_BYTES) { "Environment Action settings file has an invalid size" }
        val content = Files.readString(path, StandardCharsets.UTF_8)
        val root = JSON.parseToJsonElement(content).jsonObject
        val legacyActive = root[LEGACY_ACTIVE_KEY]?.jsonPrimitive?.contentOrNull
        val persistedRevision = root[REVISION_KEY]?.jsonPrimitive?.contentOrNull
        return JSON.decodeFromString<EnvironmentActionSettingsState>(content).apply {
            if (!EnvironmentActionSettingsState.isUuid(persistedRevision.orEmpty())) {
                revision = UUID.nameUUIDFromBytes(
                    "$LEGACY_REVISION_NAMESPACE$content".toByteArray(StandardCharsets.UTF_8),
                ).toString()
            }
            normalize(legacyActive)
        }
    }

    private fun writeUnlocked(value: EnvironmentActionSettingsState) {
        val parent = path.parent
        Files.createDirectories(parent)
        val encoded = JSON.encodeToString(value)
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_STATE_BYTES) {
            "Environment Action settings exceed the storage limit"
        }
        val temporary = Files.createTempFile(parent, "environment-actions-", ".json")
        try {
            Files.writeString(temporary, encoded, StandardCharsets.UTF_8)
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun <T> withFileLock(action: () -> T): T {
        val parent = path.parent
        Files.createDirectories(parent)
        val lockPath = parent.resolve("${path.fileName}.lock")
        val processLock = JVM_LOCKS.computeIfAbsent(lockPath.toAbsolutePath().normalize()) { Any() }
        synchronized(processLock) {
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { return action() }
            }
        }
    }

    companion object {
        private const val MAX_STATE_BYTES = 2_000_000L
        private const val LEGACY_ACTIVE_KEY = "activeEnvironmentId"
        private const val REVISION_KEY = "revision"
        private const val LEGACY_REVISION_NAMESPACE = "ezcodemarks-environment-actions-migration:"
        private val JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
        private val JVM_LOCKS = ConcurrentHashMap<Path, Any>()
    }
}

class EnvironmentActionSettingsConflictException(
    val current: EnvironmentActionSettingsState?,
    val conflictingPaths: List<String> = emptyList(),
) : IllegalStateException("Environment Action definitions changed in another IDE")

@Service(Service.Level.APP)
class EnvironmentActionSettingsService internal constructor(
    private val store: EnvironmentActionStore,
) {
    constructor() : this(
        EnvironmentActionStore(
            PathManager.getCommonDataPath()
                .resolve("EzCodeMarks")
                .resolve("environment-actions")
                .resolve("global-settings.json"),
        ),
    )

    private val lock = Any()
    private var state: EnvironmentActionSettingsState = initializeState()

    fun snapshot(): EnvironmentActionSettingsState = synchronized(lock) {
        refreshLocked()
        state.copyForSnapshot()
    }

    internal fun replaceState(
        base: EnvironmentActionSettingsState,
        replacement: EnvironmentActionSettingsState,
        conflictResolution: EnvironmentActionConflictResolution? = null,
    ): EnvironmentActionSettingsState {
        synchronized(lock) {
            var mergeBase = base.copyForSnapshot()
            var candidate = replacement.copyForSnapshot().apply { normalize() }
            var expectedRevision: String? = mergeBase.revision
            repeat(MAX_CAS_ATTEMPTS) {
                when (val result = store.compareAndWrite(expectedRevision, candidate)) {
                    is EnvironmentActionStoreWriteResult.Success -> {
                        state = result.state.copyForSnapshot()
                        return result.state
                    }
                    is EnvironmentActionStoreWriteResult.Conflict -> {
                        val remote = result.current
                        if (remote == null) {
                            if (conflictResolution == null) throw EnvironmentActionSettingsConflictException(null)
                            expectedRevision = null
                            if (conflictResolution == EnvironmentActionConflictResolution.REMOTE) {
                                candidate = EnvironmentActionSettingsState().apply { normalize() }
                            }
                            return@repeat
                        }
                        state = remote.copyForSnapshot()
                        val merge = EnvironmentActionSettingsMerger.merge(
                            mergeBase,
                            candidate,
                            remote,
                            conflictResolution,
                        )
                        if (merge.conflicts.isNotEmpty() && conflictResolution == null) {
                            throw EnvironmentActionSettingsConflictException(remote, merge.conflicts)
                        }
                        mergeBase = remote
                        expectedRevision = remote.revision
                        candidate = merge.state
                    }
                }
            }
            throw EnvironmentActionSettingsConflictException(state.copyForSnapshot())
        }
    }

    fun environment(environmentId: String): EnvironmentDefinition? = snapshot().environments
        .firstOrNull { it.id == environmentId }
        ?.copyForSnapshot()

    fun defaultEnvironment(): EnvironmentDefinition? {
        val snapshot = snapshot()
        return snapshot.environments.firstOrNull { it.id == snapshot.defaultEnvironmentId }?.copyForSnapshot()
    }

    private fun initializeState(): EnvironmentActionSettingsState {
        val found = runCatching(store::read).getOrNull()
        if (found != null) return found
        val initial = EnvironmentActionSettingsState().apply { normalize() }
        return when (val result = store.compareAndWrite(null, initial)) {
            is EnvironmentActionStoreWriteResult.Success -> result.state
            is EnvironmentActionStoreWriteResult.Conflict -> result.current ?: initial
        }
    }

    private fun refreshLocked() {
        val found = runCatching(store::read).getOrNull() ?: return
        if (found.revision != state.revision) state = found
    }

    companion object {
        private const val MAX_CAS_ATTEMPTS = 4

        fun getInstance(): EnvironmentActionSettingsService =
            ApplicationManager.getApplication().getService(EnvironmentActionSettingsService::class.java)
    }
}
