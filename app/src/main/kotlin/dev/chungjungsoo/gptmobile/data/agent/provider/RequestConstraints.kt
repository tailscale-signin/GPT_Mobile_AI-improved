package dev.chungjungsoo.gptmobile.data.agent.provider

/** Per-request hard constraints take precedence over saved provider preferences. */
data class RequestConstraints(
    val maxOutputTokens: Int? = null,
    val allowTools: Boolean = true,
    val allowReasoning: Boolean = true,
    val allowGatewayLocalTools: Boolean = false,
    val requestRole: String? = null,
    val attemptId: String? = null,
    val freshResponse: Boolean = false,
    val contextCeiling: Int? = null,
    val permissionRevision: String = "legacy-unverified",
    val requireKnownCapacity: Boolean = false,
    val finalResponseSchemaJson: String? = null,
    val combined: dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext? = null
) {
    init {
        finalResponseSchemaJson?.let { schema ->
            require(schema.toByteArray().size <= 32 * 1024) { "Final-response schema exceeds 32 KiB" }
            require(kotlinx.serialization.json.Json.parseToJsonElement(schema) is kotlinx.serialization.json.JsonObject) { "Final-response schema must be an object" }
        }
        require(maxOutputTokens == null || maxOutputTokens > 0)
    }

    fun outputLimit(preferred: Int?): Int? = dev.chungjungsoo.gptmobile.data.context.RequestLimitResolver.output(preferred, maxOutputTokens ?: combined?.let { it.plan?.outputTokens(it.role) }).tokens
}
