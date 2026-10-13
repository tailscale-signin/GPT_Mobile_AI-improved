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
        val reserved = reservedReviewer(config, profiles, primary)
        val candidates = DelegationPolicyResolver.ranked(profiles, config, primary, reserved)
        val worker = if (config.targetProfileUid.isNotBlank()) candidates.firstOrNull { it.uid == config.targetProfileUid } else candidates.firstOrNull()
        val reviewer = reserved?.takeIf {
            reviewerEligible(it, config, primary) && worker != null && CanonicalModelIdentity.independence(it, worker) == ModelIndependence.DIFFERENT
        }
        val required = config.reviewerEnabled && config.reviewMode == DelegationReviewMode.REQUIRED
        val reason = when {
            !config.enabled -> "Delegation is off."
            worker == null -> "No eligible helper under the current primary, privacy, and profile restrictions."
            config.reviewerEnabled && reviewer == null -> {
                val details = reserved?.let {
                    (
                        DelegationPolicyResolver.reasons(it, config.copy(approvedWorkerUids = null), primary).map { reason -> reason.name } +
                            if (CanonicalModelIdentity.independence(it, primary) == ModelIndependence.UNKNOWN || CanonicalModelIdentity.independence(it, worker) == ModelIndependence.UNKNOWN) listOf("MODEL_INDEPENDENCE_UNKNOWN") else emptyList()
                        ).joinToString()
                }.orEmpty().ifBlank { "REVIEWER_MISSING_OR_CONFLICTING" }
                "Independent reviewer unavailable: $details. ${if (required) "Preparation paused." else "Preparation may proceed with unverified evidence."}"
            }
            else -> "Helper: ${worker.name}; reviewer: ${reviewer?.name ?: "off"}; generation readiness: unknown."
        }
        return DelegationAdmission(worker, reviewer, required && (worker == null || reviewer == null), reason, reason.takeIf { config.reviewerEnabled && reviewer == null && !required })
    }
}
