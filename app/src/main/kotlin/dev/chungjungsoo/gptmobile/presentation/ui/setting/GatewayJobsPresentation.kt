package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.github.GatewayJobGroup
import dev.chungjungsoo.gptmobile.data.github.GatewayJobSummary
import dev.chungjungsoo.gptmobile.data.github.gatewayJobGroup

internal fun gatewayJobDisplayGroup(
    state: String,
    phase: String? = null,
    providerRetryAt: Double? = null,
    retryOutcome: String? = null
): GatewayJobGroup {
    val stage = phase?.uppercase(java.util.Locale.ROOT)
    val attentionPhase = stage in setOf(
        "MERGE_READY", "APPROVAL", "WAITING_APPROVAL", "AWAITING_APPROVAL",
        "DEVICE", "WAITING_DEVICE", "WAITING_FOR_DEVICE", "NEEDS_INPUT",
        "WAITING_FOR_CLIENT_TOOL", "CANCELLING", "BLOCKED", "UNKNOWN_OUTCOME",
        "RECONCILING", "RECOVERY_BLOCKED"
    )
    val attentionOutcome = retryOutcome?.uppercase(java.util.Locale.ROOT) in setOf("NEEDS_INPUT", "INTERRUPTED")
    val waiting = state.equals("waiting", ignoreCase = true)
    val ciWait = stage in setOf("CI", "CHECKS", "WAITING_CI", "WAITING_FOR_CI")
    val retryScheduled = providerRetryAt?.let { it.isFinite() && it > 0.0 } == true
    return when {
        state.equals("interrupted", ignoreCase = true) -> GatewayJobGroup.NEEDS_ATTENTION
        waiting && (attentionPhase || attentionOutcome) -> GatewayJobGroup.NEEDS_ATTENTION
        waiting && ciWait -> GatewayJobGroup.RUNNING
        waiting && retryScheduled -> GatewayJobGroup.QUEUED
        else -> gatewayJobGroup(state)
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

internal fun gatewayPhaseLabel(phase: String): String = when (phase.uppercase(java.util.Locale.ROOT)) {
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

internal fun gatewayRetryPresentation(job: GatewayJobSummary): GatewayRetryPresentation = when (job.retry?.outcome?.uppercase(java.util.Locale.ROOT)) {
    "NEEDS_INPUT" -> GatewayRetryPresentation.NEEDS_INPUT
    "INTERRUPTED" -> GatewayRetryPresentation.INTERRUPTED
    "RESUMED" -> GatewayRetryPresentation.RESUMED
    else -> {
        val group = gatewayJobDisplayGroup(job.schedulingState ?: job.state, job.phase, job.providerRetryAt, job.retry?.outcome)
        if (group in setOf(GatewayJobGroup.RUNNING, GatewayJobGroup.QUEUED) && job.providerRetryAt?.let { it.isFinite() && it > 0.0 } == true) {
            GatewayRetryPresentation.SCHEDULED
        } else {
            GatewayRetryPresentation.RECORDED
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
