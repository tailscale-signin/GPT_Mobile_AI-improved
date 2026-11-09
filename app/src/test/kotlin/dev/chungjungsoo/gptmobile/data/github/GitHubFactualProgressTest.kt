package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.dto.ApiState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubFactualProgressTest {
    @Test fun defaultProgressUsesReceiptsWithoutAModelRequestReasoningBudgetOrWorkDelay() = runTest {
        var clock = 0L
        val summaries = mutableListOf<ApiState.ActivitySummary>()
        val narrator = GitHubProgressNarrator(
            snapshot = { GitHubProgressSnapshot(1, "Inspect CI", 10, 0, "Checking saved results", listOf("Workflow results are retained."), "Evaluate remaining requirements") },
            openSelected = { _, _ -> error("Factual progress cannot consume model or reasoning tokens") },
            validate = {},
            admit = { _, _ -> error("No model budget is needed") },
            publish = { summaries += it },
            tokenBudget = 0,
            now = { clock }
        )
        clock = 15_000
        narrator.beforeRound(Long.MAX_VALUE)
        assertEquals(1, summaries.size)
        assertFalse(summaries.single().modelAuthored)
        assertTrue(summaries.single().text.contains("Saved operation results: 10"))
        assertTrue(summaries.single().text.contains("Evaluate remaining requirements"))
        clock += 15_000
        narrator.beforeRound()
        assertEquals(1, summaries.size)
    }
}
