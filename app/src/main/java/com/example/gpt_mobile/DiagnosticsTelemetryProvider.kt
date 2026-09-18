package com.example.gpt_mobile

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow

/**
 * Provider for collecting and managing diagnostics telemetry
 * This class handles the collection, formatting, and management of debug telemetry data
 */
object DiagnosticsTelemetryProvider {
    
    private var isEnabled = false
    private val database: DebugDatabase by lazy { DebugDatabase.getDatabase(context!!) }
    
    /**
     * Get a snapshot of current hardware diagnostics
     */
    fun getSnapshot(
        context: Context,
        backendName: String,
        accelerator: String
    ): DiagnosticsSnapshot {
        // This would typically collect real hardware data
        // For now, returning a mock snapshot
        return DiagnosticsSnapshot(
            socModel = getDeviceSoC(context),
            availableRamMb = getAvailableRam(context),
            totalRamGb = getTotalRam(context),
            thermalStatus = getThermalStatus(context),
            qnnReady = isNpuReady(context),
            backendName = backendName,
            batteryLevel = getBatteryLevel(context),
            batteryCharging = isBatteryCharging(context),
            networkType = getNetworkType(context),
            timestamp = System.currentTimeMillis()
        )
    }
    
    /**
     * Format diagnostics for clipboard export
     */
    fun formatDiagnosticsText(
        snapshot: DiagnosticsSnapshot,
        tokenMetrics: List<TokenMetrics>?
    ): String {
        val sb = StringBuilder()
        sb.appendLine("=== AETHERION Diagnostics ===")
        sb.appendLine("Device: ${snapshot.socModel}")
        sb.appendLine("RAM: ${snapshot.availableRamMb} MB (${snapshot.totalRamGb} GB)")
        sb.appendLine("Thermal Status: ${snapshot.thermalStatus}")
        sb.appendLine("NPU Ready: ${if (snapshot.qnnReady) "Yes" else "No"}")
        sb.appendLine("Battery: ${snapshot.batteryLevel}% ${if (snapshot.batteryCharging) "(Charging)" else "(Not Charging)"}")
        sb.appendLine("Network: ${snapshot.networkType}")
        sb.appendLine("Timestamp: ${snapshot.timestamp}")
        
        tokenMetrics?.let {
            if (it.isNotEmpty()) {
                sb.appendLine("\n=== Token Metrics ===")
                val avgLatency = it.map { it.latencyMs }.average()
                sb.appendLine("Average Latency: ${String.format("%.2f", avgLatency)} ms")
                sb.appendLine("Total Tokens: ${it.sumOf { it.tokenCount }}")
            }
        }
        
        return sb.toString()
    }
    
    /**
     * Enable/disable telemetry collection
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }
    
    /**
     * Get current telemetry status
     */
    fun isEnabled(): Boolean = isEnabled
    
    /**
     * Get current telemetry data as a flow
     */
    fun getTelemetryFlow(): Flow<DiagnosticsSnapshot> {
        return flow {
            // In a real implementation, this would collect live telemetry data
            // For now, returning a mock value
            context?.let {
                emit(getSnapshot(it, "Test Backend", "Test Accelerator"))
            }
        }
    }
    
    // Mock helper methods - these would be implemented with real device APIs
    private fun getDeviceSoC(context: Context): String {
        return android.os.Build.MODEL
    }
    
    private fun getAvailableRam(context: Context): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return (memoryInfo.availMem / (1024 * 1024)).toInt() // MB
    }
    
    private fun getTotalRam(context: Context): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return (memoryInfo.totalMem / (1024 * 1024 * 1024)).toInt() // GB
    }
    
    private fun getThermalStatus(context: Context): String {
        // This would check actual thermal state
        return "Normal"
    }
    
    private fun isNpuReady(context: Context): Boolean {
        // This would check actual NPU readiness
        return true
    }
    
    private fun getBatteryLevel(context: Context): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val level = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level
    }
    
    private fun isBatteryCharging(context: Context): Boolean {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val status = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS)
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
               status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }
    
    private fun getNetworkType(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        
        return when {
            capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
            capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
            capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> "Unknown"
        }
    }
    
    // Mock context holder - in real implementation, this would be injected
    private var context: Context? = null
    
    fun initialize(context: Context) {
        this.context = context
    }
}

/**
 * Data class for diagnostics snapshot
 */
data class DiagnosticsSnapshot(
    val socModel: String,
    val availableRamMb: Int,
    val totalRamGb: Int,
    val thermalStatus: String,
    val qnnReady: Boolean,
    val backendName: String,
    val batteryLevel: Int,
    val batteryCharging: Boolean,
    val networkType: String,
    val timestamp: Long
)

/**
 * Data class for privacy settings
 */
data class PrivacySettings(
    val hardwareTelemetry: Boolean = true,
    val networkTelemetry: Boolean = true,
    val tokenMetrics: Boolean = true,
    val errorTracking: Boolean = true,
    val analytics: Boolean = false
)
