package dev.chungjungsoo.gptmobile.data.accounting

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Complete diagnostic shape; absent server evidence stays explicitly unverified. */
internal data class ProviderCallReceipt(
    val clientRequestId: String,
    val serverRequestId: String? = null,
    val finishReason: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val inputSource: String = "unreported",
    val outputSource: String = "unreported",
    val usageConfirmed: Boolean = false,
    val contextTokens: Long? = null,
    val contextSource: String = "UNVERIFIED",
    val outputCap: Int? = null,
    val outputCapSource: String = "UNVERIFIED",
    val reasoningState: String? = null,
    val settingsConfirmed: Boolean = false,
    val runtime: String? = null,
    val accelerator: String? = null
) {
    fun json(): JsonObject {
        val serverId = serverRequestId?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,160}")) }
        val missing = buildList {
            if (finishReason.isNullOrBlank()) add("finishReason")
            if (!usageConfirmed || inputTokens == null || outputTokens == null) add("usage")
            if (contextTokens == null || contextSource !in setOf("VERIFIED_ROUTE", "LOCAL_RUNTIME")) add("effectiveContext")
            if (outputCap == null) add("outputCap")
            if (!settingsConfirmed) add("effectiveSettings")
            if (reasoningState == null) add("reasoningState")
            if (runtime == null) add("runtime")
            if (accelerator == null) add("accelerator")
        }
        return buildJsonObject {
            put("schema", "gptmobile.provider-receipt.v1")
            put("clientRequestId", clientRequestId)
            put("requestId", serverId ?: clientRequestId)
            put("requestIdSource", if (serverId == null) "CLIENT" else "SERVER")
            put("serverRequestId", serverId?.let(::JsonPrimitive) ?: JsonNull)
            put("finishReason", finishReason?.let(::JsonPrimitive) ?: JsonNull)
            put("inputTokens", inputTokens?.let(::JsonPrimitive) ?: JsonNull)
            put("outputTokens", outputTokens?.let(::JsonPrimitive) ?: JsonNull)
            put("inputSource", if (inputTokens == null) "unreported" else inputSource)
            put("outputSource", if (outputTokens == null) "unreported" else outputSource)
            put("usageConfirmed", usageConfirmed)
            put("contextTokens", contextTokens?.let(::JsonPrimitive) ?: JsonNull)
            put("contextSource", contextSource)
            put("outputCap", outputCap?.let(::JsonPrimitive) ?: JsonNull)
            put("outputCapSource", outputCapSource)
            put("reasoningState", reasoningState?.let(::JsonPrimitive) ?: JsonNull)
            put("settingsConfirmed", settingsConfirmed)
            put("runtime", runtime?.let(::JsonPrimitive) ?: JsonNull)
            put("accelerator", accelerator?.let(::JsonPrimitive) ?: JsonNull)
            put("receiptStatus", if (missing.isEmpty()) "COMPLETE" else "PARTIAL")
            put("unverifiedFields", JsonArray(missing.map(::JsonPrimitive)))
        }
    }
}
