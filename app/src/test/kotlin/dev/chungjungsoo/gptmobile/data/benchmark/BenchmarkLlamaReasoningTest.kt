package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkLlamaReasoningTest {
    @Test fun deterministicQuickDoesNotRequireTheUnusedSavedOnMode() {
        val plan = BenchmarkSuiteRegistry.cases(BenchmarkMode.QUICK, 0)
        val modes = BenchmarkLlamaReasoning.forPlan(BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1, ReasoningMode.ON, plan.map { it.requestCategory })
        assertEquals(listOf(ReasoningMode.OFF), modes)
        assertTrue(plan.all { BenchmarkLlamaReasoning.forRequest(BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1, ReasoningMode.ON, it.requestCategory) in modes })
    }

    @Test fun deterministicFullChecksTheSavedModeUsedByIntelligenceFixturesToo() {
        val plan = BenchmarkSuiteRegistry.cases(BenchmarkMode.FULL, 0)
        val modes = BenchmarkLlamaReasoning.forPlan(BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1, ReasoningMode.ON, plan.map { it.requestCategory })
        assertEquals(setOf(ReasoningMode.OFF, ReasoningMode.ON), modes.toSet())
        assertEquals(ReasoningMode.ON, BenchmarkLlamaReasoning.forRequest(BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1, ReasoningMode.ON, "intelligence_individual"))
    }

    @Test fun savedProfilePolicyUsesExactlyTheSavedModeForEveryFixture() {
        for (saved in ReasoningMode.entries) {
            val categories = BenchmarkSuiteRegistry.cases(BenchmarkMode.FULL, 0).map { it.requestCategory }
            assertEquals(listOf(saved), BenchmarkLlamaReasoning.forPlan(BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1, saved, categories))
        }
    }

    @Test fun readinessWithoutAManifestStillRequiresDeterministicOff() {
        assertEquals(listOf(ReasoningMode.OFF), BenchmarkLlamaReasoning.forPlan(BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1, ReasoningMode.AUTO, emptyList()))
        assertEquals(listOf(ReasoningMode.AUTO), BenchmarkLlamaReasoning.forPlan(BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1, ReasoningMode.AUTO, emptyList()))
    }
}
