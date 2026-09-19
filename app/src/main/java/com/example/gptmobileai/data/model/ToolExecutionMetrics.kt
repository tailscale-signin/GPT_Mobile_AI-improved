package com.example.gptmobileai.data.model

data class ToolExecutionMetrics(
    val toolId: String,
    val toolName: String,
    val serverName: String,
    val totalCalls: Int,
    val successfulCalls: Int,
    val failedCalls: Int,
    val totalTokensUsed: Int,
    val avgExecutionTimeMs: Double,
    val minExecutionTimeMs: Double,
    val maxExecutionTimeMs: Double,
    val p95ExecutionTimeMs: Double,
    val errorTypes: Map<String, Int>,
    val timestamp: Long = System.currentTimeMillis()
)

data class ToolCallEvent(
    val toolId: String,
    val tokensUsed: Int,
    val executionTimeMs: Long,
    val success: Boolean,
    val errorType: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
