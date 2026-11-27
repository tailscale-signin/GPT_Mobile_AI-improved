package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.RequestAdmission
import dev.chungjungsoo.gptmobile.data.context.RequestCapacityException
import dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveToolCatalogContextTest {
    private val reader = object : AgentTool {
        override val definition = AgentToolDefinition("read_url", "Read a retained source. " + "r".repeat(360), buildJsonObject {})
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Planning must not execute the reader")
    }
    private val tools = listOf(ResolvedAgentTool(reader, null, "Web", reader.definition.name, reader.definition.name))
    private val turn = ConversationTurn(MessageV2(content = "u".repeat(3840), platformType = null), null, true)
    private val system = "s".repeat(6144)
    private val limits = TokenBudgetSettings(contextTokens = 5632, outputTokens = 1408)

    @Test fun ordinaryCatalogCarriesAdaptiveOutputPermissionAndRequiredReaderIntoReplanning() {
        val plan = planAuthorizedToolContext(
            listOf(turn),
            system,
            tools,
            limits,
            externalTools = false,
            requestedTask = "Use read_url",
            allowOutputReduction = true
        )
        assertEquals(listOf(reader.definition), plan.tools)
        assertEquals(listOf(turn), plan.turns)
        assertEquals(system, plan.system)
        assertTrue(requireNotNull(plan.outputTokens) in 512 until 1408)
        val input = plan.promptTokens.toLong() + plan.toolResultBytes / 3
        assertTrue(input + RequestAdmission.estimateSafety(input) + requireNotNull(plan.outputTokens) <= 5632)
    }

    @Test fun ordinaryCatalogCannotReduceAnExplicitOutputTarget() {
        val failure = assertThrows(RequestCapacityException::class.java) {
            planAuthorizedToolContext(
                listOf(turn),
                system,
                tools,
                limits,
                externalTools = false,
                requestedTask = "Use read_url",
                allowOutputReduction = false
            )
        }
        assertEquals(1408L, failure.outputTokens)
        assertEquals(5632L, failure.capacityTokens)
    }
}
