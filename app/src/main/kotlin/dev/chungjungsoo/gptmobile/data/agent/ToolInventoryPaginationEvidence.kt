package dev.chungjungsoo.gptmobile.data.agent

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Fresh native guard observation. Its private call binding is never serialized or displayed. */
class ToolInventoryPaginationEvidence private constructor(
    private val callId: String,
    private val argumentsBinding: String,
    private val reason: Reason,
    private val restartOffered: Boolean
) {
    internal enum class Reason { MISSING_VERSION, VERSION_MISMATCH }

    override fun toString(): String = "ToolInventoryPaginationEvidence"

    companion object {
        internal val REPORT_FIELD_NAMES = setOf("inventory_pagination_reason", "inventory_restart_offered")
        private const val MAX_ARGUMENT_CHARACTERS = 65_536

        /** Called only inside the catalog's owning stale-inventory branch. */
        internal fun capture(callId: String, arguments: JsonObject, reason: Reason, restartOffered: Boolean): ToolInventoryPaginationEvidence? {
            if (callId.isBlank() || callId.length > 256) return null
            val binding = argumentBinding(arguments) ?: return null
            return ToolInventoryPaginationEvidence(callId, binding, reason, restartOffered)
        }

        /** Normal same-call copies retain this observation; raw/restored receipts cannot recreate it. */
        internal fun reportFields(result: AgentToolResult, persistedCallId: String?, persistedModelToolName: String?, persistedArguments: String?): Map<String, Any> {
            val evidence = result.inventoryPaginationEvidence ?: return emptyMap()
            if (result.callId != evidence.callId || persistedCallId != evidence.callId || persistedModelToolName != ProgressiveToolCatalog.NAME || !directRefusal(result, evidence)) return emptyMap()
            if (persistedArguments == null || persistedArguments.length > MAX_ARGUMENT_CHARACTERS) return emptyMap()
            val arguments = runCatching { Json.parseToJsonElement(persistedArguments) as? JsonObject }.getOrNull() ?: return emptyMap()
            if (argumentBinding(arguments) != evidence.argumentsBinding) return emptyMap()
            return mapOf("inventory_pagination_reason" to evidence.reason.name, "inventory_restart_offered" to evidence.restartOffered)
        }

        private fun directRefusal(result: AgentToolResult, evidence: ToolInventoryPaginationEvidence): Boolean {
            val receipt = result.executionReceipt ?: return false
            val reasonCode = when (evidence.reason) {
                Reason.MISSING_VERSION -> "INVENTORY_VERSION_MISSING"
                Reason.VERSION_MISMATCH -> "INVENTORY_VERSION_CHANGED"
            }
            fun absentOrEqual(name: String, expected: JsonPrimitive): Boolean = receipt[name] == null || receipt[name] == expected
            return result.isError &&
                !result.dispatched &&
                !result.sharedResult &&
                result.errorCode == "STALE_INVENTORY" &&
                ToolFailureMetadata.authorityCode(result) == null &&
                receipt["failed_stage"] == JsonPrimitive("inventory_pagination") &&
                receipt["catalog_pagination_invalidated"] == JsonPrimitive(true) &&
                receipt["requires_argument_correction"] == JsonPrimitive(evidence.restartOffered) &&
                receipt["retryable"] == JsonPrimitive(false) &&
                receipt["permissions_changed"] == JsonPrimitive(false) &&
                receipt["native_dependency_state"] == null &&
                receipt["dependency_admission_reasons"] == null &&
                absentOrEqual("dispatched", JsonPrimitive(false)) &&
                absentOrEqual("execution_state", JsonPrimitive("PRECHECK_REJECTED")) &&
                absentOrEqual("phase", JsonPrimitive("tool_precheck")) &&
                listOf("requires_reconciliation", "requiresReconciliation").all { absentOrEqual(it, JsonPrimitive(false)) } &&
                listOf("call_id", "callId").all { absentOrEqual(it, JsonPrimitive(evidence.callId)) } &&
                listOf("tool", "tool_name").all { absentOrEqual(it, JsonPrimitive(ProgressiveToolCatalog.NAME)) } &&
                listOf("error_code", "errorCode").all { absentOrEqual(it, JsonPrimitive("STALE_INVENTORY")) } &&
                listOf("reason_code", "reasonCode").all { absentOrEqual(it, JsonPrimitive(reasonCode)) }
        }

        /** Bounded complete-value binding, kept private; argument order does not change ownership. */
        internal fun argumentBinding(arguments: JsonObject): String? {
            var nodes = 0
            var characters = 0
            fun textFits(value: String): Boolean {
                if (value.length > MAX_ARGUMENT_CHARACTERS - characters) return false
                characters += value.length
                return true
            }
            fun bounded(value: JsonElement, depth: Int): Boolean {
                if (++nodes > 256 || depth > 12) return false
                return when (value) {
                    is JsonObject -> value.size <= 256 && value.all { (key, item) -> textFits(key) && bounded(item, depth + 1) }
                    is JsonArray -> value.size <= 256 && value.all { bounded(it, depth + 1) }
                    is JsonPrimitive -> textFits(value.content)
                }
            }
            if (!bounded(arguments, 0)) return null
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("gptmobile.inventory-pagination-binding.v1".toByteArray(Charsets.UTF_8))
            fun token(value: String) {
                for (shift in listOf(24, 16, 8, 0)) digest.update((value.length ushr shift).toByte())
                // UTF-16 code units keep even an unpaired surrogate distinct without allocating another payload.
                value.forEach { character ->
                    digest.update((character.code ushr 8).toByte())
                    digest.update(character.code.toByte())
                }
            }
            fun append(value: JsonElement) {
                when (value) {
                    is JsonObject -> {
                        digest.update(1.toByte())
                        value.toSortedMap().forEach { (key, item) ->
                            token(key)
                            append(item)
                        }
                        digest.update(2.toByte())
                    }
                    is JsonArray -> {
                        digest.update(3.toByte())
                        value.forEach(::append)
                        digest.update(4.toByte())
                    }
                    JsonNull -> digest.update(5.toByte())
                    is JsonPrimitive -> {
                        digest.update(if (value.isString) 6.toByte() else 7.toByte())
                        token(value.content)
                    }
                }
            }
            append(arguments)
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
