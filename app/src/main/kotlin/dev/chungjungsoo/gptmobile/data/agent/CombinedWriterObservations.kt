package dev.chungjungsoo.gptmobile.data.agent

import java.util.concurrent.ConcurrentHashMap

/** Observations expire and are scoped to actual endpoint/model/routing, never a provider-wide promise. */
internal object CombinedWriterObservations {
    data class Observation(val reasoningDisabled: String, val reasoningOnlyAttempts: Int, val updatedAt: Long, val completedPassages: Int = 0, val malformedAttempts: Int = 0, val emptyAttempts: Int = 0, val queueDelays: Int = 0, val reasoningControl: String = "UNVERIFIED", val servedModel: String? = null, val backend: String? = null, val minimumUsefulOutputAllowance: Int? = null, val stalledOutputAllowance: Int? = null, val reasoningTokensHighWater: Int = 0, val completeAnswers: Int = 0, val incompleteAnswers: Int = 0, val observedPassages: Int = 0, val disableRequested: Boolean? = null, val transmitsDisable: Boolean? = null, val providerControlSupport: String = "UNVERIFIED", val reasoningObserved: Boolean? = null)
    private val resolved = ConcurrentHashMap<String, String>()
    private val observed = ConcurrentHashMap<String, Observation>()
    fun get(key: String): Observation? = observed[resolved[key] ?: key]?.takeIf { System.currentTimeMillis() - it.updatedAt < 24 * 60 * 60_000L }
    fun record(key: String, reasoning: Boolean, visiblePassages: Int, outputAllowance: Int? = null) {
        if (observed.size >= 256 && !observed.containsKey(key)) observed.clear()
        val previous = get(key)
        observed[resolved[key] ?: key] = Observation(
            if ((previous?.reasoningTokensHighWater ?: 0) > 0 || reasoning) {
                if (previous?.transmitsDisable == true) "NOT_HONORED" else "DISABLE_UNVERIFIED"
            } else if (visiblePassages > 0) {
                "OBSERVED_NO_REASONING"
            } else {
                "UNKNOWN"
            },
            if (reasoning && visiblePassages == 0) (previous?.reasoningOnlyAttempts ?: 0) + 1 else 0,
            System.currentTimeMillis(),
            completedPassages = previous?.completedPassages ?: 0,
            observedPassages = (previous?.observedPassages ?: 0) + visiblePassages,
            malformedAttempts = previous?.malformedAttempts ?: 0,
            emptyAttempts = if (!reasoning && visiblePassages == 0) (previous?.emptyAttempts ?: 0) + 1 else 0,
            queueDelays = previous?.queueDelays ?: 0,
            reasoningControl = previous?.reasoningControl ?: "UNVERIFIED", servedModel = previous?.servedModel, backend = previous?.backend,
            minimumUsefulOutputAllowance = previous?.minimumUsefulOutputAllowance,
            stalledOutputAllowance = if (reasoning && visiblePassages == 0) listOfNotNull(previous?.stalledOutputAllowance, outputAllowance).maxOrNull() else previous?.stalledOutputAllowance,
            reasoningTokensHighWater = previous?.reasoningTokensHighWater ?: 0, completeAnswers = previous?.completeAnswers ?: 0, incompleteAnswers = previous?.incompleteAnswers ?: 0,
            disableRequested = previous?.disableRequested, transmitsDisable = previous?.transmitsDisable, providerControlSupport = previous?.providerControlSupport ?: "UNVERIFIED",
            reasoningObserved = if (reasoning || (previous?.reasoningTokensHighWater ?: 0) > 0) true else false.takeIf { visiblePassages > 0 } ?: previous?.reasoningObserved
        )
    }
    fun recordUsage(key: String, reasoningTokens: Int?, finishReason: String? = null, outputAllowance: Int? = null) {
        val limited = finishReason?.lowercase() in setOf("length", "max_tokens", "output_limit")
        if ((reasoningTokens ?: 0) <= 0 && !limited) return
        val previous = get(key) ?: Observation("UNKNOWN", 0, System.currentTimeMillis())
        val observedReasoning = (reasoningTokens ?: 0) > 0
        val reasoningControlViolated = previous.disableRequested == true &&
            (
                previous.transmitsDisable == true ||
                    (outputAllowance != null && outputAllowance > 0 && (reasoningTokens ?: 0) > outputAllowance)
                )
        val reasoningDisabled = if (observedReasoning) {
            if (reasoningControlViolated) {
                "NOT_HONORED"
            } else {
                "DISABLE_UNVERIFIED"
            }
        } else {
            previous.reasoningDisabled
        }
        observed[resolved[key] ?: key] = previous.copy(
            updatedAt = System.currentTimeMillis(),
            // Reject routes that report reasoning despite an explicit disable request,
            // especially when usage exceeds the provider's own output allowance.
            reasoningDisabled = reasoningDisabled,
            reasoningObserved = true.takeIf { observedReasoning } ?: previous.reasoningObserved,
            reasoningTokensHighWater = maxOf(previous.reasoningTokensHighWater, reasoningTokens ?: 0),
            stalledOutputAllowance = if (limited) {
                listOfNotNull(previous.stalledOutputAllowance, outputAllowance?.takeIf { it > 0 }).maxOrNull()
            } else {
                previous.stalledOutputAllowance
            }
        )
    }
    fun recordDelivery(key: String, report: CombinedQualityReport, outputAllowance: Int?) {
        if (!report.finalAssessment || report.answerDigest.isBlank() || report.deliveredScopeStatus in setOf("CHECKING", "PENDING")) return
        val previous = get(key) ?: Observation("UNKNOWN", 0, System.currentTimeMillis())
        val complete = report.complete && !report.correctionRequired && report.deliveredScopeStatus in setOf("COMPLETED", "COMPLETED_WITH_LIMITS") && report.lengthCompliance == "MET" && report.topicCoverage == "LOCAL_CHECKS_PASSED"
        observed[resolved[key] ?: key] = previous.copy(
            updatedAt = System.currentTimeMillis(),
            completeAnswers = previous.completeAnswers + if (complete) 1 else 0,
            completedPassages = previous.completedPassages + if (complete) report.passages.size else 0,
            incompleteAnswers = previous.incompleteAnswers + if (complete) 0 else 1,
            minimumUsefulOutputAllowance = if (complete) listOfNotNull(previous.minimumUsefulOutputAllowance, outputAllowance).minOrNull() else previous.minimumUsefulOutputAllowance
        )
    }
    fun recordFailure(key: String, category: CombinedRecoveryCategory) {
        val previous = get(key) ?: Observation("UNKNOWN", 0, System.currentTimeMillis())
        observed[resolved[key] ?: key] = previous.copy(updatedAt = System.currentTimeMillis(), malformedAttempts = previous.malformedAttempts + if (category == CombinedRecoveryCategory.MALFORMED_RECORD) 2 else 0, queueDelays = previous.queueDelays + if (category == CombinedRecoveryCategory.QUEUE_PENDING) 1 else 0)
    }
    fun recordRoute(key: String, model: String?, backend: String?, control: String?, reasoning: Boolean, disableRequested: Boolean = true, transmitsDisable: Boolean = control in setOf("reasoning.enabled=false", "chat_template_kwargs.enable_thinking=false", "reasoning_effort=none"), providerControlSupport: String = "UNVERIFIED"): String {
        if (resolved.size >= 256 && !resolved.containsKey(key)) resolved.clear()
        val actual = "$key|served=${model ?: "unreported"}|backend=${backend ?: "unreported"}"
        resolved[key] = actual
        val prior = observed[actual] ?: Observation("UNKNOWN", 0, System.currentTimeMillis())
        observed[actual] = prior.copy(updatedAt = System.currentTimeMillis(), reasoningDisabled = if (reasoning) if (transmitsDisable) "NOT_HONORED" else "DISABLE_UNVERIFIED" else prior.reasoningDisabled, reasoningControl = control ?: "UNVERIFIED", servedModel = model, backend = backend, disableRequested = disableRequested, transmitsDisable = transmitsDisable, providerControlSupport = providerControlSupport, reasoningObserved = true.takeIf { reasoning } ?: prior.reasoningObserved)
        return actual
    }
    internal fun clearForTest() {
        observed.clear()
        resolved.clear()
    }
    fun key(profile: dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2): String = "${profile.compatibleType}|${profile.apiUrl}|${profile.model}|${profile.openRouterRouting.orEmpty()}"
}
