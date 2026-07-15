package emohce.data.commitmessage

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

internal object CommitMessageCredentialAccess {
    private val lock = ReentrantReadWriteLock()

    fun <T> read(action: () -> T): T = lock.read(action)

    fun <T> write(action: () -> T): T = lock.write(action)
}

class CommitMessageSecretStore(
    private val passwordSafe: PasswordSafe = PasswordSafe.instance,
) {
    fun getApiKey(profileId: String): String? = CommitMessageCredentialAccess.read {
        passwordSafe.getPassword(attributes(profileId))
    }

    fun setApiKey(profileId: String, apiKey: CharArray) {
        CommitMessageCredentialAccess.write {
            if (apiKey.isEmpty()) {
                passwordSafe.set(attributes(profileId), null)
            } else {
                passwordSafe.set(attributes(profileId), Credentials(profileId, apiKey))
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
}
