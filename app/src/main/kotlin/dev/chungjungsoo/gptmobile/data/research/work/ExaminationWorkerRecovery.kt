package dev.chungjungsoo.gptmobile.data.research.work

import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationJob
import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage

/** Foreground/process recovery is resumable; a paused technical stage requires an explicit decision. */
internal fun examinationAutomaticWaitReason(job: ExaminationJob): String? {
    if (job.stage != ExaminationStage.PAUSED) return null
    return when {
        job.cancellationOrigin in setOf("USER_PAUSE", "USER_STOP") -> "USER_PAUSED"
        job.terminalReason.orEmpty().contains("foreground_required") -> null
        job.cancellationOrigin in setOf("PROCESS_RECOVERY", "WAITING_FOR_CONFIGURATION") -> null
        job.cancellationOrigin in setOf("POLICY_CHANGED", "PERMISSION_REVOKED") -> "WAITING_FOR_CONFIGURATION"
        else -> "EXECUTION_PAUSED"
    }
}

internal fun examinationConfigurationBlocked(failure: Exception): Boolean = failure.message.orEmpty().let {
    it.contains("EXAMINER_OUTPUT_CAPACITY") ||
        it.contains("Choose an enabled examination") ||
        it.contains("consent") ||
        it.contains("disabled") ||
        it.contains("reviewer unavailable", true) ||
        it.contains("REQUIRED_REVIEWER_UNAVAILABLE") ||
        it.contains("RESEARCH_TOOL_NOT_READY") ||
        it.contains("policy changed", true) ||
        it.contains("permission changed", true) ||
        it.contains("does not permit article retrieval") ||
        it.contains("Article tools are excluded") ||
        it.contains("Web-only scope") ||
        it.contains("Increase Research Depth") ||
        it.contains("Research is off")
}
