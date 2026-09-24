package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayEfficiencyTest {

    @Test
    fun `efficiency is useful calls divided by total calls`() {
        assertEquals(30, gatewayEfficiencyPercent(10, 3))
        assertEquals(100, gatewayEfficiencyPercent(2, 4))
        assertNull(gatewayEfficiencyPercent(0, 0))
    }

    @Test
    fun `recovery outranks ordinary exploration when no progress accumulates`() {
        assertEquals(
            GatewayWorkState.RECOVERING,
            resolveGatewayWorkState(
                stage = "research",
                event = "progress",
                totalToolCalls = 10,
                usefulToolCalls = 3,
                noProgress = 3,
                currentTool = null
            )
        )
    }

    @Test
    fun `synthesis and finalization are surfaced explicitly`() {
        assertEquals(
            GatewayWorkState.SYNTHESIZING,
            resolveGatewayWorkState("hard synthesis", null, 10, 3, 2, null)
        )
        assertEquals(
            GatewayWorkState.FINALIZING,
            resolveGatewayWorkState("final response", null, 10, 3, 2, null)
        )
    }

    @Test
    fun `activity history deduplicates consecutive repeats and keeps newest samples`() {
        var history = emptyList<GatewayActivitySample>()
        repeat(8) { index ->
            history = appendGatewayActivity(
                history,
                GatewayProgress(
                    sequence = index,
                    stage = "round-$index",
                    message = "message-$index"
                ),
                limit = 3
            )
        }
        history = appendGatewayActivity(
            history,
            GatewayProgress(
                sequence = 7,
                stage = "round-7",
                message = "message-7"
            ),
            limit = 3
        )

        assertEquals(listOf(5, 6, 7), history.mapNotNull { it.sequence })
    }
}
