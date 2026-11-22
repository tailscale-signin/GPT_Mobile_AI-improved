package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.context.PromptBudgetReceipt
import dev.chungjungsoo.gptmobile.data.context.PromptBudgets
import dev.chungjungsoo.gptmobile.data.context.RequestAdmission
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Counts only. A response body's Content-Length is never a request capacity. */
internal object RequestByteBudget {
    const val CODE = "REQUEST_BYTES_EXCEEDED"
    const val FEATURE = "REQUEST_BYTE_BUDGET_V1"
    val counts = setOf("request_serialized_bytes", "request_tool_schema_bytes", "request_evidence_bytes", "provider_request_byte_limit")
    val fields = counts + setOf("provider_request_limit_source", "payload_replan_allowed")

    fun fields(budget: PromptBudgetReceipt, limit: Long? = null): JsonObject = buildJsonObject {
        budget.serializedRequestBytes?.let { put("request_serialized_bytes", it) }
        put("request_tool_schema_bytes", budget.components.filter { it.name == "tool_schemas" }.sumOf { it.serializedBytes })
        put("request_evidence_bytes", budget.components.filter { it.name == "tool_results" }.sumOf { it.serializedBytes })
        limit?.let { put("provider_request_byte_limit", it) }
        put("provider_request_limit_source", if (limit == null) "UNREPORTED" else "RESPONSE_HEADER")
    }

    fun responseLimit(header: (String) -> String?): Long? = listOf("X-Gateway-Max-Request-Bytes", "X-Max-Request-Bytes")
        .mapNotNull { name -> header(name)?.trim()?.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) }?.toLongOrNull()?.takeIf { it <= Int.MAX_VALUE } }
        .minOrNull()

    /** Scope never leaves this invocation; changed credentials, model or authority invalidate the observation. */
    fun scope(config: ProviderRequestConfig, model: String): String = researchHash(
        listOf(config.apiUrl, config.profileUid.orEmpty(), model, config.permissionRevision, config.token.orEmpty()).joinToString("\u0000")
    )

    /** Reduce only replayable tool evidence, retaining the accepted request and exact tool-result pairs. */
    fun replayTarget(details: JsonObject?, replayTokens: Long, admittedLimit: Int): Int? {
        if (details?.get("payload_replan_allowed")?.let { (it as? JsonPrimitive)?.booleanOrNull } != true) return null
        fun count(key: String) = (details[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0..Int.MAX_VALUE }
        val bytes = count("request_serialized_bytes")?.takeIf { it > 0 } ?: return null
        val evidence = count("request_evidence_bytes")?.takeIf { it > 0 } ?: return null
        val ceiling = count("provider_request_byte_limit")
        val reduction = if (ceiling != null) (bytes - ceiling + 1024).coerceAtLeast(1024) else (evidence / 2).coerceAtLeast(1024)
        if (reduction >= evidence) return null // The fixed request cannot be made to fit by replay compaction.
        val current = minOf(replayTokens, admittedLimit.toLong())
        return (current * (evidence - reduction) / evidence).takeIf { it in 1 until current }?.toInt()
    }
}

internal class RequestByteLimitException(val budget: PromptBudgetReceipt, val limit: Long) :
    IllegalStateException(
        "${RequestByteBudget.CODE}: The serialized request exceeds the byte limit observed for this exact route. " +
            "Compact retained tool evidence or reduce attachments before continuing; this attempt was not sent."
    )

/** One agent run owns both observed route limits and one-use replanning evidence. */
internal class RequestByteBudgetScope : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestByteBudgetScope>
    private val limits = mutableMapOf<String, Long>()
    private var rejection: Map<String, kotlinx.serialization.json.JsonElement>? = null

    @Synchronized fun limit(scope: String): Long? = limits[scope]

    @Synchronized fun observe(scope: String, limit: Long) {
        require(limit in 1..Int.MAX_VALUE)
        limits[scope] = minOf(limits[scope] ?: limit, limit)
    }

    @Synchronized fun clearReplan() {
        rejection = null
    }

    @Synchronized fun rejected(fields: JsonObject) {
        rejection = fields.filterKeys { it in RequestByteBudget.fields }
    }

    @Synchronized fun consumeReplan(fields: JsonObject?): Boolean {
        val expected = rejection ?: return false
        rejection = null
        return fields != null &&
            fields.filterKeys { it in RequestByteBudget.fields } == expected &&
            fields["payload_replan_allowed"] == JsonPrimitive(true)
    }
}

internal suspend fun requireRequestByteBudget(budget: PromptBudgetReceipt, config: ProviderRequestConfig, model: String) {
    val scope = currentCoroutineContext()[RequestByteBudgetScope] ?: return
    scope.clearReplan()
    val limit = scope.limit(RequestByteBudget.scope(config, model)) ?: return
    if ((budget.serializedRequestBytes ?: 0) > limit) {
        scope.rejected(JsonObject(RequestByteBudget.fields(budget, limit) + ("payload_replan_allowed" to JsonPrimitive(true))))
        throw RequestByteLimitException(budget, limit)
    }
}

/** Only an actual HTTP 413 boundary may mint this bounded replanning observation. */
internal suspend fun requestPayloadRejection(
    status: Int,
    payload: String,
    config: ProviderRequestConfig,
    model: String,
    partialOutput: Boolean,
    header: (String) -> String?
): JsonObject {
    if (status != 413) return JsonObject(emptyMap())
    val budget = PromptBudgets.capture(payload, RequestAdmission.estimate(payload), null, null, "rejected-wire-envelope")
    val limit = RequestByteBudget.responseLimit(header)
    val knownRejected = !partialOutput &&
        !config.requireGatewayConversation &&
        config.gatewayConversationId == null &&
        config.gatewayConversationKey == null &&
        header("X-Gateway-Job-ID").isNullOrBlank()
    val scope = currentCoroutineContext()[RequestByteBudgetScope]
    if (knownRejected && limit != null) scope?.observe(RequestByteBudget.scope(config, model), limit)
    val result = JsonObject(
        RequestByteBudget.fields(budget, limit) + buildJsonObject {
            put("http_status", status)
            put("payload_replan_allowed", knownRejected)
            put("recovery_action", "REPLAN_REQUEST_BUDGET")
            put("request_replay_allowed", false)
        }
    )
    if (knownRejected) scope?.rejected(result)
    return result
}
