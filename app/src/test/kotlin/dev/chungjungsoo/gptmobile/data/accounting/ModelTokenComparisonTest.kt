package dev.chungjungsoo.gptmobile.data.accounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTokenComparisonTest {
    private fun request(id: String, kind: String = "primary", model: String = "Qwen-Q6", provider: String = "GATEWAY") = ModelInvocation(
        id = id,
        parentRunId = "run",
        turnKey = "turn",
        provider = provider,
        model = model,
        kind = kind,
        inputTokens = 100,
        outputTokens = 25,
        estimated = false,
        startedAt = 1
    )

    @Test
    fun combinesPrimaryDelegateReviewerAndRetryAcrossProfiles() {
        val rows = compareModelTokens(
            listOf(
                request("a"),
                request("b", "delegate").copy(profileUid = "another-profile"),
                request("c", "reviewer"),
                request("d", "delegate").copy(estimated = true)
            )
        )
        assertEquals(1, rows.size)
        assertEquals(4, rows.single().requestCount)
        assertEquals(400L, rows.single().inputTokens)
        assertEquals(100L, rows.single().outputTokens)
        assertEquals(500L, rows.single().totalTokens)
        assertEquals(listOf("delegate", "primary", "reviewer"), rows.single().kinds)
        assertTrue(rows.single().estimated)
    }

    @Test
    fun duplicateRequestCountsCurrentObservationOnce() {
        val live = request("a").copy(outputTokens = 80)
        val rows = compareModelTokens(listOf(live, request("a")))
        assertEquals(1, rows.single().requestCount)
        assertEquals(80L, rows.single().outputTokens)
        assertFalse(rows.single().estimated)
    }

    @Test
    fun doesNotMergeDifferentProvidersVersionsOrCase() {
        val rows = compareModelTokens(
            listOf(
                request("a"),
                request("b", model = "Qwen-Q8"),
                request("c", model = "qwen-q6"),
                request("d", provider = "OPENROUTER")
            )
        )
        assertEquals(4, rows.size)
    }

    @Test
    fun blankIdentityDoesNotCollapseUnrelatedRequests() {
        assertEquals(2, compareModelTokens(listOf(request("a", model = ""), request("b", model = ""))).size)
    }

    @Test
    fun usesLongTotalsAndRecomputesLiveValues() {
        val first = request("a").copy(inputTokens = Int.MAX_VALUE, outputTokens = Int.MAX_VALUE)
        val second = request("b").copy(inputTokens = Int.MAX_VALUE, outputTokens = Int.MAX_VALUE)
        assertEquals(8589934588L, compareModelTokens(listOf(first, second)).single().totalTokens)
        assertEquals(26L, compareModelTokens(listOf(request("a").copy(outputTokens = 26))).single().outputTokens)
    }
}
