package dev.chungjungsoo.gptmobile.data.accounting

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRecoveryUsageTest {
    private fun details(body: String = """{"attempts":1,"output_allowance":1536,"attempt_usage":[{"prompt_tokens":17237,"completion_tokens":1536,"total_tokens":18773}]}"""): JsonObject = buildJsonObject {
        put("failed_stage", "provider_generation")
        put("gateway_recovery", JsonObject(Json.parseToJsonElement(body).jsonObject + mapOf("usage_origin" to JsonPrimitive("PROVIDER_REPORTED"), "usage_estimated" to JsonPrimitive(false))))
    }

    @Test fun laterLocalPreflightCannotOverwriteKnownFailedResponseUsage() {
        val dispatch = RequestDispatchContext(emptySet(), "run", "review")
        dispatch.rejectedBeforeInference(dev.chungjungsoo.gptmobile.data.context.InferencePreflightReason.REASONING_CAPABILITY_UNAVAILABLE)
        val round = InvocationRoundAccounting(ModelInvocation("review", "run", "turn", "LLAMA", "selected", "github-review", 16894, 1536), null, dispatch, null, mockk(relaxed = true), true, MutableStateFlow(emptyMap()), null, "turn")
        val failure = ProviderEvent.Failed("Repair output exhausted", errorCode = "COMPLETION_REPAIR_BUDGET_EXHAUSTED", invocationId = "review", failureDetails = details())
        val retained = round.preservePreflightFailure(failure) as ProviderEvent.Failed
        assertEquals(failure.errorCode, retained.errorCode)
        assertNull(retained.failureDetails?.get("dispatch_state"))
        round.observe(retained)
        assertEquals(17237, round.retainedInput())
        assertEquals(1536, round.retainedOutput())
    }

    @Test fun failedReviewConsumptionIsReconciledOnceIntoInvocationAndKnownRunSubtotal() = runTest {
        val dao = mockk<InvocationDao>(relaxed = true)
        var saved: ModelInvocation? = null
        coEvery { dao.save(any()) } answers { saved = firstArg() }
        val round = InvocationRoundAccounting(ModelInvocation("review", "run", "turn", "LLAMA", "selected", "github-review", 16894, 1536), null, RequestDispatchContext(emptySet(), "run", "review"), null, dao, true, MutableStateFlow(emptyMap()), null, "turn")
        round.observe(ProviderEvent.Usage(inputTokens = 17000, outputTokens = 500, phase = dev.chungjungsoo.gptmobile.data.agent.ProviderUsagePhase.INTERIM, invocationId = "review"))
        val failure = ProviderEvent.Failed("Repair output exhausted", errorCode = "COMPLETION_REPAIR_BUDGET_EXHAUSTED", invocationId = "review", failureDetails = details())
        repeat(2) { round.observe(failure) }
        assertEquals(17237, round.retainedInput())
        assertEquals(1536, round.retainedOutput())
        assertFalse(round.estimatedUsage())
        assertEquals(18773, round.recoveredUsageEvent()?.totalTokens)
        round.finish()
        coVerify(exactly = 1) { dao.save(any()) }
        val measured = requireNotNull(saved)
        assertEquals("FAILED", measured.status)
        assertEquals("gateway_reported", measured.inputTokenSource)
        assertEquals(17237L, measured.observedInputTokens)
        val evidence = Json.parseToJsonElement(requireNotNull(measured.usageEvidenceJson)).jsonObject
        assertEquals(JsonPrimitive(true), evidence["final_usage_complete"])
        assertEquals(JsonPrimitive(1536), evidence["known_recovery_output_tokens"])
        val previous = measured.copy(id = "earlier", inputTokens = 96122, outputTokens = 3857, observedInputTokens = 96122, observedOutputTokens = 3857, usageEvidenceJson = null)
        val unknown = measured.copy(id = "preflight-unknown", inputTokenSource = "estimated", outputTokenSource = "estimated", observedInputTokens = null, observedOutputTokens = null, usageEvidenceJson = null)
        val run = RunTokenUsage.from(listOf(previous, measured, measured, unknown))
        assertEquals(113359, run.inputTokens)
        assertEquals(5393, run.outputTokens)
        assertEquals(118752, run.measuredSubtotalTokens)
        assertEquals(1, run.unmeasuredInvocations)
        assertNull(run.totalTokens)
    }

    @Test fun knownCompletedAttemptsRemainPartialWhenAnotherAttemptHasNoUsage() = runTest {
        val dao = mockk<InvocationDao>(relaxed = true)
        var saved: ModelInvocation? = null
        coEvery { dao.save(any()) } answers { saved = firstArg() }
        val round = InvocationRoundAccounting(ModelInvocation("review", "run", "turn", "LLAMA", "selected", "github-review", 900, 500), null, RequestDispatchContext(emptySet()), null, dao, true, MutableStateFlow(emptyMap()), null, "turn")
        round.observe(ProviderEvent.Usage(inputTokens = 12, outputTokens = 30, invocationId = "review"))
        round.observe(ProviderEvent.Failed("Backend repair failed", invocationId = "review", failureDetails = details("""{"completed_attempts":1,"failed_attempt":2,"failed_attempt_usage_available":false,"attempt_usage":[{"prompt_tokens":12,"completion_tokens":30,"total_tokens":42}]}""")))
        assertTrue(round.estimatedUsage())
        assertEquals("gateway_reported_partial", round.inputSource)
        assertEquals(12, round.input)
        round.finish()
        val run = RunTokenUsage.from(listOf(requireNotNull(saved)))
        assertEquals(12, run.inputTokens)
        assertEquals(30, run.outputTokens)
        assertEquals(42, run.measuredSubtotalTokens)
        assertEquals(1, run.unmeasuredInvocations)
        assertNull(run.totalTokens)
    }

    @Test fun estimatesInvalidCountersAndForeignInvocationsCannotBecomeMeasuredUsage() {
        assertNull(GatewayRecoveryUsage.from(details("""{"attempts":1,"attempt_usage":[{"prompt_tokens":-1,"completion_tokens":"20"}]}""")))
        assertNull(GatewayRecoveryUsage.from(details("""{"attempts":1,"attempt_usage":[{"prompt_tokens":12,"completion_tokens":30,"total_tokens":99}]}""")))
        assertNull(GatewayRecoveryUsage.from(buildJsonObject { put("gateway_recovery", JsonObject(details().getValue("gateway_recovery").jsonObject + ("usage_estimated" to JsonPrimitive(true)))) }))
        val round = InvocationRoundAccounting(ModelInvocation("primary", "run", "turn", "LLAMA", "selected", "primary", 100, 500), null, RequestDispatchContext(emptySet()), null, mockk(relaxed = true), false, MutableStateFlow(emptyMap()), null, "turn")
        round.observe(ProviderEvent.Failed("Foreign reviewer", invocationId = "review", failureDetails = details()))
        assertNull(round.input)
        assertNull(round.output)
        assertNull(round.recoveredUsageEvent())
    }

    @Test fun physicalRequestSnapshotsReplaceDuplicatesAndSumIndependentAttempts() {
        val usage = requireNotNull(GatewayRecoveryUsage.from(details()))
        val attempts = linkedMapOf("physical-1" to usage)
        attempts["physical-1"] = usage
        assertEquals(18773, GatewayRecoveryUsage.combine(attempts, true)?.event("invocation")?.totalTokens)
        attempts["physical-2"] = usage
        assertEquals(37546, GatewayRecoveryUsage.combine(attempts, true)?.event("invocation")?.totalTokens)
        assertFalse(requireNotNull(GatewayRecoveryUsage.combine(attempts, false)).inputComplete)
    }
}
