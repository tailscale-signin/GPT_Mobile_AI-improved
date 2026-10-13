package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints
import dev.chungjungsoo.gptmobile.data.assist.AssistChunkPlanner
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterProfileOverrides
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** These replay failure shape, never the user's private subject matter or a qualified device. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IncidentShapeRegressionTest {
    private val shapes = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/reliability/incident-shapes.json")).readText()).jsonObject

    @Test
    fun i03RoutingOnlyProfileCannotManufactureTheRecordedClamp() {
        val incident = shapes.getValue("I03").jsonObject
        val overrides = OpenRouterProfileOverrides.parse(incident.getValue("routingOnly").toString())
        assertEquals(OpenRouterProfileOverrides.Presence.ABSENT, overrides.presence("max_tokens"))
        assertEquals(16384, RequestConstraints(maxOutputTokens = incident.getValue("requestedOutputTokens").jsonPrimitive.int).outputLimit(null))
        assertEquals(4096, RequestConstraints(maxOutputTokens = 16384).outputLimit(4096))
    }

    @Test
    fun i01BothRecordedAggregateSizesRemainEligibleForChunkPlanning() {
        val incident = shapes.getValue("I01").jsonObject
        for (key in listOf("searchAUtf16", "searchBDecodedUtf16")) {
            val source = "s".repeat(incident.getValue(key).jsonPrimitive.int)
            val spans = source.chunked(1200)
            val plan = AssistChunkPlanner.plan(spans, 8192, 1024) { it.joinToString("\n") }
            assertTrue(plan.chunks.isNotEmpty())
            assertTrue(plan.unprocessed.isEmpty())
            assertEquals(source, plan.chunks.flatten().joinToString(""))
        }
    }

    @Test
    fun i02ReasoningOnlyFailureIsNotReclassifiedAsContextOverflow() = runTest {
        val incident = shapes.getValue("I02").jsonObject
        val initial = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                emit(ProviderEvent.ThinkingDelta("r".repeat(incident.getValue("reasoningBytes").jsonPrimitive.int)))
                delay(200_000)
            }
        }
        val events = initial.withProgressWatchdog(nowMillis = { testScheduler.currentTime }).streamRound(emptyList(), emptyList()).toList()
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().isEmpty())
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().message.startsWith("GENERATION_STALLED"))
    }
}
