package com.example.gpt_mobile_ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File

/**
 * Routes between different local runtime implementations (QNN, LiteRT, etc.)
 */
class LocalRuntimeRouter(
    private val context: Context
) : LocalRuntime {
    private val TAG = "LocalRuntimeRouter"
    private val isInitialized = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var currentRuntime: LocalRuntime? = null
    private var qnnRuntime: LocalRuntimeQnnImpl? = null
    private var liteRtRuntime: LocalRuntimeLiteRtImpl? = null
    
    private val fallbackEnabled = true
    private val debugMode = true

    /**
     * Initializes the runtime router
     */
    override fun initialize(): Boolean {
        if (isInitialized.get()) {
            Log.d(TAG, "Runtime router already initialized")
            return true
        }

        return try {
            Log.d(TAG, "Initializing runtime router")
            
            // Initialize QNN runtime
            qnnRuntime = LocalRuntimeQnnImpl(context, QnnEnvironment(context))
            if (qnnRuntime?.initialize() == true) {
                Log.d(TAG, "QNN runtime initialized successfully")
                currentRuntime = qnnRuntime
            } else {
                Log.w(TAG, "QNN runtime initialization failed, falling back to LiteRT")
                // Initialize LiteRT as fallback
                liteRtRuntime = LocalRuntimeLiteRtImpl(context)
                if (liteRtRuntime?.initialize() == true) {
                    Log.d(TAG, "LiteRT runtime initialized successfully")
                    currentRuntime = liteRtRuntime
                } else {
                    Log.e(TAG, "Failed to initialize any runtime")
                    return false
                }
            }
            
            isInitialized.set(true)
            Log.d(TAG, "Runtime router initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize runtime router", e)
            false
        }
    }

    /**
     * Loads a model for inference
     */
    override suspend fun loadModel(modelPath: String): Boolean {
        if (!isInitialized.get()) {
            Log.e(TAG, "Runtime router not initialized")
            return false
        }

        return try {
            Log.d(TAG, "Loading model: $modelPath")
            val result = currentRuntime?.loadModel(modelPath) ?: false
            
            if (debugMode) {
                Log.d(TAG, "Model loading result: $result")
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: $modelPath", e)
            if (fallbackEnabled && currentRuntime is LocalRuntimeQnnImpl) {
                Log.w(TAG, "Falling back to LiteRT due to model loading error")
                switchToLiteRt()
                return loadModel(modelPath)
            }
            false
        }
    }

    /**
     * Performs inference on a model
     */
    override suspend fun runInference(input: Map<String, Any>): Map<String, Any> {
        if (!isInitialized.get()) {
            Log.e(TAG, "Runtime router not initialized")
            return emptyMap()
        }

        return try {
            Log.d(TAG, "Running inference with input: ${input.keys.joinToString()}")
            
            val result = currentRuntime?.runInference(input) ?: emptyMap()
            
            if (debugMode) {
                Log.d(TAG, "Inference completed with result size: ${result.size}")
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run inference", e)
            
            if (fallbackEnabled && currentRuntime is LocalRuntimeQnnImpl) {
                Log.w(TAG, "Falling back to LiteRT due to inference error")
                switchToLiteRt()
                return runInference(input)
            }
            
            emptyMap()
        }
    }

    /**
     * Unloads a model
     */
    override suspend fun unloadModel(modelPath: String): Boolean {
        if (!isInitialized.get()) {
            Log.d(TAG, "Runtime router not initialized, skipping unload")
            return true
        }

        return try {
            Log.d(TAG, "Unloading model: $modelPath")
            val result = currentRuntime?.unloadModel(modelPath) ?: false
            
            if (debugMode) {
                Log.d(TAG, "Model unloading result: $result")
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unload model: $modelPath", e)
            false
        }
    }

    /**
     * Gets the runtime type
     */
    override fun getRuntimeType(): String {
        return currentRuntime?.getRuntimeType() ?: "Unknown"
    }

    /**
     * Gets the runtime capabilities
     */
    override fun getCapabilities(): Set<String> {
        return currentRuntime?.getCapabilities() ?: emptySet()
    }

    /**
     * Gets the runtime status
     */
    override fun getStatus(): Map<String, Any> {
        val baseStatus = mapOf(
            "is_initialized" to isInitialized.get(),
            "current_runtime" to getRuntimeType(),
            "fallback_enabled" to fallbackEnabled,
            "debug_mode" to debugMode
        )
        
        val runtimeStatus = currentRuntime?.getStatus() ?: emptyMap()
        
        return baseStatus + runtimeStatus
    }

    /**
     * Cleans up resources
     */
    override fun cleanup() {
        if (isInitialized.get()) {
            Log.d(TAG, "Cleaning up runtime router")
            scope.cancel()
            
            currentRuntime?.cleanup()
            qnnRuntime?.cleanup()
            liteRtRuntime?.cleanup()
            
            currentRuntime = null
            qnnRuntime = null
            liteRtRuntime = null
            
            isInitialized.set(false)
        }
    }

    /**
     * Performs a health check on the current runtime
     */
    override fun healthCheck(): Map<String, Any> {
        return try {
            val status = getStatus()
            val capabilities = getCapabilities()
            
            mapOf(
                "status" to status,
                "capabilities" to capabilities,
                "is_healthy" to isInitialized.get(),
                "timestamp" to System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed", e)
            mapOf(
                "is_healthy" to false,
                "error" to e.message
            )
        }
    }

    /**
     * Switches to LiteRT runtime
     */
    private fun switchToLiteRt() {
        if (liteRtRuntime?.initialize() == true) {
            Log.d(TAG, "Switched to LiteRT runtime")
            currentRuntime = liteRtRuntime
        } else {
            Log.e(TAG, "Failed to switch to LiteRT runtime")
        }
    }
}