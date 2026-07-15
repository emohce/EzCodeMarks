package emohce.domain.commitmessage

import java.nio.charset.StandardCharsets

object GitContextPolicy {
    const val STATUS_LIMIT: Int = 4_000
    const val DIFF_LIMIT: Int = 12_000
    const val UNVERSIONED_FILE_LIMIT: Int = 4_000
    const val UNVERSIONED_TOTAL_LIMIT: Int = 12_000
    const val RECENT_COMMITS_LIMIT: Int = 2_000

    private val sensitiveNames = listOf(
        Regex("(^|/)\\.env(?:\\..*)?$", RegexOption.IGNORE_CASE),
        Regex("(^|/)(?:credentials?|secrets?|service[-_]?account)(?:[./_-]|$)", RegexOption.IGNORE_CASE),
        Regex("(^|/)(?:id_rsa|id_dsa|id_ecdsa|id_ed25519)(?:\\.|$)", RegexOption.IGNORE_CASE),
        Regex("(^|/)\\.(?:npmrc|pypirc|netrc)$", RegexOption.IGNORE_CASE),
        Regex("(^|/)(?:\\.git-credentials|\\.dockercfg|docker-config\\.json|kubeconfig|auth\\.json)$", RegexOption.IGNORE_CASE),
        Regex("(^|/)\\.(?:docker|kube)/config$", RegexOption.IGNORE_CASE),
        Regex("\\.(?:pem|key|p12|pfx|jks|keystore|crt|cer)$", RegexOption.IGNORE_CASE),
    )
    private val generatedNames = listOf(
        Regex("(^|/)(?:build|dist|out|target|generated|node_modules|vendor)(/|$)", RegexOption.IGNORE_CASE),
        Regex("\\.(?:min\\.js|min\\.css|map|lock)$", RegexOption.IGNORE_CASE),
    )

    fun shouldExclude(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return sensitiveNames.any { it.containsMatchIn(normalized) } ||
            generatedNames.any { it.containsMatchIn(normalized) }
    }

    fun isProbablyBinary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        if (bytes.any { it == 0.toByte() }) return true
        val sample = bytes.take(1_024).toByteArray()
        val decoded = sample.toString(StandardCharsets.UTF_8)
        val replacements = decoded.count { it == '\uFFFD' }
        return replacements > decoded.length / 20
    }

    fun containsLikelySecret(content: String): Boolean {
        if (content.contains("-----BEGIN PRIVATE KEY-----") ||
            content.contains("-----BEGIN RSA PRIVATE KEY-----") ||
            content.contains("-----BEGIN OPENSSH PRIVATE KEY-----")
        ) return true
        return secretPatterns.any { it.containsMatchIn(content) }
    }

    fun truncate(value: String, limit: Int): Pair<String, Boolean> {
        if (value.length <= limit) return value to false
        val marker = "\n…[truncated]"
        return value.take((limit - marker.length).coerceAtLeast(0)) + marker to true
    }

    private val secretPatterns = listOf(
        Regex(
            "(?im)^\\s*[\"']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|" +
                "aws[_-]?(?:secret[_-]?access[_-]?key|access[_-]?key[_-]?id|session[_-]?token)|" +
                "secret|password|passwd|private[_-]?key)[\"']?\\s*[:=]\\s*[\"']?[^\\s\"'#},]{8,}",
        ),
        Regex(
            "(?im)^\\s*[\"']?(?:token|authorization|auth)[\"']?\\s*[:=]\\s*[\"']?(?:bearer\\s+)?[A-Za-z0-9_./+=-]{20,}",
        ),
        Regex("\\bAKIA[0-9A-Z]{16}\\b"),
        Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),
        Regex("\\bglpat-[A-Za-z0-9_-]{20,}\\b"),
        Regex("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"),
        Regex("\\bAIza[0-9A-Za-z_-]{30,}\\b"),
        Regex("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"),
        Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{12,}"),
    )
}
