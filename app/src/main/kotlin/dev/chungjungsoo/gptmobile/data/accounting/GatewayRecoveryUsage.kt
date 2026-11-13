package dev.chungjungsoo.gptmobile.data.accounting

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Completed backend attempts can consume tokens even when the gateway returns an error. */
internal data class GatewayRecoveryUsage(
    val inputTokens: Int?,
    val outputTokens: Int?,
    val inputComplete: Boolean,
    val outputComplete: Boolean,
    val evidence: JsonObject
) {
    fun event(invocationId: String?): ProviderEvent.Usage = ProviderEvent.Usage(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = if (inputTokens != null && outputTokens != null) (inputTokens.toLong() + outputTokens).takeIf { it <= Int.MAX_VALUE }?.toInt() else null,
        cumulative = true,
        inputSource = if (inputComplete) "gateway_reported" else "gateway_reported_partial",
        outputSource = if (outputComplete) "gateway_reported" else "gateway_reported_partial",
        invocationId = invocationId
    )

    companion object {
        fun from(details: JsonObject?): GatewayRecoveryUsage? {
            val recovery = details?.get("gateway_recovery") as? JsonObject ?: return null
            if (recovery["usage_origin"] != JsonPrimitive("PROVIDER_REPORTED") || recovery["usage_estimated"] != JsonPrimitive(false)) return null
            val attempts = recovery["attempt_usage"] as? JsonArray ?: return null
            if (attempts.isEmpty() || attempts.size > 8 || attempts.any { it !is JsonObject }) return null
            fun number(root: JsonObject, key: String): Int? = (root[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 }
            val rows = attempts.map { raw ->
                val row = raw as JsonObject
                val input = number(row, "prompt_tokens")
                val output = number(row, "completion_tokens")
                val total = number(row, "total_tokens")
                if (input != null && output != null && total != null && input.toLong() + output != total.toLong()) {
                    null to null
                } else {
                    input to output
                }
            }
            fun sum(values: List<Int?>): Int? = values.filterNotNull().takeIf { it.isNotEmpty() }
                ?.sumOf { it.toLong() }?.takeIf { it <= Int.MAX_VALUE }?.toInt()
            val input = sum(rows.map { it.first })
            val output = sum(rows.map { it.second })
            if (input == null && output == null) return null
            val declared = number(recovery, "attempts")?.takeIf { it in 1..8 }
            val completed = number(recovery, "completed_attempts")?.takeIf { it in 1..8 }
            val failed = number(recovery, "failed_attempt")?.takeIf { it in 1..8 }
            val coversAttempts = (declared == attempts.size || completed == attempts.size && failed == attempts.size) &&
                (failed == null || failed <= attempts.size) &&
                recovery["failed_attempt_usage_available"] != JsonPrimitive(false)
            return GatewayRecoveryUsage(
                input,
                output,
                coversAttempts && input != null && rows.all { it.first != null },
                coversAttempts && output != null && rows.all { it.second != null },
                buildJsonObject {
                    GatewayRecoveryEvidence.fromRecovery(recovery)?.forEach { (key, value) -> put(key, value) }
                    put("usage_origin", "PROVIDER_REPORTED")
                    put("usage_estimated", false)
                    declared?.let { put("attempts", it) }
                    completed?.let { put("completed_attempts", it) }
                    failed?.let { put("failed_attempt", it) }
                    number(recovery, "output_allowance")?.let { put("output_allowance", it) }
                    recovery["failed_attempt_usage_available"]?.takeIf { it == JsonPrimitive(true) || it == JsonPrimitive(false) }?.let { put("failed_attempt_usage_available", it) }
                    put(
                        "attempt_usage",
                        JsonArray(
                            rows.map { (prompt, completion) ->
                                buildJsonObject {
                                    prompt?.let { put("prompt_tokens", it) }
                                    completion?.let { put("completion_tokens", it) }
                                }
                            }
                        )
                    )
                }
            )
        }

        /** Each key is one physical HTTP attempt; repeated error projections replace, never add. */
        fun combine(attempts: Map<String, GatewayRecoveryUsage>, allPhysicalAttemptsCovered: Boolean): GatewayRecoveryUsage? {
            if (attempts.isEmpty()) return null
            fun sum(values: List<Int?>) = values.filterNotNull().takeIf { it.isNotEmpty() }?.sumOf { it.toLong() }?.takeIf { it <= Int.MAX_VALUE }?.toInt()
            val input = sum(attempts.values.map { it.inputTokens })
            val output = sum(attempts.values.map { it.outputTokens })
            return GatewayRecoveryUsage(
                input,
                output,
                allPhysicalAttemptsCovered && input != null && attempts.values.all { it.inputComplete },
                allPhysicalAttemptsCovered && output != null && attempts.values.all { it.outputComplete },
                buildJsonObject {
                    put("scope", "backend_attempts_within_physical_http_requests")
                    put("all_physical_attempts_covered", allPhysicalAttemptsCovered)
                    put(
                        "physical_requests",
                        JsonArray(
                            attempts.map { (id, usage) ->
                                buildJsonObject {
                                    put("physical_attempt_id", id)
                                    put("recovery", usage.evidence)
                                }
                            }
                        )
                    )
                }
            )
        }
    }
}
