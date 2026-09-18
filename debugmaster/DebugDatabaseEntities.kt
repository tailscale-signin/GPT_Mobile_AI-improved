package com.gptmobileai.debug

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AETHERION Debug Mode - Database Entities
 * 
 * Core data models for real-time telemetry, hardware diagnostics, and token metrics.
 * Designed for <1ms sampling rates with 95%+ device coverage.
 * 
 * @author AETHERION Debug Team
 * @version 1.0.0
 */

/**
 * Telemetry Event Entity
 * 
 * Captures real-time application events with configurable sampling rates.
 * Supports 100ms to 10s sampling intervals for performance optimization.
 */
@Entity(
    tableName = "telemetry_events",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["deviceId"]),
        Index(value = ["sessionId"])
    ]
)
data class TelemetryEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val timestamp: Long,
    
    val eventType: String,
    val eventName: String,
    val eventCategory: String,
    
    val payload: String,
    
    val samplingRateMs: Int,
    
    val deviceId: String,
    val sessionId: String,
    
    val version: Int = 1
)

/**
 * Hardware Diagnostics Entity
 * 
 * Comprehensive device health monitoring including SoC, RAM, thermal state,
 * NPU readiness, battery, network, and performance metrics.
 */
@Entity(
    tableName = "hardware_diagnostics",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["npuReady"]),
        Index(value = ["temperatureCelsius"])
    ]
)
data class HardwareDiagnostics(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val timestamp: Long,
    
    val socId: String,
    val socName: String,
    val socVersion: String,
    
    val ramTotalMb: Int,
    val ramUsedMb: Int,
    val ramFreeMb: Int,
    
    val temperatureCelsius: Float,
    val thermalState: String,
    val thermalThrottling: Boolean,
    
    val npuReady: Boolean,
    val npuVersion: String,
    val npuLoad: Float,
    
    val batteryLevelPercent: Int,
    val batteryTemperatureCelsius: Float,
    val charging: Boolean,
    
    val networkType: String,
    val networkSpeedMbps: Float,
    val networkLatencyMs: Float,
    
    val cpuLoadPercent: Float,
    val gpuLoadPercent: Float,
    val npuLoadPercent: Float,
    
    val uptimeSeconds: Long,
    val bootTime: Long,
    
    val version: Int = 1
)

/**
 * Token Metrics Entity
 * 
 * Per-token timing visibility for model performance tracking.
 * Enables real-time latency analysis and TTFB measurement.
 */
@Entity(
    tableName = "token_metrics",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["sessionId"]),
        Index(value = ["provider"]),
        Index(value = ["latencyMs"])
    ]
)
data class TokenMetrics(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val timestamp: Long,
    
    val sessionId: String,
    val provider: String,
    val model: String,
    
    val tokenIndex: Int,
    val tokenType: String,
    val tokenCount: Int,
    
    val durationMs: Float,
    val latencyMs: Float,
    val ttfbMs: Float,
    
    val tokensPerSecond: Float,
    val totalTokens: Int,
    
    val error: Boolean,
    val errorMessage: String,
    
    val samplingRateMs: Int,
    
    val version: Int = 1
)