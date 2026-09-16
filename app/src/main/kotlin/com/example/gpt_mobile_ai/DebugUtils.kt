package com.example.gpt_mobile_ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Build

/**
 * Utility class for debugging and logging in the GPT Mobile AI application
 */
object DebugUtils {
    private val TAG = "DebugUtils"
    private val isDebugMode = BuildConfig.DEBUG
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    /**
     * Logs debug information with timestamp
     */
    fun logDebug(message: String) {
        if (isDebugMode) {
            val timestamp = System.currentTimeMillis()
            Log.d(TAG, "[$timestamp] $message")
        }
    }

    /**
     * Logs debug information with detailed context
     */
    fun logDebug(message: String, context: String) {
        if (isDebugMode) {
            val timestamp = System.currentTimeMillis()
            Log.d(TAG, "[$timestamp] [$context] $message")
        }
    }

    /**
     * Logs error information with stack trace
     */
    fun logError(message: String, throwable: Throwable? = null) {
        if (isDebugMode) {
            val timestamp = System.currentTimeMillis()
            Log.e(TAG, "[$timestamp] $message", throwable)
        }
    }

    /**
     * Logs warning information
     */
    fun logWarning(message: String) {
        if (isDebugMode) {
            val timestamp = System.currentTimeMillis()
            Log.w(TAG, "[$timestamp] $message")
        }
    }

    /**
     * Logs information with timestamp
     */
    fun logInfo(message: String) {
        if (isDebugMode) {
            val timestamp = System.currentTimeMillis()
            Log.i(TAG, "[$timestamp] $message")
        }
    }

    /**
     * Measures execution time of a block of code
     */
    fun <T> measureExecutionTime(block: () -> T): Pair<T, Long> {
        val startTime = System.currentTimeMillis()
        val result = block()
        val endTime = System.currentTimeMillis()
        val executionTime = endTime - startTime
        
        if (isDebugMode) {
            Log.d(TAG, "Execution time: $executionTime ms")
        }
        
        return Pair(result, executionTime)
    }

    /**
     * Checks if debug mode is enabled
     */
    fun isDebugModeEnabled(): Boolean {
        return isDebugMode
    }

    /**
     * Verifies that a condition is true, logs error if not
     */
    fun verifyCondition(condition: Boolean, message: String): Boolean {
        if (!condition) {
            Log.e(TAG, "Verification failed: $message")
            return false
        }
        return true
    }

    /**
     * Logs runtime information
     */
    fun logRuntimeInfo() {
        if (isDebugMode) {
            Log.d(TAG, "Runtime Info:")
            Log.d(TAG, "  Thread: ${Thread.currentThread().name}")
            Log.d(TAG, "  Memory: ${Runtime.getRuntime().totalMemory() / (1024 * 1024)} MB")
            Log.d(TAG, "  Available processors: ${Runtime.getRuntime().availableProcessors()}")
        }
    }
}