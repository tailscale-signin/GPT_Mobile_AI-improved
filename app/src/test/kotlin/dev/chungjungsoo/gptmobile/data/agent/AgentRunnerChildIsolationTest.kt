package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunnerChildIsolationTest {
    private fun tool(name: String, owner: AgentToolExecutionOwner = AgentToolExecutionOwner.CLIENT, block: suspend () -> String) = object : OwnedAgentTool {
        override val definition = AgentToolDefinition(name, "Child fixture", buildJsonObject {})
        override val executionOwner = owner
        override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, ToolResultContent.Text(block()), false)
    }

    @Test fun successfulChildEvidenceRemainsAvailableWhenAnotherChildFails() = runBlocking {
        val good = tool("child_good") { "Confirmed child evidence" }
        val bad = tool("child_bad") { error("Child unavailable") }
        var round = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (round++ == 0) {
                    emit(ProviderEvent.ToolCall("good", good.definition.name, buildJsonObject {}))
                    emit(ProviderEvent.ToolCall("bad", bad.definition.name, buildJsonObject {}))
                } else {
                    val receipts = exchanges.single().results.associateBy { it.callId }
                    assertFalse(receipts.getValue("good").isError)
                    assertTrue(receipts.getValue("good").content.toString().contains("Confirmed child evidence"))
                    assertTrue(receipts.getValue("bad").isError)
                    emit(ProviderEvent.TextDelta("One child succeeded; the other child remains unfinished."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner().run(session, listOf(good, bad)).toList()
        assertEquals(2, events.filterIsInstance<AgentRunEvent.ToolFinished>().size)
        assertTrue(events.filterIsInstance<AgentRunEvent.Provider>().any { it.event == ProviderEvent.Completed })
    }

    @Test fun completedChildIsPublishedAndCheckpointedBeforeSiblingCancellation() = runBlocking {
        val published = CompletableDeferred<Unit>()
        val good = tool("child_good") { "Confirmed operation" }
        val canceled = tool("child_canceled") {
            published.await()
            throw CancellationException("Child stopped")
        }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.ToolCall("good", good.definition.name, buildJsonObject {}))
                emit(ProviderEvent.ToolCall("canceled", canceled.definition.name, buildJsonObject {}))
                emit(ProviderEvent.Completed)
            }
        }
        val checkpoint = ToolBatchCheckpoint(null, "child-retention")
        val events = mutableListOf<AgentRunEvent>()
        val failure = runCatching {
            withTimeout(5_000) {
                AgentRunner(checkpoint = checkpoint).run(session, listOf(good, canceled)).collect { event ->
                    events += event
                    if (event is AgentRunEvent.ToolFinished && event.call.callId == "good") published.complete(Unit)
                }
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("Child stopped", failure?.message)
        assertEquals(listOf("good"), events.filterIsInstance<AgentRunEvent.ToolFinished>().map { it.call.callId })
        assertFalse(events.filterIsInstance<AgentRunEvent.Provider>().any { it.event is ProviderEvent.Failed })
        assertEquals(1, checkpoint.recoverySnapshot().completedCount)
    }

    @Test fun earlierOwnerCancellationSurfacesConfirmedChildrenBufferedBehindItsOriginalCallPosition() = runBlocking {
        val checkpoint = ToolBatchCheckpoint(null, "mixed-owner-cancellation")
        val observed = mutableSetOf<String>()
        val client = tool("child_client") { "Confirmed client operation" }
        val gateway = tool("child_gateway", AgentToolExecutionOwner.GATEWAY) { throw CancellationException("Gateway child stopped") }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.ToolCall("client-1", client.definition.name, buildJsonObject {}))
                emit(ProviderEvent.ToolCall("gateway-1", gateway.definition.name, buildJsonObject {}))
                emit(ProviderEvent.ToolCall("client-2", client.definition.name, buildJsonObject {}))
                emit(ProviderEvent.Completed)
            }
        }
        val events = mutableListOf<AgentRunEvent>()
        val failure = runCatching {
            withTimeout(5_000) {
                AgentRunner(checkpoint = checkpoint, onToolResult = { call, _ -> observed += call.callId }).run(session, listOf(client, gateway)).collect { events += it }
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("Gateway child stopped", failure?.message)
        assertEquals(listOf("client-1", "client-2"), events.filterIsInstance<AgentRunEvent.ToolFinished>().map { it.call.callId })
        assertTrue(events.filterIsInstance<AgentRunEvent.ToolFinished>().all { !it.result.isError && it.result.content.toString().contains("Confirmed client operation") })
        assertTrue(observed.containsAll(listOf("client-1", "client-2")))
        assertEquals(2, checkpoint.recoverySnapshot().completedCount)
        assertTrue(events.filterIsInstance<AgentRunEvent.Provider>().none { it.event is ProviderEvent.Completed || it.event is ProviderEvent.Failed })
    }

    @Test fun laterConcurrentSuccessSurvivesCancellationOfTheFirstCallWithoutInventingItsResult() = runBlocking {
        val checkpoint = ToolBatchCheckpoint(null, "concurrent-child-cancellation")
        val canceled = tool("child_canceled") {
            while (checkpoint.recoverySnapshot().completedCount == 0) yield()
            throw CancellationException("First child stopped")
        }
        val good = tool("child_good") { "Confirmed later operation" }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.ToolCall("canceled", canceled.definition.name, buildJsonObject {}))
                emit(ProviderEvent.ToolCall("good", good.definition.name, buildJsonObject {}))
                emit(ProviderEvent.Completed)
            }
        }
        val events = mutableListOf<AgentRunEvent>()
        val failure = runCatching {
            withTimeout(5_000) {
                AgentRunner(checkpoint = checkpoint).run(session, listOf(canceled, good)).collect { events += it }
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("First child stopped", failure?.message)
        assertEquals(listOf("good"), events.filterIsInstance<AgentRunEvent.ToolFinished>().map { it.call.callId })
        assertEquals(1, checkpoint.recoverySnapshot().completedCount)
        assertTrue(events.filterIsInstance<AgentRunEvent.Provider>().none { it.event is ProviderEvent.Completed || it.event is ProviderEvent.Failed })
    }
}
