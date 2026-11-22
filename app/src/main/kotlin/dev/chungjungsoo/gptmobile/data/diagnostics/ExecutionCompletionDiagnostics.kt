package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.GitHubNoOutputProgress
import dev.chungjungsoo.gptmobile.data.agent.ProviderRecoveryFailure
import dev.chungjungsoo.gptmobile.data.agent.ProviderWaitSchedulingResult
import dev.chungjungsoo.gptmobile.data.network.error.ProviderFailure
import dev.chungjungsoo.gptmobile.data.network.error.ProviderFailureKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Closed same-failure observations. Public hashes are domain separated from private checkpoint digests. */
internal object ExecutionCompletionDiagnostics {
    const val FEATURE = "EXECUTION_COMPLETION_EVIDENCE_V1"
    val codes = setOf("WORKSPACE_EVIDENCE_CONFLICT", "BILLING_UNAVAILABLE", "PROVIDER_CONTENT_POLICY", "PROVIDER_GENERATION_FAILED")
    val actions = setOf("REVIEW_APPLICATION_EVIDENCE", "UPDATE_OUTPUT_CAPACITY_OR_REMAINING_REQUEST", "AUTHORIZE_NEW_TIME_WINDOW")
    val deadlineFields = setOf("task_deadline_clock", "task_deadline_renewed", "task_deadline_elapsed_seconds", "task_deadline_remaining_seconds")
    private val digestAliases = mapOf(
        "draft_sha256" to "no_output_draft_hash",
        "segment_sha256" to "no_output_segment_hash",
        "evidence_digest" to "no_output_evidence_hash",
        "record_identity_sha256" to "evidence_record_hash",
        "proposed_payload_sha256" to "evidence_proposed_payload_hash",
        "retained_payload_sha256" to "evidence_retained_payload_hash",
        "expected_tool_journal_digest" to "provider_wait_expected_tool_journal_hash",
        "observed_tool_journal_digest" to "provider_wait_observed_tool_journal_hash",
        "expected_tool_evidence_digest" to "provider_wait_expected_tool_evidence_hash",
        "observed_tool_evidence_digest" to "provider_wait_observed_tool_evidence_hash",
        "tool_checkpoint_binding_sha256" to "provider_wait_tool_binding_hash"
    )
    private val countAliases = mapOf(
        "visible_bytes" to "no_output_visible_bytes",
        "reasoning_bytes" to "no_output_reasoning_bytes",
        "tool_call_count" to "no_output_tool_call_count",
        "no_output_repairs" to "no_output_repairs",
        "no_output_repair_limit" to "no_output_repair_limit",
        "queued_tool_count" to "provider_wait_queued_tool_count",
        "unsettled_tool_count" to "provider_wait_unsettled_tool_count",
        "settled_tool_count" to "provider_wait_settled_tool_count"
    )
    private val enums = mapOf(
        "no_output_kind" to GitHubNoOutputProgress.Kind.entries.mapTo(mutableSetOf()) { it.name },
        "evidence_conflict_reason" to setOf("IMMUTABLE_PAYLOAD_CHANGED", "RETAINED_PAYLOAD_UNAVAILABLE", "CONCURRENT_APPEND_CONFLICT"),
        "provider_failure_kind" to ProviderFailureKind.entries.mapTo(mutableSetOf()) { it.name },
        "upstream_error_code" to ProviderFailure.SAFE_CODES,
        "upstream_error_type" to ProviderFailure.SAFE_CODES,
        "checkpoint_validation_stage" to setOf("TOOL_JOURNAL"),
        "provider_wait_decision_state" to ProviderWaitSchedulingResult.decisionStates,
        "provider_wait_no_wait_reason" to ProviderWaitSchedulingResult.noWaitReasons,
        "provider_wait_scheduler_state" to ProviderWaitSchedulingResult.schedulerStates,
        "provider_wait_state" to setOf("WAITING_PROVIDER_LIMIT", "CHECKPOINT_UNAVAILABLE", "AUTHORITY_CHANGED", "PERSIST_FAILED", "NOT_SCHEDULED", "RESUMED")
    )
    val fields = digestAliases.values.toSet() + countAliases.values + enums.keys + setOf("provider_semantic_status", "no_output_repair_scheduled", "provider_wait_persisted")
    val newFields = fields - setOf("provider_wait_decision_state", "provider_wait_state", "provider_wait_persisted")
    val newWaitStates = setOf("DURABLE_WAIT", "NOT_SCHEDULED", "PERSISTENCE_UNCERTAIN")
    val compactFields = digestAliases.keys + countAliases.keys + enums.keys + setOf("provider_semantic_status", "repair_scheduled", "http_status", "provider_wait_persisted")
    private val hash = Regex("[a-f0-9]{64}")

    fun validValue(key: String, value: JsonElement): JsonElement? {
        val primitive = value as? JsonPrimitive ?: return null
        if (key in digestAliases.values) return primitive.takeIf { it.isString && hash.matches(it.content) }
        enums[key]?.let { choices -> return primitive.takeIf { it.isString && it.content in choices } }
        if (key in setOf("no_output_repair_scheduled", "provider_wait_persisted")) return primitive.takeUnless { it.isString }?.booleanOrNull?.let(::JsonPrimitive)
        val number = primitive.takeUnless { it.isString }?.longOrNull ?: return null
        return when {
            key == "provider_semantic_status" && number == 429L -> primitive
            key in countAliases.values && number in 0L..Int.MAX_VALUE.toLong() -> primitive
            else -> null
        }
    }

    /** Only the current typed failure supplies a family; arbitrary adjacent records cannot lend evidence. */
    fun compact(code: String, details: JsonObject): JsonObject = buildJsonObject {
        val output = code == "OUTPUT_CONTINUATION_NO_PROGRESS" && details["no_output_kind"]?.let { validValue("no_output_kind", it) } != null
        val persistence = code == "WORKSPACE_EVIDENCE_CONFLICT" && details["failure_origin"] == JsonPrimitive("local_persistence")
        val recovery = code in ProviderRecoveryFailure.CODES && details["failure_origin"] == JsonPrimitive("local_recovery")
        val provider = details["provider_failure_kind"]?.let { validValue("provider_failure_kind", it) } != null
        val waitDecision = details["provider_wait_decision_state"]?.let { validValue("provider_wait_decision_state", it) } != null
        if (persistence) put("failure_origin", "local_persistence")
        if (recovery) put("failure_origin", "local_recovery")
        for ((key, value) in details) {
            val eligible = when (key) {
                "provider_wait_decision_state", "provider_wait_no_wait_reason", "provider_wait_scheduler_state", "provider_wait_state", "provider_wait_persisted" -> waitDecision
                "no_output_kind", "visible_bytes", "reasoning_bytes", "tool_call_count", "no_output_repairs", "no_output_repair_limit", "repair_scheduled", "draft_sha256", "segment_sha256", "evidence_digest" -> output
                "evidence_conflict_reason", "record_identity_sha256", "proposed_payload_sha256", "retained_payload_sha256" -> persistence
                "expected_tool_journal_digest", "observed_tool_journal_digest", "expected_tool_evidence_digest", "observed_tool_evidence_digest", "tool_checkpoint_binding_sha256", "queued_tool_count", "unsettled_tool_count", "settled_tool_count", "checkpoint_validation_stage" -> recovery
                "provider_failure_kind", "upstream_error_code", "upstream_error_type", "http_status" -> provider
                "provider_semantic_status" -> provider && details["provider_failure_kind"] == JsonPrimitive("RATE_LIMIT")
                else -> false
            }
            if (!eligible) continue
            val primitive = value as? JsonPrimitive ?: continue
            val valid = when (key) {
                in digestAliases -> primitive.isString && hash.matches(primitive.content)
                "http_status" -> !primitive.isString && primitive.longOrNull in 100L..599L
                else -> validValue(countAliases[key] ?: if (key == "repair_scheduled") "no_output_repair_scheduled" else key, value) != null
            }
            if (valid) put(key, value)
        }
    }

    fun publicFields(code: String, fields: Map<String, Any?>): Map<String, JsonElement> {
        val details = JsonObject(
            fields.mapNotNull { (key, value) ->
                val element = when (value) {
                    is JsonElement -> value
                    is String -> JsonPrimitive(value)
                    is Boolean -> JsonPrimitive(value)
                    is Number -> JsonPrimitive(value)
                    else -> null
                }
                element?.let { key to it }
            }.toMap()
        )
        return compact(code, details).mapNotNull { (key, value) ->
            val alias = digestAliases[key]
            if (alias != null) {
                IssueReportContext.identifierHash("execution-completion:$alias", (value as JsonPrimitive).contentOrNull)?.let { alias to JsonPrimitive(it) }
            } else {
                val publicKey = countAliases[key] ?: if (key == "repair_scheduled") "no_output_repair_scheduled" else key
                value.takeIf { publicKey in this.fields }?.let { publicKey to it }
            }
        }.toMap()
    }
}
