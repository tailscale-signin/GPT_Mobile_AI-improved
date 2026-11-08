package dev.chungjungsoo.gptmobile.presentation.ui.setting

internal fun gatewayJobGroup(state: String, phase: String = ""): String = when {
    state == "waiting" && phase.uppercase(java.util.Locale.ROOT) in setOf("CI", "CHECKS", "WAITING_CI", "WAITING_FOR_CI") -> "Running"
    else -> when (state) {
        "queued" -> "Queued"
        "running" -> "Running"
        "completed", "failed", "cancelled", "interrupted" -> "Recent"
        else -> "Needs attention"
    }
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
    else -> phase.lowercase(java.util.Locale.ROOT).replace('_', ' ').replaceFirstChar { it.titlecase(java.util.Locale.ROOT) }
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
