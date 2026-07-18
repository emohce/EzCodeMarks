package emohce.data.commitmessage

import emohce.domain.commitmessage.CommitMessageDefaults
import emohce.domain.commitmessage.LlmProfile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.net.URI

object SourceContextConsent {
    fun fingerprint(profile: LlmProfile, accountGeneration: String = ""): String {
        val material = listOf(
            profile.provider.name,
            normalizeEndpoint(profile.baseUrl),
            accountGeneration,
            CommitMessageDefaults.PRIVACY_POLICY_VERSION,
        ).joinToString("\n")
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun isGranted(profile: LlmProfile): Boolean = profile.sourceConsentFingerprint == fingerprint(profile)

    fun grant(profile: LlmProfile) {
        profile.sourceConsentFingerprint = fingerprint(profile)
    }

    fun invalidate(profile: LlmProfile) {
        profile.sourceConsentFingerprint = ""
    }

    private fun normalizeEndpoint(value: String): String {
        val fallback = value.trim().trimEnd('/')
        val uri = runCatching { URI(fallback) }.getOrNull() ?: return fallback
        val scheme = uri.scheme ?: return fallback
        val host = uri.host ?: return fallback
        if (uri.rawUserInfo != null) return fallback
        val normalizedHost = if (host.contains(':')) "[${host.lowercase()}]" else host.lowercase()
        return buildString {
            append(scheme.lowercase()).append("://").append(normalizedHost)
            if (uri.port >= 0) append(':').append(uri.port)
            append(uri.rawPath.orEmpty().trimEnd('/'))
            uri.rawQuery?.let { append('?').append(it) }
        }
    }
}

internal fun isSafeProviderEndpoint(value: String): Boolean {
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
    return uri.scheme?.lowercase() in setOf("http", "https") &&
        uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
}

internal fun hasSameCredentialDestination(left: LlmProfile, right: LlmProfile): Boolean =
    left.provider == right.provider &&
        normalizeProviderEndpoint(left.baseUrl) == normalizeProviderEndpoint(right.baseUrl)

private fun normalizeProviderEndpoint(value: String): String = value.trim().trimEnd('/')
