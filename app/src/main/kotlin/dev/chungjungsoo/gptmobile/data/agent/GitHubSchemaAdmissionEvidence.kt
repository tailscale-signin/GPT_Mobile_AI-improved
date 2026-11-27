package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.provider.ProviderRouteCapacity
import dev.chungjungsoo.gptmobile.data.context.CapacityProvenance
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bind a preparation failure to its consumed capacity snapshot; never refresh or increase a limit. */
internal fun <T> withGitHubSchemaAdmissionEvidence(
    profileUid: String,
    model: String,
    configuredOutputTokens: Int?,
    capacity: ProviderRouteCapacity.PrimaryContextDecision,
    planningContextTokens: Int,
    plan: () -> T
): T = try {
    plan()
} catch (failure: GitHubToolContextBudgetException) {
    val evidence = buildJsonObject {
        fun count(key: String, value: Int?) {
            value?.takeIf { it in 1..16_000_000 }?.let { put(key, it) }
        }
        profileUid.takeIf(String::isNotBlank)?.let { put("profile_hash", researchHash(it)) }
        model.takeIf(String::isNotBlank)?.let { put("model_hash", researchHash(it)) }
        configuredOutputTokens?.takeIf { it in 0..16_000_000 }?.let { put("configured_output_limit", it) }
        count("admission_route_context_tokens", capacity.verifiedRouteTokens)
        count("admission_catalog_context_tokens", capacity.catalogTokens)
        // Preserve the original profile ceiling; the captured plan carries its own effective capacity.
        val samePlanningBoundary = failure.admissionCapacityTokens == planningContextTokens
        count("admission_configured_context_tokens", capacity.configuredTokens)
        val provenance = if (samePlanningBoundary && planningContextTokens <= capacity.tokens) {
            capacity.toolPlanningProvenance(planningContextTokens, planningContextTokens)
        } else {
            CapacityProvenance.UNKNOWN
        }
        put("admission_capacity_provenance", provenance.name)
    }
    throw failure.withAdmissionEvidence(evidence)
} catch (failure: dev.chungjungsoo.gptmobile.data.context.RequestCapacityException) {
    // Input can fail before schema selection. Bind the same immutable owner and
    // consumed capacity observations, without changing or retrying that admission.
    throw failure.withPlanningObservation(
        dev.chungjungsoo.gptmobile.data.context.RequestCapacityException.PlanningObservation(
            profileUid.takeIf(String::isNotBlank)?.let(::researchHash),
            model.takeIf(String::isNotBlank)?.let(::researchHash),
            configuredOutputTokens,
            capacity,
            planningContextTokens
        )
    )
}
