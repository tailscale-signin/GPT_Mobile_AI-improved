package com.example.gptmobileai.data.model

data class NetworkDiagnostics(
    val endpoint: String,
    val statusCode: Int,
    val latencyMs: Double,
    val retryCount: Int,
    val errorType: String?,
    val circuitBreakerState: CircuitBreakerState,
    val timestamp: Long = System.currentTimeMillis()
)

enum class CircuitBreakerState { CLOSED, OPEN, HALF_OPEN }
