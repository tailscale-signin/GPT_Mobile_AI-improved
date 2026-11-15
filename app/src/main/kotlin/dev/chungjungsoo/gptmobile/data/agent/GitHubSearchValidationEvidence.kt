package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A fresh native schema rejection. Its complete argument binding stays private. */
class GitHubSearchValidationEvidence private constructor(
    private val callId: String,
    private val modelToolName: String,
    private val argumentsBinding: String,
    private val schemaHash: String,
    private val fields: List<String>
) {
    override fun toString(): String = "GitHubSearchValidationEvidence"

    /** Minted only by a search facade backed by the actual native GitHub tool. */
    class Owner private constructor(private val modelToolName: String, private val schemaHash: String) {
        override fun toString(): String = "GitHubSearchValidationOwner"

        /** Called by the rejecting validator, before any receipt can be restored or replaced. */
        internal fun capture(result: AgentToolResult, toolName: String, arguments: JsonObject, schema: JsonObject): AgentToolResult {
            if (toolName != modelToolName || ToolContractFingerprint.schema(schema) != schemaHash || result.callId.isBlank() || result.callId.length > 256) return result
            val receipt = result.executionReceipt ?: return result
            val invalid = (receipt["invalid_fields"] as? JsonArray).orEmpty().mapNotNull { field ->
                (field as? JsonPrimitive)?.takeIf { it.isString && it.content in SEARCH_FIELDS }?.content
            }.toSet()
            val observed = arguments.keys.filter { it in SEARCH_FIELDS && it in invalid }
            if (observed.isEmpty()) return result
            val binding = ToolInventoryPaginationEvidence.argumentBinding(arguments) ?: return result
            val evidence = GitHubSearchValidationEvidence(result.callId, modelToolName, binding, schemaHash, observed.toList())
            if (!evidence.ownsRejection(result)) return result
            return result.copy(searchValidationEvidence = evidence)
        }

        companion object {
            internal fun nativeDefinition(definition: AgentToolDefinition): Owner = Owner(definition.name, ToolContractFingerprint.schema(definition.inputSchema))
        }
    }

    private fun ownsRejection(result: AgentToolResult): Boolean {
        val receipt = result.executionReceipt ?: return false
        fun absentOrEqual(name: String, expected: JsonPrimitive): Boolean = receipt[name] == null || receipt[name] == expected
        return result.callId == callId &&
            result.isError &&
            !result.dispatched &&
            result.errorCode == "INVALID_ARGUMENTS" &&
            ToolFailureMetadata.authorityCode(result) == null &&
            receipt["failed_stage"] == JsonPrimitive("tool_precheck") &&
            receipt["reason_code"] == JsonPrimitive("TOOL_ARGUMENT_SCHEMA_REJECTED") &&
            receipt["tool_schema_hash"] == JsonPrimitive(schemaHash) &&
            receipt["retryable"] == JsonPrimitive(false) &&
            receipt["requires_reconciliation"] == JsonPrimitive(false) &&
            receipt["native_dependency_state"] == null &&
            receipt["dependency_admission_reasons"] == null &&
            absentOrEqual("dispatched", JsonPrimitive(false)) &&
            absentOrEqual("requiresReconciliation", JsonPrimitive(false)) &&
            listOf("execution_state", "state").all { absentOrEqual(it, JsonPrimitive("PRECHECK_REJECTED")) } &&
            absentOrEqual("phase", JsonPrimitive("tool_precheck")) &&
            listOf("call_id", "callId").all { absentOrEqual(it, JsonPrimitive(callId)) } &&
            listOf("tool", "tool_name", "model_tool_name").all { absentOrEqual(it, JsonPrimitive(modelToolName)) } &&
            listOf("error_code", "errorCode").all { absentOrEqual(it, JsonPrimitive("INVALID_ARGUMENTS")) } &&
            absentOrEqual("reasonCode", JsonPrimitive("TOOL_ARGUMENT_SCHEMA_REJECTED")) &&
            listOf("remote_acceptance", "remoteAcceptance").all { absentOrEqual(it, JsonPrimitive("NO")) }
    }

    companion object {
        internal val REPORT_FIELD_NAMES = setOf("search_validation_field_names")
        private val SEARCH_FIELDS = setOf("fuzzy_threshold", "context_lines", "max_new_blobs", "batch_size")
        private val REPOSITORY_FIELDS = setOf("owner", "repo", "branch", "from_branch")

        /** Fresh second repairs are observed again even when their display is marked shared. */
        internal fun reportFields(result: AgentToolResult, persistedCallId: String?, persistedModelToolName: String?, persistedArguments: String?): Map<String, Any> {
            val evidence = result.searchValidationEvidence ?: return emptyMap()
            if (persistedCallId != evidence.callId || persistedModelToolName != evidence.modelToolName || !evidence.ownsRejection(result)) return emptyMap()
            if (persistedArguments == null || persistedArguments.length > 65_536) return emptyMap()
            val arguments = runCatching { Json.parseToJsonElement(persistedArguments) as? JsonObject }.getOrNull() ?: return emptyMap()
            if (ToolInventoryPaginationEvidence.argumentBinding(arguments) != evidence.argumentsBinding) return emptyMap()
            return mapOf("search_validation_field_names" to evidence.fields.toList())
        }

        /** Existing native wrappers may add or normalize repository identity, never rejected search values. */
        internal fun rebindRepositoryArguments(result: AgentToolResult, toolName: String, executed: JsonObject, original: JsonObject): AgentToolResult {
            val evidence = result.searchValidationEvidence ?: return result
            if (executed == original) return result
            if (toolName != evidence.modelToolName ||
                !evidence.ownsRejection(result) ||
                JsonObject(executed - REPOSITORY_FIELDS) != JsonObject(original - REPOSITORY_FIELDS) ||
                ToolInventoryPaginationEvidence.argumentBinding(executed) != evidence.argumentsBinding
            ) {
                return result.copy(searchValidationEvidence = null)
            }
            val binding = ToolInventoryPaginationEvidence.argumentBinding(original) ?: return result.copy(searchValidationEvidence = null)
            return result.copy(searchValidationEvidence = GitHubSearchValidationEvidence(evidence.callId, evidence.modelToolName, binding, evidence.schemaHash, evidence.fields))
        }
    }
}
