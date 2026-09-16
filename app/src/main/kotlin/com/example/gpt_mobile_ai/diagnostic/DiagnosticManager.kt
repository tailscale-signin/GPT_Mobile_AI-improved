package com.example.gpt_mobile_ai.diagnostic

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centralized manager for all diagnostic functionality
 */
class DiagnosticManager(private val context: Context) {
    companion object {
        private const val TAG = "DiagnosticManager"
    }

    private val isInitialized = AtomicBoolean(false)
    private lateinit var coroutineScope: CoroutineScope
    private lateinit var hardwareGovernor: DeviceHardwareGovernor
    private lateinit var diagnosticReporter: DiagnosticReporter

    /**
     * Initialize the diagnostic manager
     */
    suspend fun initialize() {
        if (isInitialized.get()) {
            Log.d(TAG, "Diagnostic manager already initialized")
            return
        }

        coroutineScope = CoroutineScope(Dispatchers.Default)
        hardwareGovernor = DeviceHardwareGovernor(context)
        diagnosticReporter = DiagnosticReporter(context)

        // Initialize components
        val hardwareInitialized = hardwareGovernor.initialize()
        val reporterInitialized = diagnosticReporter.initialize()

        if (hardwareInitialized && reporterInitialized) {
            isInitialized.set(true)
            Log.d(TAG, "Diagnostic manager initialized successfully")
        } else {
            Log.e(TAG, "Failed to initialize diagnostic manager components")
        }
    }

    /**
     * Collect comprehensive diagnostics
     */
    suspend fun collectDiagnostics(): DiagnosticReport {
        return try {
            // Collect hardware information
            val hardwareInfo = hardwareGovernor.getDeviceHardwareInfo()
            
            // Collect runtime information
            val runtimeInfo = collectRuntimeInfo()
            
            // Collect system information
            val systemInfo = collectSystemInfo()
            
            // Collect performance metrics
            val performanceMetrics = collectPerformanceMetrics()
            
            // Collect QNN-specific information
            val qnnInfo = collectQnnInfo()
            
            DiagnosticReport(
                hardwareInfo = hardwareInfo,
                runtimeInfo = runtimeInfo,
                systemInfo = systemInfo,
                performanceMetrics = performanceMetrics,
                qnnInfo = qnnInfo,
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting diagnostics", e)
            DiagnosticReport(
                hardwareInfo = DeviceHardwareInfo(
                    memoryInfo = MemoryInfo(0, 0),
                    storageInfo = StorageInfo(0, 0),
                    cpuInfo = CpuInfo("", 0),
                    gpuInfo = GpuInfo("", 0),
                    qnnInfo = QnnInfo(false, ""),
                    timestamp = System.currentTimeMillis()
                ),
                runtimeInfo = emptyMap(),
                systemInfo = emptyMap(),
                performanceMetrics = emptyMap(),
                qnnInfo = emptyMap(),
                timestamp = System.currentTimeMillis()
            )
        }
    }

    /**
     * Collect runtime information
     */
    private fun collectRuntimeInfo(): Map<String, Any> {
        return mapOf(
            "debug_mode" to DebugUtils.isDebugMode(),
            "service_running" to RemoteDiagnosticsService::class.java.isInstance(this)
        )
    }

    /**
     * Collect system information
     */
    private fun collectSystemInfo(): Map<String, Any> {
        return mapOf(
            "android_version" to android.os.Build.VERSION.SDK_INT,
            "device_model" to android.os.Build.MODEL,
            "device_manufacturer" to android.os.Build.MANUFACTURER,
            "cpu_architecture" to android.os.Build.CPU_ABI,
            "is_emulator" to android.os.Build.FINGERPRINT.contains("generic", ignoreCase = true)
        )
    }

    /**
     * Collect performance metrics
     */
    private fun collectPerformanceMetrics(): Map<String, Any> {
        return mapOf(
            "memory_usage_mb" to (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024),
            "max_memory_mb" to Runtime.getRuntime().maxMemory() / (1024 * 1024),
            "available_memory_mb" to Runtime.getRuntime().freeMemory() / (1024 * 1024)
        )
    }

    /**
     * Collect QNN-specific information
     */
    private fun collectQnnInfo(): Map<String, Any> {
        return mapOf(
            "qnn_supported" to checkQnnSupport(),
            "qnn_version" to getQnnVersion()
        )
    }

    /**
     * Check QNN support
     */
    private fun checkQnnSupport(): Boolean {
        try {
            val manufacturer = android.os.Build.MANUFACTURER
            val model = android.os.Build.MODEL
            
            val isQualcommDevice = manufacturer.contains("Qualcomm", ignoreCase = true) ||
                                  model.contains("Snapdragon", ignoreCase = true)
            
            return isQualcommDevice
        } catch (e: Exception) {
            Log.e(TAG, "Error checking QNN support", e)
            return false
        }
    }

    /**
     * Get QNN version
     */
    private fun getQnnVersion(): String {
        return "QNN v7.9" // Placeholder
    }

    /**
     * Send diagnostics to remote server
     */
    suspend fun sendDiagnostics(report: DiagnosticReport): Boolean {
        return try {
            if (!isInitialized.get()) {
                Log.e(TAG, "Diagnostic manager not initialized")
                return false
            }

            DebugUtils.logDiagnosticInfo("Sending diagnostics to remote server")
            
            // In a real implementation, this would:
            // 1. Format the report
            // 2. Send via HTTP/HTTPS
            // 3. Handle network errors
            // 4. Retry logic
            
            val sent = diagnosticReporter.sendReport(report)
            Log.d(TAG, "Diagnostics sent successfully: $sent")
            sent
        } catch (e: Exception) {
            Log.e(TAG, "Error sending diagnostics", e)
            false
        }
    }

    /**
     * Clean up diagnostic manager resources
     */
    fun cleanup() {
        try {
            Log.d(TAG, "Cleaning up diagnostic manager")
            isInitialized.set(false)
            
            // Cancel any ongoing operations
            coroutineScope.launch {
                // Cleanup logic here
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    /**
     * Check if diagnostic manager is initialized
     */
    fun isInitialized(): Boolean {
        return isInitialized.get()
    }
}

/**
 * Diagnostic report data class
 */
data class DiagnosticReport(
    val hardwareInfo: DeviceHardwareInfo,
    val runtimeInfo: Map<String, Any>,
    val systemInfo: Map<String, Any>,
    val performanceMetrics: Map<String, Any>,
    val qnnInfo: Map<String, Any>,
    val timestamp: Long
)