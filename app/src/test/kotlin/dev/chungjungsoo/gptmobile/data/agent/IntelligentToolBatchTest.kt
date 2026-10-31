package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IntelligentToolBatchTest {
    private fun call(id: String, name: String = "github_read", action: String? = null) = ProviderEvent.ToolCall(id, name, buildJsonObject { action?.let { put("action", it) } })
    private fun tool(name: String) = object : AgentTool {
        override val definition = AgentToolDefinition(name, "test", JsonObject(emptyMap()))
        override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, ToolResultContent.Text(callId), false)
    }

    @Test fun sameToolReadsOverlapAndReceiptsRemainInModelOrder() = runTest {
        val calls = List(10) { call(it.toString()) }
        val nodes = planIntelligentBatch(calls, mapOf("github_read" to tool("github_read")))
        var active = 0
        var peak = 0
        val progress = mutableListOf<ToolBatchProgress>()
        val result = IntelligentToolBatch(AdaptiveBatchWindow(6), { testScheduler.currentTime }).execute(
            nodes,
            dispatch = {
                active++
                peak = maxOf(active, peak)
                delay(100)
                active--
                AgentToolResult(it.callId, ToolResultContent.Text(it.callId), false)
            },
            completed = { _, _ -> },
            progress = { progress += it }
        )
        assertTrue(peak in 5..6)
        assertEquals(calls.map { it.callId }, result.map { it.callId })
        assertEquals(10, progress.last().completed)
        assertEquals(0, progress.last().active)
    }

    @Test fun failedReadPreventsDependentWriteAndLaterReads() = runTest {
        val calls = listOf(call("read"), call("write", "github_commit"), call("later"))
        val tools = listOf(tool("github_read"), tool("github_commit")).associateBy { it.definition.name }
        val dispatched = mutableListOf<String>()
        val results = IntelligentToolBatch(AdaptiveBatchWindow(6)).execute(
            planIntelligentBatch(calls, tools),
            dispatch = {
                dispatched += it.callId
                ToolResultEnvelope.error(it.callId, "READ_FAILED", "failed", dispatched = true)
            },
            completed = { _, _ -> },
            progress = {}
        )
        assertEquals(listOf("read"), dispatched)
        assertTrue(results.drop(1).all { it.errorCode == "DEPENDENCY_FAILED" && !it.dispatched })
    }

    @Test fun cancelledWorkerStopsTheCollectorAndLeavesQueuedCallsUndispatched() = runTest {
        val calls = listOf(call("interrupted"), call("queued"))
        val dispatched = mutableListOf<String>()
        try {
            withTimeout(1_000) {
                IntelligentToolBatch(AdaptiveBatchWindow(1)).execute(
                    planIntelligentBatch(calls, mapOf("github_read" to tool("github_read"))),
                    dispatch = {
                        dispatched += it.callId
                        throw CancellationException("Interrupted tool")
                    },
                    completed = { _, _ -> error("A cancelled operation has no completed receipt") },
                    progress = {}
                )
            }
            error("Expected cancellation")
        } catch (cancelled: CancellationException) {
            assertFalse("The collector must stop without waiting for its deadline", cancelled is TimeoutCancellationException)
            assertEquals("Interrupted tool", cancelled.message)
        }
        assertEquals(listOf("interrupted"), dispatched)
    }

    @Test fun contextChangesAndUnknownOperationsAreBarriers() {
        val calls = listOf(call("a"), call("context", "github_context", "resolve"), call("b"), call("unknown", "custom"), call("c"))
        val tools = calls.map { tool(it.name) }.associateBy { it.definition.name }
        val plan = planIntelligentBatch(calls, tools)
        assertFalse(plan[1].parallelRead)
        assertFalse(plan[3].parallelRead)
        assertEquals(setOf("context"), plan[2].dependencies)
        assertTrue(plan[3].dependencies.containsAll(setOf("a", "context", "b")))
        assertEquals(setOf("unknown"), plan[4].dependencies)
    }

    @Test fun invalidGraphIsRejectedBeforeAnyDispatch() = runTest {
        var dispatched = false
        try {
            IntelligentToolBatch(AdaptiveBatchWindow(5)).execute(
                listOf(ToolBatchNode(call("a"), setOf("b"), true), ToolBatchNode(call("b"), setOf("a"), true)),
                dispatch = {
                    dispatched = true
                    error("must not dispatch")
                },
                completed = { _, _ -> },
                progress = {}
            )
            error("Expected graph rejection")
        } catch (_: IllegalArgumentException) {
            assertFalse(dispatched)
        }
    }

    @Test fun adaptiveWindowHonorsProfileCapAndBacksOff() {
        val window = AdaptiveBatchWindow(8)
        repeat(100) { window.observe(false, 100) }
        assertEquals(8, window.concurrency)
        window.observe(true, 100)
        assertEquals(4, window.concurrency)
        window.observe(false, 20_000)
        assertEquals(2, window.concurrency)
        assertEquals(1, AdaptiveBatchWindow(1).concurrency)
    }

    @Test fun checkpointResumesInterruptedReadButNeverRepeatsUnknownWrite() = runTest {
        for (read in listOf(true, false)) {
            val checkpoint = ToolBatchCheckpoint(null, "scope")
            val call = call("call", if (read) "github_read" else "github_commit")
            checkpoint.enqueue(listOf(call))
            try {
                checkpoint.execute(call) { throw IllegalStateException("lost process") }
            } catch (_: IllegalStateException) {
                // Dispatch intent remains, with no invented receipt.
            }
            var dispatched = false
            val result = checkpoint.execute(call, replayableRead = read) {
                dispatched = true
                AgentToolResult(call.callId, ToolResultContent.Text("retained result"), false)
            }
            assertEquals(read, dispatched)
            assertEquals(!read, result.isError)
        }
    }
}
