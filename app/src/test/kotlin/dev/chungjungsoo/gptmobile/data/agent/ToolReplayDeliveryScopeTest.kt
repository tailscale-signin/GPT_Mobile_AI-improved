package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolReplayDeliveryScopeTest {
    private fun session(vararg events: ProviderEvent) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flowOf(*events)
    }

    @Test fun reopenedProjectionCannotAcknowledgeCallerBodiesThroughOuterFlowWrappers() = runBlocking {
        val source = AgentToolExchange(
            listOf(ProviderEvent.ToolCall("source", "github_read", JsonObject(emptyMap()))),
            listOf(AgentToolResult("source", ToolResultContent.Text("Exact original source body"), false))
        )
        for (reopen in listOf(false, true)) {
            val first = if (reopen) {
                session(ProviderEvent.TextDelta("First finding. "), ProviderEvent.Failed("The response reached its output limit.", transportComplete = true))
            } else {
                session(ProviderEvent.TextDelta("Complete finding."), ProviderEvent.Completed)
            }
            var reopened = 0
            val continued = GitHubOutputContinuationSession(first) { _, _, _ ->
                reopened++
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                        assertTrue(exchanges.isEmpty())
                        emit(ProviderEvent.TextDelta("Next finding from a different bounded projection."))
                        emit(ProviderEvent.Completed)
                    }
                }
            }
            val outer = object : AgentProviderSession by continued {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    emitAll(continued.streamRound(tools, exchanges))
                }.flowOn(Dispatchers.Default)
            }
            val proof = ToolReplayDeliveryScope()
            val output = outer.streamRound(emptyList(), listOf(source)).flowOn(proof).toList()
            assertEquals(ProviderEvent.Completed, output.last())
            assertEquals(if (reopen) 1 else 0, reopened)
            assertEquals(!reopen, proof.acknowledgesCallerBodies)
        }
    }

    @Test fun suppressionCannotLeakIntoTheNextRoundOrBeReplenishedWithinOneRound() {
        val original = ToolReplayDeliveryScope()
        original.replaySubstituted()
        original.replaySubstituted()
        assertFalse(original.acknowledgesCallerBodies)
        assertTrue(ToolReplayDeliveryScope().acknowledgesCallerBodies)
    }
}
