package dev.chungjungsoo.gptmobile.data.agent

/** The saved retry deadline for the attempt that still owns its visible message. */
data class ProviderWaitDeadline(
    val runId: String,
    val fence: Long,
    val retryAt: Long
)
