package com.example.gpt_mobile_ai.diagnostic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis

/**
 * Utility class for debugging and performance measurement
 */
object DebugUtils {
    private const val TAG = "DebugUtils"
    private val isDebugMode = AtomicBoolean(false)

    /**
     * Enable or disable debug mode
     */
    fun setDebugMode(enabled: Boolean) {
        isDebugMode.set(enabled)
        Log.d(TAG, "Debug mode ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Check if debug mode is enabled
     */
    fun isDebugMode(): Boolean {
        return isDebugMode.get()
    }

    /**
     * Log a message with timestamp if debug mode is enabled
     */
    fun logDebug(message: String) {
        if (isDebugMode.get()) {
            val timestamp = System.currentTimeMillis()
            Log.d(TAG, "[$timestamp] $message")
        }
    }

    /**
     * Log a warning with timestamp if debug mode is enabled
     */
    fun logWarning(message: String) {
        if (isDebugMode.get()) {
            val timestamp = System.currentTimeMillis()
            Log.w(TAG, "[$timestamp] $message")
        }
    }

    /**
     * Log an error with timestamp if debug mode is enabled
     */
    fun logError(message: String, throwable: Throwable? = null) {
        if (isDebugMode.get()) {
            val timestamp = System.currentTimeMillis()
            if (throwable != null) {
                Log.e(TAG, "[$timestamp] $message", throwable)
            } else {
                Log.e(TAG, "[$timestamp] $message")
            }
        }
    }

    /**
     * Measure execution time of a block of code
     */
    suspend fun <T> measureExecutionTime(block: suspend () -> T): Pair<T, Long> {
        return withContext(Dispatchers.IO) {
            val timeMillis = measureTimeMillis {
                Log.d(TAG, "Starting execution measurement")
            }
            
            val result = block()
            
            Log.d(TAG, "Execution completed in $timeMillis ms")
            result to timeMillis
        }
    }

    /**
     * Log performance metrics
     */
    fun logPerformanceMetrics(metrics: Map<String, Any>) {
        if (isDebugMode.get()) {
            Log.d(TAG, "Performance Metrics:")
            metrics.forEach { (key, value) ->
                Log.d(TAG, "  $key: $value")
            }
        }
    }

    /**
     * Log runtime information
     */
    fun logRuntimeInfo() {
        if (isDebugMode.get()) {
            val runtime = Runtime.getRuntime()
            val maxMemory = runtime.maxMemory()
            val totalMemory = runtime.totalMemory()
            val freeMemory = runtime.freeMemory()
            val usedMemory = totalMemory - freeMemory
            
            Log.d(TAG, "Runtime Info:")
            Log.d(TAG, "  Max Memory: ${maxMemory / (1024 * 1024)} MB")
            Log.d(TAG, "  Total Memory: ${totalMemory / (1024 * 1024)} MB")
            Log.d(TAG, "  Free Memory: ${freeMemory / (1024 * 1024)} MB")
            Log.d(TAG, "  Used Memory: ${usedMemory / (1024 * 1024)} MB")
        }
    }

    /**
     * Log QNN-specific debugging information
     */
    fun logQnnDebugInfo(message: String) {
        if (isDebugMode.get()) {
            Log.d(TAG, "QNN DEBUG: $message")
        }
    }

    /**
     * Log diagnostic information
     */
    fun logDiagnosticInfo(message: String) {
        if (isDebugMode.get()) {
            Log.d(TAG, "DIAGNOSTIC: $message")
        }
    }

    /**
     * Log system information
     */
    fun logSystemInfo() {
        if (isDebugMode.get()) {
            Log.d(TAG, "System Info:")
            Log.d(TAG, "  Android Version: ${android.os.Build.VERSION.SDK_INT}")
            Log.d(TAG, "  Device Model: ${android.os.Build.MODEL}")
            Log.d(TAG, "  Device Manufacturer: ${android.os.Build.MANUFACTURER}")
            Log.d(TAG, "  CPU Architecture: ${android.os.Build.CPU_ABI}")
        }
    }
}