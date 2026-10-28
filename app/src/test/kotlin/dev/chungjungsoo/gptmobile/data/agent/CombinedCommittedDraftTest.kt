package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedCommittedDraftTest {
    @Test fun savedPassageIsPublishedAtomicallyBeforeCancellationAndFinalChecks() = runTest {
        val sources = listOf(CombinedModelResponse("p", "Writer", content = "The French Revolution began in 1789."))
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(continueLongAnswers = true), listOf("p"), mapOf("p" to "model"), "history of France", singlePass = true, appendFeatureEnabled = true)
        val execution = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "root", CombinedRunRole.SYNTHESIS, "p")
        val wire = """{"type":"passage","seq":1,"id":"P1","text":"The French Revolution began in 1789.","claims":[],"contributors":["C1"],"evidence":[]}"""
        var saved = false
        val events = mutableListOf<ProviderEvent>()
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                emit(ProviderEvent.TextDelta(wire))
                assertTrue(events.any { it is ProviderEvent.CombinedAnswer })
                throw CancellationException("Process interrupted after the first saved passage")
            }
        }
        val protocol = CombinedRecordProtocol(CombinedRecordCheckpoint(snapshotDigest = "snapshot", revisionId = "revision"), emptySet(), setOf("C1"), emptySet(), 10000)
        val recovery = CombinedRecordRecoverySession(provider, protocol, 2, acceptedAppend = true, validateAccess = {}, save = { saved = it.records.isNotEmpty() }) { _, _ -> error("Cancellation must not dispatch again") }
        val delivery = CombinedDeliverySession(recovery, sources, 10000, validateAccess = {}, originalRequest = "history of France", structured = true, recordProtocol = true, combined = execution, revisionId = "revision") { _, _ -> error("No repair") }
        val failure = runCatching {
            delivery.streamRound(emptyList(), emptyList()).collect {
                if (it is ProviderEvent.CombinedAnswer) {
                    assertTrue(saved)
                    CombinedAnswerPublication.validate(it.text, it.report)
                    assertFalse(it.report.complete)
                    assertFalse(it.report.finalAssessment)
                    assertEquals(CombinedCommittedDraft.STAGE, it.report.assessmentStage)
                }
                events += it
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, events.filterIsInstance<ProviderEvent.CombinedAnswer>().size)
        assertTrue(events.none { it == ProviderEvent.Completed })
    }

    @Test fun draftCannotForgeCompletionVerificationOrMetrics() {
        val text = "Saved unfinished answer."
        val draft = CombinedCommittedDraft.report(text, "revision", emptyList(), setOf("p"))
        CombinedAnswerPublication.validate(text, draft)
        val invalid = listOf(
            draft.copy(complete = true), draft.copy(protocolComplete = true),
            draft.copy(finalAssessment = true), draft.copy(semanticCoverageVerified = true),
            draft.copy(factualAccuracyVerified = true), draft.copy(deliveredScopeStatus = "COMPLETED"), draft.copy(status = "COMPLETED"),
            draft.copy(displayedCharacters = 0), draft.copy(answerDigest = "incorrect")
        )
        invalid.forEach { report -> assertTrue(runCatching { CombinedAnswerPublication.validate(text, report) }.isFailure) }
    }
}
