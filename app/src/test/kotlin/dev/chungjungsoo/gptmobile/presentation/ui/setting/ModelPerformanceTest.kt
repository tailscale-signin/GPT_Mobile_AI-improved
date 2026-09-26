package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelPerformanceTest {
    @Test fun `latency percentiles and throughput exclude interrupted and estimated requests appropriately`() {
        fun invocation(id: String, duration: Long, tokens: Int, estimated: Boolean = false, status: String = "COMPLETED") =
            ModelInvocation(id, "run", "turn", "provider", "model", "primary", 20, tokens, estimated, status, durationMs = duration, firstTokenMs = duration / 10)
        val rows = listOf(invocation("1", 1000, 100), invocation("2", 3000, 300), invocation("3", 2000, 200, true), invocation("4", 9000, 50, status = "STOPPED"), invocation("5", 0, 0, status = "RUNNING"))
        val stats = modelPerformance(rows).single()
        assertEquals(4, stats.requests)
        assertEquals(3, stats.completed)
        assertEquals(2000L, stats.medianLatencyMs)
        assertEquals(3000L, stats.p95LatencyMs)
        assertEquals(200L, stats.medianFirstTokenMs)
        assertEquals(650L, stats.outputTokens)
        assertEquals(100.0, stats.outputTokensPerSecond!!, .0001)
        assertEquals(1, stats.estimatedRequests)
        assertNull(modelPerformance(listOf(invocation("estimate", 1000, 12, true))).single().outputTokensPerSecond)
    }
}
