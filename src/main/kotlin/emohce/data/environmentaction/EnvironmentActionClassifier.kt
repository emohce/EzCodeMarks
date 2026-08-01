package emohce.data.environmentaction

object EnvironmentActionClassifier {
    fun classify(definition: String): EnvironmentActionType {
        val value = definition.trim()
        val lower = value.lowercase()
        if (lower.startsWith("git commit") || lower.startsWith("commit:")) return EnvironmentActionType.PREPARE_COMMIT
        if (lower.startsWith("codex ") || lower.startsWith("skill:") || lower.startsWith("agent:")) {
            return EnvironmentActionType.CODEX
        }
        if (SCRIPT_EXTENSION.containsMatchIn(lower)) return EnvironmentActionType.SCRIPT
        return EnvironmentActionType.SHELL
    }

    fun classify(action: EnvironmentActionDefinition): EnvironmentActionType =
        classify(action.scriptPath.ifBlank { action.command })

    private val SCRIPT_EXTENSION = Regex("""\.(py|js|mjs|cjs|sh|bash|ps1|rb|php)(?:\s|$)""")
}
