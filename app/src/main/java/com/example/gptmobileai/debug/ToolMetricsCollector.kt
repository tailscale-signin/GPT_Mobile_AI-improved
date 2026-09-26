package com.example.gptmobileai.debug

import com.example.gptmobileai.data.model.ToolExecutionMetrics
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ToolMetricsCollector(
    private val eventBus: TelemetryCollector,
    private val config: AetherionMaxConfig = AetherionMaxConfig
) {
    private data class Totals(
        val calls: Int = 0,
        val successes: Int = 0,
        val tokens: Long = 0,
        val durationMs: Long = 0,
        val minMs: Long = Long.MAX_VALUE,
        val maxMs: Long = 0,
        val recentDurations: List<Long> = emptyList(),
        val errors: Map<String, Int> = emptyMap()
    )

    private val toolMetricsMap = ConcurrentHashMap<String, Totals>()

    private val _liveToolMetrics = MutableStateFlow<Map<String, LiveToolMetrics>>(emptyMap())
    val liveToolMetrics: StateFlow<Map<String, LiveToolMetrics>> = _liveToolMetrics.asStateFlow()

    data class LiveToolMetrics(
        val toolId: String,
        val totalCalls: Int,
        val successfulCalls: Int,
        val failedCalls: Int,
        val totalTokensUsed: Int,
        val avgExecutionTimeMs: Double,
        val successRate: Double
    )

    @Synchronized
    fun onToolExecuted(
        toolId: String,
        tokensUsed: Int,
        executionTimeMs: Long,
        success: Boolean,
        errorType: String? = null
    ) {
        val duration = executionTimeMs.coerceAtLeast(0)
        val previous = toolMetricsMap[toolId] ?: Totals()
        val errors = previous.errors.toMutableMap()
        if (!success) {
            val type = errorType ?: "unknown"
            errors[type] = (errors[type] ?: 0) + 1
        }
        toolMetricsMap[toolId] = Totals(
            calls = previous.calls + 1,
            successes = previous.successes + if (success) 1 else 0,
            tokens = previous.tokens + tokensUsed.coerceAtLeast(0),
            durationMs = previous.durationMs + duration,
            minMs = minOf(previous.minMs, duration),
            maxMs = maxOf(previous.maxMs, duration),
            recentDurations = (previous.recentDurations + duration).takeLast(128),
            errors = errors
        )

        eventBus.emit(
            TelemetryEvent.ToolExecuted(
                toolId = toolId,
                tokensUsed = tokensUsed,
                executionTimeMs = executionTimeMs,
                success = success,
                errorType = errorType
            )
        )

        updateLiveMetrics()
    }

    private fun updateLiveMetrics() {
        val updated = mutableMapOf<String, LiveToolMetrics>()

        toolMetricsMap.forEach { (toolId, totals) ->
            updated[toolId] = LiveToolMetrics(
                toolId = toolId,
                totalCalls = totals.calls,
                successfulCalls = totals.successes,
                failedCalls = totals.calls - totals.successes,
                totalTokensUsed = totals.tokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                avgExecutionTimeMs = totals.durationMs.toDouble() / totals.calls,
                successRate = totals.successes * 100.0 / totals.calls
            )
        }

        _liveToolMetrics.value = updated
    }

    @Synchronized
    fun getToolSummary(): Map<String, ToolExecutionMetrics> = toolMetricsMap.mapValues { (key, totals) ->
        val sorted = totals.recentDurations.sorted()
        ToolExecutionMetrics(
            toolId = key,
            toolName = key,
            serverName = "client",
            totalCalls = totals.calls,
            successfulCalls = totals.successes,
            failedCalls = totals.calls - totals.successes,
            totalTokensUsed = totals.tokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            avgExecutionTimeMs = totals.durationMs.toDouble() / totals.calls,
            minExecutionTimeMs = totals.minMs.toDouble(),
            maxExecutionTimeMs = totals.maxMs.toDouble(),
            p95ExecutionTimeMs = sorted[(ceil(sorted.size * 0.95).toInt() - 1).coerceAtLeast(0)].toDouble(),
            errorTypes = totals.errors
        )
    }
}
