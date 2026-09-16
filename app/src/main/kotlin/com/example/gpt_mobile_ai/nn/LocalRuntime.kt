package com.example.gpt_mobile_ai.nn

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
}