package emohce.data.environmentaction

internal object EnvironmentActionSecurity {
    private val sensitiveName = Regex(
        "(?i)(?:^|_)(?:token|secret|password|passwd|api_?key|private_?key|credential|authorization)(?:$|_)",
    )
    private val sensitiveValue = Regex(
        "(?i)((?:token|secret|password|passwd|api[_-]?key|authorization|credential)\\s*[=:]\\s*)([^\\s]+)",
    )

    fun isSensitiveVariableName(name: String): Boolean = sensitiveName.containsMatchIn(name)

    fun redact(value: String): String = sensitiveValue.replace(value) { match ->
        "${match.groupValues[1]}<redacted>"
    }
}
