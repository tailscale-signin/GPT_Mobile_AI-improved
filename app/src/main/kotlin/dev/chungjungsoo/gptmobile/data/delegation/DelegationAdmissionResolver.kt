package dev.chungjungsoo.gptmobile.data.delegation

import dev.chungjungsoo.gptmobile.data.agent.tool.reservedReviewer
import dev.chungjungsoo.gptmobile.data.agent.tool.reviewerEligible
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import kotlinx.serialization.Serializable

@Serializable
enum class DelegationReviewMode { OFF, BEST_EFFORT, REQUIRED }

data class DelegationAdmission(val worker: PlatformV2?, val reviewer: PlatformV2?, val blocked: Boolean, val explanation: String, val warning: String? = null)

/** No network probes or native loads. Readiness is unknown until independently qualified. */
object DelegationAdmissionResolver {
    fun resolve(config: ModelDelegationSettings, profiles: List<PlatformV2>, primary: PlatformV2): DelegationAdmission {
        val policy = config.normalized()
        val reserved = reservedReviewer(policy, profiles, primary)
        val candidates = DelegationPolicyResolver.ranked(profiles, policy, primary, reserved)
        val worker = if (policy.targetProfileUid.isNotBlank()) candidates.firstOrNull { it.uid == policy.targetProfileUid } else candidates.firstOrNull()
        val reviewer = reserved?.takeIf {
            reviewerEligible(it, policy, primary) && worker != null && CanonicalModelIdentity.independence(it, worker) == ModelIndependence.DIFFERENT
        }
        val required = policy.reviewerEnabled && policy.reviewMode == DelegationReviewMode.REQUIRED
        val reason = when {
            !policy.enabled -> "Delegation is off."
            worker == null -> {
                val selected = profiles.firstOrNull { it.uid == policy.targetProfileUid }
                val details = selected?.let { DelegationPolicyResolver.reasons(it, policy, primary, reserved).joinToString { reason -> reason.name } }
                    ?: if (policy.targetProfileUid.isNotBlank()) "MISSING_PROFILE" else "NO_ELIGIBLE_CANDIDATE"
                "No eligible helper under the current primary, privacy, and profile restrictions: $details."
            }
            policy.reviewerEnabled && reviewer == null -> {
                val details = reserved?.let {
                    (
                        DelegationPolicyResolver.reasons(it, policy.copy(approvedWorkerUids = null), primary).map { reason -> reason.name } +
                            when {
                                CanonicalModelIdentity.independence(it, primary) == ModelIndependence.UNKNOWN || CanonicalModelIdentity.independence(it, worker) == ModelIndependence.UNKNOWN -> listOf("MODEL_INDEPENDENCE_UNKNOWN")
                                CanonicalModelIdentity.independence(it, worker) == ModelIndependence.SAME -> listOf("HELPER_CONFLICT")
                                it.model.isBlank() -> listOf("MISSING_MODEL")
                                else -> emptyList()
                            }
                        ).joinToString()
                }.orEmpty().ifBlank { "REVIEWER_MISSING_OR_CONFLICTING" }
                "Independent reviewer unavailable: $details. ${if (required) "Preparation paused." else "Preparation may proceed with unverified evidence."}"
            }
            else -> "Helper: ${worker.name}; reviewer: ${reviewer?.name ?: "off"}; generation readiness: unknown."
        }
        return DelegationAdmission(worker, reviewer, required && (worker == null || reviewer == null), reason, reason.takeIf { policy.reviewerEnabled && reviewer == null && !required })
    }
}
