package com.example.gptmobileai.debug

import com.example.gptmobileai.data.model.ToolCallEvent
import com.example.gptmobileai.data.model.ToolExecutionMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class ToolMetricsCollector(
    private val eventBus: TelemetryCollector,
    private val config: AetherionMaxConfig = AetherionMaxConfig
) {
    private val toolMetricsMap = ConcurrentHashMap<String, ToolCallEvent>()

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

    fun onToolExecuted(
        toolId: String,
        tokensUsed: Int,
        executionTimeMs: Long,
        success: Boolean,
        errorType: String? = null
    ) {
        val event = ToolCallEvent(
            toolId = toolId,
            tokensUsed = tokensUsed,
            executionTimeMs = executionTimeMs,
            success = success,
            errorType = errorType
        )

        toolMetricsMap[toolId] = event

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

        toolMetricsMap.forEach { (toolId, event) ->
            val totalCalls = if (event.executionTimeMs >= 0L) 1 else 0
            val successfulCalls = if (event.success) 1 else 0
            val failedCalls = if (event.success) 0 else 1
            val totalTokensUsed = event.tokensUsed
            val avgExecutionTimeMs = event.executionTimeMs.toDouble()

            updated[toolId] = LiveToolMetrics(
                toolId = toolId,
                totalCalls = totalCalls,
                successfulCalls = successfulCalls,
                failedCalls = failedCalls,
                totalTokensUsed = totalTokensUsed,
                avgExecutionTimeMs = avgExecutionTimeMs,
                successRate = if (totalCalls > 0) (successfulCalls * 100.0 / totalCalls) else 0.0
            )
        }

        _liveToolMetrics.value = updated
    }

    fun getToolSummary(): Map<String, ToolExecutionMetrics> {
        return toolMetricsMap.mapValues { (key, event) ->
            ToolExecutionMetrics(
                toolId = key,
                toolName = key,
                serverName = "local",
                totalCalls = 1,
                successfulCalls = if (event.success) 1 else 0,
                failedCalls = if (event.success) 0 else 1,
                totalTokensUsed = event.tokensUsed,
                avgExecutionTimeMs = event.executionTimeMs.toDouble(),
                minExecutionTimeMs = event.executionTimeMs.toDouble(),
                maxExecutionTimeMs = event.executionTimeMs.toDouble(),
                p95ExecutionTimeMs = event.executionTimeMs.toDouble(),
                errorTypes = if (event.errorType != null) mapOf(event.errorType to 1) else emptyMap()
            )
        }
    }
}
