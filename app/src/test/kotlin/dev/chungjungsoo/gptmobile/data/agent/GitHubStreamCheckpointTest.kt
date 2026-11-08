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
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubStreamCheckpointTest {
    private val tool = AgentToolDefinition("github_read", "Read a retained file", JsonObject(emptyMap()))
    private val call = ProviderEvent.ToolCall("read-1", tool.name, JsonObject(emptyMap()))
    private val receipt = AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Text("Verified read result"), false)))
    private val interrupted = ProviderEvent.Failed(
        "Connection was interrupted",
        errorCode = "CONNECTION_ABORTED",
        failureDetails = buildJsonObject {
            put("failed_stage", "response_stream")
            put("request_replay_allowed", false)
            put("logical_continuation_allowed", true)
            put("remote_acceptance", "YES")
        }
    )
    private fun session(vararg events: ProviderEvent) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flowOf(*events)
    }

    @Test fun ordinaryEmptyToolHandoffPreservesTheDelegatesNextDraftBeforeContinuing() = runTest {
        var checkpoint: JsonObject? = null
        var originalRounds = 0
        var continuations = 0
        val original = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (originalRounds++ == 0) {
                    emit(call)
                    emit(ProviderEvent.Completed)
                } else {
                    assertEquals(listOf(receipt), exchanges)
                    emit(ProviderEvent.TextDelta("Saved findings."))
                    emit(ProviderEvent.Failed("The response reached its output limit.", transportComplete = true))
                }
            }
        }
        val continuation = GitHubOutputContinuationSession(original, maxContinuations = 1, saveCheckpoint = { checkpoint = it }) { draft, results, _ ->
            continuations++
            assertEquals("Saved findings.", draft)
            assertEquals(listOf(receipt), results)
            session(ProviderEvent.TextDelta(" Finished from the receipt."), ProviderEvent.Completed)
        }
        assertEquals(listOf(call), continuation.streamRound(listOf(tool), emptyList()).toList().filterIsInstance<ProviderEvent.ToolCall>())
        assertEquals(JsonPrimitive(false), checkpoint?.get("handoff_continuation"))
        val completed = continuation.streamRound(listOf(tool), listOf(receipt)).toList()
        assertEquals(2, originalRounds)
        assertEquals(1, continuations)
        assertEquals("Saved findings. Finished from the receipt.", completed.filterIsInstance<ProviderEvent.TextDelta>().single().text)
    }

    @Test fun reasoningOnlyInterruptedContinuationKeepsItsOriginAcrossAnotherToolHandoff() = runTest {
        var checkpoint: JsonObject? = null
        var originalRounds = 0
        var continuations = 0
        val next = call.copy(callId = "read-2")
        val nextReceipt = AgentToolExchange(listOf(next), listOf(AgentToolResult(next.callId, ToolResultContent.Text("Second verified result"), false)))
        val original = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                check(originalRounds++ == 0) { "The interrupted generation must not replay" }
                emit(ProviderEvent.ThinkingDelta("Private reasoning before disconnect"))
                emit(interrupted)
            }
        }
        val continuation = GitHubOutputContinuationSession(original, readCheckpoint = { checkpoint }, saveCheckpoint = { checkpoint = it }) { draft, results, _ ->
            assertEquals("", draft)
            if (++continuations == 1) {
                assertEquals(listOf(receipt), results)
                session(next, ProviderEvent.Completed)
            } else {
                assertEquals(listOf(receipt, nextReceipt), results)
                session(ProviderEvent.TextDelta("Answer from both saved results."), ProviderEvent.Completed)
            }
        }
        assertEquals(interrupted, continuation.streamRound(listOf(tool), listOf(receipt)).toList().last())
        assertEquals(listOf(next), continuation.streamRound(listOf(tool), listOf(receipt)).toList().filterIsInstance<ProviderEvent.ToolCall>())
        assertEquals(JsonPrimitive(true), checkpoint?.get("handoff_continuation"))
        val completed = continuation.streamRound(listOf(tool), listOf(receipt, nextReceipt)).toList()
        assertEquals(1, originalRounds)
        assertEquals(2, continuations)
        assertEquals("Answer from both saved results.", completed.filterIsInstance<ProviderEvent.TextDelta>().single().text)
    }

    @Test fun reasoningOnlyFailurePersistsTheCompletedBoundaryBeforeDispatchAndResumesAnEmptyDraft() = runTest {
        var checkpoint: JsonObject? = null
        val context = buildJsonObject {
            put("profile_uid", "profile-1")
            put("model", "chosen-model")
            put("repository", "owner/repo")
            put("captured_head_sha", "a".repeat(40))
        }
        val original = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                assertEquals(JsonPrimitive("GENERATING"), checkpoint?.get("state"))
                assertEquals(context, checkpoint?.get("context"))
                assertEquals(1, (checkpoint?.get("completed_results") as JsonArray).size)
                emit(ProviderEvent.ThinkingDelta("Private reasoning"))
                emit(interrupted)
            }
        }
        val first = GitHubOutputContinuationSession(original, checkpointContext = context, recoveryRevision = "run-1", saveCheckpoint = { checkpoint = it }) { _, _, _ -> error("No wire retry") }
            .streamRound(listOf(tool), listOf(receipt)).toList()
        assertEquals(interrupted, first.last())
        assertEquals(JsonPrimitive(""), checkpoint?.get("draft"))
        assertEquals(JsonPrimitive("NO_VISIBLE_DRAFT"), checkpoint?.get("draft_kind"))
        assertEquals(JsonPrimitive("AFTER_TOOL_RESULTS"), checkpoint?.get("resume_kind"))
        assertEquals(JsonPrimitive(true), checkpoint?.get("resume_allowed"))
        assertFalse(checkpoint.toString().contains("Private reasoning"))
        var continuations = 0
        val restored = GitHubOutputContinuationSession(session(), checkpointContext = context, recoveryRevision = "run-1", readCheckpoint = { checkpoint }, saveCheckpoint = { checkpoint = it }) { draft, results, definitions ->
            continuations++
            assertEquals("", draft)
            assertEquals(listOf(receipt), results)
            assertEquals(listOf(tool), definitions)
            session(ProviderEvent.TextDelta("Answer from saved read evidence."), ProviderEvent.Completed)
        }.streamRound(listOf(tool), listOf(receipt)).toList()
        assertEquals(1, continuations)
        assertEquals("Answer from saved read evidence.", restored.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertEquals(JsonPrimitive(false), checkpoint?.get("tool_actions_replayed"))
    }

    @Test fun interruptedVisiblePrefixIsKeptAndDuplicateSectionsAreNotPublishedTwice() = runTest {
        var checkpoint: JsonObject? = null
        val prefix = "This is the sufficiently long verified first section."
        GitHubOutputContinuationSession(session(ProviderEvent.TextDelta(prefix), interrupted), saveCheckpoint = { checkpoint = it }) { _, _, _ -> error("No request replay") }
            .streamRound(emptyList(), emptyList()).toList()
        val restored = GitHubOutputContinuationSession(session(), readCheckpoint = { checkpoint }) { _, _, _ ->
            session(ProviderEvent.TextDelta(prefix + "\nRemaining findings."), ProviderEvent.Completed)
        }.streamRound(emptyList(), emptyList()).toList()
        assertEquals(prefix + "\nRemaining findings.", restored.filterIsInstance<ProviderEvent.TextDelta>().single().text)
    }

    @Test fun unknownMutationBlocksEvenWhenThereIsNoVisibleDraftOrGatewayJob() = runTest {
        val unknown = receipt.copy(results = listOf(receipt.results.single().copy(errorCode = "TOOL_OUTCOME_UNKNOWN", isError = true)))
        val events = GitHubOutputContinuationSession(session(ProviderEvent.TextDelta("Must not generate"))) { _, _, _ -> error("Reconcile first") }
            .streamRound(listOf(tool), listOf(unknown)).toList()
        assertEquals("RECONCILIATION_REQUIRED", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertTrue(events.none { it is ProviderEvent.TextDelta })
    }

    @Test fun processRestartDoesNotReplenishAClaimedAutomaticConnectionContinuation() = runTest {
        var checkpoint: JsonObject? = null
        GitHubOutputContinuationSession(session(interrupted), recoveryRevision = "same-run", saveCheckpoint = { checkpoint = it }) { _, _, _ -> error("No retry") }
            .streamRound(emptyList(), emptyList()).toList()
        var requests = 0
        fun resume(revision: String) = GitHubOutputContinuationSession(session(), recoveryRevision = revision, readCheckpoint = { checkpoint }, saveCheckpoint = { checkpoint = it }) { _, _, _ ->
            requests++
            session(interrupted)
        }
        resume("same-run").streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, requests)
        val bounded = resume("same-run").streamRound(emptyList(), emptyList()).toList()
        assertEquals("OUTPUT_CONTINUATION_UNAVAILABLE", (bounded.last() as ProviderEvent.Failed).errorCode)
        assertEquals(1, requests)
        resume("explicit-new-request").streamRound(emptyList(), emptyList()).toList()
        assertEquals(2, requests)
    }

    @Test fun existingNativeApprovalBlocksGenerationUntilItsOwnAuthoritativeResolution() = runTest {
        var checkpoint: JsonObject? = null
        val stagedCall = ProviderEvent.ToolCall(
            "stage-once",
            "github_commit",
            buildJsonObject {
                put("branch", "owned-fixture")
                put("expected_head_sha", "a".repeat(40))
                put("stage_id", "stage-1")
            }
        )
        val trace = buildJsonObject { put("tool_trace", buildJsonObject { put("schema", "github.trace.v1") }) }
        val pending = AgentToolExchange(
            listOf(stagedCall),
            listOf(
                AgentToolResult(
                    stagedCall.callId,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("engineering_task_id", "approval-1")
                            put("status", "AWAITING_APPROVAL")
                            put("committed", false)
                        }
                    ),
                    false,
                    dispatched = false,
                    executionReceipt = trace
                )
            )
        )
        var requests = 0
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                requests++
                emit(ProviderEvent.TextDelta("Continue after the saved decision."))
                emit(ProviderEvent.Completed)
            }
        }
        fun gate() = GitHubOutputContinuationSession(provider, readCheckpoint = { checkpoint }, saveCheckpoint = { checkpoint = it }) { _, _, _ -> error("No fresh fixture or wire replay") }
        repeat(2) {
            val result = gate().streamRound(emptyList(), listOf(pending)).toList()
            assertEquals("WAITING_FOR_APPROVAL", (result.last() as ProviderEvent.Failed).errorCode)
        }
        assertEquals(0, requests)
        assertEquals(JsonPrimitive(true), checkpoint?.get("user_input_required"))
        assertEquals(JsonPrimitive("WAITING_FOR_APPROVAL"), checkpoint?.get("blocking_condition"))
        val status = ProviderEvent.ToolCall(
            "status-once",
            "github_context",
            buildJsonObject {
                put("action", "recovery")
                put("engineering_task_id", "approval-1")
            }
        )
        val resolved = AgentToolExchange(
            listOf(status),
            listOf(
                AgentToolResult(
                    status.callId,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("engineering_task_id", "approval-1")
                            put("approval_state", "CANCELLED_BEFORE_PUBLICATION")
                            put("remote_mutation_performed", false)
                            put("branch", "owned-fixture")
                        }
                    ),
                    false,
                    dispatched = false,
                    executionReceipt = trace
                )
            )
        )
        assertEquals(ProviderEvent.Completed, gate().streamRound(emptyList(), listOf(pending, resolved)).toList().last())
        assertEquals(1, requests)
    }

    @Test fun exactNativeReadbackReleasesAnUnknownHandoffWhileKeepingTheOriginalReceipt() = runTest {
        var checkpoint: JsonObject? = null
        val args = buildJsonObject {
            put("action", "commit")
            put("owner", "owner")
            put("repo", "repo")
            put("branch", "feature")
            put("expected_head_sha", "a".repeat(40))
            put("message", "Approved change")
        }
        val write = ProviderEvent.ToolCall("lost-write", "github_write", args)
        val unknown = AgentToolExchange(listOf(write), listOf(AgentToolResult(write.callId, ToolResultContent.Text("Lost response"), true, dispatched = true, errorCode = "TOOL_OUTCOME_UNKNOWN")))
        val scope = buildJsonObject {
            put("binding", "task")
            put("repository", "owner/repo")
            put("branch", "feature")
            put("tool", write.name)
            put("arguments", args)
            put("trusted", true)
        }
        val lost = JsonObject(
            scope + buildJsonObject {
                put("call_id", write.callId)
                put("success", false)
                put("dispatched", true)
                put("error_code", "TOOL_OUTCOME_UNKNOWN")
            }
        )
        val proof = JsonObject(
            scope + buildJsonObject {
                put("call_id", "readback")
                put("success", true)
                put(
                    "result",
                    buildJsonObject {
                        put("reconciled", true)
                        put("verification", "REMOTE_REF_PARENT_MESSAGE_AND_COMPLETE_TREE")
                        put("sha", "b".repeat(40))
                        put("tree_sha", "c".repeat(40))
                        put("expected_head_sha", "b".repeat(40))
                        put("parent_sha", "a".repeat(40))
                        put("branch", "feature")
                    }
                )
            }
        )
        GitHubOutputContinuationSession(session(ProviderEvent.TextDelta("Saved prefix."), write, ProviderEvent.Completed), saveCheckpoint = { checkpoint = it }) { _, _, _ -> error("No repeat") }
            .streamRound(emptyList(), emptyList()).toList()
        var receipts = mapOf("lost" to lost, "proof" to JsonObject(proof + ("binding" to JsonPrimitive("other-task"))))
        var requests = 0
        fun gate() = GitHubOutputContinuationSession(session(), readCheckpoint = { checkpoint }, saveCheckpoint = { checkpoint = it }, reconciledCallIds = {
            dev.chungjungsoo.gptmobile.data.github.GitHubExecutionLedger.reconciledReceiptIds(receipts).mapNotNullTo(mutableSetOf()) { id -> (receipts[id]?.get("call_id") as? JsonPrimitive)?.content }
        }) { draft, retained, _ ->
            requests++
            assertEquals("Saved prefix.", draft)
            assertEquals("TOOL_OUTCOME_UNKNOWN", retained.single().results.single().errorCode)
            session(ProviderEvent.TextDelta(" The exact readback verified the change."), ProviderEvent.Completed)
        }
        assertEquals("OUTPUT_CONTINUATION_PENDING_TOOLS", (gate().streamRound(emptyList(), listOf(unknown)).toList().last() as ProviderEvent.Failed).errorCode)
        assertEquals(0, requests)
        receipts = mapOf("lost" to lost, "proof" to proof)
        val completed = gate().streamRound(emptyList(), listOf(unknown)).toList()
        assertEquals(ProviderEvent.Completed, completed.last())
        assertEquals(1, requests)
        assertEquals(JsonPrimitive("TOOL_OUTCOME_UNKNOWN"), lost["error_code"])
        assertEquals(JsonArray(listOf(JsonPrimitive("lost-write"))), checkpoint?.get("reconciled_call_ids"))
    }
}
