package dev.chungjungsoo.gptmobile.data.agent.provider

/** Per-request hard constraints take precedence over saved provider preferences. */
data class RequestConstraints(
    val maxOutputTokens: Int? = null,
    val allowTools: Boolean = true,
    val allowReasoning: Boolean = true,
    val allowGatewayLocalTools: Boolean = false,
    val requestRole: String? = null,
    val attemptId: String? = null
) {
    init {
        require(maxOutputTokens == null || maxOutputTokens > 0)
    }

    fun outputLimit(preferred: Int?): Int? = listOfNotNull(maxOutputTokens, preferred?.takeIf { it > 0 }).minOrNull()
}
