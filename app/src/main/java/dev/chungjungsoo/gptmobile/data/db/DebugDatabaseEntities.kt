package dev.chungjungsoo.gptmobile.data.db

import androidx.room.*

@Entity(
    tableName = "telemetry_events",
    primaryKeys = ["id", "timestamp"],
    indices = [Index(value = ["timestamp"], name = "idx_telemetry_timestamp")]
)
data class TelemetryEvent(
    @PrimaryKey val id: String,
    @PrimaryKey val timestamp: Long,
    val eventType: String,
    val eventName: String,
    val eventCategory: String,
    val payload: String,
    val samplingRateMs: Int,
    val deviceId: String,
    val sessionId: String,
    val version: String
)

@Entity(
    tableName = "hardware_diagnostics",
    primaryKeys = ["id", "timestamp"],
    indices = [Index(value = ["timestamp"], name = "idx_hw_diagnostics_timestamp")]
)
data class HardwareDiagnostics(
    @PrimaryKey val id: String,
    @PrimaryKey val timestamp: Long,
    val soCId: String,
    val soCModel: String,
    val ramTotalMB: Int,
    val ramFreeMB: Int,
    val thermalState: String,
    val thermalTemperatureC: Double,
    val npuReady: Boolean,
    val npuModel: String,
    val batteryLevelPercent: Int,
    val batteryTemperatureC: Double,
    val networkType: String,
    val networkLatencyMs: Int,
    val networkJitterMs: Int,
    val networkPacketLossPercent: Double,
    val cpuLoadPercent: Double,
    val gpuLoadPercent: Double,
    val deviceModel: String,
    val androidVersion: String,
    val appVersion: String
)

@Entity(
    tableName = "token_metrics",
    primaryKeys = ["id", "timestamp"],
    indices = [Index(value = ["timestamp"], name = "idx_token_metrics_timestamp")]
)
data class TokenMetrics(
    @PrimaryKey val id: String,
    @PrimaryKey val timestamp: Long,
    val tokenId: String,
    val tokenType: String,
    val tokenCount: Int,
    val tokenDurationMs: Double,
    val tokenPerSecond: Double,
    val modelProvider: String,
    val modelId: String,
    val sessionId: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val latencyMs: Double,
    val timeToFirstTokenMs: Double,
    val timePerOutputTokenMs: Double,
    val samplingRateMs: Int,
    val deviceId: String,
    val version: String
)
