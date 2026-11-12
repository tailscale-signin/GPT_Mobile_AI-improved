package dev.chungjungsoo.gptmobile.data.accounting

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Backend observations are independent of token usage. No response content is retained. */
internal object GatewayRecoveryEvidence {
    private val finishes = setOf("STOP", "TOOL_CALLS", "LENGTH", "CONTENT_FILTER", "OTHER", "UNKNOWN")
    private val statuses = setOf("COMPLETED", "FAILED", "UNKNOWN")
    private val terminalCodes = setOf(
        "backend_deadline_exceeded", "backend_timeout", "backend_stream_interrupted", "backend_http_error",
        "backend_template_invalid", "upstream_stream_error", "incomplete_stream", "malformed_stream",
        "malformed_completion", "multiple_choices", "invalid_tool_call", "invalid_tool_arguments",
        "incomplete_tool_call", "unauthorized_tool_call", "empty_completion", "missing_required_tool_call",
        "mixed_tool_batch", "completion_repair_budget_unavailable", "completion_repair_usage_unavailable",
        "completion_repair_budget_exhausted", "context_budget_exceeded", "context_capacity_unverified"
    )

    fun fromDetails(details: JsonObject?): JsonObject? = fromRecovery(details?.get("gateway_recovery") as? JsonObject)

    fun fromRecovery(recovery: JsonObject?): JsonObject? {
        if (recovery?.get("schema_version") != JsonPrimitive(1)) return null
        val attempts = recovery["attempt_receipts"] as? JsonArray ?: return null
        if (attempts.isEmpty() || attempts.size > 2 || attempts.any { it !is JsonObject }) return null
        val ordinals = attempts.map { number(it as JsonObject, "attempt")?.takeIf { value -> value in 1..2 } }
        if (ordinals.any { it == null } || ordinals.distinct().size != ordinals.size || ordinals != ordinals.sortedBy { it }) return null
        val receipts = attempts.mapIndexed { index, raw ->
            val row = raw as JsonObject
            buildJsonObject {
                put("attempt", requireNotNull(ordinals[index]))
                put("output_allowance", number(row, "output_allowance")?.takeIf { it > 0 }?.let(::JsonPrimitive) ?: JsonNull)
                put("finish_reason", category(row, "finish_reason", finishes) ?: "UNKNOWN")
                for (key in listOf("visible_output_bytes", "reasoning_output_bytes")) {
                    put(key, number(row, key)?.let(::JsonPrimitive) ?: JsonNull)
                }
                val code = (row["terminal_error_code"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    ?.takeIf { it in terminalCodes }
                put("terminal_error_code", code?.let(::JsonPrimitive) ?: JsonNull)
                put("terminal_status_code", number(row, "terminal_status_code")?.takeIf { it in 100..599 }?.let(::JsonPrimitive) ?: JsonNull)
                put("status", category(row, "status", statuses) ?: "UNKNOWN")
                put("backend_response_observed", row["backend_response_observed"]?.takeIf { it == JsonPrimitive(true) || it == JsonPrimitive(false) } ?: JsonNull)
            }
        }
        return buildJsonObject {
            put("schema_version", 1)
            for (key in listOf("attempts", "completed_attempts", "failed_attempt")) {
                number(recovery, key)?.takeIf { it in 0..2 }?.let { put(key, it) }
            }
            number(recovery, "output_allowance")?.takeIf { it > 0 }?.let { put("output_allowance", it) }
            put("attempt_receipts", JsonArray(receipts))
        }
    }

    fun observedBackendExecution(evidence: JsonObject?): Boolean = receipts(evidence).any { row ->
        row["status"] == JsonPrimitive("COMPLETED") && row["backend_response_observed"] == JsonPrimitive(true)
    }

    fun finishReason(evidence: JsonObject?): String? = receipts(evidence).lastOrNull()
        ?.let { category(it, "finish_reason", finishes) }?.takeUnless { it == "UNKNOWN" }?.lowercase(Locale.ROOT)

    /** Saved jobs are historical observations, never backend work caused by their result-read request. */
    fun combine(attempts: Map<String, JsonObject>, savedJobs: Map<String, JsonObject> = emptyMap()): JsonObject? =
        (attempts.isNotEmpty() || savedJobs.isNotEmpty()).takeIf { it }?.let {
            buildJsonObject {
                put("scope", if (savedJobs.isEmpty()) "backend_attempts_within_physical_http_requests" else "backend_attempts_by_observation_source")
                put(
                    "physical_requests",
                    JsonArray(
                        attempts.entries.toList().takeLast(8).map { (id, evidence) ->
                            buildJsonObject {
                                put("physical_attempt_id", id)
                                put("recovery", evidence)
                            }
                        }
                    )
                )
                if (attempts.size > 8) put("physical_requests_omitted", attempts.size - 8)
                if (savedJobs.isNotEmpty()) {
                    put(
                        "saved_jobs",
                        JsonArray(
                            savedJobs.entries.toList().takeLast(8).map { (id, evidence) ->
                                buildJsonObject {
                                    put("job_id", id)
                                    put("recovery", evidence)
                                    put("usage_accounted_to_current_invocation", false)
                                }
                            }
                        )
                    )
                    if (savedJobs.size > 8) put("saved_jobs_omitted", savedJobs.size - 8)
                }
            }
        }

    private fun receipts(evidence: JsonObject?): List<JsonObject> = (evidence?.get("attempt_receipts") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    private fun category(row: JsonObject, key: String, allowed: Set<String>): String? = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in allowed }
    private fun number(row: JsonObject, key: String): Int? = (row[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
}
