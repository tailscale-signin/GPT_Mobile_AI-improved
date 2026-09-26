package dev.chungjungsoo.gptmobile.data.agent.provider

/** Per-request hard constraints take precedence over saved provider preferences. */
data class RequestConstraints(
    val maxOutputTokens: Int? = null,
    val allowTools: Boolean = true,
    val allowReasoning: Boolean = true
) {
    init {
        require(maxOutputTokens == null || maxOutputTokens > 0)
    }

    fun outputLimit(preferred: Int?): Int? = listOfNotNull(maxOutputTokens, preferred?.takeIf { it > 0 }).minOrNull()
}
