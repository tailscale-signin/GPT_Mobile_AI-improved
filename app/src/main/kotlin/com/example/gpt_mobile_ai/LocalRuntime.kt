package com.example.gpt_mobile_ai

import kotlinx.coroutines.Deferred

/**
 * Interface for local runtime implementations (QNN, LiteRT, etc.)
 */
interface LocalRuntime {
    /**
     * Initializes the runtime
     */
    fun initialize(): Boolean

    /**
     * Loads a model for inference
     */
    suspend fun loadModel(modelPath: String): Boolean

    /**
     * Performs inference on a model
     */
    suspend fun runInference(input: Map<String, Any>): Map<String, Any>

    /**
     * Unloads a model
     */
    suspend fun unloadModel(modelPath: String): Boolean

    /**
     * Gets the runtime type
     */
    fun getRuntimeType(): String

    /**
     * Gets the runtime capabilities
     */
    fun getCapabilities(): Set<String>

    /**
     * Gets the runtime status
     */
    fun getStatus(): Map<String, Any>

    /**
     * Cleans up resources
     */
    fun cleanup()

    /**
     * Performs a health check on the runtime
     */
    fun healthCheck(): Map<String, Any>
}