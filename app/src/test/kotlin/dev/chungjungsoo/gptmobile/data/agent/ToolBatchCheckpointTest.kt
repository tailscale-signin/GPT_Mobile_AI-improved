package dev.chungjungsoo.gptmobile.data.agent

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
