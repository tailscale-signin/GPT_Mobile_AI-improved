package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.DeviceLocation
import dev.chungjungsoo.gptmobile.data.agent.tool.DeviceLocationProvider
import dev.chungjungsoo.gptmobile.data.agent.tool.DeviceLocationTool
import dev.chungjungsoo.gptmobile.data.model.ClientType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLocationSessionTest {
    private val locationProvider = mockk<DeviceLocationProvider>()
    private val tool = DeviceLocationTool(locationProvider, mockk(relaxed = true))

    @Test
    fun `direct llama location request executes on phone and hands actual result to provider once`() = runTest {
        every { locationProvider.hasPermission() } returns true
        coEvery { locationProvider.getCurrentLocation(any()) } returns DeviceLocation(43.0, -79.0, 8f, null, 10L, "gps")
        val delegate = RecordingSession()
        val session = delegate.withDeviceLocation(ClientType.LLAMA, "Where am I?", tool.definition.name)

        val events = AgentRunner().run(session, listOf(tool)).toList()

        coVerify(exactly = 1) { locationProvider.getCurrentLocation(any()) }
        assertEquals(1, delegate.requests.size)
        val exchange = delegate.requests.single().single()
        val result = exchange.results.single()
        assertEquals(exchange.calls.single().callId, result.callId)
        assertFalse(result.isError)
        assertTrue((result.content as ToolResultContent.Json).value.toString().contains("accuracy_meters"))
        assertEquals(1, events.filterIsInstance<AgentRunEvent.ToolFinished>().size)
    }

    @Test
    fun `real Android permission denial reaches llama without a made up result`() = runTest {
        every { locationProvider.hasPermission() } returns false
        val delegate = RecordingSession()
        AgentRunner().run(delegate.withDeviceLocation(ClientType.LLAMA, "Test device_location", tool.definition.name), listOf(tool)).toList()
        val result = delegate.requests.single().single().results.single()
        assertTrue(result.isError)
        assertTrue((result.content as ToolResultContent.Text).text.contains("Android location permission is not granted"))
        coVerify(exactly = 0) { locationProvider.getCurrentLocation(any()) }
    }

    @Test
    fun `disabled location tools and exhausted budget do not cause a lookup`() = runTest {
        listOf(false, true).forEach { zeroBudget ->
            val delegate = RecordingSession()
            val runner = AgentRunner(AgentRunLimits(maxToolCalls = if (zeroBudget) 0 else 12))
            val events = runner.run(
                delegate.withDeviceLocation(ClientType.LLAMA, "Where am I?", tool.definition.name),
                if (zeroBudget) listOf(tool) else emptyList()
            ).toList()
            assertTrue(events.filterIsInstance<AgentRunEvent.ToolStarted>().isEmpty())
            assertTrue(delegate.requests.single().isEmpty())
        }
        coVerify(exactly = 0) { locationProvider.getCurrentLocation(any()) }
    }

    @Test
    fun `tool descriptions reports negation quoted requests and other providers are untouched`() {
        val delegate = RecordingSession()
        listOf(
            "What can the location tool do?",
            "Do not call device_location",
            "Explain why device_location reports ACCESS_DENIED",
            "\"Where am I?\"",
            "Translate: Where am I?",
            "Use my timezone to guess my location",
            "Where am I in this story?",
            "Where am I?\nDo not use GPS.",
            "```device_location()```"
        ).forEach { prompt ->
            assertSame(prompt, delegate, delegate.withDeviceLocation(ClientType.LLAMA, prompt, tool.definition.name))
        }
        assertSame(delegate, delegate.withDeviceLocation(ClientType.OPENROUTER, "Where am I?", tool.definition.name))
        assertSame(delegate, delegate.withDeviceLocation(ClientType.LLAMA, "Where am I?", null))
    }

    private class RecordingSession : AgentProviderSession {
        val requests = mutableListOf<List<AgentToolExchange>>()
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> {
            requests += exchanges
            return flowOf(ProviderEvent.TextDelta("Answer based on tool result"), ProviderEvent.Completed)
        }
    }
}
