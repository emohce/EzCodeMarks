package emohce.domain.commitmessage

object CommitMessageParser {
    private val headerPattern = Regex("^([A-Za-z0-9_-]+)(?:\\(([^)]+)\\))?(!)?:\\s*(.+)$")
    private val breakingPattern = Regex("^BREAKING(?:[ -]CHANGE):\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val closesPattern = Regex("^(?:Closes?|Fixes?):\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val skipCiPattern = Regex("\\[(?:skip ci|ci skip)]", RegexOption.IGNORE_CASE)

    fun parse(message: String, fallbackType: String = "feat"): CommitDraft {
        val normalized = message.replace("\r\n", "\n").trim()
        if (normalized.isBlank()) return CommitDraft(type = fallbackType)

        val lines = normalized.lines().toMutableList()
        val firstIndex = lines.indexOfFirst { it.isNotBlank() }
        val firstLine = lines.getOrNull(firstIndex).orEmpty().trim()
        val header = headerPattern.matchEntire(skipCiPattern.replace(firstLine, "").trim())

        val draft = if (header == null) {
            CommitDraft(type = fallbackType, subject = skipCiPattern.replace(firstLine, "").trim())
        } else {
            CommitDraft(
                type = header.groupValues[1],
                scope = header.groupValues[2],
                subject = header.groupValues[4],
                breakingChanges = if (header.groupValues[3].isNotEmpty()) "Breaking change" else "",
            )
        }
        draft.skipCi = skipCiPattern.containsMatchIn(normalized)

        val bodyLines = mutableListOf<String>()
        for (line in lines.drop(firstIndex + 1)) {
            val cleaned = skipCiPattern.replace(line, "").trimEnd()
            when {
                breakingPattern.matches(cleaned.trim()) -> {
                    val value = breakingPattern.matchEntire(cleaned.trim())?.groupValues?.get(1).orEmpty()
                    if (value.isNotBlank()) draft.breakingChanges = value.trim()
                }
                closesPattern.matches(cleaned.trim()) -> {
                    draft.closes = closesPattern.matchEntire(cleaned.trim())?.groupValues?.get(1).orEmpty().trim()
                }
                cleaned.isNotBlank() || bodyLines.isNotEmpty() -> bodyLines += cleaned
            }
        }
        draft.body = bodyLines.joinToString("\n").trim()
        return draft.normalized()
    }
}
