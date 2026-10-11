package dev.chungjungsoo.gptmobile.data.marketplace

/** Visible, stable lifecycle state for long marketplace operations. */
enum class MarketplaceOperationPhase {
    QUEUED,
    DOWNLOADING,
    VERIFYING,
    INSTALLING,
    SAVING,
    REMOVING,
    REPAIRING,
    FAILED
}

data class MarketplaceOperationState(
    val operationId: String,
    val packageId: String,
    val phase: MarketplaceOperationPhase,
    val startedAt: Long,
    val updatedAt: Long = startedAt,
    val failureCode: String? = null
)
