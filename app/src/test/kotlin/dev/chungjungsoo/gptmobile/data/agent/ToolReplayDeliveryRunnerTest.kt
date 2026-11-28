package dev.chungjungsoo.gptmobile.data.agent

import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ToolReplayDeliveryRunnerTest {
    @Test fun completedContinuationCannotPersistTheCallersUnsentBodyAsDelivered() = runBlocking {
        for (reopen in listOf(false, true)) {
            val directory = Files.createTempDirectory("replay-delivery").toFile()
            try {
                val call = ProviderEvent.ToolCall("source", "lookup", JsonObject(emptyMap()))
                val result = AgentToolResult(call.callId, ToolResultContent.Text("Exact original source body"), false)
                val checkpoint = ToolBatchCheckpoint.forTurn(directory, 1, "task", persistent = true)
                checkpoint.enqueue(listOf(call))
                checkpoint.complete(call, result)
                checkpoint.finish()
                val reader = object : AgentTool {
                    override val definition = AgentToolDefinition(call.name, "Read", JsonObject(emptyMap()))
                    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("A completed tool must not be repeated")
                }
                fun provider(vararg events: ProviderEvent) = object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flowOf(*events)
                }
                val first = if (reopen) {
                    provider(ProviderEvent.TextDelta("First finding. "), ProviderEvent.Failed("The response reached its output limit.", transportComplete = true))
                } else {
                    provider(ProviderEvent.TextDelta("Complete finding."), ProviderEvent.Completed)
                }
                val continued = GitHubOutputContinuationSession(first) { _, _, _ -> provider(ProviderEvent.TextDelta("Next finding from bounded source notes."), ProviderEvent.Completed) }
                val events = AgentRunner(AgentRunLimits(maxRounds = 4, maxToolCalls = 4), checkpoint = checkpoint).run(continued, listOf(reader)).toList()
                assertFalse(events.filterIsInstance<AgentRunEvent.Provider>().any { it.event is ProviderEvent.Failed })
                val restored = checkpoint.recoverySnapshot()
                assertEquals(!reopen, call.callId in restored.consumedCallIds)
                assertEquals(if (reopen) null else ToolResultDeliveryProof.digest(result.content), restored.deliveredResultDigests[call.callId])
            } finally {
                directory.deleteRecursively()
            }
        }
    }
}
