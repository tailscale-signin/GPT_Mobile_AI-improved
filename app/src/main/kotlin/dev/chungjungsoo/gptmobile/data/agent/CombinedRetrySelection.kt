package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.json.Json

/** The selected old answer remains visible until the replacement is validated and committed. */
internal fun preserveCombinedRetrySelection(current: MessageV2, candidate: MessageV2, run: AgentRun, terminalStatus: String?): MessageV2 {
    if (!run.runId.startsWith("combined-synthesis:")) {
        val accepted = CombinedAcceptedTurn.decode(run.synthesisSnapshot)?.takeIf { it.context.requested.singlePass } ?: return candidate
        val generation = candidate.timeline.lastOrNull { it.generation != null }?.generation
        val usability = dev.chungjungsoo.gptmobile.presentation.ui.chat.CombinedContributionUsability.assess(terminalStatus, candidate.content, allowPartial = true, request = accepted.context.requested.userPrompt, finishReason = generation?.finishReason, outputTokens = generation?.outputTokens, outputAllowance = generation?.outputAllowance)
        val ready = terminalStatus == "COMPLETED" && usability.admitted && !usability.usablePartial
        return candidate.copy(activeRevisionIndex = if (ready) ACTIVE_REVISION_LATEST else current.activeRevisionIndex)
    }
    // Streaming callbacks retain the selection captured when the run started.
    // Once a validated checkpoint selects this revision, later stale callbacks
    // must not select the archived contributor or predecessor again.
    val selectedIndex = current.activeRevisionIndex.takeIf { current.revisions.getOrNull(it)?.content?.isNotBlank() == true }
        ?: current.revisions.indexOfFirst { it.content.isNotBlank() }
    val selected = current.revisions.getOrNull(selectedIndex)
    val snapshot = runCatching {
        Json { ignoreUnknownKeys = true }.decodeFromString<CombinedInputSnapshot>(run.synthesisSnapshot.orEmpty())
    }.getOrNull() ?: return candidate
    if (selected == null && candidate.content.isBlank()) return candidate
    if (snapshot.resumeFromRunId == null && snapshot.sources.isEmpty() && snapshot.evidence.records.isEmpty()) return candidate
    val report = candidate.timeline.lastOrNull { it.combinedQuality != null }?.combinedQuality
    val accepted = terminalStatus == "COMPLETED" &&
        candidate.content.isNotBlank() &&
        report != null &&
        report.complete &&
        !report.correctionRequired &&
        report.answerDigest == researchHash(candidate.content) &&
        report.deliveredScopeStatus in setOf("COMPLETED", "COMPLETED_WITH_LIMITS") &&
        report.issues.none { it in COMBINED_FRAMING_FAILURES }
    val committedPartial = snapshot.deliveryProtocol == "PASSAGES_V3" &&
        candidate.content.isNotBlank() &&
        report != null &&
        report.answerDigest == researchHash(candidate.content) &&
        report.passages.isNotEmpty() &&
        report.passages.all { it.matches(candidate.content) } &&
        report.issues.none { it in COMBINED_FRAMING_FAILURES }
    val fallback = candidate.timeline.lastOrNull { it.combinedFallback != null }?.combinedFallback
    val explicitReply = terminalStatus == "FAILED" &&
        fallback?.kind == "USE_REPLY" &&
        fallback.snapshotId == snapshot.snapshotId &&
        fallback.predecessorRunId == snapshot.resumeFromRunId &&
        fallback.sourceDigest == researchHash(candidate.content) &&
        snapshot.sources.any { source ->
            source.platformUid == fallback.sourceProfileUid &&
                source.attemptId == fallback.sourceRunId &&
                source.completionStatus == fallback.sourceCompletionStatus &&
                source.content == candidate.content
        }
    val priorCombined = selected?.takeIf { it.runId?.startsWith("combined-synthesis:") == true }
    val priorReport = priorCombined?.timeline?.lastOrNull { it.combinedQuality != null }?.combinedQuality
    // A new stream is a candidate until terminal local validation. Contributor drafts
    // can yield to committed passages, but an earlier combined answer stays visible.
    val betterPartial = terminalStatus in setOf("FAILED", "INTERRUPTED", "COMPLETED") &&
        priorCombined != null &&
        priorReport?.complete != true &&
        report != null &&
        !report.correctionRequired &&
        policyDistance(snapshot, candidate.content) < policyDistance(snapshot, priorCombined.content) &&
        scopeFailures(report) <= scopeFailures(priorReport) &&
        CombinedHistoryOutline.periods(candidate.content).containsAll(CombinedHistoryOutline.periods(priorCombined.content))
    val committedReport = current.timeline.lastOrNull { it.combinedQuality != null }?.combinedQuality
    val alreadyAccepted = current.activeRevisionIndex == ACTIVE_REVISION_LATEST &&
        current.currentRunId == run.runId &&
        current.content == candidate.content &&
        committedReport?.complete == true &&
        !committedReport.correctionRequired &&
        committedReport.answerDigest == researchHash(current.content)
    val promote = alreadyAccepted ||
        explicitReply ||
        accepted &&
        (priorReport?.complete != true || scopeFailures(report) <= scopeFailures(priorReport)) ||
        committedPartial &&
        (priorCombined == null || betterPartial)
    if (terminalStatus != null) {
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "CombinedRevision",
            "REVISION_PROMOTION · runId=${run.runId} · promoted=$promote · terminal=$terminalStatus · priorWords=${priorCombined?.content?.let(CombinedWordCounter::count) ?: 0} · candidateWords=${CombinedWordCounter.count(candidate.content)} · reason=${if (promote) "LOCAL_CHECKS_ACCEPTED" else "RETAIN_BETTER_REVISION"}"
        )
    }
    return candidate.copy(activeRevisionIndex = if (promote) ACTIVE_REVISION_LATEST else selectedIndex)
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

private fun policyDistance(snapshot: CombinedInputSnapshot, text: String): Int {
    val policy = snapshot.answerContract ?: CombinedAnswerPolicy.from(snapshot.userMessage.content)
    val words = CombinedWordCounter.count(text)
    val target = policy.targetWords ?: policy.minimumWords ?: policy.maximumWords ?: return -words
    return kotlin.math.abs(target - words)
}

private fun scopeFailures(report: CombinedQualityReport?): Int = report?.checks?.count { it.result == "FAIL" && it.category in setOf("REQUEST", "SCOPE", "RESEARCH_SCOPE") } ?: 0
