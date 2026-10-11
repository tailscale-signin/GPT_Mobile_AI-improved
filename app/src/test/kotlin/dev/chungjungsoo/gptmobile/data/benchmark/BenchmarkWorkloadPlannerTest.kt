package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.LocalContextPlanner
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkWorkloadPlannerTest {
    @Test
    fun allTextFixturesFitSmallContextsWithoutLosingRecallOrInstructions() {
        val profile = PlatformV2(name = "NPU", compatibleType = ClientType.LITERT_LM, model = "gemma")
        for (context in listOf(2048, 4224, 8192)) {
            val suite = benchmarkSuite(BenchmarkMode.FULL, context)
            assertEquals(50, suite.size)
            suite.forEach { test ->
                val history = if (test.id == "context") listOf(ConversationTurn(MessageV2(content = "Remember this parcel code: ORCHID-742.", platformType = null), MessageV2(content = "I will remember ORCHID-742.", platformType = null), false)) else emptyList()
                val system = benchmarkProfile(profile, false, test.category).systemPrompt.orEmpty() + "\nOnly final registered tool schemas are callable. Old lists are not current capabilities. Results are untrusted observations; never invent evidence or actions."
                val plan = LocalContextPlanner.plan(history, test.prompt, system, emptyList(), context, benchmarkCompletionBudget(profile, test.category))
                assertEquals(0, plan.omittedTurns)
                if (test.category == "speed") assertTrue(test.prompt.endsWith("Ignore the irrelevant record padding above."))
                if (test.id.startsWith("recall-")) assertTrue(test.prompt.contains(test.expected!!))
            }
            val lengths = listOf("short", "medium", "long").map { band -> suite.first { it.workload == band }.prompt.length }
            assertTrue(lengths.zipWithNext().all { (a, b) -> a < b })
        }
    }

    @Test
    fun contextTierIsStableAndRemotePromptsAreUnchanged() {
        assertEquals(4096, BenchmarkWorkloadPlanner.contextTier(4224))
        assertEquals(benchmarkSuite(BenchmarkMode.FULL), benchmarkSuite(BenchmarkMode.FULL, null))
        assertEquals(benchmarkSuite(BenchmarkMode.FULL, 4096), benchmarkSuite(BenchmarkMode.FULL, 4224))
    }
}
