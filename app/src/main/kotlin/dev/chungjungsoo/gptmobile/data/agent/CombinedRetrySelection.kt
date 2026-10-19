package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.json.Json

/** The selected old answer remains visible until the replacement is validated and committed. */
internal fun preserveCombinedRetrySelection(current: MessageV2, candidate: MessageV2, run: AgentRun, terminalStatus: String?): MessageV2 {
    if (!run.runId.startsWith("combined-synthesis:")) return candidate
    val selected = current.revisions.getOrNull(current.activeRevisionIndex) ?: return candidate
    val snapshot = runCatching {
        Json { ignoreUnknownKeys = true }.decodeFromString<CombinedInputSnapshot>(run.synthesisSnapshot.orEmpty())
    }.getOrNull() ?: return candidate
    if (snapshot.resumeFromRunId == null || selected.content.isBlank()) return candidate
    val report = candidate.timeline.lastOrNull { it.combinedQuality != null }?.combinedQuality
    val accepted = terminalStatus == "COMPLETED" &&
        candidate.content.isNotBlank() &&
        report != null &&
        report.complete &&
        !report.correctionRequired &&
        report.answerDigest == researchHash(candidate.content) &&
        report.deliveredScopeStatus in setOf("COMPLETED", "COMPLETED_WITH_LIMITS") &&
        report.issues.none { it in COMBINED_FRAMING_FAILURES }
    return candidate.copy(activeRevisionIndex = if (accepted) ACTIVE_REVISION_LATEST else current.activeRevisionIndex)
}

internal val COMBINED_FRAMING_FAILURES = setOf(
    "INVALID_DELIVERY_RECORD",
    "UNKNOWN_SOURCE_REFERENCE",
    "UNKNOWN_PROFILE_REFERENCE",
    "UNKNOWN_CLAIM_REFERENCE",
    "UNRESOLVED_CITATION_URL",
    "INTERNAL_RECOVERY_MARKER_REJECTED",
    "INVALID_CLAIM_DECISION"
)
