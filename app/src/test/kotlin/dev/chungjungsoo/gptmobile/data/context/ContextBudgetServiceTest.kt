package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.provider.ProviderRouteCapacity
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.EndpointLocality
import dev.chungjungsoo.gptmobile.data.model.endpointLocality
import dev.chungjungsoo.gptmobile.data.model.isPrivateDestination
import dev.chungjungsoo.gptmobile.data.network.capacityAdmissionEvidence
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetServiceTest {
    @Test fun ordinaryWindowPreservesGoalRecentTurnAndOlderMatchingEvidence() {
        val turns = listOf("original goal", "evidence tungsten", "irrelevant exchange", "latest turn").map {
            ConversationTurn(MessageV2(content = it, platformType = null), null, false)
        }
        assertEquals(listOf(turns[0], turns[1], turns[3]), MemoryTokenWindow.forQuestion(turns, 3, "tungsten") { 1 })
        assertEquals(emptyList<ConversationTurn>(), MemoryTokenWindow.forQuestion(turns, 1, "goal") { 2 })
    }

    @Test fun olderEvidenceCanMatchTheEndOfALongQuestionAndMessage() {
        val filler = "unrelated ".repeat(300)
        val turns = listOf("goal", filler + "zirconium", "please could you", "latest").map {
            ConversationTurn(MessageV2(content = it, platformType = null), null, false)
        }
        val question = "please could you " + "context ".repeat(350) + "find zirconium"
        assertEquals(listOf(turns[0], turns[1], turns[3]), MemoryTokenWindow.forQuestion(turns, 3, question) { 1 })
    }

    @Test fun `defaults preserve context and use fifty tool calls`() {
        val turns = (0..9).map { ConversationTurn(MessageV2(content = "history ".repeat(3000), platformType = null), null, it == 9) }
        val plan = ContextBudgetService.plan(turns, "system", emptyList(), TokenBudgetSettings())
        assertEquals(turns, plan.turns)
        assertNull(plan.outputTokens)
        assertEquals(Int.MAX_VALUE, TokenBudgetSettings().contextTokens)
        assertEquals(Int.MAX_VALUE, TokenBudgetSettings().totalRunTokens)
        assertEquals(50, dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits().maxToolCalls)
        assertEquals(50, PlatformV2(name = "New model").maxToolCalls)
    }

    @Test fun `explicit output limits above the old cap are retained`() {
        val plan = ContextBudgetService.plan(emptyList(), "", emptyList(), TokenBudgetSettings(outputTokens = 100000))
        assertEquals(100000, plan.outputTokens)
    }

    @Test fun `context reservation does not silently lower explicit output limits`() {
        val plan = ContextBudgetService.plan(emptyList(), "", emptyList(), TokenBudgetSettings(contextTokens = 4096, outputTokens = 2048))
        assertEquals(2048, plan.outputTokens)
        assertTrue(plan.notice.contains("2048 output reserved"))
    }

    @Test fun `small text only contexts do not reserve space for unavailable tools`() {
        val current = ConversationTurn(MessageV2(content = "Hi", platformType = null), null, true)
        val plan = ContextBudgetService.plan(listOf(current), "Concise reply.", emptyList(), TokenBudgetSettings(contextTokens = 256))
        assertEquals(listOf(current), plan.turns)
        assertEquals(0, plan.toolResultBytes)
        assertNull(plan.outputTokens)
    }

    @Test
    fun `discovery only accepts a matching model and explicit bounded context limit`() {
        val body = """{"data":[{"id":"large","context_length":128000},{"id":"small","context_length":4096}]}"""
        assertEquals(4096, discoverContextCeiling(body, "small"))
        assertNull(discoverContextCeiling(body, "unknown"))
        assertNull(discoverContextCeiling("""{"context_length":128000,"models":[{"name":"other"}]}""", "small"))
        assertNull(discoverContextCeiling("""{"id":"other","context_length":128000}""", "small"))
        assertEquals(4096, discoverContextCeiling("""{"model":"small","meta":{"n_ctx":4096}}""", "small"))
        assertEquals(2048, discoverContextCeiling("""{"data":[{"id":"small","n_ctx":8192},{"id":"small","n_ctx":2048}]}""", "small"))
        assertNull(discoverContextCeiling("""{"context_length":-1}""", "model"))
    }

    @Test fun `tools history and output share one context reservation without dropping current input`() {
        val turns = (0..9).map { ConversationTurn(MessageV2(content = "history ".repeat(300), platformType = null), null, it == 9) }
        val plan = ContextBudgetService.plan(turns, "system", listOf(AgentToolDefinition("tool", "description".repeat(400), buildJsonObject {})), TokenBudgetSettings(contextTokens = 2048, outputTokens = 512))
        assertTrue(plan.turns.last().isCurrentTurn)
        assertTrue(plan.turns.size < turns.size)
        assertTrue(plan.notice.contains("omitted"))
        assertEquals(512, plan.outputTokens)
        assertEquals(256 * 3, plan.toolResultBytes)
    }

    @Test fun `implicit output can use remaining headroom while explicit output preserves its allowance`() {
        val current = ConversationTurn(MessageV2(content = "x".repeat(9300), platformType = null), null, true)
        val settings = TokenBudgetSettings(contextTokens = 4096)
        val plan = ContextBudgetService.plan(listOf(current), "", emptyList(), settings)
        assertEquals(listOf(current), plan.turns)
        assertEquals(724, plan.outputTokens)
        val blocked = runCatching {
            ContextBudgetService.plan(listOf(current), "", emptyList(), settings.copy(outputTokens = 1024))
        }.exceptionOrNull() as RequestCapacityException
        assertEquals("CONTEXT_CAPACITY_EXCEEDED", blocked.code)
        assertEquals(3116L, blocked.promptTokens)
        assertEquals(256L, blocked.safetyTokens)
        assertEquals(1024L, blocked.outputTokens)
        assertEquals(4096L, blocked.capacityTokens)
    }

    @Test fun `tool result reservation remains visible when the current input cannot fit`() {
        val tool = AgentToolDefinition("read", "Read", buildJsonObject {})
        val blocked = runCatching {
            ContextBudgetService.plan(emptyList(), "x".repeat(12000), listOf(tool), TokenBudgetSettings(contextTokens = 4096))
        }.exceptionOrNull() as RequestCapacityException
        assertEquals(4000L, blocked.promptTokens)
        assertEquals(512, blocked.planningToolResultReserveTokens)
        assertTrue(blocked.message.orEmpty().contains("No output headroom remains"))
    }

    @Test fun smallToolRoutesPageSavedEvidenceBeforeAssemblingTheCurrentTurn() {
        val route = TokenBudgetSettings()
        val budget = ReplayAllowance.conservativeToolPlanningBudget(route, toolEvidenceRequired = true)
        val reader = AgentToolDefinition("read_recovery_context", "Read a saved page", buildJsonObject {})
        val evidence = "x".repeat(7000)
        for (reportedPrompt in listOf(3589, 3597)) {
            val goal = "g".repeat((reportedPrompt - 2016) * 3)
            fun turn(context: Int) = ConversationTurn(MessageV2(content = goal + evidence.take(ReplayAllowance.inlineRecoveryCharacters(context)), platformType = null), null, true)
            val oldFailure = runCatching { ContextBudgetService.plan(listOf(turn(route.contextTokens)), "", listOf(reader), budget) }.exceptionOrNull() as RequestCapacityException
            assertEquals(reportedPrompt.toLong(), oldFailure.promptTokens)
            val current = turn(budget.contextTokens)
            val plan = ContextBudgetService.plan(listOf(current), "", listOf(reader), budget)
            assertTrue(current.userMessage.content.startsWith(goal))
            assertEquals(listOf(current), plan.turns)
            assertEquals(listOf(reader), plan.tools)
            assertEquals(768, plan.outputTokens)
            assertEquals(512 * 3, plan.toolResultBytes)
        }
    }

    @Test fun planningRejectionBindsOnlyTheFixedInputsAndTheActualBudgetSource() {
        val unknown = ProviderRouteCapacity.PrimaryContextDecision(Int.MAX_VALUE, null, null, null, CapacityProvenance.UNKNOWN)
        val declared = unknown.copy(tokens = 4096, configuredTokens = 4096, provenance = CapacityProvenance.DECLARED)
        assertEquals(CapacityProvenance.DECLARED, declared.toolPlanningProvenance(4096, 4096))
        assertEquals(CapacityProvenance.CONSERVATIVE_POLICY, declared.toolPlanningProvenance(2048, 2048))
        val planned = ReplayAllowance.conservativeToolPlanningBudget(TokenBudgetSettings(), true)
        val source = unknown.toolPlanningProvenance(Int.MAX_VALUE, planned.contextTokens)
        assertEquals(CapacityProvenance.CONSERVATIVE_POLICY, source)
        val system = "instruction".repeat(600)
        val turns = listOf(
            ConversationTurn(MessageV2(content = "private history", platformType = null), null, false),
            ConversationTurn(MessageV2(content = "current".repeat(900), platformType = null), null, true)
        )
        val failure = assertThrows(RequestCapacityException::class.java) {
            ContextBudgetService.plan(turns, system, emptyList(), planned, planningProvenance = source)
        }
        val fields = capacityAdmissionEvidence(failure, 1)
        assertEquals(JsonPrimitive("CONSERVATIVE_POLICY"), fields["admission_capacity_provenance"])
        assertEquals(JsonPrimitive(ContextBudgetService.estimate(system)), fields["admission_system_tokens"])
        assertEquals(JsonPrimitive(ContextBudgetService.estimate(turns.last().userMessage.content) + 16), fields["admission_current_request_tokens"])
        assertEquals(JsonPrimitive(0), fields["admission_attachment_reserve_tokens"])
        assertNull(fields["admission_history_tokens"])
        assertNull(fields["admission_route_context_tokens"])
        assertFalse(fields.toString().contains("private history"))
    }

    @Test fun `oversize current message fails visibly rather than dropping the user request`() {
        assertTrue(
            runCatching {
                ContextBudgetService.plan(listOf(ConversationTurn(MessageV2(content = "x".repeat(50000), platformType = null), null, true)), "", emptyList(), TokenBudgetSettings(contextTokens = 2048))
            }.isFailure
        )
    }

    @Test fun `privacy depends on endpoint rather than llama or ollama label`() {
        assertFalse(PlatformV2(name = "remote", compatibleType = ClientType.OLLAMA, apiUrl = "https://external.example").isPrivateDestination())
        assertTrue(PlatformV2(name = "private", compatibleType = ClientType.CUSTOM, apiUrl = "http://192.168.1.2:8000").isPrivateDestination())
        assertEquals(EndpointLocality.EXTERNAL, endpointLocality("https://192.168.1.2.external.example"))
        assertEquals(EndpointLocality.EXTERNAL, endpointLocality("http://127.evil.example"))
        assertEquals(EndpointLocality.PRIVATE_NETWORK, endpointLocality("http://[fd00::1]:8000"))
    }
}
