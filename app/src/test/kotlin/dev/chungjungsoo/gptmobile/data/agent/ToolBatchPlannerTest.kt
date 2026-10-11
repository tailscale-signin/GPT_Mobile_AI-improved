package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolBatchPlannerTest {
    @Test fun admittedCallsSurviveRemovalOfARepeatedTool() {
        val calls = (0..3).map { ProviderEvent.ToolCall("$it", "search_nodes", JsonObject(emptyMap())) }
        val plan = planToolBatch(calls, 10, setOf("search_nodes"), mapOf("search_nodes" to 1), emptySet(), 16) { it.name }
        assertEquals(listOf("0", "1"), plan.executable.map { it.callId })
        assertEquals(setOf("2", "3"), plan.perToolSuppressedIds)
        assertEquals(setOf("search_nodes"), plan.disabledNames)
        assertEquals(2, plan.acceptedByName["search_nodes"])
    }

    @Test fun hostSuppressionDoesNotBlockIndependentEndpoints() {
        val calls = (0..2).map { ProviderEvent.ToolCall("$it", "read_url", JsonObject(emptyMap())) }
        val plan = planToolBatch(calls, 2, setOf("read_url"), emptyMap(), setOf("0"), 16) { it.callId }
        assertEquals(listOf("1"), plan.executable.map { it.callId })
        assertEquals(listOf("0", "2"), plan.deferred.map { it.callId })
        assertEquals(setOf("0"), plan.hostSuppressedIds)
        assertTrue(plan.disabledNames.isEmpty())
    }
}
