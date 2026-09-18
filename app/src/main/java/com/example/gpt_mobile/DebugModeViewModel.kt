package com.example.gpt_mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for debug mode functionality
 */
class DebugModeViewModel(application: Application) : AndroidViewModel(application) {
    
    private val _debugModeEnabled = MutableStateFlow(false)
    val debugModeEnabled: StateFlow<Boolean> = _debugModeEnabled.asStateFlow()
    
    private val _telemetryData = MutableLiveData<List<TelemetryEvent>>()
    val telemetryData: LiveData<List<TelemetryEvent>> = _telemetryData
    
    private val _diagnosticsData = MutableLiveData<List<HardwareDiagnostics>>()
    val diagnosticsData: LiveData<List<HardwareDiagnostics>> = _diagnosticsData
    
    private val _tokenMetrics = MutableLiveData<List<TokenMetrics>>()
    val tokenMetrics: LiveData<List<TokenMetrics>> = _tokenMetrics
    
    private val debugModeManager = DebugModeManager(application)
    
    /**
     * Enable debug mode
     */
    fun enableDebugMode() {
        viewModelScope.launch {
            debugModeManager.enableDebugMode()
            _debugModeEnabled.value = true
        }
    }
    
    /**
     * Disable debug mode
     */
    fun disableDebugMode() {
        viewModelScope.launch {
            debugModeManager.disableDebugMode()
            _debugModeEnabled.value = false
        }
    }
    
    /**
     * Toggle debug mode
     */
    fun toggleDebugMode() {
        viewModelScope.launch {
            if (debugModeEnabled.value) {
                disableDebugMode()
            } else {
                enableDebugMode()
            }
        }
    }
    
    /**
     * Collect telemetry data
     */
    fun collectTelemetry() {
        viewModelScope.launch {
            // In a real implementation, this would collect actual telemetry data
            // For now, we'll just update the UI with mock data
            val mockTelemetry = listOf(
                TelemetryEvent(
                    timestamp = System.currentTimeMillis(),
                    eventType = "test_event",
                    eventName = "test_event_name",
                    eventCategory = "test_category",
                    payload = "{}",
                    samplingRateMs = 1000,
                    deviceId = "test_device",
                    sessionId = "test_session"
                )
            )
            _telemetryData.value = mockTelemetry
        }
    }
    
    /**
     * Collect diagnostics data
     */
    fun collectDiagnostics() {
        viewModelScope.launch {
            // In a real implementation, this would collect actual diagnostics data
            // For now, we'll just update the UI with mock data
            val mockDiagnostics = listOf(
                HardwareDiagnostics(
                    timestamp = System.currentTimeMillis(),
                    socId = "test_soc",
                    socName = "Test SoC",
                    socVersion = "1.0",
                    ramTotalMb = 8192,
                    ramUsedMb = 4096,
                    ramFreeMb = 4096,
                    temperatureCelsius = 45.0f,
                    thermalState = "Normal",
                    thermalThrottling = 0,
                    npuReady = 1,
                    npuVersion = "1.0",
                    npuLoad = 0.2f,
                    batteryLevelPercent = 85,
                    batteryTemperatureCelsius = 32.0f,
                    charging = 1,
                    networkType = "WiFi",
                    networkSpeedMbps = 100.0f,
                    networkLatencyMs = 15.0f,
                    cpuLoadPercent = 25.0f,
                    gpuLoadPercent = 15.0f,
                    npuLoadPercent = 20.0f,
                    uptimeSeconds = 3600,
                    bootTime = System.currentTimeMillis() - 3600000
                )
            )
            _diagnosticsData.value = mockDiagnostics
        }
    }
    
    /**
     * Collect token metrics
     */
    fun collectTokenMetrics() {
        viewModelScope.launch {
            // In a real implementation, this would collect actual token metrics
            // For now, we'll just update the UI with mock data
            val mockMetrics = listOf(
                TokenMetrics(
                    timestamp = System.currentTimeMillis(),
                    sessionId = "test_session",
                    provider = "test_provider",
                    model = "test_model",
                    tokenIndex = 1,
                    tokenType = "test_type",
                    tokenCount = 100,
                    durationMs = 50.0f,
                    latencyMs = 25.0f,
                    ttfbMs = 10.0f,
                    tokensPerSecond = 2000.0f,
                    totalTokens = 100,
                    error = 0,
                    errorMessage = "",
                    samplingRateMs = 1000
                )
            )
            _tokenMetrics.value = mockMetrics
        }
    }
    
    /**
     * Export telemetry data
     */
    fun exportTelemetryData() {
        viewModelScope.launch {
            // In a real implementation, this would export data to a file
            // For now, we'll just log it
            val snapshot = DiagnosticsTelemetryProvider.getSnapshot(
                getApplication(),
                "Test Backend",
                "Test Accelerator"
            )
            val exportText = DiagnosticsTelemetryProvider.formatDiagnosticsText(snapshot, null)
            // In a real app, this would save to a file or share via intent
            println("Exported telemetry data: $exportText")
        }
    }
    
    /**
     * Get current debug mode status
     */
    fun isDebugModeEnabled(): Boolean = debugModeEnabled.value
    
    /**
     * Get the debug mode manager
     */
    fun getDebugModeManager(): DebugModeManager = debugModeManager
}