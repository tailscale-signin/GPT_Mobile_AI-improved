package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.context.RequestCapacityException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Numeric observations only; runtime identities and prompt contents never enter this projection. */
internal fun capacityAdmissionEvidence(error: RequestCapacityException, nowMillis: Long): JsonObject = buildJsonObject {
    fun observedCount(key: String, value: Long?) {
        value?.takeIf { it in 0..16_000_000L }?.let { put(key, it) }
    }
    error.capacityObservation?.let { observation ->
        put("admission_capacity_provenance", observation.capacity.provenance.name)
        observedCount("admission_route_context_tokens", observation.routeContextTokens)
        observedCount("admission_configured_context_tokens", observation.configuredContextTokens)
        observedCount("admission_catalog_context_tokens", observation.catalogContextTokens)
        observedCount("admission_input_ceiling_tokens", observation.capacity.inputTokens)
        observedCount("admission_output_ceiling_tokens", observation.capacity.outputTokens)
        observation.capacity.observedAtMillis.takeIf { it > 0 && it <= nowMillis }?.let {
            observedCount("admission_capacity_age_ms", nowMillis - it)
        }
    }
    error.budget?.let { budget ->
        for ((component, field) in listOf(
            "system_instructions" to "admission_system_tokens",
            "retained_history" to "admission_history_tokens",
            "current_request" to "admission_current_request_tokens",
            "tool_results" to "admission_tool_result_tokens",
            "response_schema" to "admission_response_schema_tokens",
            "serialization_and_template_estimate" to "admission_envelope_tokens"
        )) {
            observedCount(field, budget.components.firstOrNull { it.name == component }?.tokens)
        }
    }
}
