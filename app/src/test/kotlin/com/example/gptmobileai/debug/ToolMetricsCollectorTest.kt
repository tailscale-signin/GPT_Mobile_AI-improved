package com.example.gptmobileai.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolMetricsCollectorTest {
    @Test
    fun `aggregates repeated calls instead of overwriting totals`() {
        val collector = ToolMetricsCollector(TelemetryCollector())
        collector.onToolExecuted("search", 10, 100, true)
        collector.onToolExecuted("search", 20, 300, false, "timeout")
        val live = collector.liveToolMetrics.value.getValue("search")
        assertEquals(2, live.totalCalls)
        assertEquals(1, live.successfulCalls)
        assertEquals(1, live.failedCalls)
        assertEquals(30, live.totalTokensUsed)
        assertEquals(200.0, live.avgExecutionTimeMs, 0.0)
        assertEquals(50.0, live.successRate, 0.0)
        val summary = collector.getToolSummary().getValue("search")
        assertEquals(100.0, summary.minExecutionTimeMs, 0.0)
        assertEquals(300.0, summary.maxExecutionTimeMs, 0.0)
        assertEquals(mapOf("timeout" to 1), summary.errorTypes)
    }
}
