package com.example.gptmobileai.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TokenMetricsCollector(
    private val eventBus: TelemetryCollector,
    private val config: AetherionMaxConfig = AetherionMaxConfig
) {
    private var startTime: Long = 0
    private var lastTimestamp: Long = 0
    private var tokenCount: Int = 0
    private var itlAccumulator: Double = 0.0
    private var ttftMs: Double? = null
    private var currentModelId: String = "unknown"
    private var currentProvider: String = "unknown"

    private val _liveMetrics = MutableStateFlow(
        LiveTokenMetrics(
            totalTokens = 0,
            avgITLms = 0.0,
            throughputTps = 0.0,
            ttftMs = null,
            modelId = currentModelId,
            provider = currentProvider
        )
    )
    val liveMetrics: StateFlow<LiveTokenMetrics> = _liveMetrics.asStateFlow()

    data class LiveTokenMetrics(
        val totalTokens: Int,
        val avgITLms: Double,
        val throughputTps: Double,
        val ttftMs: Double?,
        val modelId: String,
        val provider: String
    )

    fun onTurnStarted(modelId: String, provider: String) {
        currentModelId = modelId
        currentProvider = provider
        startTime = System.currentTimeMillis()
        lastTimestamp = startTime
        tokenCount = 0
        itlAccumulator = 0.0
        ttftMs = null
    }

    fun onTokenGenerated(tokenIndex: Int, timestamp: Long) {
        if (tokenCount == 0) {
            ttftMs = (System.currentTimeMillis() - startTime).toDouble()
        } else {
            val itl = (timestamp - lastTimestamp).toDouble()
            itlAccumulator += itl
            eventBus.emit(
                TelemetryEvent.TokenGenerated(
                    tokenIndex = tokenIndex,
                    itlMs = itl,
                    modelId = currentModelId,
                    provider = currentProvider
                )
            )
        }
        lastTimestamp = timestamp
        tokenCount++

        val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
        val throughputTps = if (elapsedSec > 0) {
            tokenCount.toDouble() / elapsedSec
        } else {
            0.0
        }

        _liveMetrics.value = LiveTokenMetrics(
            totalTokens = tokenCount,
            avgITLms = if (tokenCount > 0) itlAccumulator / tokenCount else 0.0,
            throughputTps = throughputTps,
            ttftMs = ttftMs,
            modelId = currentModelId,
            provider = currentProvider
        )
    }

    fun onTurnCompleted(totalTokens: Int, totalLatencyMs: Long) {
        val avgITLms = if (tokenCount > 0) itlAccumulator / tokenCount else 0.0
        eventBus.emit(
            TelemetryEvent.TokenGenerated(
                tokenIndex = totalTokens,
                itlMs = avgITLms,
                modelId = currentModelId,
                provider = currentProvider
            )
        )
    }
}
