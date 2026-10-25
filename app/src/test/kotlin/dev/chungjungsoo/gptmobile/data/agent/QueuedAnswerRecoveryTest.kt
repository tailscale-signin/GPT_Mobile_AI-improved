package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedAnswerRecoveryTest {
    private val first = "The initial source describes how saved results preserve their original attribution."
    private val second = "The additional source explains that completed remote jobs do not need another generation."

    @Test fun onlyTheMatchingSavedPrefixCanAcceptAContinuation() {
        assertEquals(first, QueuedAnswerRecovery.plain("", first, null))
        assertEquals(first + second, QueuedAnswerRecovery.plain(first, second, researchHash(first)))
        assertTrue(runCatching { QueuedAnswerRecovery.plain(first, second, researchHash("changed")) }.isFailure)
        assertTrue(runCatching { QueuedAnswerRecovery.plain(first, second, null) }.isFailure)
        assertEquals(first, QueuedAnswerRecovery.plain(first, first, null))
    }

    @Test fun cachedCombinedResultGetsAnExactLocalReportWithoutAnotherProviderSession() = runBlocking {
        var validations = 0
        val frozen = snapshot("PLAIN_TEXT")
        val recovered = QueuedAnswerRecovery.combined(first, "", null, frozen, "combined-synthesis:queued", null) { validations++ }
        assertEquals(first, recovered.text)
        assertNotNull(recovered.report)
        assertEquals(researchHash(first), recovered.report!!.answerDigest)
        assertEquals("revision", recovered.report.revisionId)
        assertTrue(validations > 0)
        assertFalse(recovered.report.factualAccuracyVerified)
    }

    @Test fun savedStructuredContinuationReplaysOnlyItsOwnedRecordsAndRetainsTheNewCheckpoint() = runBlocking {
        val frozen = snapshot("PASSAGES_V3")
        val initial = CombinedRecordProtocol(CombinedRecordCheckpoint(snapshotDigest = frozen.inputDigest, revisionId = "revision"), emptySet(), setOf("C1"), emptySet(), 100_000)
        initial.accept(passage(1, first))
        val wire = passage(2, second) + "\n" + """{"type":"end","seq":3,"id":"END","lastSeq":2}"""
        val result = QueuedAnswerRecovery.combined(wire, first, null, frozen, "combined-synthesis:queued", initial.checkpoint()) {}
        assertEquals(first + "\n\n" + second, result.text)
        assertEquals(researchHash(result.text), result.report!!.answerDigest)
        assertTrue(result.recordCheckpoint!!.ended)
        assertEquals(listOf("P1", "P2", "END"), result.recordCheckpoint.records.map { it.id })
        assertEquals("COMPLETED", result.recordCheckpoint.lastAttemptOutcome)
    }

    @Test fun restartAfterSavingAnEndedFencedResultReplaysItsRecordsWithoutReimportingTheWrapper() = runBlocking {
        val frozen = snapshot("PASSAGES_V3")
        val wire = "```jsonl\n" + passage(1, first) + "\n" + """{"type":"end","seq":2,"id":"END","lastSeq":1}""" + "\n```"
        val initial = QueuedAnswerRecovery.combined(wire, "", null, frozen, "combined-synthesis:queued", null) {}
        assertTrue(initial.recordCheckpoint!!.ended)
        val reopened = QueuedAnswerRecovery.combined(wire, "", null, frozen, "combined-synthesis:queued", initial.recordCheckpoint) {}
        assertEquals(first, reopened.text)
        assertEquals(initial.report, reopened.report)
        assertEquals(initial.failure, reopened.failure)
        assertEquals(initial.recordCheckpoint, reopened.recordCheckpoint)
    }

    @Test fun queuedPrematureEndRetainsResumablePassagesAndTheirAtomicAssessmentWithoutGenerating() = runBlocking {
        var validations = 0
        val frozen = lengthSnapshot()
        val wire = passage(1, first) + "\n" + """{"type":"end","seq":2,"id":"END","lastSeq":1}"""
        val recovered = QueuedAnswerRecovery.combined(wire, "", null, frozen, "combined-synthesis:queued", null) { validations++ }
        val checkpoint = requireNotNull(recovered.recordCheckpoint)
        val report = requireNotNull(recovered.report)
        assertEquals(first, recovered.text)
        assertEquals(researchHash(first), report.answerDigest)
        assertEquals("revision", report.revisionId)
        assertFalse(report.complete)
        assertFalse(report.protocolComplete)
        assertEquals("UNMET", report.lengthCompliance)
        assertTrue(recovered.failure.orEmpty().startsWith("COMBINED_RECORD_INCOMPLETE:"))
        assertEquals(listOf("P1"), checkpoint.records.map { it.id })
        assertFalse(checkpoint.ended)
        assertTrue(checkpoint.appendReady)
        assertEquals("COMPLETED", checkpoint.lastAttemptOutcome)
        assertEquals("COMBINED_LENGTH_REQUIREMENT_UNMET", checkpoint.failure)
        assertTrue(checkpoint.pending.contains("\"END\""))
        assertEquals(0, checkpoint.noProgressAttempts)
        assertTrue(validations > 0)
        CombinedAnswerPublication.validate(recovered.text, report)

        val resumed = CombinedRecordProtocol(checkpoint, emptySet(), setOf("C1"), emptySet(), 100_000, continuationAnswerPolicy = frozen.recordContinuationAnswerPolicy())
        assertTrue(resumed.canContinueUnderlength)
        assertTrue(resumed.continuationContext(continueUnderlength = true).contains("\"next_sequence\":2"))
        assertEquals(checkpoint.records, resumed.checkpoint().records)
        assertEquals(0, resumed.checkpoint().malformedRecoveryAttempts)
    }

    @Test fun restartAfterSavingAWithheldEndReplaysTheSamePartialBundleWithoutReimportingTheReceipt() = runBlocking {
        val frozen = lengthSnapshot()
        val wire = "```jsonl\n" + passage(1, first) + "\n" + """{"type":"end","seq":2,"id":"END","lastSeq":1}""" + "\n```"
        val initial = QueuedAnswerRecovery.combined(wire, "", null, frozen, "combined-synthesis:queued", null) {}
        assertFalse(initial.recordCheckpoint!!.ended)
        val reopened = QueuedAnswerRecovery.combined(wire, "", null, frozen, "combined-synthesis:queued", initial.recordCheckpoint) {}
        assertEquals(first, reopened.text)
        assertEquals(initial.report, reopened.report)
        assertEquals(initial.failure, reopened.failure)
        assertEquals(initial.recordCheckpoint, reopened.recordCheckpoint)
    }

    @Test fun strictAndArchivedVersionOneWritersKeepTheirOriginalEndContract() = runBlocking {
        val wire = passage(1, first) + "\n" + """{"type":"end","seq":2,"id":"END","lastSeq":1}"""
        listOf(lengthSnapshot(append = false), lengthSnapshot(writerPlanVersion = 1)).forEach { frozen ->
            assertEquals(null, frozen.recordContinuationAnswerPolicy())
            val recovered = QueuedAnswerRecovery.combined(wire, "", null, frozen, "combined-synthesis:queued", null) {}
            assertEquals(listOf("P1", "END"), recovered.recordCheckpoint!!.records.map { it.id })
            assertTrue(recovered.recordCheckpoint.ended)
            assertTrue(recovered.report!!.protocolComplete)
            assertFalse(recovered.report.complete)
            assertEquals("UNMET", recovered.report.lengthCompliance)
            assertTrue(recovered.failure.orEmpty().startsWith("COMBINED_REQUIREMENTS_UNMET:"))
        }
    }

    private fun passage(sequence: Int, text: String) = """{"type":"passage","seq":$sequence,"id":"P$sequence","text":${Json.encodeToString(text)},"claims":[],"contributors":["C1"],"evidence":[]}"""

    private fun lengthSnapshot(append: Boolean = true, writerPlanVersion: Int = 2): CombinedInputSnapshot {
        val request = "Combine the saved observations in at least 100 words."
        val base = snapshot("PASSAGES_V3")
        val profile = PlatformV2(uid = "p", name = "Profile", compatibleType = ClientType.CUSTOM, model = "model", apiUrl = "https://writer.example/v1", enabled = true, maxTokens = 16_384)
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(continueLongAnswers = append), listOf(profile.uid), mapOf(profile.uid to profile.model), request, singlePass = true)
        val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "root", CombinedRunRole.SYNTHESIS, profile.uid)
        val currentContract = append && writerPlanVersion >= 2
        val system = combinedWriterSystemPrompt(base.systemPrompt, base.deliveryProtocol, request, currentContract)
        val plan = CombinedWriterRequestPlan.freeze(profile, request, base.prompt, system, emptyList(), base.deliveryProtocol, 2048, appendContinuations = currentContract)
        return base.copy(userMessage = base.userMessage.copy(content = request), combined = context, answerContract = CombinedAnswerPolicy.from(request), writerRequestPlan = plan, inputDigest = "").seal()
    }

    private fun snapshot(protocol: String): CombinedInputSnapshot = CombinedInputSnapshot(
        version = 6,
        userMessage = MessageV2(id = 2, chatId = 1, platformType = null, content = "Combine the saved observations."),
        history = emptyList(), replies = emptyList(), sources = listOf(CombinedModelResponse("p", "Profile", content = first + "\n\n" + second)),
        prompt = "Frozen", profileUid = "p", model = "model", systemPrompt = "", configKey = "config", snapshotId = "snapshot", revisionId = "revision", deliveryProtocol = protocol
    ).seal()
}
