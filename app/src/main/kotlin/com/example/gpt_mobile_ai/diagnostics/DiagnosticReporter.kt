package com.example.gpt_mobile_ai.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reports diagnostic information to remote servers
 */
class DiagnosticReporter(
    private val context: Context,
    private val remoteDiagnosticsManager: RemoteDiagnosticsManager
) {
    private val TAG = "DiagnosticReporter"
    private val isInitialized = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Initializes the diagnostic reporter
     */
    fun initialize() {
        if (isInitialized.get()) {
            Log.d(TAG, "Diagnostic reporter already initialized")
            return
        }

        try {
            Log.d(TAG, "Initializing diagnostic reporter")
            isInitialized.set(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize diagnostic reporter", e)
        }
    }

    /**
     * Reports device diagnostics to remote server
     */
    fun reportDeviceDiagnostics() {
        if (!isInitialized.get()) {
            Log.w(TAG, "Diagnostic reporter not initialized")
            return
        }

        scope.launch {
            try {
                Log.d(TAG, "Collecting and reporting device diagnostics")
                
                // Collect device diagnostics
                val deviceDiagnostics = DeviceDiagnostics.collect(context)
                Log.d(TAG, "Collected diagnostics: ${deviceDiagnostics.deviceModel}")

                // Send to remote diagnostics manager
                val diagnosticData = mapOf(
                    "device_info" to mapOf(
                        "model" to deviceDiagnostics.deviceModel,
                        "manufacturer" to deviceDiagnostics.manufacturer,
                        "android_version" to deviceDiagnostics.androidVersion,
                        "app_version" to deviceDiagnostics.appVersion,
                        "is_debug_build" to deviceDiagnostics.isDebugBuild,
                        "timestamp" to deviceDiagnostics.timestamp
                    ),
                    "hardware_info" to mapOf(
                        "total_memory" to deviceDiagnostics.totalMemory,
                        "available_memory" to deviceDiagnostics.availableMemory,
                        "total_storage" to deviceDiagnostics.totalStorage,
                        "available_storage" to deviceDiagnostics.availableStorage,
                        "is_qnn_supported" to deviceDiagnostics.isQnnSupported
                    ),
                    "qnn_info" to mapOf(
                        "library_version" to deviceDiagnostics.qnnLibraryVersion,
                        "runtime_status" to deviceDiagnostics.runtimeStatus,
                        "hardware_health" to deviceDiagnostics.hardwareHealth
                    )
                )

                remoteDiagnosticsManager.sendDiagnosticData(diagnosticData)
                Log.d(TAG, "Device diagnostics reported successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to report device diagnostics", e)
            }
        }
    }

    /**
     * Reports QNN-specific diagnostics
     */
    fun reportQnnDiagnostics() {
        if (!isInitialized.get()) {
            Log.w(TAG, "Diagnostic reporter not initialized")
            return
        }

        scope.launch {
            try {
                Log.d(TAG, "Collecting and reporting QNN diagnostics")
                
                // In a real implementation, this would collect QNN-specific metrics
                val qnnData = mapOf(
                    "qnn_enabled" to true,
                    "qnn_performance_metrics" to mapOf(
                        "model_load_time_ms" to 150L,
                        "inference_time_ms" to 250L,
                        "memory_usage_mb" to 150L
                    ),
                    "fallback_reason" to null // Will be populated if fallback occurred
                )

                remoteDiagnosticsManager.sendDiagnosticData(mapOf("qnn_metrics" to qnnData))
                Log.d(TAG, "QNN diagnostics reported successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to report QNN diagnostics", e)
            }
        }
    }

    /**
     * Reports runtime diagnostics
     */
    fun reportRuntimeDiagnostics() {
        if (!isInitialized.get()) {
            Log.w(TAG, "Diagnostic reporter not initialized")
            return
        }

        scope.launch {
            try {
                Log.d(TAG, "Collecting and reporting runtime diagnostics")
                
                // In a real implementation, this would collect runtime-specific metrics
                val runtimeData = mapOf(
                    "runtime_type" to "QNN",
                    "capabilities" to listOf("quantized_models", "hardware_acceleration"),
                    "status" to mapOf(
                        "is_initialized" to true,
                        "is_healthy" to true
                    )
                )

                remoteDiagnosticsManager.sendDiagnosticData(mapOf("runtime_metrics" to runtimeData))
                Log.d(TAG, "Runtime diagnostics reported successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to report runtime diagnostics", e)
            }
        }
    }

    /**
     * Reports all diagnostics
     */
    fun reportAllDiagnostics() {
        scope.launch {
            try {
                Log.d(TAG, "Reporting all diagnostics")
                
                // Report all types of diagnostics
                reportDeviceDiagnostics()
                reportQnnDiagnostics()
                reportRuntimeDiagnostics()
                
                Log.d(TAG, "All diagnostics reported successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to report all diagnostics", e)
            }
        }
    }

    /**
     * Cleans up resources
     */
    fun cleanup() {
        if (isInitialized.get()) {
            Log.d(TAG, "Cleaning up diagnostic reporter")
            scope.cancel()
            isInitialized.set(false)
        }
    }
}