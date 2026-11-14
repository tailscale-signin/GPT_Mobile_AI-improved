package dev.chungjungsoo.gptmobile.data.agent.provider

/** Per-request hard constraints take precedence over saved provider preferences. */
data class RequestConstraints(
    val maxOutputTokens: Int? = null,
    val githubSampling: dev.chungjungsoo.gptmobile.data.github.GitHubSamplingSettings? = null,
    val allowTools: Boolean = true,
    val allowReasoning: Boolean = true,
    val reasoningModeOverride: dev.chungjungsoo.gptmobile.data.context.ReasoningMode? = null,
    val maximizeReasoning: Boolean = false,
    val allowGatewayLocalTools: Boolean = false,
    val lockSelectedRoute: Boolean = false,
    val restoredGatewayJob: dev.chungjungsoo.gptmobile.data.network.gateway.GatewayContinuationBinding? = null,
    /** Set only after a same-task terminal result was read under the current authority. */
    val gatewayNewStageAfterTerminal: Boolean = false,
    val requestRole: String? = null,
    val attemptId: String? = null,
    val queueScope: String? = null,
    val queuePersistent: Boolean = false,
    /** Stable logical chat identity; never inferred from a transient request/job ID. */
    val gatewayConversationKey: String? = null,
    val gatewayConversationId: String? = null,
    /** Complete the local turn/command reservation before a gateway model POST can begin. */
    val onGatewayCommandPrepared: (suspend (String) -> Unit)? = null,
    val onGatewayCommandNotDispatched: (suspend (String) -> Unit)? = null,
    val onGatewayCommandDispatching: (suspend (String) -> Unit)? = null,
    val requireGatewayConversation: Boolean = false,
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
        require(restoredGatewayJob == null || !gatewayNewStageAfterTerminal)
        require(maxOutputTokens == null || maxOutputTokens > 0)
        require(continuationPrefixDigest == null || continuationPrefixDigest.matches(Regex("[a-f0-9]{64}")))
    }

    /** Extraction is bounded formatting; engineering reasoning must leave room for visible JSON. */
    fun forRole(role: String): RequestConstraints {
        val briefHelper = role in setOf("answer_contract_rewrite", "github-claim-extraction", "github-progress-summary") || role.startsWith("github-review") && !role.endsWith("repair")
        return copy(
            requestRole = role,
            restoredGatewayJob = restoredGatewayJob.takeIf { role == "primary" },
            gatewayNewStageAfterTerminal = gatewayNewStageAfterTerminal && role == "primary",
            gatewayConversationKey = gatewayConversationKey.takeIf { role == "primary" || role == "synthesis" },
            gatewayConversationId = gatewayConversationId.takeIf { role == "primary" || role == "synthesis" },
            onGatewayCommandPrepared = onGatewayCommandPrepared.takeIf { role == "primary" || role == "synthesis" },
            onGatewayCommandNotDispatched = onGatewayCommandNotDispatched.takeIf { role == "primary" || role == "synthesis" },
            onGatewayCommandDispatching = onGatewayCommandDispatching.takeIf { role == "primary" || role == "synthesis" },
            requireGatewayConversation = requireGatewayConversation && role in setOf("primary", "synthesis"),
            maxOutputTokens = when (role) {
                dev.chungjungsoo.gptmobile.data.github.GitHubReviewCompatibility.PREFLIGHT_ROLE -> minOf(maxOutputTokens ?: Int.MAX_VALUE, dev.chungjungsoo.gptmobile.data.github.GitHubReviewCompatibility.OUTPUT_TOKENS)
                dev.chungjungsoo.gptmobile.data.github.GitHubReviewCompatibility.OUTPUT_RETRY_ROLE -> minOf(maxOutputTokens ?: Int.MAX_VALUE, dev.chungjungsoo.gptmobile.data.github.GitHubReviewCompatibility.OUTPUT_RETRY_TOKENS)
                else -> maxOutputTokens
            },
            // A short helper is a preference, not a new strict Off requirement. Preserve the
            // user's explicit policy; the serializer chooses verified Off only for Auto helpers.
            allowReasoning = allowReasoning,
            reasoningModeOverride = reasoningModeOverride,
            maximizeReasoning = maximizeReasoning && allowReasoning && !briefHelper && !role.startsWith("github-review")
        )
    }

    /** Text-only continuation changes tool access, never the accepted reasoning policy. */
    fun withoutTools(): RequestConstraints = copy(allowTools = false, allowGatewayLocalTools = false)

    fun outputLimit(preferred: Int?): Int? = dev.chungjungsoo.gptmobile.data.context.RequestLimitResolver.output(preferred, maxOutputTokens ?: combined?.let { it.plan?.outputTokens(it.role) }).tokens
}
