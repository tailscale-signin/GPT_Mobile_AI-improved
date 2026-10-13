package dev.chungjungsoo.gptmobile.data.orchestration

import dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation
import dev.chungjungsoo.gptmobile.data.assist.AssistPreparation
import dev.chungjungsoo.gptmobile.data.assist.LocalAssistPolicy
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionPolicyTest {
    private fun record(id: String, provider: String = "remote", kind: String = "delegate", output: Int = 100, status: String = "COMPLETED") = ModelInvocation(id, "generation-$id", "root", provider, "model", kind, 200, output, status = status)

    @Test fun finalWritingCapacitySurvivesPreparationAndRetries() {
        val policy = ExecutionPolicy(sharedRunBudget = true, remoteOutputTokens = 600, finalOutputReserve = 400, physicalModelRequests = 3)
        val first = record("one")
        ModelBudgetAdmission.check(emptyList(), first, policy)
        ModelBudgetAdmission.check(listOf(first), record("two"), policy)
        assertThrows(ExecutionAllowanceReached::class.java) { ModelBudgetAdmission.check(listOf(first, record("two")), record("retry"), policy) }
        ModelBudgetAdmission.check(listOf(first, record("two")), record("writer", kind = "primary", output = 400), policy)
    }

    @Test fun unknownDispatchedUsageAndSeparateLocalTokensRemainCharged() {
        val policy = ExecutionPolicy(sharedRunBudget = true, localGeneratedTokens = 150, remoteOutputTokens = 1000, finalOutputReserve = 400)
        val failed = record("failed", "LITERT_LM", "assist", 100, "FAILED").copy(outputTokenSource = "reserved_unknown")
        assertThrows(ExecutionAllowanceReached::class.java) { ModelBudgetAdmission.check(listOf(failed), record("retry", "LITERT_LM"), policy) }
        ModelBudgetAdmission.check(listOf(failed), record("writer", kind = "primary", output = 900), policy)
    }

    @Test fun concurrencyAndUnboundedOutputCannotBypassAdmission() {
        val policy = ExecutionPolicy(sharedRunBudget = true, concurrentModels = 1, remoteOutputTokens = 1000, finalOutputReserve = 400)
        assertThrows(ExecutionAllowanceReached::class.java) { ModelBudgetAdmission.check(listOf(record("running", status = "RUNNING")), record("other"), policy) }
        assertThrows(ExecutionAllowanceReached::class.java) { ModelBudgetAdmission.check(emptyList(), record("unbounded", output = 0), policy) }
    }

    @Test fun disablingOrIncreasingPolicyDoesNotRefundRootBudget() {
        val admitted = ExecutionPolicy(sharedRunBudget = true, remoteInputTokens = 1000, remoteOutputTokens = 5000)
        assertEquals(admitted, admitted.restrictWith(ExecutionPolicy()))
        assertEquals(1000L, admitted.restrictWith(admitted.copy(remoteInputTokens = 2000)).remoteInputTokens)
        assertEquals(500L, admitted.restrictWith(admitted.copy(remoteInputTokens = 500)).remoteInputTokens)
    }

    @Test fun modesNeverAuthorizeColdLoadsOrEnableDisabledAssistImplicitly() {
        val settings = AppFeatureSettings(localAssist = false, localAssistPolicy = LocalAssistPolicy(preparation = AssistPreparation.PREPARE_AUTOMATICALLY, summarizeRetrievedText = true), executionPolicy = ExecutionPolicy(mode = OptimizationMode.BALANCED, coldLoadAssist = true))
        assertFalse(settings.assistEnabled())
        assertFalse(settings.effectiveAssistPolicy().summarizeRetrievedText)
        assertEquals(AssistPreparation.WARM_ONLY, settings.effectiveAssistPolicy().preparation)
        assertEquals(AssistPreparation.PREPARE_AUTOMATICALLY, settings.copy(executionPolicy = settings.executionPolicy.copy(mode = OptimizationMode.CUSTOM)).effectiveAssistPolicy().preparation)
        assertTrue(settings.copy(localAssist = true).assistEnabled())
    }
}
