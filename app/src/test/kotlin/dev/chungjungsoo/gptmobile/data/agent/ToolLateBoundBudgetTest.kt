package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.assist.AssistTurnPlanningTool
import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService
import dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLateBoundBudgetTest {
    private val arguments = JsonObject(emptyMap())

    @Test fun finiteContextPlannerAdmissionFundsAlreadyBoundToolWithoutResettingCalls() = runBlocking {
        val settings = TokenBudgetSettings(contextTokens = 8192, outputTokens = 1024)
        val initial = ContextBudgetService.plan(emptyList(), "Plan the current task.", emptyList(), settings)
        assertEquals(0, initial.toolResultBytes)
        val planner = AssistTurnPlanningTool("Plan the research steps for this task")
        val budget = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 4, maxToolOutputBytes = initial.toolResultBytes), initialCalls = 2)
        val bound = budget.bind(planner)
        assertFalse(budget.canExecute())

        val admitted = ContextBudgetService.plan(emptyList(), "Plan the current task.", listOf(planner.definition), settings)
        assertEquals(listOf(planner.definition), admitted.tools)
        assertEquals(3072, admitted.toolResultBytes)
        assertTrue(budget.admitPlannedOutputAllowance(admitted.toolResultBytes))
        assertEquals(2, budget.usedCalls())
        assertEquals(2, budget.remainingCalls())
        assertTrue(budget.canExecute())

        val result = bound.execute("plan", arguments)
        assertFalse(result.isError)
        assertTrue((result.content as ToolResultContent.Json).value.toString().contains("Plan the research steps for this task"))
        assertEquals(3072, result.toolResultBudgetLimitBytes)
        assertTrue(requireNotNull(result.toolResultBudgetUsedBytes) in 1 until 3072)
        assertEquals(3, result.toolCallBudgetUsed)
        assertEquals(1, budget.remainingCalls())
    }

    @Test fun repeatedPlanningCannotRefillTheAdmittedAllowanceOrRenewSpentCalls() = runBlocking {
        val budget = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 2, maxToolOutputBytes = 0), initialCalls = 1)
        assertTrue(budget.admitPlannedOutputAllowance(48))
        assertFalse(budget.admitPlannedOutputAllowance(4096))
        val result = budget.bind(textTool("x".repeat(200))).execute("first", arguments)
        assertFalse(result.isError)
        assertEquals(48, result.toolResultBudgetLimitBytes)
        assertEquals(48, result.toolResultBudgetUsedBytes)
        assertTrue((result.content as ToolResultContent.Text).text.toByteArray().size <= 48)
        assertTrue(result.outputBudgetExhausted)
        assertEquals(2, budget.usedCalls())
        assertEquals(0, budget.remainingOutputBytes())
        assertFalse(budget.admitPlannedOutputAllowance(4096))
        assertEquals(0, budget.remainingOutputBytes())
        assertFalse(budget.canExecute())

        val exhaustedCalls = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 2, maxToolOutputBytes = 0), initialCalls = 2)
        assertTrue(exhaustedCalls.admitPlannedOutputAllowance(48))
        assertEquals(2, exhaustedCalls.usedCalls())
        assertEquals(0, exhaustedCalls.remainingCalls())
        assertFalse(exhaustedCalls.canExecute())
    }

    @Test fun existingAllowancesAndSpecializedBudgetsCannotBeExpanded() = runBlocking {
        val funded = ToolExecutionBudget(AgentRunLimits(maxToolOutputBytes = 48))
        assertFalse(funded.admitPlannedOutputAllowance(4096))
        assertEquals(48, funded.remainingOutputBytes())
        funded.bind(textTool("x".repeat(200))).execute("consume", arguments)
        assertFalse(funded.admitPlannedOutputAllowance(4096))
        assertEquals(0, funded.remainingOutputBytes())
        for (limits in listOf(AgentRunLimits(maxToolOutputBytes = 0, githubTask = true), AgentRunLimits(maxToolOutputBytes = 0, researchAcquisition = true))) {
            assertFalse(ToolExecutionBudget(limits).admitPlannedOutputAllowance(4096))
        }
        val unadmitted = ToolExecutionBudget(AgentRunLimits(maxToolOutputBytes = 0))
        assertFalse(unadmitted.admitPlannedOutputAllowance(0))
        assertFalse(unadmitted.admitPlannedOutputAllowance(-1))
        assertTrue(unadmitted.admitPlannedOutputAllowance(48))
    }

    @Test fun preparationAttemptClosesAdmissionBeforePhysicalDispatch() = runBlocking {
        var dispatched = false
        val budget = ToolExecutionBudget(AgentRunLimits(maxToolOutputBytes = 0))
        val bound = budget.bind(textTool("unused") { dispatched = true }) as PreparableAgentTool
        val prepared = bound.prepareExecution("blocked", arguments)
        assertFalse(dispatched)
        assertEquals(0, budget.usedCalls())
        assertFalse(budget.admitPlannedOutputAllowance(4096))
        val result = prepared()
        assertTrue(result.isError)
        assertTrue(result.outputBudgetExhausted)
        assertFalse(dispatched)
        assertEquals(0, result.toolResultBudgetLimitBytes)
    }

    @Test fun admissionAndFirstAttemptHaveOneConsistentWinner() = runBlocking {
        repeat(32) { index ->
            val budget = ToolExecutionBudget(AgentRunLimits(maxToolOutputBytes = 0))
            val executions = AtomicInteger()
            val bound = budget.bind(textTool("observed") { executions.incrementAndGet() })
            val start = CompletableDeferred<Unit>()
            val admission = async(Dispatchers.Default) {
                start.await()
                budget.admitPlannedOutputAllowance(128)
            }
            val attempt = async(Dispatchers.Default) {
                start.await()
                bound.execute("race-$index", arguments)
            }
            start.complete(Unit)
            val admitted = admission.await()
            val result = attempt.await()
            assertEquals(admitted, !result.isError)
            assertEquals(if (admitted) 1 else 0, executions.get())
            assertEquals(if (admitted) 128 else 0, result.toolResultBudgetLimitBytes)
            assertFalse(budget.admitPlannedOutputAllowance(4096))
        }
    }

    @Test fun replanningCannotClearAnAuthorizationFailure() = runBlocking {
        var authorized = false
        var checks = 0
        var executions = 0
        val budget = ToolExecutionBudget(AgentRunLimits(maxToolOutputBytes = 0))
        assertTrue(budget.admitPlannedOutputAllowance(4096))
        val bound = budget.bind(textTool("private") { executions++ }, authorize = { _, _ ->
            checks++
            authorized
        })
        val denied = bound.execute("denied", arguments)
        assertTrue(denied.isError)
        assertEquals("AUTH_REQUIRED", denied.errorCode)
        assertFalse(budget.admitPlannedOutputAllowance(8192))
        authorized = true
        val retry = bound.execute("retry", arguments)
        assertTrue(retry.isError)
        assertEquals("AUTH_REQUIRED", retry.errorCode)
        assertEquals(1, checks)
        assertEquals(0, executions)
        assertEquals(0, budget.usedCalls())
        assertEquals(4096, retry.toolResultBudgetLimitBytes)
    }

    private fun textTool(text: String, dispatched: () -> Unit = {}): AgentTool = object : AgentTool {
        override val definition = AgentToolDefinition("local_assist_plan", "Plan the task", JsonObject(emptyMap()))
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
            dispatched()
            return AgentToolResult(callId, ToolResultContent.Text(text), false)
        }
    }
}
