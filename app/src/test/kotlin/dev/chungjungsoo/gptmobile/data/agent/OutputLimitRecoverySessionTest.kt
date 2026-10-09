package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputLimitRecoverySessionTest {
    @Test fun truncatedAnswerContinuesOnceWithEvidenceAndWithoutRepeatingTools() = runTest {
        var continuations = 0
        val call = ProviderEvent.ToolCall("done", "write", buildJsonObject {})
        val evidence = AgentToolExchange(listOf(call), listOf(AgentToolResult("done", ToolResultContent.Text("Saved change"), false)))
        val initial = session {
            emit(ProviderEvent.TextDelta("First part. "))
            emit(ProviderEvent.ToolCall("incomplete", "write", buildJsonObject {}))
            emit(ProviderEvent.Failed("The response reached its output limit."))
            emit(ProviderEvent.Usage(100, 200, 300))
        }
        val recovered = OutputLimitRecoverySession(initial) { draft, exchanges ->
            continuations++
            assertEquals("First part. ", draft)
            assertEquals("Saved change", (exchanges.single().results.single().content as ToolResultContent.Text).text)
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                    assertTrue(tools.isEmpty())
                    assertTrue(exchanges.isEmpty())
                    emit(ProviderEvent.TextDelta("Remaining part."))
                    emit(ProviderEvent.Usage(20, 10, 30))
                    emit(ProviderEvent.Completed)
                }
            }
        }
        val events = recovered.streamRound(listOf(AgentToolDefinition("write", "", buildJsonObject {})), listOf(evidence)).toList()
        assertEquals(1, continuations)
        assertEquals("First part. Remaining part.", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.none { it is ProviderEvent.ToolCall || it is ProviderEvent.Failed })
        assertEquals(1, events.count { it == ProviderEvent.Completed })
        assertEquals(210, events.filterIsInstance<ProviderEvent.Usage>().sumOf { it.outputTokens ?: 0 })
        assertTrue(events.filterIsInstance<ProviderEvent.Usage>().none { it.cumulative })
    }

    @Test fun secondOutputLimitAndNonLimitFailuresRemainFailures() = runTest {
        var attempts = 0
        val recovery = OutputLimitRecoverySession(session { emit(ProviderEvent.Failed("The response reached its output limit.")) }) { _, _ ->
            attempts++
            session { emit(ProviderEvent.Failed("The response reached its output limit.")) }
        }
        assertEquals(1, recovery.streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Failed>().size)
        assertEquals(1, attempts)
        val failure = OutputLimitRecoverySession(session { emit(ProviderEvent.Failed("Authentication failed")) }) { _, _ -> error("No retry") }
        assertEquals("Authentication failed", failure.streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Failed>().single().message)
    }

    @Test fun internallyManagedToolsAreNeverAutomaticallyReplayed() = runTest {
        val initial = object : AgentProviderSession {
            override val handlesToolsInternally = true
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.Failed("The response reached its output limit."))
            }
        }
        val wrapper = OutputLimitRecoverySession(initial) { _, _ -> error("Native tool session must not be retried") }
        assertTrue(wrapper.streamRound(emptyList(), emptyList()).toList().single() is ProviderEvent.Failed)
    }

    @Test fun cancellationIsNotConvertedIntoRecoveryOrFailure() = runTest {
        val wrapper = OutputLimitRecoverySession(session { throw CancellationException("Canceled") }) { _, _ -> error("No retry") }
        assertTrue(runCatching { wrapper.streamRound(emptyList(), emptyList()).toList() }.exceptionOrNull() is CancellationException)
    }

    @Test fun multipleCumulativeUsageSnapshotsAreNotDoubleCounted() = runTest {
        val wrapper = OutputLimitRecoverySession(
            session {
                emit(ProviderEvent.Usage(10, 20, 30))
                emit(ProviderEvent.Usage(10, 40, 50))
                emit(ProviderEvent.Completed)
            }
        ) { _, _ -> error("No retry") }
        val usage = wrapper.streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Usage>().single()
        assertEquals(40, usage.outputTokens)
        assertFalse(usage.cumulative)
    }

    private fun session(events: suspend kotlinx.coroutines.flow.FlowCollector<ProviderEvent>.() -> Unit) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow(events)
    }
}
