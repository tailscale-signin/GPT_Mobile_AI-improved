package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.Serializable

/** A conversation override; detailed budgets continue to use the global settings. */
@Serializable
data class ConversationDelegationSettings(
    val enabled: Boolean,
    val targetProfileUid: String = "",
    val allowRemoteWorker: Boolean = false,
    val delegationAmount: Int? = null,
    val researchDepth: Int? = null
) {
    fun applyTo(defaults: ModelDelegationSettings): ModelDelegationSettings = defaults
        .let { if (delegationAmount != null) it.withDelegationAmount(delegationAmount) else it }
        .let { if (researchDepth != null) it.withStrategy(researchDepth) else it }
        .copy(
            enabled = enabled,
            targetProfileUid = targetProfileUid.ifBlank { defaults.targetProfileUid },
            allowRemoteWorkers = defaults.allowRemoteWorkers || allowRemoteWorker,
            fallbackToAnotherProfile = false
        ).normalized()
}
