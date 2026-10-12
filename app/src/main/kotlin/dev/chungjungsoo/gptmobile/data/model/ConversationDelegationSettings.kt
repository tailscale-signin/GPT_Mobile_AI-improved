package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.Serializable

/** A conversation override; detailed budgets continue to use the global settings. */
@Serializable
data class ConversationDelegationSettings(
    val enabled: Boolean,
    val targetProfileUid: String = "",
    val allowRemoteWorker: Boolean = false,
    val delegationAmount: Int? = null,
    val researchDepth: Int? = null,
    val cloudPermission: dev.chungjungsoo.gptmobile.data.delegation.ScopedPermission = dev.chungjungsoo.gptmobile.data.delegation.ScopedPermission.INHERIT,
    val selectionMode: dev.chungjungsoo.gptmobile.data.delegation.WorkerSelectionMode = dev.chungjungsoo.gptmobile.data.delegation.WorkerSelectionMode.INHERIT
) {
    fun applyTo(defaults: ModelDelegationSettings): ModelDelegationSettings = defaults
        .let { if (delegationAmount != null) it.withDelegationAmount(delegationAmount) else it }
        .let { if (researchDepth != null) it.withStrategy(researchDepth) else it }
        .copy(
            enabled = defaults.enabled && enabled && !(selectionMode == dev.chungjungsoo.gptmobile.data.delegation.WorkerSelectionMode.FIXED && targetProfileUid.isBlank()),
            selectionMode = if (selectionMode == dev.chungjungsoo.gptmobile.data.delegation.WorkerSelectionMode.INHERIT) defaults.selectionMode else selectionMode,
            targetProfileUid = when (selectionMode) {
                dev.chungjungsoo.gptmobile.data.delegation.WorkerSelectionMode.AUTOMATIC -> ""
                dev.chungjungsoo.gptmobile.data.delegation.WorkerSelectionMode.FIXED -> targetProfileUid
                dev.chungjungsoo.gptmobile.data.delegation.WorkerSelectionMode.INHERIT -> targetProfileUid.ifBlank { defaults.targetProfileUid }
            },
            allowRemoteWorkers = defaults.remoteWorkersAllowed() && cloudPermission != dev.chungjungsoo.gptmobile.data.delegation.ScopedPermission.DENY,
            localPlatformsOnly = !defaults.remoteWorkersAllowed() || cloudPermission == dev.chungjungsoo.gptmobile.data.delegation.ScopedPermission.DENY,
            cloudPermission = if (defaults.remoteWorkersAllowed() && cloudPermission != dev.chungjungsoo.gptmobile.data.delegation.ScopedPermission.DENY) defaults.cloudPermission else dev.chungjungsoo.gptmobile.data.delegation.ScopedPermission.DENY,
            fallbackToAnotherProfile = false
        ).normalized()
}
