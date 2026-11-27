package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.context.RequestCapacityException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bounded admission observations; raw identities and prompt contents never enter this projection. */
internal fun capacityAdmissionEvidence(error: RequestCapacityException, monotonicMillis: Long): JsonObject = buildJsonObject {
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
        observation.capacity.observedAtMillis.takeIf { it > 0 && it <= monotonicMillis }?.let {
            observedCount("admission_capacity_age_ms", monotonicMillis - it)
        }
    }
    error.planningInput?.let { plan ->
        put("admission_capacity_provenance", plan.provenance.name)
        // Planning charges exact current inputs and, when pinned for adaptive
        // fitting, the required schemas. Optional history remains unselected.
        if (plan.costs.total.toLong() == error.promptTokens) {
            observedCount("admission_system_tokens", plan.costs.system.toLong())
            observedCount("admission_current_request_tokens", plan.costs.currentMessage.toLong())
            observedCount("admission_attachment_reserve_tokens", plan.costs.attachments.toLong())
            plan.costs.toolSchemas.takeIf { it > 0 }?.let { observedCount("admission_tool_schema_tokens", it.toLong()) }
        }
    }
    error.planningObservation?.let { observation ->
        observation.profileHash?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { put("profile_hash", it) }
        observation.modelHash?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { put("model_hash", it) }
        observedCount("configured_output_limit", observation.configuredOutputTokens?.toLong())
        observedCount("admission_route_context_tokens", observation.capacity.verifiedRouteTokens?.toLong())
        observedCount("admission_catalog_context_tokens", observation.capacity.catalogTokens?.toLong())
        val smallerDeclaredPlan = observation.planningContextTokens.takeIf { it in 1 until observation.capacity.tokens }
        val configured = listOfNotNull(observation.capacity.configuredTokens, smallerDeclaredPlan).minOrNull()
        observedCount("admission_configured_context_tokens", configured?.toLong())
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
