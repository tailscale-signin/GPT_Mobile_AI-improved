package dev.chungjungsoo.gptmobile.data.agent

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ToolBatchCheckpointTest {
    @Test fun acceptedJobAndVerifiedReceiptSurviveRestartWithoutReplayingPolicyRejections() = runBlocking {
        val file = File.createTempFile("engineering-context", ".json").apply { delete() }
        try {
            val job = ToolBatchJobContext("Repair the selected repository", "GITHUB_ENGINEERING", "owner/repo", "feature", "a".repeat(40))
            val journal = ToolBatchCheckpoint(file, "authorized-job", job)
            val observed = ProviderEvent.ToolCall("verified-pr", "github_pr", JsonObject(emptyMap()))
            val blocked = ProviderEvent.ToolCall("policy", "github_write", JsonObject(emptyMap()))
            journal.enqueue(listOf(observed, blocked))
            journal.execute(observed) {
                AgentToolResult(
                    observed.callId,
                    ToolResultContent.Json(buildJsonObject { put("number", 22) }),
                    false,
                    executionReceipt = buildJsonObject {
                        put("phase", "OBSERVED")
                        put("read_requests", 1)
                    }
                )
            }
            journal.execute(blocked) { ToolResultEnvelope.error(blocked.callId, "PERSONAL_DATA_LOCAL_ONLY", "Local-only source cannot be shared") }
            val restored = ToolBatchCheckpoint(file, "authorized-job")
            restored.prepareExplicitBudgetRetry()
            val snapshot = restored.recoverySnapshot()
            assertEquals(job.objective, snapshot.jobContext?.objective)
            assertEquals(job.branch, snapshot.jobContext?.branch)
            assertEquals(job.commitSha, snapshot.jobContext?.commitSha)
            assertEquals(22, snapshot.jobContext?.pullRequest)
            assertEquals(JsonPrimitive(observed.callId), snapshot.jobContext?.lastVerifiedReceipt?.get("callId"))
            assertEquals("NOT_CONFIGURED", snapshot.objectiveStatus)
            assertEquals(1, snapshot.rejectedCount)
            assertEquals(0, snapshot.failedCount)
            val result = restored.execute(blocked) { error("A saved policy rejection must never dispatch again") }
            assertEquals("PERSONAL_DATA_LOCAL_ONLY", result.errorCode)
            assertTrue(!result.dispatched)
        } finally {
            file.delete()
        }
    }

    @Test fun explicitBudgetRetryReopensOnlyUndispatchedBudgetFailuresAndKeepsReceipts() = runBlocking {
        val journal = ToolBatchCheckpoint(null, "retry")
        val calls = listOf("budget", "completed", "uncertain", "invalid", "dispatched-budget").map { ProviderEvent.ToolCall(it, "write", JsonObject(emptyMap())) }
        journal.enqueue(calls)
        val receipts = listOf(
            AgentToolResult("budget", ToolResultContent.Text("limit"), true, errorCode = "BUDGET_EXHAUSTED", dispatched = false, toolCallBudgetExhausted = true),
            AgentToolResult("completed", ToolResultContent.Text("commit receipt"), false, toolCallBudgetExhausted = true),
            AgentToolResult("uncertain", ToolResultContent.Text("reconcile"), true, errorCode = "TOOL_OUTCOME_UNKNOWN"),
            AgentToolResult("invalid", ToolResultContent.Text("fix arguments"), true, errorCode = "INVALID_ARGUMENTS", dispatched = false),
            AgentToolResult("dispatched-budget", ToolResultContent.Text("saved partial output"), true, errorCode = "BUDGET_EXHAUSTED", outputBudgetExhausted = true)
        )
        calls.zip(receipts).forEach { (call, result) -> journal.execute(call) { result } }
        val before = journal.dispatchedCount()
        assertEquals("BUDGET_EXHAUSTED", journal.execute(calls.first()) { error("Implicit resume must not retry") }.errorCode)
        journal.prepareExplicitBudgetRetry()
        val snapshot = journal.recoverySnapshot()
        assertEquals(1, snapshot.queuedCount)
        assertEquals(before, snapshot.dispatchedCount)
        var newDispatches = 0
        val restored = calls.map { call ->
            journal.execute(call) {
                newDispatches++
                assertEquals("budget", call.callId)
                AgentToolResult(call.callId, ToolResultContent.Text("newly admitted"), false)
            }
        }
        assertEquals(1, newDispatches)
        assertEquals("commit receipt", (restored[1].content as ToolResultContent.Text).text)
        assertEquals("TOOL_OUTCOME_UNKNOWN", restored[2].errorCode)
        assertEquals("INVALID_ARGUMENTS", restored[3].errorCode)
        assertEquals("BUDGET_EXHAUSTED", restored[4].errorCode)
        assertTrue(restored.none { it.toolCallBudgetExhausted || it.outputBudgetExhausted })
        journal.prepareExplicitBudgetRetry()
        calls.forEach { journal.execute(it) { error("Completed or uncertain work must never replay") } }
    }

    @Test fun retainedUnknownReceiptCannotBeRestoredAsSuccess() = runBlocking {
        val call = ProviderEvent.ToolCall("one", "write", JsonObject(emptyMap()))
        val journal = ToolBatchCheckpoint(null, "grant")
        journal.enqueue(listOf(call))
        journal.execute(call) { AgentToolResult(call.callId, ToolResultContent.Text("contradictory legacy receipt"), false, errorCode = "DISPATCH_OUTCOME_UNKNOWN") }
        val restored = journal.execute(call) { error("must not dispatch") }
        assertTrue(restored.isError)
        assertTrue(restored.sharedResult)
        assertEquals("DISPATCH_OUTCOME_UNKNOWN", restored.errorCode)
        val metrics = journal.recoverySnapshot()
        assertEquals(1, metrics.unknownCount)
        assertEquals(0, metrics.completedCount)
    }

    @Test fun backupOnlyCheckpointRestoresClaimsInsteadOfDispatchingThemAgain() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("checkpoint-backup").toFile()
        val file = File(directory, "batch.json")
        try {
            val call = ProviderEvent.ToolCall("one", "write", JsonObject(emptyMap()))
            val journal = ToolBatchCheckpoint(file, "grant")
            journal.enqueue(listOf(call))
            journal.execute(call) { AgentToolResult(call.callId, ToolResultContent.Text("saved"), false) }
            assertTrue(file.renameTo(File(file.path + ".bak")))
            val restored = ToolBatchCheckpoint(file, "grant")
            assertEquals(listOf(call), restored.pending())
            assertEquals(ToolResultContent.Text("saved"), restored.execute(call) { error("must not dispatch") }.content)
            assertEquals(1, restored.recoverySnapshot().completedCount)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun concurrentInstancesKeepOneDispatchPerIdentityAndConsistentRecoveryCounts() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("checkpoint-concurrent").toFile()
        try {
            val file = File(directory, "batch.json")
            val writers = List(2) { ToolBatchCheckpoint(file, "grant") }
            val calls = (1..64).map { ProviderEvent.ToolCall("call-$it", "write", JsonObject(emptyMap())) }
            writers.first().enqueue(calls)
            val counts = ConcurrentHashMap<String, Int>()
            writers.flatMap { writer ->
                calls.map { call ->
                    async {
                        writer.execute(call) {
                            counts.compute(call.callId) { _, previous -> (previous ?: 0) + 1 }
                            AgentToolResult(call.callId, ToolResultContent.Text("saved"), false)
                        }
                    }
                }
            }.awaitAll()
            assertEquals(64, counts.size)
            assertTrue(counts.values.all { it == 1 })
            val snapshot = ToolBatchCheckpoint(file, "grant").recoverySnapshot()
            assertEquals(64, snapshot.completedCount)
            assertEquals(0, snapshot.unknownCount)
            assertEquals(0, snapshot.queuedCount)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun rejectedCallsDoNotConsumeRestoredBudgetButUncertainClaimsRemainUnknown() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("checkpoint-dispatch-accounting").toFile()
        val file = File(directory, "batch.json")
        try {
            val rejected = ProviderEvent.ToolCall("rejected", "write", JsonObject(emptyMap()))
            val uncertain = ProviderEvent.ToolCall("uncertain", "write", JsonObject(emptyMap()))
            val journal = ToolBatchCheckpoint(file, "grant")
            journal.enqueue(listOf(rejected, uncertain))
            journal.execute(rejected) { AgentToolResult(rejected.callId, ToolResultContent.Text("Invalid"), true, errorCode = "INVALID_ARGUMENTS", dispatched = false) }
            try {
                journal.execute(uncertain) { throw CancellationException("lost response") }
            } catch (_: CancellationException) { }
            val restored = ToolBatchCheckpoint(file, "grant")
            assertEquals(1, restored.dispatchedCount())
            assertEquals(setOf("uncertain"), restored.pendingDispatchedIds())
            val snapshot = restored.recoverySnapshot(CheckpointRestoreObjective(60_000))
            assertEquals(listOf(rejected, uncertain), snapshot.pending)
            assertEquals(1, snapshot.dispatchedCount)
            assertEquals(setOf("uncertain"), snapshot.pendingDispatchedIds)
            assertEquals(setOf("rejected", "uncertain"), snapshot.pendingRetainedIds)
            assertEquals("MET", snapshot.objectiveStatus)
            assertEquals("NOT_CONFIGURED", restored.recoverySnapshot().objectiveStatus)
            assertEquals(1, snapshot.unknownCount)
            assertEquals(1, snapshot.rejectedCount)
            assertEquals(0, snapshot.completedCount)
            val unknown = restored.execute(uncertain) { error("must reconcile") }
            assertEquals("TOOL_OUTCOME_UNKNOWN", unknown.errorCode)
            val encoded = Json.parseToJsonElement(ToolResultEnvelope.encode("write", unknown)).jsonObject
            assertEquals(JsonPrimitive(false), encoded["execution"]!!.jsonObject["dispatched"])
            assertEquals(JsonPrimitive("unknown"), encoded["execution"]!!.jsonObject["outcome"])
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun checkpointRestoreObjectiveRejectsInvalidTargetsAndEvaluatesMeasuredDuration() {
        assertTrue(runCatching { CheckpointRestoreObjective(0) }.isFailure)
        val objective = CheckpointRestoreObjective(100)
        assertEquals("MET", objective.assess(100))
        assertEquals("EXCEEDED", objective.assess(101))
    }

    @Test fun restoredRejectionsAndUnknownClaimsDoNotDisplaceQueuedCallsFromTheRemainingBudget() = runBlocking {
        val journal = ToolBatchCheckpoint(null, "grant")
        val rejected = ProviderEvent.ToolCall("rejected", "write", JsonObject(emptyMap()))
        val uncertain = ProviderEvent.ToolCall("uncertain", "write", JsonObject(emptyMap()))
        val queued = ProviderEvent.ToolCall("queued", "write", buildJsonObject { put("path", "different-target") })
        journal.enqueue(listOf(rejected, uncertain, queued))
        journal.execute(rejected) { AgentToolResult(rejected.callId, ToolResultContent.Text("Invalid"), true, errorCode = "INVALID_ARGUMENTS", dispatched = false) }
        try {
            journal.execute(uncertain) { throw CancellationException("lost response") }
        } catch (_: CancellationException) { }
        var dispatched = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("write", "Write", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatched++
                assertEquals("queued", callId)
                return AgentToolResult(callId, ToolResultContent.Text("completed"), false)
            }
        }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                val results = exchanges.single().results.associateBy { it.callId }
                assertEquals("INVALID_ARGUMENTS", results.getValue("rejected").errorCode)
                assertEquals("TOOL_OUTCOME_UNKNOWN", results.getValue("uncertain").errorCode)
                assertTrue(results.getValue("queued").content.toString().contains("completed"))
                emit(ProviderEvent.TextDelta("One action requires reconciliation."))
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits(maxToolCalls = 2), journal).run(session, listOf(tool)).toList()
        assertEquals(1, dispatched)
        assertTrue(events.any { it is AgentRunEvent.Provider && it.event == ProviderEvent.Completed })
        assertTrue(journal.pending().isEmpty())
    }

    @Test fun mismatchedToolReceiptIsAnUncertainErrorAndIsNotRedispatched() = runBlocking {
        val checkpoint = ToolBatchCheckpoint(null, "grant")
        val call = ProviderEvent.ToolCall("expected", "write", JsonObject(emptyMap()))
        var count = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("write", "Write", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                count++
                return AgentToolResult("other", ToolResultContent.Text("untrusted receipt"), false)
            }
        }
        var round = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (round++ == 0) emit(call) else emit(ProviderEvent.TextDelta("done"))
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(checkpoint = checkpoint).run(session, listOf(tool)).toList()
        val result = events.filterIsInstance<AgentRunEvent.ToolFinished>().single().result
        assertEquals("expected", result.callId)
        assertEquals("TOOL_RESULT_ID_MISMATCH", result.errorCode)
        assertTrue(result.isError)
        assertEquals("TOOL_RESULT_ID_MISMATCH", checkpoint.execute(call) { error("must not repeat") }.errorCode)
        assertEquals(1, count)
    }

    @Test fun deferredReplayCannotReplaceAnInFlightWriteReceipt() = runBlocking {
        val checkpoint = ToolBatchCheckpoint(null, "grant")
        val call = ProviderEvent.ToolCall("one", "write", JsonObject(emptyMap()))
        checkpoint.enqueue(listOf(call))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = async {
            checkpoint.execute(call) {
                entered.complete(Unit)
                release.await()
                AgentToolResult(call.callId, ToolResultContent.Text("published"), false)
            }
        }
        entered.await()
        checkpoint.complete(call, AgentToolResult(call.callId, ToolResultContent.Text("deferred"), true, dispatched = false))
        assertEquals("TOOL_OUTCOME_UNKNOWN", checkpoint.execute(call) { error("must not dispatch") }.errorCode)
        release.complete(Unit)
        owner.await()
        assertEquals(ToolResultContent.Text("published"), checkpoint.execute(call) { error("must not dispatch") }.content)
    }

    @Test fun checkpointVersionRejectsUnknownVersionsAndReadsLegacyState() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("checkpoint-version").toFile()
        val file = File(directory, "batch.json")
        try {
            file.writeText("""{"binding":"grant","pending":[],"entries":{}}""")
            assertTrue(ToolBatchCheckpoint(file, "grant").pending().isEmpty())
            file.writeText("""{"binding":"grant","schemaVersion":2,"pending":[],"entries":{}}""")
            assertTrue(runCatching { ToolBatchCheckpoint(file, "grant").pending() }.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun sixtySevenCallsRecoverWithoutRepeatingOrLosingQueuedWork() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("batch-67").toFile()
        val file = File(directory, "batch.json")
        val calls = (1..67).map { ProviderEvent.ToolCall("call-$it", "operation", JsonObject(emptyMap())) }
        try {
            val original = ToolBatchCheckpoint(file, "grant")
            original.enqueue(calls)
            var dispatched = 0
            for (call in calls.take(33)) {
                original.execute(call) {
                    dispatched++
                    AgentToolResult(call.callId, ToolResultContent.Text("verified"), false)
                }
            }
            try {
                original.execute(calls[33]) {
                    dispatched++
                    throw CancellationException("lost response")
                }
            } catch (_: CancellationException) { }
            assertTrue(runCatching { original.enqueue(listOf(ProviderEvent.ToolCall("other", "operation", JsonObject(emptyMap())))) }.isFailure)
            assertTrue(runCatching { original.finish() }.isFailure)
            val restored = ToolBatchCheckpoint(file, "grant")
            val results = restored.pending().map { call ->
                restored.execute(call) {
                    dispatched++
                    AgentToolResult(call.callId, ToolResultContent.Text("verified"), false)
                }
            }
            assertEquals(67, results.size)
            assertEquals("TOOL_OUTCOME_UNKNOWN", results[33].errorCode)
            assertTrue(results.filterIndexed { index, _ -> index != 33 }.none { it.isError })
            assertEquals(67, dispatched)
            restored.finish()
            assertTrue(restored.pending().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun savedReceiptCannotBeReplacedByADeferredOrMismatchedResult() = runBlocking {
        val checkpoint = ToolBatchCheckpoint(null, "grant")
        val call = ProviderEvent.ToolCall("one", "write", JsonObject(emptyMap()))
        checkpoint.enqueue(listOf(call))
        checkpoint.execute(call) { AgentToolResult(call.callId, ToolResultContent.Text("published"), false) }
        checkpoint.complete(call, AgentToolResult(call.callId, ToolResultContent.Text("deferred"), true, dispatched = false))
        assertEquals(ToolResultContent.Text("published"), checkpoint.execute(call) { error("must not dispatch") }.content)
        assertTrue(runCatching { checkpoint.complete(call, AgentToolResult("other", ToolResultContent.Text("wrong"), false)) }.isFailure)
        assertTrue(runCatching { checkpoint.enqueue(listOf(call, call)) }.isFailure)
    }

    @Test fun interruptedThirtyThreeCallBatchRestoresCommittedResultsAndOnlyDispatchesQueuedCalls() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("batch-recovery").toFile()
        val file = File(directory, "batch.json")
        val counts = ConcurrentHashMap<String, Int>()
        val calls = (1..33).map { ProviderEvent.ToolCall("call-$it", "read_url", buildJsonObject { put("url", "https://example.org/$it") }) }
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("read_url", "Read", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                counts.compute(callId) { _, old -> (old ?: 0) + 1 }
                if (callId == "call-32") throw CancellationException("Simulated process interruption after dispatch")
                return AgentToolResult(callId, ToolResultContent.Text("Saved evidence for $callId"), false)
            }
        }
        val first = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                calls.forEach { emit(it) }
                emit(ProviderEvent.Completed)
            }
        }
        try {
            try {
                AgentRunner(AgentRunLimits(maxConcurrentTools = 1), ToolBatchCheckpoint(file, "accepted-turn-policy")).run(first, listOf(tool)).toList()
                error("Must interrupt")
            } catch (_: CancellationException) { }
            assertEquals(32, counts.size)
            val restored = ToolBatchCheckpoint(file, "accepted-turn-policy")
            assertEquals(33, restored.pending().size)
            val resumed = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    assertEquals(33, exchanges.flatMap { it.results }.size)
                    assertTrue(exchanges.flatMap { it.results }.single { it.callId == "call-32" }.isError)
                    assertTrue(exchanges.flatMap { it.results }.filter { it.callId != "call-32" }.none { it.isError })
                    emit(ProviderEvent.TextDelta("Retained partial report; one call has an unknown outcome."))
                    emit(ProviderEvent.Completed)
                }
            }
            val events = AgentRunner(AgentRunLimits(maxConcurrentTools = 1), restored).run(resumed, listOf(tool)).toList()
            assertEquals(33, counts.size)
            assertTrue(counts.values.all { it == 1 })
            assertEquals(33, events.filterIsInstance<AgentRunEvent.ToolFinished>().size)
            assertTrue(ToolBatchCheckpoint(file, "accepted-turn-policy").pending().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun changedCallIdentityAndChangedAuthorizationCannotReuseTheJournal() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("batch-policy").toFile()
        val file = File(directory, "batch.json")
        val call = ProviderEvent.ToolCall("id", "write", buildJsonObject { put("value", 1) })
        try {
            val journal = ToolBatchCheckpoint(file, "original-grant")
            journal.enqueue(listOf(call))
            val changed = runCatching { journal.enqueue(listOf(call.copy(arguments = buildJsonObject { put("value", 2) }))) }
            assertTrue(changed.isFailure)
            assertTrue(runCatching { ToolBatchCheckpoint(file, "different-grant").pending() }.isFailure)
            var executions = 0
            repeat(2) {
                journal.execute(call) {
                    executions++
                    AgentToolResult(call.callId, ToolResultContent.Text("written"), false)
                }
            }
            assertEquals(1, executions)
        } finally {
            directory.deleteRecursively()
        }
    }
}
