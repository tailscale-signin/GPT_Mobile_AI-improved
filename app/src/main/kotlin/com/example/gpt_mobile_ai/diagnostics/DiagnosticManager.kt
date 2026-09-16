package com.example.gpt_mobile_ai.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central manager for all diagnostic functionality
 */
class DiagnosticManager(
    private val context: Context
) {
    private val TAG = "DiagnosticManager"
    private val isInitialized = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var remoteDiagnosticsManager: RemoteDiagnosticsManager? = null
    private var diagnosticReporter: DiagnosticReporter? = null

    /**
     * Initializes the diagnostic manager
     */
    fun initialize() {
        if (isInitialized.get()) {
            Log.d(TAG, "Diagnostic manager already initialized")
            return
        }

        try {
            Log.d(TAG, "Initializing diagnostic manager")
            
            // Initialize components
            remoteDiagnosticsManager = RemoteDiagnosticsManager.getInstance(context)
            remoteDiagnosticsManager?.initialize()
            
            diagnosticReporter = DiagnosticReporter(context, remoteDiagnosticsManager!!)
            diagnosticReporter?.initialize()
            
            isInitialized.set(true)
            Log.d(TAG, "Diagnostic manager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize diagnostic manager", e)
        }
    }

    /**
     * Reports all available diagnostics
     */
    fun reportAllDiagnostics() {
        if (!isInitialized.get()) {
            Log.w(TAG, "Diagnostic manager not initialized")
            return
        }

        scope.launch {
            try {
                Log.d(TAG, "Reporting all diagnostics")
                
                // Report device diagnostics
                diagnosticReporter?.reportDeviceDiagnostics()
                
                // Report QNN diagnostics
                diagnosticReporter?.reportQnnDiagnostics()
                
                Log.d(TAG, "All diagnostics reported successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to report all diagnostics", e)
            }
        }
    }

    /**
     * Collects and returns diagnostic information
     */
    fun collectDiagnostics(): Map<String, Any> {
        if (!isInitialized.get()) {
            Log.w(TAG, "Diagnostic manager not initialized")
            return emptyMap()
        }

        return try {
            val deviceDiagnostics = DeviceDiagnostics.collect(context)
            mapOf(
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
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to collect diagnostics", e)
            emptyMap()
        }
    }

    /**
     * Checks if diagnostics are enabled
     */
    fun areDiagnosticsEnabled(): Boolean {
        // In a real implementation, this would check user preferences
        return true
    }

    /**
     * Cleans up resources
     */
    fun cleanup() {
        if (isInitialized.get()) {
            Log.d(TAG, "Cleaning up diagnostic manager")
            remoteDiagnosticsManager?.cleanup()
            diagnosticReporter?.cleanup()
            scope.cancel()
            isInitialized.set(false)
        }
    }
}