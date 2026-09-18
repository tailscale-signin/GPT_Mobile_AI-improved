package com.gptmobileai.debug

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * AETHERION Debug Mode - Data Access Object
 * 
 * Provides 30+ optimized queries for real-time telemetry, hardware diagnostics,
 * and token metrics. Supports time-range queries, session filtering, and
 * threshold-based analysis.
 * 
 * @author AETHERION Debug Team
 * @version 1.0.0
 */

@Dao
interface DebugDatabaseDao {
    
    // ==================== TELEMETRY EVENTS ====================
    
    @Query("SELECT * FROM telemetry_events ORDER BY timestamp DESC LIMIT 100")
    fun getRecentTelemetryEvents(): Flow<List<TelemetryEvent>>
    
    @Query("SELECT * FROM telemetry_events WHERE deviceId = :deviceId ORDER BY timestamp DESC LIMIT 100")
    fun getRecentTelemetryEventsByDevice(deviceId: String): Flow<List<TelemetryEvent>>
    
    @Query("SELECT * FROM telemetry_events WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT 100")
    fun getRecentTelemetryEventsBySession(sessionId: String): Flow<List<TelemetryEvent>>
    
    @Query("SELECT * FROM telemetry_events WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC LIMIT 100")
    suspend fun getTelemetryEventsByTimeRange(start: Long, end: Long): List<TelemetryEvent>
    
    @Query("SELECT * FROM telemetry_events WHERE eventCategory = :category ORDER BY timestamp DESC LIMIT 100")
    suspend fun getTelemetryEventsByCategory(category: String): List<TelemetryEvent>
    
    @Query("SELECT * FROM telemetry_events WHERE eventName = :eventName ORDER BY timestamp DESC LIMIT 100")
    suspend fun getTelemetryEventsByName(eventName: String): List<TelemetryEvent>
    
    @Query("SELECT * FROM telemetry_events WHERE samplingRateMs <= :rate ORDER BY timestamp DESC LIMIT 100")
    suspend fun getTelemetryEventsBySamplingRate(rate: Int): List<TelemetryEvent>
    
    @Query("SELECT * FROM telemetry_events WHERE version = :version ORDER BY timestamp DESC LIMIT 100")
    suspend fun getTelemetryEventsByVersion(version: Int): List<TelemetryEvent>
    
    @Query("SELECT COUNT(*) as count FROM telemetry_events WHERE timestamp BETWEEN :start AND :end")
    suspend fun getTelemetryEventCountByTimeRange(start: Long, end: Long): Int
    
    @Query("SELECT COUNT(*) as count FROM telemetry_events WHERE deviceId = :deviceId")
    suspend fun getTelemetryEventCountByDevice(deviceId: String): Int
    
    @Query("SELECT COUNT(*) as count FROM telemetry_events WHERE sessionId = :sessionId")
    suspend fun getTelemetryEventCountBySession(sessionId: String): Int
    
    @Query("SELECT * FROM telemetry_events WHERE timestamp < :cutoff ORDER BY timestamp ASC")
    suspend fun getOldTelemetryEvents(cutoff: Long): List<TelemetryEvent>
    
    @Query("DELETE FROM telemetry_events WHERE timestamp < :cutoff")
    suspend fun deleteOldTelemetryEvents(cutoff: Long)
    
    @Query("INSERT OR REPLACE INTO telemetry_events (timestamp, eventType, eventName, eventCategory, payload, samplingRateMs, deviceId, sessionId, version) VALUES (:timestamp, :eventType, :eventName, :eventCategory, :payload, :samplingRateMs, :deviceId, :sessionId, :version)")
    suspend fun insertTelemetryEvent(event: TelemetryEvent): Long
    
    @Query("INSERT INTO telemetry_events (timestamp, eventType, eventName, eventCategory, payload, samplingRateMs, deviceId, sessionId, version) VALUES (:timestamp, :eventType, :eventName, :eventCategory, :payload, :samplingRateMs, :deviceId, :sessionId, :version)")
    suspend fun insertTelemetryEvents(events: List<TelemetryEvent>)
    
    // ==================== HARDWARE DIAGNOSTICS ====================
    
    @Query("SELECT * FROM hardware_diagnostics ORDER BY timestamp DESC LIMIT 100")
    fun getRecentHardwareDiagnostics(): Flow<List<HardwareDiagnostics>>
    
    @Query("SELECT * FROM hardware_diagnostics WHERE npuReady = 1 ORDER BY timestamp DESC LIMIT 100")
    suspend fun getNpuReadyDiagnostics(): List<HardwareDiagnostics>
    
    @Query("SELECT * FROM hardware_diagnostics WHERE temperatureCelsius > :threshold ORDER BY timestamp DESC LIMIT 100")
    suspend fun getHighTemperatureDiagnostics(threshold: Float): List<HardwareDiagnostics>
    
    @Query("SELECT * FROM hardware_diagnostics WHERE thermalThrottling = 1 ORDER BY timestamp DESC LIMIT 100")
    suspend fun getThrottlingDiagnostics(): List<HardwareDiagnostics>
    
    @Query("SELECT * FROM hardware_diagnostics WHERE cpuLoadPercent > :threshold ORDER BY timestamp DESC LIMIT 100")
    suspend fun getHighCpuLoadDiagnostics(threshold: Float): List<HardwareDiagnostics>
    
    @Query("SELECT * FROM hardware_diagnostics WHERE gpuLoadPercent > :threshold ORDER BY timestamp DESC LIMIT 100")
    suspend fun getHighGpuLoadDiagnostics(threshold: Float): List<HardwareDiagnostics>
    
    @Query("SELECT * FROM hardware_diagnostics WHERE batteryLevelPercent < :threshold ORDER BY timestamp DESC LIMIT 100")
    suspend fun getLowBatteryDiagnostics(threshold: Int): List<HardwareDiagnostics>
    
    @Query("SELECT * FROM hardware_diagnostics WHERE networkType = :networkType ORDER BY timestamp DESC LIMIT 100")
    suspend fun getDiagnosticsByNetworkType(networkType: String): List<HardwareDiagnostics>
    
    @Query("SELECT * FROM hardware_diagnostics WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC LIMIT 100")
    suspend fun getDiagnosticsByTimeRange(start: Long, end: Long): List<HardwareDiagnostics>
    
    @Query("SELECT * FROM hardware_diagnostics WHERE timestamp < :cutoff ORDER BY timestamp ASC")
    suspend fun getOldDiagnostics(cutoff: Long): List<HardwareDiagnostics>
    
    @Query("DELETE FROM hardware_diagnostics WHERE timestamp < :cutoff")
    suspend fun deleteOldDiagnostics(cutoff: Long)
    
    @Query("SELECT * FROM hardware_diagnostics ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestDiagnostics(): HardwareDiagnostics?
    
    @Query("SELECT * FROM hardware_diagnostics WHERE timestamp = :timestamp")
    suspend fun getDiagnosticsByTimestamp(timestamp: Long): HardwareDiagnostics?
    
    @Query("SELECT * FROM hardware_diagnostics WHERE socId = :socId ORDER BY timestamp DESC LIMIT 100")
    suspend fun getDiagnosticsBySoc(socId: String): List<HardwareDiagnostics>
    
    @Query("SELECT * FROM hardware_diagnostics WHERE ramUsedMb > :threshold ORDER BY timestamp DESC LIMIT 100")
    suspend fun getHighMemoryDiagnostics(threshold: Int): List<HardwareDiagnostics>
    
    @Query("SELECT * FROM hardware_diagnostics WHERE charging = 1 ORDER BY timestamp DESC LIMIT 100")
    suspend fun getChargingDiagnostics(): List<HardwareDiagnostics>
    
    @Query("SELECT * FROM hardware_diagnostics WHERE uptimeSeconds > :threshold ORDER BY timestamp DESC LIMIT 100")
    suspend fun getLongUptimeDiagnostics(threshold: Long): List<HardwareDiagnostics>
    
    @Query("SELECT * FROM hardware_diagnostics WHERE networkLatencyMs > :threshold ORDER BY timestamp DESC LIMIT 100")
    suspend fun getHighNetworkLatencyDiagnostics(threshold: Float): List<HardwareDiagnostics>
    
    @Query("SELECT * FROM hardware_diagnostics WHERE networkSpeedMbps < :threshold ORDER BY timestamp DESC LIMIT 100")
    suspend fun getLowNetworkSpeedDiagnostics(threshold: Float): List<HardwareDiagnostics>
    
    @Query("SELECT * FROM hardware_diagnostics WHERE batteryTemperatureCelsius > :threshold ORDER BY timestamp DESC LIMIT 100")
    suspend fun getHighBatteryTemperatureDiagnostics(threshold: Float): List<HardwareDiagnostics>
    
    @Query("SELECT * FROM hardware_diagnostics WHERE npuLoad > :threshold ORDER BY timestamp DESC LIMIT 100")
    suspend fun getHighNpuLoadDiagnostics(threshold: Float): List<HardwareDiagnostics>
    
    @Query("SELECT * FROM hardware_diagnostics WHERE npuLoadPercent > :threshold ORDER BY timestamp DESC LIMIT 100")
    suspend fun getHighNpuLoadPercentDiagnostics(threshold: Float): List<HardwareDiagnostics>
    
    @Query("SELECT * FROM hardware_diagnostics WHERE version = :version ORDER BY timestamp DESC LIMIT 100")
    suspend fun getDiagnosticsByVersion(version: Int): List<HardwareDiagnostics>
    
    @Query("INSERT OR REPLACE INTO hardware_diagnostics (timestamp, socId, socName, socVersion, ramTotalMb, ramUsedMb, ramFreeMb, temperatureCelsius, thermalState, thermalThrottling, npuReady, npuVersion, npuLoad, batteryLevelPercent, batteryTemperatureCelsius, charging, networkType, networkSpeedMbps, networkLatencyMs, cpuLoadPercent, gpuLoadPercent, npuLoadPercent, uptimeSeconds, bootTime, version) VALUES (:timestamp, :socId, :socName, :socVersion, :ramTotalMb, :ramUsedMb, :ramFreeMb, :temperatureCelsius, :thermalState, :thermalThrottling, :npuReady, :npuVersion, :npuLoad, :batteryLevelPercent, :batteryTemperatureCelsius, :charging, :networkType, :networkSpeedMbps, :networkLatencyMs, :cpuLoadPercent, :gpuLoadPercent, :npuLoadPercent, :uptimeSeconds, :bootTime, :version)")
    suspend fun insertDiagnostics(diagnostics: HardwareDiagnostics): Long
    
    @Query("INSERT INTO hardware_diagnostics (timestamp, socId, socName, socVersion, ramTotalMb, ramUsedMb, ramFreeMb, temperatureCelsius, thermalState, thermalThrottling, npuReady, npuVersion, npuLoad, batteryLevelPercent, batteryTemperatureCelsius, charging, networkType, networkSpeedMbps, networkLatencyMs, cpuLoadPercent, gpuLoadPercent, npuLoadPercent, uptimeSeconds, bootTime, version) VALUES (:timestamp, :socId, :socName, :socVersion, :ramTotalMb, :ramUsedMb, :ramFreeMb, :temperatureCelsius, :thermalState, :thermalThrottling, :npuReady, :npuVersion, :npuLoad, :batteryLevelPercent, :batteryTemperatureCelsius, :charging, :networkType, :networkSpeedMbps, :networkLatencyMs, :cpuLoadPercent, :gpuLoadPercent, :npuLoadPercent, :uptimeSeconds, :bootTime, :version)")
    suspend fun insertDiagnostics(diagnostics: List<HardwareDiagnostics>)
    
    // ==================== TOKEN METRICS ====================
    
    @Query("SELECT * FROM token_metrics ORDER BY timestamp DESC LIMIT 100")
    fun getRecentTokenMetrics(): Flow<List<TokenMetrics>>
    
    @Query("SELECT * FROM token_metrics WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT 100")
    suspend fun getTokenMetricsBySession(sessionId: String): List<TokenMetrics>
    
    @Query("SELECT * FROM token_metrics WHERE provider = :provider ORDER BY timestamp DESC LIMIT 100")
    suspend fun getTokenMetricsByProvider(provider: String): List<TokenMetrics>
    
    @Query("SELECT * FROM token_metrics WHERE latencyMs > :threshold ORDER BY timestamp DESC LIMIT 100")
    suspend fun getHighLatencyTokenMetrics(threshold: Float): List<TokenMetrics>
    
    @Query("SELECT * FROM token_metrics WHERE ttfbMs > :threshold ORDER BY timestamp DESC LIMIT 100")
    suspend fun getHighTtfbTokenMetrics(threshold: Float): List<TokenMetrics>
    
    @Query("SELECT * FROM token_metrics WHERE tokensPerSecond < :threshold ORDER BY timestamp DESC LIMIT 100")
    suspend fun getLowTpsTokenMetrics(threshold: Float): List<TokenMetrics>
    
    @Query("SELECT * FROM token_metrics WHERE error = 1 ORDER BY timestamp DESC LIMIT 100")
    suspend fun getErrorTokenMetrics(): List<TokenMetrics>
    
    @Query("SELECT * FROM token_metrics WHERE model = :model ORDER BY timestamp DESC LIMIT 100")
    suspend fun getTokenMetricsByModel(model: String): List<TokenMetrics>
    
    @Query("SELECT * FROM token_metrics WHERE tokenType = :tokenType ORDER BY timestamp DESC LIMIT 100")
    suspend fun getTokenMetricsByTokenType(tokenType: String): List<TokenMetrics>
    
    @Query("SELECT * FROM token_metrics WHERE samplingRateMs <= :rate ORDER BY timestamp DESC LIMIT 100")
    suspend fun getTokenMetricsBySamplingRate(rate: Int): List<TokenMetrics>
    
    @Query("SELECT * FROM token_metrics WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC LIMIT 100")
    suspend fun getTokenMetricsByTimeRange(start: Long, end: Long): List<TokenMetrics>
    
    @Query("SELECT * FROM token_metrics WHERE timestamp < :cutoff ORDER BY timestamp ASC")
    suspend fun getOldTokenMetrics(cutoff: Long): List<TokenMetrics>
    
    @Query("DELETE FROM token_metrics WHERE timestamp < :cutoff")
    suspend fun deleteOldTokenMetrics(cutoff: Long)
    
    @Query("SELECT * FROM token_metrics ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestTokenMetrics(): TokenMetrics?
    
    @Query("SELECT * FROM token_metrics WHERE timestamp = :timestamp")
    suspend fun getTokenMetricsByTimestamp(timestamp: Long): TokenMetrics?
    
    @Query("SELECT * FROM token_metrics WHERE tokenIndex = :index ORDER BY timestamp DESC LIMIT 100")
    suspend fun getTokenMetricsByIndex(index: Int): List<TokenMetrics>
    
    @Query("SELECT * FROM token_metrics WHERE totalTokens > :threshold ORDER BY timestamp DESC LIMIT 100")
    suspend fun getHighTotalTokensTokenMetrics(threshold: Int): List<TokenMetrics>
    
    @Query("SELECT * FROM token_metrics WHERE version = :version ORDER BY timestamp DESC LIMIT 100")
    suspend fun getTokenMetricsByVersion(version: Int): List<TokenMetrics>
    
    @Query("SELECT * FROM token_metrics WHERE errorMessage LIKE '%' || :pattern || '%' ORDER BY timestamp DESC LIMIT 100")
    suspend fun getTokenMetricsByErrorMessage(pattern: String): List<TokenMetrics>
    
    @Query("SELECT * FROM token_metrics WHERE durationMs > :threshold ORDER BY timestamp DESC LIMIT 100")
    suspend fun getHighDurationTokenMetrics(threshold: Float): List<TokenMetrics>
    
    @Query("SELECT * FROM token_metrics WHERE latencyMs < :threshold ORDER BY timestamp DESC LIMIT 100")
    suspend fun getLowLatencyTokenMetrics(threshold: Float): List<TokenMetrics>
    
    @Query("SELECT * FROM token_metrics WHERE ttfbMs < :threshold ORDER BY timestamp DESC LIMIT 100")
    suspend fun getLowTtfbTokenMetrics(threshold: Float): List<TokenMetrics>
    
    @Query("SELECT * FROM token_metrics WHERE tokensPerSecond > :threshold ORDER BY timestamp DESC LIMIT 100")
    suspend fun getHighTpsTokenMetrics(threshold: Float): List<TokenMetrics>
    
    @Query("INSERT OR REPLACE INTO token_metrics (timestamp, sessionId, provider, model, tokenIndex, tokenType, tokenCount, durationMs, latencyMs, ttfbMs, tokensPerSecond, totalTokens, error, errorMessage, samplingRateMs, version) VALUES (:timestamp, :sessionId, :provider, :model, :tokenIndex, :tokenType, :tokenCount, :durationMs, :latencyMs, :ttfbMs, :tokensPerSecond, :totalTokens, :error, :errorMessage, :samplingRateMs, :version)")
    suspend fun insertTokenMetrics(metrics: TokenMetrics): Long
    
    @Query("INSERT INTO token_metrics (timestamp, sessionId, provider, model, tokenIndex, tokenType, tokenCount, durationMs, latencyMs, ttfbMs, tokensPerSecond, totalTokens, error, errorMessage, samplingRateMs, version) VALUES (:timestamp, :sessionId, :provider, :model, :tokenIndex, :tokenType, :tokenCount, :durationMs, :latencyMs, :ttfbMs, :tokensPerSecond, :totalTokens, :error, :errorMessage, :samplingRateMs, :version)")
    suspend fun insertTokenMetrics(metrics: List<TokenMetrics>)
}