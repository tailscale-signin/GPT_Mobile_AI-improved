package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreePromptToolSessionTest {
    private val tool = AgentToolDefinition("web_search", "Search selected public engines", JsonObject(emptyMap()))
    private val command = """{"type":"tool_call","name":"web_search","arguments":{"query":"public research"}}"""
    private fun session(block: suspend FlowCollector<ProviderEvent>.() -> Unit) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
            assertTrue(tools.isEmpty())
            assertTrue(exchanges.isEmpty())
            block()
        }
    }

    @Test fun chunkedCommandsAreHiddenAndBecomeAuthorizedTypedCallsOnlyAfterCompletion() = runTest {
        val bridge = FreePromptToolSession { instruction, _ ->
            assertTrue(instruction.contains("web_search"))
            session {
                command.chunked(3).forEach { emit(ProviderEvent.TextDelta(it)) }
                emit(ProviderEvent.Completed)
            }
        }
        val events = bridge.streamRound(listOf(tool), emptyList()).toList()
        assertEquals("web_search", events.filterIsInstance<ProviderEvent.ToolCall>().single().name)
        assertFalse(events.any { it is ProviderEvent.TextDelta })
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test fun interruptedOrFailedCommandsNeverExecuteAndAreNeverPublishedAsProse() = runTest {
        for (explicitFailure in listOf(false, true)) {
            val bridge = FreePromptToolSession { _, _ ->
                session {
                    emit(ProviderEvent.TextDelta(command))
                    if (explicitFailure) emit(ProviderEvent.Failed("Rate limited", errorCode = "PROVIDER_RATE_LIMITED"))
                }
            }
            val events = bridge.streamRound(listOf(tool), emptyList()).toList()
            assertFalse(events.any { it is ProviderEvent.ToolCall || it is ProviderEvent.TextDelta || it == ProviderEvent.Completed })
            assertEquals(1, events.filterIsInstance<ProviderEvent.Failed>().size)
        }
    }

    @Test fun unknownCommandsCannotEscapeTheAllowlistOrTriggerHiddenRetries() = runTest {
        var attempts = 0
        val bridge = FreePromptToolSession { _, _ ->
            session {
                attempts++
                emit(ProviderEvent.TextDelta(command.replace("web_search", "delete_files")))
                emit(ProviderEvent.Completed)
            }
        }
        val events = bridge.streamRound(listOf(tool), emptyList()).toList()
        assertEquals(1, attempts)
        assertFalse(events.any { it is ProviderEvent.ToolCall || it is ProviderEvent.TextDelta || it == ProviderEvent.Completed })
        assertEquals("OUTPUT_PROTOCOL_INVALID", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
    }

    @Test fun toolsDisabledNeverRepairsOrExecutesACommand() = runTest {
        var attempts = 0
        val bridge = FreePromptToolSession { _, _ ->
            session {
                attempts++
                emit(ProviderEvent.TextDelta(command))
                emit(ProviderEvent.Completed)
            }
        }
        val events = bridge.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, attempts)
        assertFalse(events.any { it is ProviderEvent.ToolCall || it is ProviderEvent.TextDelta })
        assertTrue(events.any { it is ProviderEvent.Failed })
    }

    @Test fun ordinaryAnswersStillStreamWithoutARepairRequest() = runTest {
        var attempts = 0
        val bridge = FreePromptToolSession { _, _ ->
            session {
                attempts++
                emit(ProviderEvent.TextDelta("Hello"))
                emit(ProviderEvent.TextDelta(" there"))
                emit(ProviderEvent.Completed)
            }
        }
        val events = bridge.streamRound(listOf(tool), emptyList()).toList()
        assertEquals("Hello there", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(1, attempts)
    }

    @Test fun cancellationPropagatesWithoutExecutingOrRepairing() = runTest {
        val bridge = FreePromptToolSession { _, _ ->
            session {
                emit(ProviderEvent.TextDelta(command))
                throw CancellationException("stopped")
            }
        }
        val events = mutableListOf<ProviderEvent>()
        val error = runCatching { bridge.streamRound(listOf(tool), emptyList()).collect { events += it } }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertFalse(events.any { it is ProviderEvent.ToolCall || it is ProviderEvent.TextDelta })
    }
}
