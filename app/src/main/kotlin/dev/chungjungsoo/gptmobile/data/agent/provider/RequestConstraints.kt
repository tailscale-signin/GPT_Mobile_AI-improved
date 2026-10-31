package dev.chungjungsoo.gptmobile.data.agent.provider

/** Per-request hard constraints take precedence over saved provider preferences. */
data class RequestConstraints(
    val maxOutputTokens: Int? = null,
    val allowTools: Boolean = true,
    val allowReasoning: Boolean = true,
    val maximizeReasoning: Boolean = false,
    val allowGatewayLocalTools: Boolean = false,
    val requestRole: String? = null,
    val attemptId: String? = null,
    val queueScope: String? = null,
    val queuePersistent: Boolean = false,
    val freshResponse: Boolean = false,
    val contextCeiling: Int? = null,
    val permissionRevision: String = "legacy-unverified",
    val requireKnownCapacity: Boolean = false,
    val finalResponseSchemaJson: String? = null,
    val combined: dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext? = null,
    val originalRequestLineage: String? = null,
    val structuredOutputMode: dev.chungjungsoo.gptmobile.data.localruntime.LocalStructuredOutputMode = dev.chungjungsoo.gptmobile.data.localruntime.LocalStructuredOutputMode.NATIVE_JSON_SAFE_V1,
    val structuredOutputCompatibilityKey: String? = null,
    val structuredOutputProbe: Boolean = false,
    val structuredOutputCompatibilityResolver: (suspend (dev.chungjungsoo.gptmobile.data.localruntime.LocalEngineSpec) -> String)? = null,
    /** Identity of the complete retained answer, even when the wire sends only its tail. */
    val continuationPrefixDigest: String? = null
) {
    init {
        finalResponseSchemaJson?.let { schema ->
            require(schema.toByteArray().size <= 32 * 1024) { "Final-response schema exceeds 32 KiB" }
            require(kotlinx.serialization.json.Json.parseToJsonElement(schema) is kotlinx.serialization.json.JsonObject) { "Final-response schema must be an object" }
        }
        require(maxOutputTokens == null || maxOutputTokens > 0)
        require(continuationPrefixDigest == null || continuationPrefixDigest.matches(Regex("[a-f0-9]{64}")))
    }

    /** Extraction is bounded formatting; engineering reasoning must leave room for visible JSON. */
    fun forRole(role: String): RequestConstraints = copy(
        requestRole = role,
        allowReasoning = allowReasoning && role != "github-claim-extraction",
        maximizeReasoning = maximizeReasoning && !role.startsWith("github-review") && role != "github-claim-extraction"
    )

    /** Text-only continuation changes tool access, never the accepted reasoning policy. */
    fun withoutTools(): RequestConstraints = copy(allowTools = false, allowGatewayLocalTools = false)

    fun outputLimit(preferred: Int?): Int? = dev.chungjungsoo.gptmobile.data.context.RequestLimitResolver.output(preferred, maxOutputTokens ?: combined?.let { it.plan?.outputTokens(it.role) }).tokens
}
