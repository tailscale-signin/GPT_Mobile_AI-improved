package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.Serializable

/** Explicit local publication does not rewrite any contributor or writer's raw completion state. */
@Serializable
data class CombinedFallbackReceipt(
    val kind: String,
    val sourceProfileUid: String? = null,
    val sourceRunId: String? = null,
    val sourceCompletionStatus: String? = null,
    val sourceDigest: String,
    val snapshotId: String,
    val predecessorRunId: String
)
