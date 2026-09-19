package com.example.gptmobileai.data.model

import java.util.UUID

data class TokenMetrics(
    val sessionId: String = UUID.randomUUID().toString(),
    val turnId: Int,
    val tokenIndex: Int,
    val ttftMs: Double?,
    val itlMs: Double,
    val throughputTps: Double,
    val modelId: String,
    val provider: String,
    val latencyP95Ms: Double,
    val latencyP99Ms: Double,
    val timestamp: Long = System.currentTimeMillis()
)

data class SessionTokenMetrics(
    val sessionId: String,
    val totalTokens: Int,
    val totalPrefillTokens: Int,
    val totalDecodeTokens: Int,
    val avgTTFTMs: Double,
    val avgITLms: Double,
    val minITLms: Double,
    val maxITLMs: Double,
    val p95ITLms: Double,
    val p99ITLms: Double,
    val avgThroughputTps: Double,
    val peakThroughputTps: Double,
    val modelSwitches: Int,
    val providerDistribution: Map<String, Int>,
    val totalLatencyMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)
