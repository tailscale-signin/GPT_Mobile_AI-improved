package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.github.GatewayJobGroup
import dev.chungjungsoo.gptmobile.data.github.GatewayJobSummary
import dev.chungjungsoo.gptmobile.data.github.gatewayJobGroup

internal fun gatewayJobDisplayGroup(state: String, phase: String? = null): GatewayJobGroup = when {
    state.equals("interrupted", ignoreCase = true) -> GatewayJobGroup.NEEDS_ATTENTION
    state.equals("waiting", ignoreCase = true) && phase?.uppercase(java.util.Locale.ROOT) in setOf("CI", "CHECKS", "WAITING_CI", "WAITING_FOR_CI") -> GatewayJobGroup.RUNNING
    else -> gatewayJobGroup(state)
}

internal fun gatewaySchedulingLabel(state: String): String = when (state) {
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

internal fun gatewayRetryPresentation(job: GatewayJobSummary): GatewayRetryPresentation = when {
    job.retry?.outcome == "NEEDS_INPUT" -> GatewayRetryPresentation.NEEDS_INPUT
    job.retry?.outcome == "INTERRUPTED" -> GatewayRetryPresentation.INTERRUPTED
    job.retry?.outcome == "RESUMED" -> GatewayRetryPresentation.RESUMED
    job.group in setOf(GatewayJobGroup.RUNNING, GatewayJobGroup.QUEUED) && job.providerRetryAt?.let { it.isFinite() && it > 0 } == true -> GatewayRetryPresentation.SCHEDULED
    else -> GatewayRetryPresentation.RECORDED
}

/** A live countdown requires a valid server observation and monotonic elapsed time. */
internal fun gatewayRetryRemainingMillis(retryAt: Double?, serverNow: Double, observedAtElapsed: Long, elapsedNow: Long, fresh: Boolean): Long? {
    if (!fresh || retryAt == null || !retryAt.isFinite() || retryAt <= 0 || !serverNow.isFinite() || serverNow <= 0 || observedAtElapsed <= 0 || elapsedNow < observedAtElapsed) return null
    val elapsed = elapsedNow - observedAtElapsed
    if (elapsed > 30_000) return null
    return ((retryAt - serverNow) * 1000.0 - elapsed).coerceIn(0.0, (Long.MAX_VALUE - 1000).toDouble()).toLong()
}
