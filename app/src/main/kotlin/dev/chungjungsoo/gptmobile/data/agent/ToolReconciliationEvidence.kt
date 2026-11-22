package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.permissions.ScopedToolGrant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Correlation only. A prior unknown result never grants replay or proves remote success. */
internal object ToolReconciliationEvidence {
    val hashes = setOf("reconciliation_original_call_hash", "reconciliation_original_tool_hash", "reconciliation_original_arguments_hash")
    val fields = hashes + setOf("reconciliation_identity_state", "reconciliation_tracked_operation_count", "reconciliation_original_code", "reconciliation_original_dispatched")
    private val digest = Regex("[a-f0-9]{64}")

    fun validValue(key: String, value: JsonElement): JsonElement? {
        val primitive = value as? JsonPrimitive ?: return null
        return when (key) {
            in hashes -> primitive.takeIf { it.isString && digest.matches(it.content) }
            "reconciliation_identity_state" -> primitive.takeIf { it.isString && it.content in setOf("IDENTIFIED", "MULTIPLE", "NOT_RECORDED") }
            "reconciliation_original_code" -> primitive.takeIf { it.isString && it.content in ToolResultEnvelope.unknownOutcomeCodes }
            "reconciliation_original_dispatched" -> primitive.takeUnless { it.isString }?.booleanOrNull?.let(::JsonPrimitive)
            "reconciliation_tracked_operation_count" -> primitive.takeUnless { it.isString }?.intOrNull?.takeIf { it in 0..1024 }?.let(::JsonPrimitive)
            else -> null
        }
    }

    fun retained(code: String?, details: JsonObject?): JsonObject? {
        if (code !in ToolResultEnvelope.unknownOutcomeCodes || details == null) return null
        val safe = JsonObject(details.mapNotNull { (key, value) -> validValue(key, value)?.let { key to it } }.toMap())
        val count = (safe["reconciliation_tracked_operation_count"] as? JsonPrimitive)?.intOrNull ?: return null
        val state = (safe["reconciliation_identity_state"] as? JsonPrimitive)?.contentOrNull
        return when {
            state == "IDENTIFIED" && count == 1 && hashes.all { it in safe } -> safe
            state == "MULTIPLE" && count > 1 -> JsonObject(safe.filterKeys { it in setOf("reconciliation_identity_state", "reconciliation_tracked_operation_count") })
            state == "NOT_RECORDED" && count == 0 -> JsonObject(safe.filterKeys { it in setOf("reconciliation_identity_state", "reconciliation_tracked_operation_count") })
            else -> null
        }
    }

    fun observation(call: ProviderEvent.ToolCall, result: AgentToolResult): JsonObject {
        require(result.callId == call.callId && result.errorCode in ToolResultEnvelope.unknownOutcomeCodes)
        retained(result.errorCode, result.executionReceipt)?.takeIf { it["reconciliation_identity_state"] == JsonPrimitive("IDENTIFIED") }?.let { return it }
        val callHash = IssueReportContext.identifierHash("call", call.callId) ?: return summary(emptyList())
        val toolHash = IssueReportContext.identifierHash("tool-name", call.name) ?: return summary(emptyList())
        val argumentsHash = IssueReportContext.identifierHash("tool-arguments", ScopedToolGrant.canonicalHash(call.arguments)) ?: return summary(emptyList())
        return buildJsonObject {
            put("reconciliation_identity_state", "IDENTIFIED")
            put("reconciliation_tracked_operation_count", 1)
            put("reconciliation_original_call_hash", callHash)
            put("reconciliation_original_tool_hash", toolHash)
            put("reconciliation_original_arguments_hash", argumentsHash)
            put("reconciliation_original_code", requireNotNull(result.errorCode))
            // A local refusal may refer to an older remote action. False here
            // would incorrectly assert that the original action was never sent.
            if (result.dispatched) put("reconciliation_original_dispatched", true)
        }
    }

    fun summary(observations: Collection<JsonObject>): JsonObject {
        if (observations.size == 1) retained("RECONCILIATION_REQUIRED", observations.single())?.let { return it }
        return buildJsonObject {
            put("reconciliation_identity_state", if (observations.size <= 1) "NOT_RECORDED" else "MULTIPLE")
            put("reconciliation_tracked_operation_count", if (observations.size <= 1) 0 else observations.size.coerceAtMost(1024))
        }
    }

    fun blocked(callId: String, original: JsonObject): AgentToolResult = ToolResultEnvelope.error(
        callId,
        "RECONCILIATION_REQUIRED",
        "An earlier identical operation has an unknown outcome. Inspect its retained execution receipt before another write. This call was not dispatched."
    ).copy(
        executionReceipt = JsonObject(
            retained("RECONCILIATION_REQUIRED", original).orEmpty() + buildJsonObject {
                put("failed_stage", "tool_precheck")
                put("dispatched", false)
                put("requires_reconciliation", true)
                put("request_replay_allowed", false)
                put("retryable", false)
            }
        )
    )
}
