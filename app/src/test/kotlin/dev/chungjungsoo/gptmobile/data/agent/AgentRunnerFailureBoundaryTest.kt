package dev.chungjungsoo.gptmobile.data.agent

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunnerFailureBoundaryTest {
    private fun provider(block: suspend FlowCollector<ProviderEvent>.(List<AgentToolDefinition>) -> Unit) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow { block(tools) }
    }

    private val unusedTool = object : AgentTool {
        override val definition = AgentToolDefinition("write", "Must not execute during recovery", JsonObject(emptyMap()))
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Recovery must not execute tools")
    }

    private fun List<AgentRunEvent>.providerEvents(): List<ProviderEvent> = filterIsInstance<AgentRunEvent.Provider>().map { it.event }

    @Test
    fun `downstream text failure is preserved without cleanup emissions or retries`() = runBlocking {
        val expected = IOException("connection reset in consumer")
        var requests = 0
        val received = mutableListOf<AgentRunEvent>()
        val session = provider {
            requests++
            emit(ProviderEvent.Usage(10, 2, 12))
            emit(ProviderEvent.TextDelta("Answer"))
            emit(ProviderEvent.Completed)
        }
        val failure = runCatching {
            AgentRunner().run(session, emptyList()).collect { event ->
                received += event
                throw expected
            }
        }.exceptionOrNull()
        assertSame(expected, failure)
        assertEquals(1, requests)
        assertEquals(listOf(ProviderEvent.TextDelta("Answer")), received.providerEvents())
    }

    @Test
    fun `downstream usage failure is not reclassified or emitted twice`() = runBlocking {
        val expected = IOException("usage consumer failed")
        var requests = 0
        var received = 0
        val session = provider {
            requests++
            emit(ProviderEvent.Usage(10, 2, 12))
            emit(ProviderEvent.Completed)
        }
        val failure = runCatching {
            AgentRunner().run(session, emptyList()).collect {
                received++
                throw expected
            }
        }.exceptionOrNull()
        assertSame(expected, failure)
        assertEquals(1, requests)
        assertEquals(1, received)
    }

    @Test
    fun `consumer tool rejection never triggers provider fallback`() = runBlocking {
        val expected = ToolDefinitionsRejectedException("consumer rejected event")
        var requests = 0
        val session = provider {
            requests++
            emit(ProviderEvent.Usage(10, 2, 12))
            emit(ProviderEvent.TextDelta("Answer"))
            emit(ProviderEvent.Completed)
        }
        val failure = runCatching {
            AgentRunner().run(session, listOf(unusedTool)).collect { throw expected }
        }.exceptionOrNull()
        assertSame(expected, failure)
        assertEquals(1, requests)
    }

    @Test
    fun `provider cancellation emits no cleanup usage or failure`() = runBlocking {
        val expected = CancellationException("user stopped")
        val events = mutableListOf<AgentRunEvent>()
        val session = provider {
            emit(ProviderEvent.Usage(10, 2, 12))
            throw expected
        }
        val failure = runCatching { AgentRunner().run(session, emptyList()).toList(events) }.exceptionOrNull()
        assertSame(expected, failure)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `consumer cancellation is not masked by cleanup usage`() = runBlocking {
        val expected = CancellationException("screen stopped collecting")
        var received = 0
        val session = provider {
            emit(ProviderEvent.Usage(10, 2, 12))
            emit(ProviderEvent.TextDelta("Answer"))
        }
        val failure = runCatching {
            AgentRunner().run(session, emptyList()).collect {
                received++
                throw expected
            }
        }.exceptionOrNull()
        assertSame(expected, failure)
        assertEquals(1, received)
    }

    @Test
    fun `short circuiting a consumer terminates without a flow transparency error`() = runBlocking {
        var reachedCompletion = false
        val session = provider {
            emit(ProviderEvent.Usage(10, 2, 12))
            emit(ProviderEvent.TextDelta("Answer"))
            reachedCompletion = true
            emit(ProviderEvent.Completed)
        }
        val events = AgentRunner().run(session, emptyList()).take(1).toList()
        assertEquals(listOf(ProviderEvent.TextDelta("Answer")), events.providerEvents())
        assertFalse(reachedCompletion)
    }

    @Test
    fun `provider exception preserves observed usage and never restarts a partial answer`() = runBlocking {
        var requests = 0
        val session = provider {
            requests++
            emit(ProviderEvent.Usage(10, 2, 12))
            emit(ProviderEvent.Usage(20, 4, 24))
            emit(ProviderEvent.Usage(3, 1, 4, cumulative = false))
            emit(ProviderEvent.TextDelta("Partial answer"))
            throw IOException("connection reset")
        }
        val events = AgentRunner().run(session, emptyList()).toList().providerEvents()
        assertEquals(1, requests)
        assertEquals(ProviderEvent.Usage(23, 5, 28, cumulative = false), events.filterIsInstance<ProviderEvent.Usage>().single())
        assertEquals(1, events.filterIsInstance<ProviderEvent.Failed>().size)
        assertFalse(events.contains(ProviderEvent.Completed))
    }

    @Test
    fun `first terminal failure survives trailing usage and a transport exception`() = runBlocking {
        var requests = 0
        val session = provider {
            requests++
            emit(ProviderEvent.Failed("The provider rejected this request."))
            emit(ProviderEvent.TextDelta("Ignore late content"))
            emit(ProviderEvent.ToolCall("late", "write", JsonObject(emptyMap())))
            emit(ProviderEvent.Usage(10, 2, 12))
            throw IOException("connection reset")
        }
        val events = AgentRunner().run(session, listOf(unusedTool)).toList().providerEvents()
        assertEquals(1, requests)
        assertEquals(listOf(ProviderEvent.Failed("The provider rejected this request.")), events.filterIsInstance<ProviderEvent.Failed>())
        assertEquals(12, events.filterIsInstance<ProviderEvent.Usage>().single().totalTokens)
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().isEmpty())
        assertTrue(events.filterIsInstance<ProviderEvent.ToolCall>().isEmpty())
    }

    @Test
    fun `provider tool rejection retains usage before one tool free fallback`() = runBlocking {
        var requests = 0
        val session = provider { tools ->
            requests++
            if (requests == 1) {
                assertTrue(tools.isNotEmpty())
                emit(ProviderEvent.Usage(10, 2, 12))
                throw ToolDefinitionsRejectedException("Tools unsupported")
            }
            assertTrue(tools.isEmpty())
            emit(ProviderEvent.Usage(5, 1, 6))
            emit(ProviderEvent.TextDelta("Recovered"))
            emit(ProviderEvent.Completed)
        }
        val events = AgentRunner().run(session, listOf(unusedTool)).toList().providerEvents()
        assertEquals(2, requests)
        assertEquals(18, events.filterIsInstance<ProviderEvent.Usage>().sumOf { it.totalTokens ?: 0 })
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().isEmpty())
        assertEquals(1, events.count { it == ProviderEvent.Completed })
    }

    @Test
    fun `tool rejection after answer text cannot restart the answer`() = runBlocking {
        var requests = 0
        val session = provider {
            requests++
            emit(ProviderEvent.TextDelta("Partial answer"))
            throw ToolDefinitionsRejectedException("Tools unsupported after text")
        }
        val events = AgentRunner().run(session, listOf(unusedTool)).toList().providerEvents()
        assertEquals(1, requests)
        assertEquals(1, events.filterIsInstance<ProviderEvent.TextDelta>().size)
        assertEquals(1, events.filterIsInstance<ProviderEvent.Failed>().size)
    }

    @Test
    fun `synchronous provider construction failure remains bounded`() = runBlocking {
        var requests = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> {
                requests++
                if (requests == 2) assertTrue(tools.isEmpty())
                throw ToolDefinitionsRejectedException("Tools unsupported")
            }
        }
        val events = AgentRunner().run(session, listOf(unusedTool)).toList().providerEvents()
        assertEquals(2, requests)
        assertEquals(1, events.filterIsInstance<ProviderEvent.Failed>().size)
    }

    @Test
    fun `silent provider interruption still gets exactly one recovery attempt`() = runBlocking {
        var requests = 0
        val session = provider { tools ->
            requests++
            if (requests == 2) assertTrue(tools.isEmpty())
            emit(ProviderEvent.Usage(10, 2, 12))
            emit(ProviderEvent.Failed("Software caused connection abort"))
        }
        val events = AgentRunner().run(session, listOf(unusedTool)).toList().providerEvents()
        assertEquals(2, requests)
        assertEquals(24, events.filterIsInstance<ProviderEvent.Usage>().sumOf { it.totalTokens ?: 0 })
        assertEquals(1, events.filterIsInstance<ProviderEvent.Failed>().size)
    }
}
