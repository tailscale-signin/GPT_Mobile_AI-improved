package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.github.GatewayJobGroup
import dev.chungjungsoo.gptmobile.data.github.GatewayJobSummary
import dev.chungjungsoo.gptmobile.data.github.gatewayJobGroup

internal fun gatewayJobDisplayGroup(
    state: String,
    phase: String? = null,
    providerRetryAt: Double? = null,
    retryOutcome: String? = null,
    cancellationRequested: Boolean = false
): GatewayJobGroup {
    val normalizedState = state.trim().uppercase(java.util.Locale.ROOT)
    val group = gatewayJobGroup(normalizedState)
    val stage = phase?.trim()?.uppercase(java.util.Locale.ROOT)
    val requiresUser = cancellationRequested ||
        stage in setOf(
            "MERGE_READY", "APPROVAL", "WAITING_APPROVAL", "AWAITING_APPROVAL",
            "DEVICE", "WAITING_DEVICE", "WAITING_FOR_DEVICE", "DEVICE_VALIDATION", "NEEDS_INPUT",
            "WAITING_FOR_CLIENT_TOOL", "CANCELLING", "BLOCKED", "UNKNOWN_OUTCOME",
            "RECONCILING", "RECOVERY_BLOCKED"
        ) ||
        retryOutcome?.trim()?.uppercase(java.util.Locale.ROOT) in setOf("NEEDS_INPUT", "INTERRUPTED")
    return when {
        group == GatewayJobGroup.RECENT -> group
        requiresUser || normalizedState == "INTERRUPTED" -> GatewayJobGroup.NEEDS_ATTENTION
        normalizedState == "WAITING" && stage in setOf("CI", "CHECKS", "WAITING_CI", "WAITING_FOR_CI") -> GatewayJobGroup.RUNNING
        normalizedState == "WAITING" &&
            retryOutcome?.trim()?.uppercase(java.util.Locale.ROOT) in setOf(null, "SCHEDULED") &&
            providerRetryAt?.let { it.isFinite() && it > 0.0 } == true -> GatewayJobGroup.QUEUED
        else -> group
    }
}

internal fun gatewaySchedulingLabel(state: String): String = when (state.trim().lowercase(java.util.Locale.ROOT)) {
    "queued" -> "queued"
    "running" -> "running"
    "waiting" -> "waiting for input or a checkpoint"
    "completed" -> "completed"
    "failed" -> "failed"
    "cancelled" -> "cancelled"
    "interrupted" -> "interrupted"
    else -> "status unconfirmed"
}

internal fun gatewayPhaseLabel(phase: String): String = when (phase.trim().uppercase(java.util.Locale.ROOT)) {
    "CI", "CHECKS", "WAITING_CI", "WAITING_FOR_CI" -> "Waiting for CI"
    "MERGE_READY", "APPROVAL", "WAITING_APPROVAL", "AWAITING_APPROVAL" -> "Waiting for approval"
    "DEVICE", "WAITING_DEVICE", "WAITING_FOR_DEVICE", "DEVICE_VALIDATION" -> "Waiting for device validation"
    "NEEDS_INPUT" -> "Waiting for input"
    "WAITING_FOR_CLIENT_TOOL" -> "Waiting for this device"
    "PROVIDER_COOLDOWN", "PROVIDER_BACKOFF", "RATE_LIMIT", "RETRY", "WAITING_RETRY" -> "Waiting to retry"
    "QUEUED" -> "Awaiting worker"
    "IMPLEMENT", "IMPLEMENTING" -> "Implementing"
    "INSPECT", "INSPECTING" -> "Inspecting"
    "VERIFY", "VERIFYING" -> "Verifying"
    else -> phase.replace('_', ' ').replaceFirstChar { it.titlecase(java.util.Locale.ROOT) }
}

internal fun gatewayElapsedLabel(millis: Long?): String {
    if (millis == null) return "Not recorded"
    val seconds = millis.coerceAtLeast(0) / 1000
    return when {
        seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m ${seconds % 60}s"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

internal enum class GatewayRetryPresentation { SCHEDULED, RESUMED, NEEDS_INPUT, INTERRUPTED, RECORDED }

internal fun gatewayRetryPresentation(job: GatewayJobSummary): GatewayRetryPresentation {
    val group = gatewayJobDisplayGroup(job.schedulingState ?: job.state, job.phase, job.providerRetryAt, job.retry?.outcome, job.cancellationRequested)
    // Retained retry receipts describe history after a job has ended.
    if (group == GatewayJobGroup.RECENT) return GatewayRetryPresentation.RECORDED
    return when (job.retry?.outcome?.trim()?.uppercase(java.util.Locale.ROOT)) {
        "NEEDS_INPUT" -> GatewayRetryPresentation.NEEDS_INPUT
        "INTERRUPTED" -> GatewayRetryPresentation.INTERRUPTED
        "RESUMED" -> GatewayRetryPresentation.RESUMED
        else -> {
            if (group in setOf(GatewayJobGroup.RUNNING, GatewayJobGroup.QUEUED) && job.providerRetryAt?.let { it.isFinite() && it > 0.0 } == true) {
                GatewayRetryPresentation.SCHEDULED
            } else {
                GatewayRetryPresentation.RECORDED
            }
        }
    }
}

/** A live countdown requires a valid server observation and monotonic elapsed time. */
internal fun gatewayRetryRemainingMillis(retryAt: Double?, serverNow: Double, observedAtElapsed: Long, elapsedNow: Long, fresh: Boolean): Long? {
    if (!fresh || retryAt == null || !retryAt.isFinite() || retryAt <= 0 || !serverNow.isFinite() || serverNow <= 0 || observedAtElapsed <= 0 || elapsedNow < observedAtElapsed) return null
    val elapsed = elapsedNow - observedAtElapsed
    if (elapsed > 30_000) return null
    return ((retryAt - serverNow) * 1000.0 - elapsed).coerceIn(0.0, (Long.MAX_VALUE - 1000).toDouble()).toLong()
}
