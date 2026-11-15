package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Local guard observation; private call ownership never enters receipt or report fields. */
class ToolRepeatAdmissionEvidence private constructor(
    private val callId: String,
    private val modelToolName: String,
    private val repeatKey: String,
    internal val snapshot: ToolDependencyDiagnostics.RepeatLimitSnapshot
) {
    override fun toString(): String = "ToolRepeatAdmissionEvidence"

    companion object {
        internal val FIELD_NAMES = setOf(
            "dependency_repeat_scope",
            "dependency_repeat_prior_admissions",
            "dependency_repeat_batch_admissions",
            "dependency_repeat_used",
            "dependency_repeat_limit"
        )

        internal fun capture(call: ProviderEvent.ToolCall, repeatKey: String, snapshot: ToolDependencyDiagnostics.RepeatLimitSnapshot): ToolRepeatAdmissionEvidence =
            ToolRepeatAdmissionEvidence(call.callId, call.name, repeatKey, snapshot)

        internal fun attachTo(result: AgentToolResult, evidence: ToolRepeatAdmissionEvidence?): AgentToolResult {
            if (evidence == null || result.callId != evidence.callId || !directRejection(result)) return result
            val fields = ToolDependencyDiagnostics.repeatLimitDetails(evidence.snapshot)
            if (fields.isEmpty()) return result
            val receipt = buildJsonObject {
                fields.forEach { (name, value) ->
                    when (value) {
                        is String -> put(name, value)
                        is Int -> put(name, value)
                    }
                }
            }
            return result.copy(executionReceipt = JsonObject(result.executionReceipt.orEmpty() + receipt), repeatAdmissionEvidence = evidence)
        }

        /** No fallback to raw receipt fields or reconstruction of retained observations. */
        internal fun reportFields(result: AgentToolResult, persistedCallId: String?, persistedModelToolName: String?, persistedArguments: String?): Map<String, Any> {
            val evidence = result.repeatAdmissionEvidence ?: return emptyMap()
            if (!directRejection(result) || result.callId != evidence.callId || persistedCallId != evidence.callId || persistedModelToolName != evidence.modelToolName) return emptyMap()
            if (persistedArguments == null || persistedArguments.length > 65_536) return emptyMap()
            val arguments = runCatching { Json.parseToJsonElement(persistedArguments) as? JsonObject }.getOrNull() ?: return emptyMap()
            var nodes = 0
            fun bounded(value: JsonElement, depth: Int): Boolean {
                if (++nodes > 256 || depth > 12) return false
                return when (value) {
                    is JsonObject -> value.size <= 256 && value.values.all { bounded(it, depth + 1) }
                    is JsonArray -> value.size <= 256 && value.all { bounded(it, depth + 1) }
                    else -> true
                }
            }
            if (!bounded(arguments, 0)) return emptyMap()
            val persistedKey = runCatching { toolRepeatKey(ProviderEvent.ToolCall(evidence.callId, evidence.modelToolName, arguments)) }.getOrNull() ?: return emptyMap()
            if (persistedKey != evidence.repeatKey) return emptyMap()
            return ToolDependencyDiagnostics.repeatLimitDetails(evidence.snapshot)
        }

        private fun directRejection(result: AgentToolResult): Boolean {
            val receipt = result.executionReceipt ?: return false
            return result.isError &&
                !result.dispatched &&
                !result.sharedResult &&
                result.errorCode == "NOT_DISPATCHED" &&
                ToolFailureMetadata.authorityCode(result) == null &&
                receipt["failed_stage"] == JsonPrimitive("tool_precheck") &&
                receipt["dependency_admission_reasons"] == JsonArray(listOf(JsonPrimitive("REPEAT_LIMIT"))) &&
                receipt["native_dependency_state"] == null &&
                listOf("requires_reconciliation", "requiresReconciliation").none { receipt[it] == JsonPrimitive(true) }
        }
    }
}
