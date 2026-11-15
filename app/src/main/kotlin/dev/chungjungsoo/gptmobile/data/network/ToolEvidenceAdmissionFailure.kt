package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.context.ToolEvidenceContextBudgetException
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ErrorDetail
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Preserve this preparation guard without classifying the earlier work in its run. */
internal fun toolEvidenceAdmissionFailure(error: Exception): ErrorDetail? {
    val refusal = error as? ToolEvidenceContextBudgetException ?: return null
    val allocation = refusal.allocation
    return ErrorDetail(
        message = requireNotNull(refusal.message),
        type = "local_admission",
        code = refusal.code,
        retryable = false,
        failureDetails = buildJsonObject {
            put("code", refusal.code)
            put("diagnostic_area", "PROVIDER")
            put("diagnostic_capture_mode", "INCIDENT")
            put("failed_stage", "provider_admission")
            put("provider_failure_stage", "ADMISSION")
            put("provider_failure_code", refusal.code)
            put("failure_origin", "APPLICATION")
            put("retryable", false)
            put("request_replay_allowed", false)
            put("logical_continuation_allowed", false)
            put("admission_prompt_tokens", allocation.input)
            put("admission_safety_tokens", allocation.safety)
            put("admission_output_tokens", allocation.output)
            put("admission_tool_result_reserve_tokens", allocation.replay)
            put("admission_capacity_tokens", allocation.ceiling)
            put("admission_capacity_provenance", refusal.capacityProvenance.name)
            refusal.observation?.let { observed ->
                fun count(key: String, value: Int?, zeroAllowed: Boolean = false) {
                    value?.takeIf { it in (if (zeroAllowed) 0 else 1)..16_000_000 }?.let { put(key, it) }
                }
                observed.profileHash?.let { put("profile_hash", it) }
                observed.modelHash?.let { put("model_hash", it) }
                count("configured_output_limit", observed.configuredOutputTokens, zeroAllowed = true)
                count("admission_configured_context_tokens", observed.capacity.configuredTokens)
                count("admission_catalog_context_tokens", observed.capacity.catalogTokens)
                count("admission_route_context_tokens", observed.capacity.verifiedRouteTokens)
            }
            // Allocation counts are planning values, distinct from the owned
            // configured limits above and not a serving-tokenizer measurement.
            // A resumed run can have earlier
            // dispatched or uncertain work even when this preparation stopped.
        }
    )
}
