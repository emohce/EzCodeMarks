package emohce.data.commitmessage

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

internal object CommitMessageCredentialAccess {
    private val lock = ReentrantReadWriteLock()
    private val transactionStore: CommitMessagePortableStore? by lazy {
        val application = runCatching { ApplicationManager.getApplication() }.getOrNull()
        if (application == null || application.isUnitTestMode) {
            null
        } else {
            val commonDataRoot = PathManager.getCommonDataPath()
            CommitMessagePortableStore(
                commonDataRoot.resolve("EzCodeMarks").resolve("commit-message").resolve("credential-apply.guard"),
                trustedRoot = commonDataRoot,
            )
        }
    }

    fun <T> read(action: () -> T): T = lock.read(action)

    fun <T> write(action: () -> T): T = lock.write(action)

    fun <T> transaction(action: () -> T): T = lock.write {
        transactionStore?.withExclusiveLock(action) ?: action()
    }
}

internal data class CommitMessageCredentialSnapshot(
    val apiKey: String?,
    val generation: String,
)

class CommitMessageSecretStore(
    private val passwordSafe: PasswordSafe = PasswordSafe.instance,
) {
    fun getApiKey(profileId: String): String? = CommitMessageCredentialAccess.read {
        passwordSafe.getPassword(attributes(profileId))
    }

    fun credentialGeneration(profileId: String): String = CommitMessageCredentialAccess.transaction {
        credentialSnapshotWithinTransaction(profileId).generation
    }

    internal fun credentialSnapshotWithinTransaction(profileId: String): CommitMessageCredentialSnapshot {
        val attributes = attributes(profileId)
        val apiKey = passwordSafe.getPassword(attributes)
        if (apiKey == null) return CommitMessageCredentialSnapshot(null, "missing:$profileId")
        val storedGeneration = passwordSafe.get(attributes)?.userName.orEmpty()
        val generation = storedGeneration.takeIf(::isUuid) ?: UUID.randomUUID().toString().also { migrated ->
            val chars = apiKey.toCharArray()
            try {
                passwordSafe.set(attributes, Credentials(migrated, chars))
            } finally {
                chars.fill('\u0000')
            }
        }
        return CommitMessageCredentialSnapshot(apiKey, generation)
    }

    internal fun getCredentials(profileId: String): Credentials? = CommitMessageCredentialAccess.read {
        passwordSafe.get(attributes(profileId))
    }

    internal fun restoreCredentials(profileId: String, credentials: Credentials?) {
        CommitMessageCredentialAccess.write {
            passwordSafe.set(attributes(profileId), credentials)
        }
    }

    fun setApiKey(profileId: String, apiKey: CharArray) {
        CommitMessageCredentialAccess.write {
            if (apiKey.isEmpty()) {
                passwordSafe.set(attributes(profileId), null)
            } else {
                passwordSafe.set(attributes(profileId), Credentials(UUID.randomUUID().toString(), apiKey))
            }
        }
    }

    fun clearApiKey(profileId: String) {
        CommitMessageCredentialAccess.write {
            passwordSafe.set(attributes(profileId), null)
        }
    }

    private fun attributes(profileId: String): CredentialAttributes = CredentialAttributes(
        generateServiceName("EzCodeMarks", "Git Commit Message"),
        profileId,
    )

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
}
