package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.network.capacityAdmissionEvidence
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePreparedContextTest {
    private val system = "s".repeat(6144)
    private val current = ConversationTurn(MessageV2(content = "u".repeat(3840), platformType = null), null, true)
    private val limits = TokenBudgetSettings(contextTokens = 5632, outputTokens = 1408)
    private val reader = AgentToolDefinition("read_url", "Read a retained source. " + "r".repeat(360), buildJsonObject {})

    @Test fun preparedSearchEvidenceCanBorrowAdaptiveOutputWithoutLosingTheRequest() {
        val plan = ContextBudgetService.plan(
            listOf(current),
            system,
            listOf(reader),
            limits,
            reserveEstimatedSafety = true,
            allowOutputReduction = true
        )
        assertEquals(3344, ContextBudgetService.breakdown(listOf(current), system, emptyList()).total)
        assertEquals(listOf(current), plan.turns)
        assertEquals(system, plan.system)
        assertEquals(572, plan.outputTokens)
        assertFits(plan)
    }

    @Test fun requiredReaderBorrowsResultSpaceBeforeRejectingAnAdaptiveReply() {
        val plan = ContextBudgetService.plan(
            listOf(current),
            system,
            listOf(reader),
            limits,
            reserveEstimatedSafety = true,
            allowOutputReduction = true,
            requiredSchemaNames = setOf(reader.name)
        )
        assertEquals(listOf(current), plan.turns)
        assertEquals(listOf(reader), plan.tools)
        assertEquals(system, plan.system)
        assertTrue(plan.toolResultBytes in 1536 until 2112)
        assertTrue(requireNotNull(plan.outputTokens) in 512 until 1408)
        assertFits(plan)
    }

    @Test fun explicitOutputStillRejectsTheSameOversizedRequestIntact() {
        val failure = assertThrows(RequestCapacityException::class.java) {
            ContextBudgetService.plan(
                listOf(current),
                system,
                listOf(reader),
                limits,
                reserveEstimatedSafety = true,
                requiredSchemaNames = setOf(reader.name)
            )
        }
        assertEquals("CONTEXT_CAPACITY_EXCEEDED", failure.code)
        assertEquals(1408L, failure.outputTokens)
        assertEquals(3344L, failure.promptTokens)
        assertEquals(704, failure.planningToolResultReserveTokens)
    }

    @Test fun adaptiveReplanningCannotDiscardRequiredSchemasOrInventCapacity() {
        val largeReader = reader.copy(description = "r".repeat(6000))
        val failure = assertThrows(RequestCapacityException::class.java) {
            ContextBudgetService.plan(
                listOf(current),
                system,
                listOf(largeReader),
                limits,
                reserveEstimatedSafety = true,
                allowOutputReduction = true,
                requiredSchemaNames = setOf(largeReader.name)
            )
        }
        assertEquals(5632L, failure.capacityTokens)
        assertEquals(ContextBudgetService.breakdown(listOf(current), system, listOf(largeReader)).total.toLong(), failure.promptTokens)
        assertEquals(failure.promptTokens, failure.planningInput?.costs?.total?.toLong())
        val diagnostic = capacityAdmissionEvidence(failure, 1)
        assertEquals(JsonPrimitive(ContextBudgetService.breakdown(listOf(current), system, listOf(largeReader)).toolSchemas), diagnostic["admission_tool_schema_tokens"])
        assertTrue(diagnostic.toString().length < 1024)
    }

    @Test fun adaptiveReplyNeverExceedsAnExistingSmallerOutputTarget() {
        val plan = ContextBudgetService.plan(
            listOf(current),
            system,
            listOf(reader),
            limits.copy(outputTokens = 256),
            reserveEstimatedSafety = true,
            allowOutputReduction = true,
            requiredSchemaNames = setOf(reader.name)
        )
        assertEquals(256, plan.outputTokens)
        assertEquals(listOf(reader), plan.tools)
        assertFits(plan)
    }

    @Test fun exhaustedAcquisitionCanSynthesizeItsRetainedEvidenceWithoutToolReserves() {
        val plan = ContextBudgetService.plan(
            listOf(current),
            system,
            emptyList(),
            limits,
            reserveEstimatedSafety = true,
            allowOutputReduction = true
        )
        assertEquals(0, plan.toolResultBytes)
        assertEquals(1408, plan.outputTokens)
        assertEquals(listOf(current), plan.turns)
        assertFits(plan)
    }

    private fun assertFits(plan: ContextPlan) {
        val inputWithReservedResults = plan.promptTokens.toLong() + plan.toolResultBytes / 3
        assertTrue(inputWithReservedResults + RequestAdmission.estimateSafety(inputWithReservedResults) + requireNotNull(plan.outputTokens) <= limits.contextTokens)
    }
}
