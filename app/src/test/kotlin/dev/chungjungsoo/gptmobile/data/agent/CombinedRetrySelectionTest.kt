package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveRunId
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class CombinedRetrySelectionTest {
    private val old = AssistantRevision(content = "Retained useful answer", createdAt = 1, runId = "combined-synthesis:old")
    private val current = MessageV2(platformType = "p", content = "", currentRunId = "combined-synthesis:new", activeRevisionIndex = 0, revisions = listOf(old))
    private val snapshot = CombinedInputSnapshot(userMessage = MessageV2(platformType = null, content = "Request"), history = emptyList(), replies = emptyList(), sources = emptyList(), prompt = "frozen", profileUid = "p", model = "m", systemPrompt = "", configKey = "key", resumeFromRunId = old.runId, deliveryProtocol = "PASSAGES_V2")
    private val run = AgentRun("combined-synthesis:new", 1, 2, 3, "p", "CUSTOM", "m", synthesisSnapshot = Json.encodeToString(CombinedInputSnapshot.serializer(), snapshot))

    private fun candidate(complete: Boolean = true, issues: List<String> = emptyList()) = current.copy(
        content = "Validated replacement",
        activeRevisionIndex = ACTIVE_REVISION_LATEST,
        timeline = listOf(
            AssistantTimelineItem(
                AssistantTimelineItemType.NOTICE,
                combinedQuality = CombinedQualityReport(
                    answerDigest = researchHash("Validated replacement"),
                    complete = complete,
                    deliveredScopeStatus = if (complete) "COMPLETED" else "PARTIAL",
                    issues = issues
                )
            )
        )
    )

    @Test fun cancellationFailureAndIncompleteReplacementKeepVisibleAnswer() {
        for (status in listOf(null, "CANCELED", "INTERRUPTED", "FAILED")) {
            assertEquals(old.content, preserveCombinedRetrySelection(current, candidate(), run, status).effectiveContent())
        }
        assertEquals(old.content, preserveCombinedRetrySelection(current, candidate(false), run, "COMPLETED").effectiveContent())
        assertEquals(old.content, preserveCombinedRetrySelection(current, candidate(issues = listOf("INVALID_DELIVERY_RECORD")), run, "COMPLETED").effectiveContent())
    }

    @Test fun validatedTerminalReplacementSelectsNewRevision() {
        val accepted = preserveCombinedRetrySelection(current, candidate(), run, "COMPLETED")
        assertEquals(ACTIVE_REVISION_LATEST, accepted.activeRevisionIndex)
        assertEquals("Validated replacement", accepted.effectiveContent())
        assertEquals(old, accepted.revisions.single())
    }

    @Test fun staleReportCannotSelectChangedCandidate() {
        assertEquals(old.content, preserveCombinedRetrySelection(current, candidate().copy(content = "Changed after validation"), run, "COMPLETED").effectiveContent())
    }

    @Test fun committedV3PassageRemainsVisibleAfterFailureWithoutClaimingCompletion() {
        val partial = candidate(complete = false).let { candidate ->
            val report = requireNotNull(candidate.timeline.single().combinedQuality).copy(
                passages = listOf(CombinedPassageRecord(0, candidate.content.length, researchHash(candidate.content), setOf("p")))
            )
            candidate.copy(timeline = listOf(candidate.timeline.single().copy(combinedQuality = report)))
        }
        val staged = run.copy(synthesisSnapshot = Json.encodeToString(CombinedInputSnapshot.serializer(), snapshot.copy(deliveryProtocol = "PASSAGES_V3")))
        val retained = preserveCombinedRetrySelection(current, partial, staged, "FAILED")
        assertEquals(partial.content, retained.effectiveContent())
        assertEquals(false, retained.timeline.single().combinedQuality?.complete)
        assertEquals("PARTIAL", retained.timeline.single().combinedQuality?.deliveredScopeStatus)
        assertEquals(old, retained.revisions.single())
    }

    @Test fun uncommittedOrChangedV3PassageCannotReplaceTheSelectedAnswer() {
        val staged = run.copy(synthesisSnapshot = Json.encodeToString(CombinedInputSnapshot.serializer(), snapshot.copy(deliveryProtocol = "PASSAGES_V3")))
        val partial = candidate(complete = false)
        assertEquals(old.content, preserveCombinedRetrySelection(current, partial, staged, "FAILED").effectiveContent())
        val invalid = partial.copy(
            timeline = listOf(
                partial.timeline.single().copy(
                    combinedQuality = requireNotNull(partial.timeline.single().combinedQuality).copy(
                        passages = listOf(CombinedPassageRecord(0, partial.content.length, researchHash("Different passage"), setOf("p")))
                    )
                )
            )
        )
        assertEquals(old.content, preserveCombinedRetrySelection(current, invalid, staged, "FAILED").effectiveContent())
    }

    @Test fun aPromotedV3PassageStaysSelectedWhenLaterCallbacksCarryTheOldSelection() {
        val staged = run.copy(synthesisSnapshot = Json.encodeToString(CombinedInputSnapshot.serializer(), snapshot.copy(deliveryProtocol = "PASSAGES_V3")))
        val stale = candidate(complete = false).let { candidate ->
            candidate.copy(
                activeRevisionIndex = current.activeRevisionIndex,
                timeline = listOf(
                    candidate.timeline.single().copy(
                        combinedQuality = requireNotNull(candidate.timeline.single().combinedQuality).copy(
                            passages = listOf(CombinedPassageRecord(0, candidate.content.length, researchHash(candidate.content), setOf("p")))
                        )
                    )
                )
            )
        }
        val promoted = preserveCombinedRetrySelection(current, stale, staged, null)
        assertEquals(ACTIVE_REVISION_LATEST, promoted.activeRevisionIndex)
        for (status in listOf(null, "COMPLETED", "FAILED", "CANCELED", "INTERRUPTED")) {
            val retained = preserveCombinedRetrySelection(promoted, stale, staged, status)
            assertEquals("Callback status=$status", ACTIVE_REVISION_LATEST, retained.activeRevisionIndex)
            assertEquals(stale.content, retained.effectiveContent())
            assertEquals(run.runId, retained.effectiveRunId())
            assertEquals(listOf(old), retained.revisions)
        }
    }

    @Test fun explicitFrozenReplySelectsOnlyTheRequestedExactSource() {
        val source = CombinedModelResponse("peer", "Peer", "model", "Selected peer reply", completionStatus = "FAILED", attemptId = "peer-attempt")
        val frozen = snapshot.copy(snapshotId = "frozen-input", sources = listOf(source))
        val staged = run.copy(synthesisSnapshot = Json.encodeToString(CombinedInputSnapshot.serializer(), frozen))
        val receipt = CombinedFallbackReceipt("USE_REPLY", source.platformUid, source.attemptId, source.completionStatus, researchHash(source.content), frozen.snapshotId, old.runId!!)
        val candidate = current.copy(
            content = source.content,
            activeRevisionIndex = ACTIVE_REVISION_LATEST,
            timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedFallback = receipt))
        )
        assertEquals(source.content, preserveCombinedRetrySelection(current, candidate, staged, "FAILED").effectiveContent())
        assertEquals(old.content, preserveCombinedRetrySelection(current, candidate, staged, null).effectiveContent())
        for (invalid in listOf(
            receipt.copy(snapshotId = "another-input"),
            receipt.copy(predecessorRunId = "another-run"),
            receipt.copy(sourceProfileUid = "another-profile"),
            receipt.copy(sourceDigest = researchHash("Changed reply"))
        )) {
            val changed = candidate.copy(timeline = listOf(candidate.timeline.single().copy(combinedFallback = invalid)))
            assertEquals(old.content, preserveCombinedRetrySelection(current, changed, staged, "FAILED").effectiveContent())
        }
        assertEquals(old.content, preserveCombinedRetrySelection(current, candidate.copy(content = "Changed reply"), staged, "FAILED").effectiveContent())
    }
}
