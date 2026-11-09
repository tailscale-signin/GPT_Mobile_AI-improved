package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reproduces the captured receipt/replay crash gap at the runner's real continuation boundary. */
class GitHubAuditRecoveryTest {
    private val tool = AgentToolDefinition("github_read", "Read pinned source", buildJsonObject { put("type", "object") })
    private fun call(id: String) = ProviderEvent.ToolCall(id, tool.name, buildJsonObject { put("path", "$id.kt") })
    private fun provider(vararg events: ProviderEvent) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flowOf(*events)
    }

    @Test fun liveCompletionClearsPendingBeforeTheNextModelRequest() = runBlocking {
        val batch = ToolBatchCheckpoint(null, "task")
        var output: JsonObject? = null
        val calls = listOf(call("pr"), call("workflow"), call("checks"))
        val session = GitHubOutputContinuationSession(
            provider(*calls.toTypedArray(), ProviderEvent.Completed),
            durableToolReceipts = batch::handoffReceipts,
            readCheckpoint = { output },
            saveCheckpoint = { output = it }
        ) { _, _, _ -> error("No continuation should occur before results.") }
        session.streamRound(listOf(tool), emptyList()).toList()
        batch.enqueue(calls)
        calls.forEachIndexed { index, call ->
            val result = AgentToolResult(call.callId, ToolResultContent.Text("source-$index"), false)
            batch.complete(call, result)
            session.observeToolResult(call, result)
            assertEquals(2 - index, (output?.get("pending_calls") as JsonArray).size)
        }
        assertEquals(JsonPrimitive("TOOL_RESULTS_READY"), output?.get("state"))
        assertEquals(3, (output?.get("completed_results") as JsonArray).size)
        assertEquals(3, (output?.get("reconciled_call_ids") as JsonArray).size)
    }

    @Test fun restartReconcilesPersistedBatchWhenAllReplayPairsWereEvicted() = runBlocking {
        val batch = ToolBatchCheckpoint(null, "task")
        val call = call("saved")
        var saved: JsonObject? = null
        GitHubOutputContinuationSession(provider(call, ProviderEvent.Completed), saveCheckpoint = { saved = it }) { _, _, _ -> error("Unexpected generation") }
            .streamRound(listOf(tool), emptyList()).toList()
        batch.enqueue(listOf(call))
        batch.complete(call, AgentToolResult(call.callId, ToolResultContent.Text("Immutable source"), false))
        // Simulate interruption after the execution journal commits, before the output checkpoint update.
        assertEquals(JsonPrimitive("TOOL_HANDOFF"), saved?.get("state"))
        var generations = 0
        val resumed = GitHubOutputContinuationSession(
            provider(ProviderEvent.TextDelta("Final synthesis"), ProviderEvent.Completed),
            durableToolReceipts = batch::handoffReceipts,
            readCheckpoint = { saved },
            saveCheckpoint = { saved = it }
        ) { _, _, _ ->
            generations++
            provider(ProviderEvent.TextDelta("Final synthesis"), ProviderEvent.Completed)
        }.streamRound(listOf(tool), emptyList()).toList()
        assertEquals(1, generations)
        assertTrue(resumed.any { it is ProviderEvent.TextDelta && it.text == "Final synthesis" })
        assertFalse(resumed.any { it is ProviderEvent.ToolCall || it is ProviderEvent.Failed })
        assertEquals(JsonPrimitive("COMPLETED"), saved?.get("state"))
    }

    @Test fun unknownMutationAndMismatchedFingerprintCannotClearPending() = runBlocking {
        val batch = ToolBatchCheckpoint(null, "task")
        val calls = listOf(call("completed"), call("unknown"))
        var saved: JsonObject? = null
        val first = GitHubOutputContinuationSession(provider(*calls.toTypedArray(), ProviderEvent.Completed), saveCheckpoint = { saved = it }) { _, _, _ -> error("Unexpected generation") }
        first.streamRound(listOf(tool), emptyList()).toList()
        batch.enqueue(calls)
        batch.complete(calls.first(), AgentToolResult("completed", ToolResultContent.Text("Done"), false))
        batch.complete(calls.last(), AgentToolResult("unknown", ToolResultContent.Text("Connection lost after dispatch"), true, errorCode = "TOOL_OUTCOME_UNKNOWN"))
        val output = GitHubOutputContinuationSession(
            provider(ProviderEvent.TextDelta("Must not run")),
            durableToolReceipts = batch::handoffReceipts,
            readCheckpoint = { saved },
            saveCheckpoint = { saved = it }
        ) { _, _, _ -> error("Unknown effect cannot be repeated.") }.streamRound(listOf(tool), emptyList()).toList()
        val failure = output.single() as ProviderEvent.Failed
        assertEquals("OUTPUT_CONTINUATION_PENDING_TOOLS", failure.errorCode)
        assertEquals(JsonPrimitive("tool_reconciliation"), failure.failureDetails?.get("failed_stage"))
        assertEquals(1, (saved?.get("pending_calls") as JsonArray).size)
        val wrong = calls.last().copy(arguments = buildJsonObject { put("path", "other.kt") })
        val unchanged = GitHubToolHandoff.reconcile(requireNotNull(saved), listOf(ToolHandoffReceipt.from(wrong, AgentToolResult(wrong.callId, ToolResultContent.Text("Other result"), false))), emptySet())
        assertEquals(saved, unchanged)
    }

    @Test fun identicalDisplayProjectionsRetainDistinctOriginalHashes() = runBlocking {
        val batch = ToolBatchCheckpoint(null, "task")
        val calls = listOf(call("first"), call("second"))
        batch.enqueue(calls)
        calls.forEach { call ->
            batch.complete(call, AgentToolResult(call.callId, ToolResultContent.Text("Omitted display"), false, retainedContent = ToolResultContent.Text("Original ${call.callId}")))
        }
        val receipts = batch.handoffReceipts(calls.mapTo(hashSetOf(), ToolHandoffReceipt::fingerprint))
        assertEquals(2, receipts.size)
        assertEquals(receipts[0].projectionSha256, receipts[1].projectionSha256)
        assertNotEquals(receipts[0].sourceResultSha256, receipts[1].sourceResultSha256)
    }

    @Test fun sharedLegacyProjectionDoesNotInventAnOriginalHash() = runBlocking {
        val batch = ToolBatchCheckpoint(null, "task")
        val call = call("legacy")
        batch.enqueue(listOf(call))
        batch.complete(call, AgentToolResult(call.callId, ToolResultContent.Text("Earlier result omitted"), false, sharedResult = true, dispatched = false))
        val receipt = batch.handoffReceipts(setOf(ToolHandoffReceipt.fingerprint(call))).single()
        assertEquals(null, receipt.sourceResultSha256)
        assertEquals(JsonPrimitive(false), receipt.json()["source_hash_available"])
        assertTrue(receipt.projectionSha256.matches(Regex("[a-f0-9]{64}")))
    }

    @Test fun fiveToolRoundsReachSynthesisWithoutAutomaticDisplayDrain() = runBlocking {
        var requests = 0
        var dispatches = 0
        val sourceHashes = linkedMapOf<String, String>()
        var saved: JsonObject? = null
        val batch = ToolBatchCheckpoint(null, "task")
        val native = object : AgentTool {
            override val definition = tool
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatches++
                val original = buildJsonObject {
                    put("schema", "github.result.v1")
                    put("data", "retained original ".repeat(5000))
                    put("replay_compacted", true)
                    put("recovery_required", true)
                    put(
                        "recovery_call",
                        buildJsonObject {
                            put("tool", tool.name)
                            put("arguments", buildJsonObject { put("view_cursor", "saved:0:1536") })
                        }
                    )
                }
                sourceHashes[callId] = dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook.digest(original.toString())
                return AgentToolResult(
                    callId,
                    ToolResultContent.Json(original),
                    false,
                    executionReceipt = buildJsonObject {
                        put("retained_original_complete", true)
                        put("semantic_summary", buildJsonObject { put("commit_sha", "a".repeat(40)) })
                    },
                    retainedContent = ToolResultContent.Json(original)
                )
            }
        }
        val selected = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                requests++
                if (requests <= 5) emit(call("round-$requests")) else emit(ProviderEvent.TextDelta("Verified final synthesis"))
                emit(ProviderEvent.Completed)
            }
        }
        val session = GitHubOutputContinuationSession(selected, durableToolReceipts = batch::handoffReceipts, readCheckpoint = { saved }, saveCheckpoint = { saved = it }) { _, _, _ -> selected }
        var earlyObservations = 0
        val events = AgentRunner(
            AgentRunLimits(githubTask = true, maxRounds = 10, maxToolCalls = 64, maxReplayTokens = 1024, maxReplayResultTokens = 256),
            checkpoint = batch,
            onToolResult = { call, _ ->
                // The first callback observes full evidence before disk commit.
                // It must not be used to mark the handoff ready.
                if (batch.handoffReceipts(setOf(ToolHandoffReceipt.fingerprint(call))).isEmpty()) {
                    earlyObservations++
                    assertEquals(JsonPrimitive("TOOL_HANDOFF"), saved?.get("state"))
                }
            },
            onToolResultCommitted = session::observeToolResult
        ).run(session, listOf(native)).toList()
        assertEquals(6, requests)
        assertEquals(5, dispatches)
        assertEquals(5, earlyObservations)
        assertEquals(5, events.filterIsInstance<AgentRunEvent.ToolFinished>().size)
        assertFalse(events.filterIsInstance<AgentRunEvent.Provider>().any { it.event is ProviderEvent.Failed })
        assertTrue(events.filterIsInstance<AgentRunEvent.Provider>().any { (it.event as? ProviderEvent.TextDelta)?.text == "Verified final synthesis" })
        val fingerprints = events.filterIsInstance<AgentRunEvent.ToolFinished>().mapTo(hashSetOf()) { ToolHandoffReceipt.fingerprint(it.call) }
        assertEquals(sourceHashes, batch.handoffReceipts(fingerprints).associate { it.callId to it.sourceResultSha256 })
    }

    @Test fun durableUnknownOutcomeOutranksAStaleSuccessfulReplay() = runBlocking {
        val call = call("remote-write")
        var checkpoint: JsonObject? = null
        GitHubOutputContinuationSession(provider(call, ProviderEvent.Completed), saveCheckpoint = { checkpoint = it }) { _, _, _ -> error("Unexpected continuation") }
            .streamRound(listOf(tool), emptyList()).toList()
        val known = ToolHandoffReceipt.from(call, AgentToolResult(call.callId, ToolResultContent.Text("Old display success"), false))
        val unknown = ToolHandoffReceipt.from(call, AgentToolResult(call.callId, ToolResultContent.Text("Outcome unknown"), true, errorCode = "TOOL_OUTCOME_UNKNOWN"))
            .copy(receiptSource = "TOOL_BATCH_CHECKPOINT")
        for (versions in listOf(listOf(known, unknown), listOf(unknown, known))) {
            val reconciled = GitHubToolHandoff.reconcile(requireNotNull(checkpoint), versions, emptySet())
            assertEquals(JsonPrimitive("TOOL_HANDOFF"), reconciled["state"])
            assertEquals(JsonPrimitive(false), reconciled["resume_allowed"])
            assertEquals(1, (reconciled["pending_calls"] as JsonArray).size)
        }
    }

    @Test fun publicProgressAppearsDuringToolRoundWithInvocationIdentity() = runBlocking {
        val model = OutputLineage(OutputOrigin.MODEL_TEXT, "invocation-1")
        val notices = mutableListOf<Pair<String, OutputLineage>>()
        val output = GitHubOutputContinuationSession(
            provider(
                ProviderEvent.TextDelta("<progress_up", model),
                ProviderEvent.TextDelta("date>Resolved the exact revision.</progress_update>Unverified draft", model),
                call("remaining"),
                ProviderEvent.Completed
            ),
            publishProgress = { text, lineage -> notices += text to lineage }
        ) { _, _, _ -> error("No continuation") }.streamRound(listOf(tool), emptyList()).toList()
        assertEquals(listOf("Resolved the exact revision." to model), notices)
        assertFalse(output.any { it is ProviderEvent.TextDelta })
        assertEquals(1, output.filterIsInstance<ProviderEvent.ToolCall>().size)
    }

    @Test fun pendingReceiptFailureKeepsItsCanonicalStageAndRetryPolicy() {
        val record = ProfileFailureRecord.fromProvider(
            "Saved results require reconciliation",
            "OUTPUT_CONTINUATION_PENDING_TOOLS",
            buildJsonObject {
                put("failed_stage", "tool_reconciliation")
                put("dispatched", false)
                put("retryable", false)
            }
        )
        assertEquals("OUTPUT_CONTINUATION_PENDING_TOOLS", record.category)
        assertEquals("tool_reconciliation", record.phase)
        assertFalse(record.retryAllowed)
        assertEquals(false, record.dispatched)
    }

    @Test fun lifecycleExportsTheSameTypedStopReasonAndRecoveryAction() {
        val failure = ProviderEvent.Failed(
            "Pending receipt",
            errorCode = "OUTPUT_CONTINUATION_PENDING_TOOLS",
            taskOutcome = "BLOCKED",
            failureDetails = buildJsonObject {
                put("failed_stage", "tool_reconciliation")
                put("next_action", "RECONCILE_TOOL_RECEIPTS")
                put("pending_invocation_fingerprints", JsonArray(listOf(JsonPrimitive("a".repeat(64)))))
                put("dispatched", false)
            }
        )
        val recorded = dev.chungjungsoo.gptmobile.data.github.GitHubTaskLifecycle.failureMetadata(failure)
        val canonical = recorded["failure_outcome"] as JsonObject
        assertEquals(JsonPrimitive("tool_reconciliation"), canonical["failed_stage"])
        assertEquals(JsonPrimitive("WAITING_FOR_RECEIPT_RECONCILIATION"), canonical["execution_state"])
        assertEquals(JsonPrimitive(false), canonical["resume_allowed"])
        assertEquals(JsonPrimitive(false), canonical["retryable_without_new_input"])
        assertEquals(canonical, JsonObject(recorded - "failure_outcome"))
    }
}
