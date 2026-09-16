package com.example.gpt_mobile_ai.nn

import com.example.gpt_mobile_ai.diagnostic.DebugUtils

/**
 * Interface for local runtime implementations (QNN, LiteRT, etc.)
 */
interface LocalRuntime {
    /**
     * Initialize the runtime
     */
    suspend fun initialize(): Boolean

    /**
     * Execute inference using this runtime
     */
    suspend fun executeInference(input: ByteArray): ByteArray

    /**
     * Clean up runtime resources
     */
    fun cleanup()

    /**
     * Check if runtime is available
     */
    fun isAvailable(): Boolean

    /**
     * Get performance metrics for this runtime
     */
    fun getPerformanceMetrics(): Map<String, Any>
    
    /**
     * Debug method to log runtime information
     */
    fun logRuntimeInfo() {
        DebugUtils.logQnnDebugInfo("Runtime: ${this.javaClass.simpleName}")
        DebugUtils.logQnnDebugInfo("Available: ${isAvailable()}")
        DebugUtils.logQnnDebugInfo("Metrics: ${getPerformanceMetrics()}")
    }
}