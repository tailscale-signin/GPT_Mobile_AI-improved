package com.example.gptmobileai.debug

import com.example.gptmobileai.data.model.HardwareDiagnostics
import com.example.gptmobileai.data.model.SessionTokenMetrics
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

sealed class TelemetryEvent {
    data class TokenGenerated(
        val tokenIndex: Int,
        val itlMs: Double,
        val modelId: String,
        val provider: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : TelemetryEvent()

    data class ToolExecuted(
        val toolId: String,
        val tokensUsed: Int,
        val executionTimeMs: Long,
        val success: Boolean,
        val errorType: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) : TelemetryEvent()

    data class HardwareSnapshot(
        val hardwareDiagnostics: HardwareDiagnostics,
        val timestamp: Long = System.currentTimeMillis()
    ) : TelemetryEvent()

    data class NetworkHealth(
        val endpoint: String,
        val statusCode: Int,
        val latencyMs: Double,
        val errorType: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) : TelemetryEvent()

    data class SessionStarted(
        val sessionId: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : TelemetryEvent()

    data class SessionEnded(
        val sessionId: String,
        val summary: SessionTokenMetrics,
        val timestamp: Long = System.currentTimeMillis()
    ) : TelemetryEvent()
}

class TelemetryCollector {
    private val _eventFlow = MutableSharedFlow<TelemetryEvent>(replay = 100, extraBufferCapacity = 1000)
    val eventFlow: SharedFlow<TelemetryEvent> = _eventFlow.asSharedFlow()
    private val eventsList = mutableListOf<TelemetryEvent>()

    private var currentSessionId: String? = null

    fun startSession(): String {
        val sessionId = UUID.randomUUID().toString()
        currentSessionId = sessionId
        emit(TelemetryEvent.SessionStarted(sessionId))
        return sessionId
    }

    fun endSession(summary: SessionTokenMetrics) {
        val sessionId = currentSessionId
        if (sessionId != null) {
            emit(TelemetryEvent.SessionEnded(sessionId, summary))
            currentSessionId = null
        }
    }

    fun emit(event: TelemetryEvent) {
        synchronized(eventsList) {
            if (eventsList.size >= AetherionMaxConfig.MAX_TELEMETRY_EVENTS) {
                eventsList.removeAt(0)
            }
            eventsList.add(event)
        }
        _eventFlow.tryEmit(event)
    }

    fun getEvents(): List<TelemetryEvent> {
        return synchronized(eventsList) { eventsList.toList() }
    }
}
