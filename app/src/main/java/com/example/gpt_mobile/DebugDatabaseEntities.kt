package com.example.gpt_mobile

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/**
 * Telemetry Events Entity
 * Stores raw telemetry data for debugging and analytics
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
    val samplingRateMs: Long,
    val deviceId: String,
    val sessionId: String,
    val version: Int = 1
)

/**
 * Hardware Diagnostics Entity
 * Stores hardware and system diagnostics data
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
    val thermalThrottling: Int,
    val npuReady: Int,
    val npuVersion: String,
    val npuLoad: Float,
    val batteryLevelPercent: Int,
    val batteryTemperatureCelsius: Float,
    val charging: Int,
    val networkType: String,
    val networkSpeedMbps: Float,
    val networkLatencyMs: Float,
    val cpuLoadPercent: Float,
    val gpuLoadPercent: Float,
    val npuLoadPercent: Float,
    val uptimeSeconds: Int,
    val bootTime: Long,
    val version: Int = 1
)

/**
 * Token Metrics Entity
 * Stores per-token timing and performance metrics
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
    val error: Int,
    val errorMessage: String,
    val samplingRateMs: Long,
    val version: Int = 1
)