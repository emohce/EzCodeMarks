package emohce.domain.commitmessage

interface CommitTemplateRenderer {
    fun render(template: CommitTemplateDefinition, draft: CommitDraft): String
    fun validate(template: CommitTemplateDefinition): TemplateValidation
}

data class TemplateValidation(
    val valid: Boolean,
    val error: String = "",
)

data class LlmCompletionRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val structured: Boolean,
    val streaming: Boolean,
    val reasoningCompatibility: Boolean,
)

data class LlmCompletion(
    val content: String,
    val model: String = "",
)

class ProviderRequestBudget(private val maximum: Int = 3) {
    init {
        require(maximum > 0)
    }

    private var used: Int = 0

    @Synchronized
    fun acquire() {
        if (used >= maximum) throw ProviderRequestBudgetExceededException(maximum)
        used += 1
    }

    @Synchronized
    fun usedRequests(): Int = used

    @Synchronized
    fun remainingRequests(): Int = maximum - used
}

class ProviderRequestBudgetExceededException(maximum: Int) :
    IllegalStateException("Provider request budget exhausted (maximum $maximum)")

enum class ProviderErrorKind {
    AUTHENTICATION,
    RATE_LIMIT,
    TIMEOUT,
    CANCELLED,
    INVALID_RESPONSE,
    SERVER,
    NETWORK,
}

open class ProviderException(
    val kind: ProviderErrorKind,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
