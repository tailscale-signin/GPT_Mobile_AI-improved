package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GitHubHandoffReconciliationEvidenceTest {
    private val call = ProviderEvent.ToolCall("PRIVATE_CALL", "github_write", buildJsonObject { put("argument", "PRIVATE_ARGUMENT") })
    private val binding = "PRIVATE_TOOL_BINDING"
    private val evidenceDigest = "a".repeat(64)
    private val journalDigest = "b".repeat(64)

    private fun session(vararg events: ProviderEvent) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flowOf(*events)
    }

    private suspend fun handoff(vararg calls: ProviderEvent.ToolCall, savedBinding: String = binding): JsonObject {
        var checkpoint: JsonObject? = null
        GitHubOutputContinuationSession(
            session(*calls, ProviderEvent.Completed),
            checkpointContext = buildJsonObject { put("tool_checkpoint_binding", savedBinding) },
            saveCheckpoint = { checkpoint = it }
        ) { _, _, _ -> error("A new handoff must not reopen generation") }
            .streamRound(emptyList(), emptyList()).toList()
        return requireNotNull(checkpoint).also { assertEquals(JsonPrimitive("TOOL_HANDOFF"), it["state"]) }
    }

    private fun result(call: ProviderEvent.ToolCall, unknown: Boolean = false) = AgentToolResult(
        call.callId,
        ToolResultContent.Text("PRIVATE_RESULT"),
        unknown,
        dispatched = true,
        errorCode = "TOOL_OUTCOME_UNKNOWN".takeIf { unknown }
    )

    private fun durable(call: ProviderEvent.ToolCall, unknown: Boolean = false) =
        ToolHandoffReceipt.from(call, result(call, unknown)).copy(receiptSource = "TOOL_BATCH_CHECKPOINT")

    @Test fun missingReceiptsCaptureTheObservedLookupWithoutAnotherReadOrDispatch() = runTest {
        val saved = handoff(call)
        var reads = 0
        var lookups = 0
        var writes = 0
        val events = GitHubOutputContinuationSession(
            session(ProviderEvent.TextDelta("Must not generate")),
            toolCheckpointBinding = binding,
            toolCheckpointEvidenceDigest = evidenceDigest,
            toolCheckpointJournalDigest = journalDigest,
            readCheckpoint = {
                reads++
                saved
            },
            saveCheckpoint = { writes++ },
            durableToolReceipts = { requested ->
                lookups++
                assertEquals(setOf(ToolHandoffReceipt.fingerprint(call)), requested)
                emptyList()
            }
        ) { _, _, _ -> error("Missing receipts cannot authorize another request") }
            .streamRound(emptyList(), emptyList()).toList()
        val failure = events.single() as ProviderEvent.Failed
        val details = requireNotNull(failure.failureDetails)
        assertEquals("OUTPUT_CONTINUATION_PENDING_TOOLS", failure.errorCode)
        assertEquals("BLOCKED", failure.taskOutcome)
        assertEquals(JsonPrimitive("TOOL_HANDOFF"), details["handoff_checkpoint_state"])
        assertEquals(JsonPrimitive("RETURNED_EMPTY"), details["handoff_durable_lookup"])
        assertEquals(JsonPrimitive("TERMINAL_RECEIPTS_MISSING"), details["pending_tool_receipt_state"])
        assertEquals(JsonPrimitive(1), details["pending_tool_count"])
        for (key in countFields) assertEquals(key, JsonPrimitive(0), details[key])
        assertEquals(JsonPrimitive(false), details["dispatched"])
        assertEquals(JsonPrimitive(false), details["resume_allowed"])
        assertEquals(JsonPrimitive(true), details["requires_reconciliation"])
        assertEquals(details["handoff_tool_binding_hash"], details["handoff_saved_tool_binding_hash"])
        for (key in hashFields) assertTrue(key, (details[key] as JsonPrimitive).content.matches(Regex("[a-f0-9]{64}")))
        assertFalse(details.toString().contains("PRIVATE_"))
        assertFalse(details.toString().contains(evidenceDigest))
        assertFalse(details.toString().contains(journalDigest))
        assertEquals(1, reads)
        assertEquals(1, lookups)
        assertEquals(0, writes)
    }

    @Test fun unrelatedReceiptsRemainUnmatchedAndDoNotReleaseTheSavedHandoff() = runTest {
        val saved = handoff(call, savedBinding = "PRIVATE_SAVED_BINDING")
        val unrelated = call.copy(callId = "PRIVATE_OTHER_CALL")
        val exchange = AgentToolExchange(listOf(unrelated), listOf(result(unrelated)))
        val events = GitHubOutputContinuationSession(
            session(ProviderEvent.TextDelta("Must not generate")),
            toolCheckpointBinding = "PRIVATE_SELECTED_BINDING",
            readCheckpoint = { saved },
            durableToolReceipts = { listOf(durable(unrelated)) }
        ) { _, _, _ -> error("An unrelated receipt must not release the handoff") }
            .streamRound(emptyList(), listOf(exchange)).toList()
        val details = requireNotNull((events.single() as ProviderEvent.Failed).failureDetails)
        assertEquals(JsonPrimitive("RETURNED_RECEIPTS"), details["handoff_durable_lookup"])
        assertEquals(JsonPrimitive(1), details["handoff_replay_receipt_count"])
        assertEquals(JsonPrimitive(1), details["handoff_durable_receipt_count"])
        for (key in listOf("handoff_replay_matched_count", "handoff_durable_matched_count", "handoff_retained_matched_count")) assertEquals(JsonPrimitive(0), details[key])
        assertFalse(details["handoff_tool_binding_hash"] == details["handoff_saved_tool_binding_hash"])
        assertFalse(details.toString().contains("PRIVATE_"))
    }

    @Test fun matchingInvocationFromADifferentJournalCannotSettleTheSavedHandoff() = runTest {
        val saved = handoff(call, savedBinding = "original")
        val resumed = GitHubOutputContinuationSession(
            session(ProviderEvent.TextDelta("Must not generate")),
            toolCheckpointBinding = "other",
            readCheckpoint = { saved },
            durableToolReceipts = { listOf(durable(call)) },
            saveCheckpoint = { error("A different journal cannot update this handoff") }
        ) { _, _, _ -> error("Must not reopen") }
        val replay = listOf(AgentToolExchange(listOf(call), listOf(result(call))))
        resumed.observeToolResult(call, result(call))
        resumed.restoreToolResults(replay)
        val failed = resumed.streamRound(emptyList(), replay).toList().single() as ProviderEvent.Failed
        assertTrue(failed.message.contains("different saved tool journal"))
        assertEquals(JsonPrimitive(false), failed.failureDetails?.get("dispatched"))
        assertEquals(JsonPrimitive(false), failed.failureDetails?.get("resume_allowed"))
    }

    @Test fun matchingCountsDescribeOnlyTheRemainingUnresolvedInvocation() = runTest {
        val settled = call.copy(callId = "PRIVATE_SETTLED_CALL")
        var saved = handoff(settled, call)
        val staleSuccess = AgentToolExchange(listOf(settled, call), listOf(result(settled), result(call)))
        val events = GitHubOutputContinuationSession(
            session(ProviderEvent.TextDelta("Must not generate")),
            readCheckpoint = { saved },
            saveCheckpoint = { saved = it },
            durableToolReceipts = { listOf(durable(settled), durable(call, unknown = true)) }
        ) { _, _, _ -> error("A stale success cannot replace the durable unknown outcome") }
            .streamRound(emptyList(), listOf(staleSuccess)).toList()
        val details = requireNotNull((events.single() as ProviderEvent.Failed).failureDetails)
        assertEquals(JsonPrimitive(1), details["pending_tool_count"])
        assertEquals(JsonPrimitive(2), details["handoff_replay_receipt_count"])
        assertEquals(JsonPrimitive(2), details["handoff_durable_receipt_count"])
        for (key in listOf("handoff_replay_matched_count", "handoff_durable_matched_count", "handoff_retained_matched_count")) assertEquals(JsonPrimitive(1), details[key])
        assertEquals(JsonPrimitive("OUTCOMES_UNRESOLVED"), details["pending_tool_receipt_state"])
        assertEquals(setOf(ToolHandoffReceipt.fingerprint(call)), GitHubToolHandoff.pending(saved))
        val retained = (saved["completed_results"] as JsonArray).filterIsInstance<JsonObject>().single { it["call_id"] == JsonPrimitive(call.callId) }
        assertEquals(JsonPrimitive("TOOL_OUTCOME_UNKNOWN"), retained["error_code"])
        assertEquals(JsonPrimitive(true), retained["dispatched"])
        assertEquals(JsonPrimitive(false), details["dispatched"])
    }

    @Test fun matchingTerminalReceiptStillAllowsTheOriginalNextRound() = runTest {
        var saved = handoff(call)
        var requests = 0
        var lookups = 0
        val exchange = AgentToolExchange(listOf(call), listOf(result(call)))
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                requests++
                assertEquals(listOf(exchange), exchanges)
                emit(ProviderEvent.TextDelta("Continued from the confirmed result"))
                emit(ProviderEvent.Completed)
            }
        }
        val events = GitHubOutputContinuationSession(
            provider,
            readCheckpoint = { saved },
            saveCheckpoint = { saved = it },
            durableToolReceipts = {
                lookups++
                listOf(durable(call))
            }
        ) { _, _, _ -> error("A normal handoff resumes the original session") }
            .streamRound(emptyList(), listOf(exchange)).toList()
        assertEquals(1, requests)
        assertEquals(1, lookups)
        assertFalse(events.any { it is ProviderEvent.Failed || it is ProviderEvent.ToolCall })
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test fun everyBlockedRoundOwnsAFreshImmutableLookupSnapshot() = runTest {
        var saved = handoff(call)
        var receipts = emptyList<ToolHandoffReceipt>()
        var lookups = 0
        val gate = GitHubOutputContinuationSession(
            session(ProviderEvent.TextDelta("Must not generate")),
            readCheckpoint = { saved },
            saveCheckpoint = { saved = it },
            durableToolReceipts = {
                lookups++
                receipts
            }
        ) { _, _, _ -> error("An unresolved outcome cannot authorize continuation") }
        suspend fun failureDetails() = requireNotNull((gate.streamRound(emptyList(), emptyList()).toList().single() as ProviderEvent.Failed).failureDetails)
        val first = failureDetails()
        receipts = listOf(durable(call, unknown = true))
        val second = failureDetails()
        receipts = emptyList()
        val third = failureDetails()
        assertEquals(JsonPrimitive("RETURNED_EMPTY"), first["handoff_durable_lookup"])
        assertEquals(JsonPrimitive(0), first["handoff_retained_matched_count"])
        assertEquals(JsonPrimitive("RETURNED_RECEIPTS"), second["handoff_durable_lookup"])
        assertEquals(JsonPrimitive(1), second["handoff_durable_matched_count"])
        assertEquals(JsonPrimitive("RETURNED_EMPTY"), third["handoff_durable_lookup"])
        assertEquals(JsonPrimitive(0), third["handoff_durable_matched_count"])
        assertEquals(JsonPrimitive(1), third["handoff_retained_matched_count"])
        assertEquals(JsonPrimitive(0), first["handoff_durable_matched_count"])
        assertEquals(3, lookups)
    }

    @Test fun failedReceiptLookupStillPropagatesWithoutInventingMissingReceiptEvidence() = runTest {
        val saved = handoff(call)
        val original = IllegalStateException("PRIVATE_LOOKUP_FAILURE")
        try {
            GitHubOutputContinuationSession(
                session(ProviderEvent.TextDelta("Must not generate")),
                readCheckpoint = { saved },
                durableToolReceipts = { throw original }
            ) { _, _, _ -> error("A failed lookup must not authorize continuation") }
                .streamRound(emptyList(), emptyList()).toList()
            fail("Expected original lookup failure")
        } catch (error: IllegalStateException) {
            assertSame(original, error)
        }
    }

    @Test fun projectionOmitsInvalidIdentityAndCountInputsWithoutTruncatingThem() = runTest {
        val saved = handoff(call)
        val unrelated = durable(call.copy(callId = "PRIVATE_OTHER_CALL"))
        val evidence = GitHubHandoffReconciliationEvidence.capture(
            JsonObject(saved + ("context" to buildJsonObject { put("tool_checkpoint_binding", true) })),
            List(1025) { unrelated },
            emptyList(),
            "x".repeat(4097),
            "PRIVATE_INVALID_DIGEST",
            null
        )
        assertNull(evidence["handoff_replay_receipt_count"])
        assertEquals(JsonPrimitive(0), evidence["handoff_replay_matched_count"])
        for (key in hashFields) assertNull(key, evidence[key])
        assertFalse(evidence.toString().contains("PRIVATE_"))
        assertTrue(evidence.keys.all { it.startsWith("handoff_") })
        for (key in listOf("dispatched", "resume_allowed", "requires_reconciliation", "request_replay_allowed")) assertNull(evidence[key])
    }

    private val countFields = listOf("handoff_replay_receipt_count", "handoff_replay_matched_count", "handoff_durable_receipt_count", "handoff_durable_matched_count", "handoff_retained_matched_count")
    private val hashFields = listOf("handoff_tool_binding_hash", "handoff_saved_tool_binding_hash", "handoff_evidence_digest_hash", "handoff_journal_digest_hash")
}
