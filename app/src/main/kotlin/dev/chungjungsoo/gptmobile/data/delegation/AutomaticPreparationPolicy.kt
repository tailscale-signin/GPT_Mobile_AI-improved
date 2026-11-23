package dev.chungjungsoo.gptmobile.data.delegation

import dev.chungjungsoo.gptmobile.data.agent.RoutineTaskPolicy
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings

/** Skip optional automatic preparation before lookup, loading, accounting or network work. */
internal object AutomaticPreparationPolicy {
    fun skipReason(
        task: String,
        config: ModelDelegationSettings,
        automatic: Boolean,
        explicitTarget: Boolean = false,
        explicitResearchGoal: Boolean = false
    ): String? {
        if (!automatic || !config.enabled || !config.automaticResearch || !config.researchEnabled) return null
        if (explicitTarget || explicitResearchGoal || config.targetProfileUid.isNotBlank() || config.selectionMode != WorkerSelectionMode.AUTOMATIC) return null
        if (config.processingOwnership < 50 || config.reviewerEnabled || config.reviewMode == DelegationReviewMode.REQUIRED) return null
        if (config.taskPlan.steps.isNotEmpty() || config.templateRole != null || config.deepResearch.enabled) return null
        val kind = RoutineTaskPolicy.classify(task) ?: return null
        return "AUTOMATIC_PREPARATION_NOT_NEEDED_${kind.name}"
    }
}
