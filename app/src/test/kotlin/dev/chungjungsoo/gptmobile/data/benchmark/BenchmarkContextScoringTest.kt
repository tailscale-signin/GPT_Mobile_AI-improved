package dev.chungjungsoo.gptmobile.data.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkContextScoringTest {
    @Test fun contextArmsCannotChangeExistingModelScoresOrEnrollment() {
        val fixture = BenchmarkEngineeringTestFixtures()
        val ordinary = fixture.runs().first()
        val context = ordinary.copy(
            id = "context-run",
            configKey = benchmarkHash("another context revision"),
            startedAt = ordinary.startedAt + 1,
            engineeringPlan = fixture.plan.copy(contextExperiment = BenchmarkContextExperiment()).frozen(),
            engineeringContextVariant = "TASK"
        )
        val before = DynamicScoreEngine.snapshot(listOf(ordinary), 1, 3000)
        val after = DynamicScoreEngine.snapshot(listOf(ordinary, context), 1, 3000)
        assertEquals(before.rows, after.rows)
        assertEquals(before.enrollment, after.enrollment)
        assertTrue(DynamicScoreEngine.snapshot(listOf(context), 1, 3000).rows.isEmpty())
        val selection = benchmarkRunSelection(listOf(context), after, null).single()
        assertTrue("CONTEXT_EXPERIMENT_PAIRED_EVIDENCE_ONLY" in selection.exclusionReasons)
    }
}
