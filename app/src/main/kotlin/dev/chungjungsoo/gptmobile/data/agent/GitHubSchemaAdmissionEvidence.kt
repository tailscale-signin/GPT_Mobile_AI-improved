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
        // Engineering or an explicit smaller preparation allowance can further
        // constrain the original configured ceiling without changing the route.
        val samePlanningBoundary = failure.admissionCapacityTokens == planningContextTokens
        val smallerPlanningLimit = planningContextTokens.takeIf { samePlanningBoundary && it > 0 && it < capacity.tokens }
        count("admission_configured_context_tokens", listOfNotNull(capacity.configuredTokens, smallerPlanningLimit).minOrNull())
        put(
            "admission_capacity_provenance",
            when {
                !samePlanningBoundary -> CapacityProvenance.UNKNOWN
                planningContextTokens == capacity.tokens -> capacity.provenance
                smallerPlanningLimit != null -> CapacityProvenance.DECLARED
                else -> CapacityProvenance.UNKNOWN
            }.name
        )
    }
    throw failure.withAdmissionEvidence(evidence)
}
