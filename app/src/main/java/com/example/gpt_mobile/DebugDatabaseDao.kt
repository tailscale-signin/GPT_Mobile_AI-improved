package com.example.gpt_mobile

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO for AETHERION Debug Database
 * Contains all queries for telemetry, diagnostics, and token metrics
 */
@Dao
interface DebugDatabaseDao {
    
    // Telemetry Events Queries
    @Query("SELECT * FROM telemetry_events WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC LIMIT :limit")
    fun getTelemetryEventsByTimeRange(startTime: Long, endTime: Long, limit: Int): Flow<List<TelemetryEvent>>
    
    @Query("SELECT * FROM telemetry_events WHERE deviceId = :deviceId ORDER BY timestamp DESC LIMIT :limit")
    fun getTelemetryEventsByDevice(deviceId: String, limit: Int): Flow<List<TelemetryEvent>>
    
    @Query("SELECT * FROM telemetry_events WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit")
    fun getTelemetryEventsBySession(sessionId: String, limit: Int): Flow<List<TelemetryEvent>>
    
    @Query("SELECT * FROM telemetry_events WHERE eventCategory = :category ORDER BY timestamp DESC LIMIT :limit")
    fun getTelemetryEventsByCategory(category: String, limit: Int): Flow<List<TelemetryEvent>>
    
    @Query("SELECT * FROM telemetry_events WHERE eventType = :eventType ORDER BY timestamp DESC LIMIT :limit")
    fun getTelemetryEventsByType(eventType: String, limit: Int): Flow<List<TelemetryEvent>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTelemetryEvent(event: TelemetryEvent)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTelemetryEvents(events: List<TelemetryEvent>)
    
    @Query("DELETE FROM telemetry_events WHERE timestamp < :cutoffTime")
    suspend fun deleteOldTelemetryEvents(cutoffTime: Long)
    
    // Hardware Diagnostics Queries
    @Query("SELECT * FROM hardware_diagnostics WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC LIMIT :limit")
    fun getHardwareDiagnosticsByTimeRange(startTime: Long, endTime: Long, limit: Int): Flow<List<HardwareDiagnostics>>
    
    @Query("SELECT * FROM hardware_diagnostics WHERE socId = :socId ORDER BY timestamp DESC LIMIT :limit")
    fun getHardwareDiagnosticsBySoC(socId: String, limit: Int): Flow<List<HardwareDiagnostics>>
    
    @Query("SELECT * FROM hardware_diagnostics WHERE npuReady = 1 ORDER BY timestamp DESC LIMIT :limit")
    fun getHardwareDiagnosticsWithNpuReady(limit: Int): Flow<List<HardwareDiagnostics>>
    
    @Query("SELECT * FROM hardware_diagnostics WHERE temperatureCelsius > :maxTemp ORDER BY timestamp DESC LIMIT :limit")
    fun getHardwareDiagnosticsByTemperature(maxTemp: Float, limit: Int): Flow<List<HardwareDiagnostics>>
    
    @Query("SELECT * FROM hardware_diagnostics WHERE batteryLevelPercent < :lowBattery ORDER BY timestamp DESC LIMIT :limit")
    fun getHardwareDiagnosticsByBattery(lowBattery: Int, limit: Int): Flow<List<HardwareDiagnostics>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHardwareDiagnostics(diagnostics: HardwareDiagnostics)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHardwareDiagnosticsList(diagnostics: List<HardwareDiagnostics>)
    
    @Query("DELETE FROM hardware_diagnostics WHERE timestamp < :cutoffTime")
    suspend fun deleteOldHardwareDiagnostics(cutoffTime: Long)
    
    // Token Metrics Queries
    @Query("SELECT * FROM token_metrics WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC LIMIT :limit")
    fun getTokenMetricsByTimeRange(startTime: Long, endTime: Long, limit: Int): Flow<List<TokenMetrics>>
    
    @Query("SELECT * FROM token_metrics WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit")
    fun getTokenMetricsBySession(sessionId: String, limit: Int): Flow<List<TokenMetrics>>
    
    @Query("SELECT * FROM token_metrics WHERE provider = :provider ORDER BY timestamp DESC LIMIT :limit")
    fun getTokenMetricsByProvider(provider: String, limit: Int): Flow<List<TokenMetrics>>
    
    @Query("SELECT * FROM token_metrics WHERE model = :model ORDER BY timestamp DESC LIMIT :limit")
    fun getTokenMetricsByModel(model: String, limit: Int): Flow<List<TokenMetrics>>
    
    @Query("SELECT * FROM token_metrics WHERE latencyMs > :maxLatency ORDER BY timestamp DESC LIMIT :limit")
    fun getTokenMetricsByLatency(maxLatency: Float, limit: Int): Flow<List<TokenMetrics>>
    
    @Query("SELECT * FROM token_metrics WHERE error = 1 ORDER BY timestamp DESC LIMIT :limit")
    fun getTokenMetricsWithError(limit: Int): Flow<List<TokenMetrics>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTokenMetrics(metrics: TokenMetrics)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTokenMetricsList(metrics: List<TokenMetrics>)
    
    @Query("DELETE FROM token_metrics WHERE timestamp < :cutoffTime")
    suspend fun deleteOldTokenMetrics(cutoffTime: Long)
    
    // Utility Queries
    @Query("SELECT COUNT(*) FROM telemetry_events")
    fun getTelemetryEventCount(): Flow<Int>
    
    @Query("SELECT COUNT(*) FROM hardware_diagnostics")
    fun getHardwareDiagnosticsCount(): Flow<Int>
    
    @Query("SELECT COUNT(*) FROM token_metrics")
    fun getTokenMetricsCount(): Flow<Int>
    
    @Query("SELECT MAX(timestamp) FROM telemetry_events")
    fun getLastTelemetryEventTimestamp(): Flow<Long?>
    
    @Query("SELECT MAX(timestamp) FROM hardware_diagnostics")
    fun getLastHardwareDiagnosticsTimestamp(): Flow<Long?>
    
    @Query("SELECT MAX(timestamp) FROM token_metrics")
    fun getLastTokenMetricsTimestamp(): Flow<Long?>
    
    // Aggregation Queries
    @Query("SELECT AVG(latencyMs) as avgLatency, COUNT(*) as count FROM token_metrics WHERE sessionId = :sessionId")
    fun getAverageLatencyForSession(sessionId: String): Flow<TokenMetricsSummary?>
    
    @Query("SELECT AVG(cpuLoadPercent) as avgCpu, AVG(gpuLoadPercent) as avgGpu, AVG(npuLoadPercent) as avgNpu FROM hardware_diagnostics WHERE timestamp >= :startTime AND timestamp <= :endTime")
    fun getAverageHardwareLoad(startTime: Long, endTime: Long): Flow<HardwareLoadSummary?>
    
    @Query("SELECT provider, COUNT(*) as count, AVG(latencyMs) as avgLatency FROM token_metrics WHERE timestamp >= :startTime AND timestamp <= :endTime GROUP BY provider")
    fun getProviderLatencySummary(startTime: Long, endTime: Long): Flow<List<ProviderLatencySummary>>
    
    // Complex Queries
    @Query("SELECT t.*, h.socName, h.temperatureCelsius FROM telemetry_events t JOIN hardware_diagnostics h ON t.timestamp = h.timestamp WHERE t.eventCategory = 'performance' AND h.temperatureCelsius > 80 ORDER BY t.timestamp DESC LIMIT :limit")
    fun getHighTempPerformanceEvents(limit: Int): Flow<List<PerformanceEventWithTemp>>
    
    @Query("SELECT t.*, h.socName, h.temperatureCelsius FROM token_metrics t JOIN hardware_diagnostics h ON t.timestamp = h.timestamp WHERE t.latencyMs > 1000 AND h.temperatureCelsius > 75 ORDER BY t.timestamp DESC LIMIT :limit")
    fun getHighLatencyHighTempTokens(limit: Int): Flow<List<HighLatencyTokenWithTemp>>
    
    // Batch operations
    @Transaction
    suspend fun insertTelemetryBatch(events: List<TelemetryEvent>) {
        insertTelemetryEvents(events)
    }
    
    @Transaction
    suspend fun insertHardwareDiagnosticsBatch(diagnostics: List<HardwareDiagnostics>) {
        insertHardwareDiagnosticsList(diagnostics)
    }
    
    @Transaction
    suspend fun insertTokenMetricsBatch(metrics: List<TokenMetrics>) {
        insertTokenMetricsList(metrics)
    }
}

/**
 * Summary data class for token metrics
 */
data class TokenMetricsSummary(
    val avgLatency: Float,
    val count: Int
)

/**
 * Summary data class for hardware load
 */
data class HardwareLoadSummary(
    val avgCpu: Float,
    val avgGpu: Float,
    val avgNpu: Float
)

/**
 * Summary data class for provider latency
 */
data class ProviderLatencySummary(
    val provider: String,
    val count: Int,
    val avgLatency: Float
)

/**
 * Performance event with temperature data
 */
data class PerformanceEventWithTemp(
    @Embedded val telemetryEvent: TelemetryEvent,
    val socName: String,
    val temperatureCelsius: Float
)

/**
 * High latency token with temperature data
 */
data class HighLatencyTokenWithTemp(
    @Embedded val tokenMetrics: TokenMetrics,
    val socName: String,
    val temperatureCelsius: Float
)
