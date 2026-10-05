package dev.chungjungsoo.gptmobile.data.accounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTokenComparisonTest {
    private fun request(id: String, model: String = "Qwen-Q6", kind: String = "PRIMARY", provider: String = "local") =
        ModelInvocation(id, "run", "turn", provider, model, kind, 100, 25, false, startedAt = 1L, profileUid = id)

    @Test
    fun `exact model combines roles profiles providers and retries without double counting snapshots`() {
        val first = request("one")
        val rows = compareModelTokens(listOf(first, request("two", kind = "DELEGATE"), first, request("three", kind = "REVIEWER", provider = "other")))
        assertEquals(1, rows.size)
        assertEquals(3, rows.single().requests)
        assertEquals(300L, rows.single().inputTokens)
        assertEquals(75L, rows.single().outputTokens)
        assertEquals(3, rows.single().roles.size)
        assertEquals(2, rows.single().providers.size)
    }

    @Test
    fun `versions quantizations and unknown profiles remain separate`() {
        assertEquals(4, compareModelTokens(listOf(request("a"), request("b", "Qwen-Q8"), request("c", ""), request("d", ""))).size)
    }

    @Test
    fun `large totals use long and mark any estimated input`() {
        val rows = compareModelTokens(listOf(request("a").copy(inputTokens = Int.MAX_VALUE), request("b").copy(inputTokens = Int.MAX_VALUE, estimated = true)))
        assertEquals(Int.MAX_VALUE.toLong() * 2, rows.single().inputTokens)
        assertTrue(rows.single().estimated)
        assertTrue(compareModelTokens(emptyList()).isEmpty())
    }
}
