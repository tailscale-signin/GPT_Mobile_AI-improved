package dev.chungjungsoo.gptmobile.data.db

import androidx.room.*

@Dao
interface DebugDatabaseDao {

    // Telemetry Events
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTelemetryEvent(event: TelemetryEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTelemetryEvents(events: List<TelemetryEvent>)

    @Query("SELECT * FROM telemetry_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTelemetryEvents(limit: Int = 100): List<TelemetryEvent>

    @Query("SELECT * FROM telemetry_events WHERE sessionId = :sessionId ORDER BY timestamp DESC")
    suspend fun getTelemetryEventsBySession(sessionId: String): List<TelemetryEvent>

    @Query("SELECT COUNT(*) FROM telemetry_events WHERE eventType = :eventType")
    suspend fun getTelemetryEventCount(eventType: String): Long

    @Query("SELECT * FROM telemetry_events WHERE eventType = :eventType AND timestamp > :since ORDER BY timestamp DESC")
    suspend fun getTelemetryEventsByType(eventType: String, since: Long): List<TelemetryEvent>

    @Query("SELECT * FROM telemetry_events WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    suspend fun getTelemetryEventsInTimeRange(start: Long, end: Long): List<TelemetryEvent>

    @Query("DELETE FROM telemetry_events WHERE timestamp < :before")
    suspend fun deleteTelemetryEventsBefore(before: Long)

    // Hardware Diagnostics
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHardwareDiagnostics(diagnostics: HardwareDiagnostics)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHardwareDiagnosticsList(diagnostics: List<HardwareDiagnostics>)

    @Query("SELECT * FROM hardware_diagnostics ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentHardwareDiagnostics(limit: Int = 100): List<HardwareDiagnostics>

    @Query("SELECT * FROM hardware_diagnostics WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun getHardwareDiagnosticsSince(since: Long): List<HardwareDiagnostics>

    @Query("SELECT * FROM hardware_diagnostics WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    suspend fun getHardwareDiagnosticsInTimeRange(start: Long, end: Long): List<HardwareDiagnostics>

    @Query("SELECT * FROM hardware_diagnostics WHERE npuReady = 1 ORDER BY timestamp DESC LIMIT 10")
    suspend fun getNpuReadyDiagnostics(): List<HardwareDiagnostics>

    @Query("SELECT * FROM hardware_diagnostics WHERE thermalTemperatureC > :threshold ORDER BY thermalTemperatureC DESC LIMIT 10")
    suspend fun getHighTemperatureDiagnostics(threshold: Double = 45.0): List<HardwareDiagnostics>

    @Query("SELECT * FROM hardware_diagnostics WHERE networkType = :networkType ORDER BY timestamp DESC LIMIT 10")
    suspend fun getNetworkDiagnostics(networkType: String): List<HardwareDiagnostics>

    @Query("SELECT * FROM hardware_diagnostics WHERE deviceId = :deviceId ORDER BY timestamp DESC")
    suspend fun getHardwareDiagnosticsByDevice(deviceId: String): List<HardwareDiagnostics>

    @Query("SELECT * FROM hardware_diagnostics WHERE timestamp < :before")
    suspend fun deleteHardwareDiagnosticsBefore(before: Long)

    // Token Metrics
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTokenMetrics(metrics: TokenMetrics)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTokenMetricsList(metrics: List<TokenMetrics>)

    @Query("SELECT * FROM token_metrics ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTokenMetrics(limit: Int = 100): List<TokenMetrics>

    @Query("SELECT * FROM token_metrics WHERE sessionId = :sessionId ORDER BY timestamp DESC")
    suspend fun getTokenMetricsBySession(sessionId: String): List<TokenMetrics>

    @Query("SELECT * FROM token_metrics WHERE modelProvider = :provider ORDER BY timestamp DESC")
    suspend fun getTokenMetricsByProvider(provider: String): List<TokenMetrics>

    @Query("SELECT * FROM token_metrics WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun getTokenMetricsSince(since: Long): List<TokenMetrics>

    @Query("SELECT * FROM token_metrics WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    suspend fun getTokenMetricsInTimeRange(start: Long, end: Long): List<TokenMetrics>

    @Query("SELECT * FROM token_metrics WHERE tokenType = :tokenType ORDER BY timestamp DESC")
    suspend fun getTokenMetricsByType(tokenType: String): List<TokenMetrics>

    @Query("SELECT * FROM token_metrics WHERE latencyMs > :threshold ORDER BY latencyMs DESC LIMIT 10")
    suspend fun getHighLatencyTokenMetrics(threshold: Double = 500.0): List<TokenMetrics>

    @Query("SELECT * FROM token_metrics WHERE deviceId = :deviceId ORDER BY timestamp DESC")
    suspend fun getTokenMetricsByDevice(deviceId: String): List<TokenMetrics>

    @Query("SELECT * FROM token_metrics WHERE timestamp < :before")
    suspend fun deleteTokenMetricsBefore(before: Long)

    // Cleanup
    @Query("DELETE FROM telemetry_events WHERE timestamp < :before")
    suspend fun deleteTelemetryEventsBefore(before: Long)

    @Query("DELETE FROM hardware_diagnostics WHERE timestamp < :before")
    suspend fun deleteHardwareDiagnosticsBefore(before: Long)

    @Query("DELETE FROM token_metrics WHERE timestamp < :before")
    suspend fun deleteTokenMetricsBefore(before: Long)

    @Query("DELETE FROM telemetry_events WHERE timestamp < :before")
    suspend fun deleteTelemetryEventsBefore(before: Long)

    @Query("DELETE FROM hardware_diagnostics WHERE timestamp < :before")
    suspend fun deleteHardwareDiagnosticsBefore(before: Long)

    @Query("DELETE FROM token_metrics WHERE timestamp < :before")
    suspend fun deleteTokenMetricsBefore(before: Long)
}
