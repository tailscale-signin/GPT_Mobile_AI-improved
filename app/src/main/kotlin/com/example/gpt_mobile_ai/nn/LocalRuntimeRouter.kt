package com.example.gpt_mobile_ai.nn

import android.content.Context
import android.util.Log
import com.example.gpt_mobile_ai.nn.LocalRuntime
import com.example.gpt_mobile_ai.nn.LocalRuntimeQnnImpl
import com.example.gpt_mobile_ai.nn.LocalRuntimeLiteRtImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Routes between different local runtime implementations (QNN, LiteRT, etc.)
 * with automatic fallback mechanisms
 */
class LocalRuntimeRouter(private val context: Context) : LocalRuntime {
    companion object {
        private const val TAG = "LocalRuntimeRouter"
        private val isInitialized = AtomicBoolean(false)
    }

    private var qnnRuntime: LocalRuntimeQnnImpl? = null
    private var liteRtRuntime: LocalRuntimeLiteRtImpl? = null
    private var currentRuntime: LocalRuntime? = null

    /**
     * Initialize the runtime router with all available runtimes
     */
    override suspend fun initialize(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (isInitialized.get()) {
                    Log.d(TAG, "Local runtime router already initialized")
                    return@withContext true
                }

                Log.d(TAG, "Initializing local runtime router")

                // Initialize QNN runtime
                val qnnEnvironment = QnnEnvironment(context)
                qnnRuntime = LocalRuntimeQnnImpl(context, qnnEnvironment)
                val qnnInitialized = qnnRuntime?.initialize() ?: false

                // Initialize LiteRT runtime as fallback
                liteRtRuntime = LocalRuntimeLiteRtImpl(context)
                val liteRtInitialized = liteRtRuntime?.initialize() ?: false

                // Determine which runtime to use
                val selectedRuntime = selectRuntime(qnnInitialized, liteRtInitialized)
                if (selectedRuntime != null) {
                    currentRuntime = selectedRuntime
                    isInitialized.set(true)
                    Log.d(TAG, "Local runtime router initialized with ${selectedRuntime.javaClass.simpleName}")
                } else {
                    Log.e(TAG, "Failed to initialize any local runtime")
                    return@withContext false
                }

                true
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing local runtime router", e)
                false
            }
        }
    }

    /**
     * Select the appropriate runtime based on availability and performance
     */
    private fun selectRuntime(qnnInitialized: Boolean, liteRtInitialized: Boolean): LocalRuntime? {
        try {
            // Prefer QNN if available and initialized
            if (qnnInitialized && qnnRuntime != null) {
                Log.d(TAG, "Selecting QNN runtime as primary")
                return qnnRuntime
            }

            // Fallback to LiteRT if QNN is not available
            if (liteRtInitialized && liteRtRuntime != null) {
                Log.d(TAG, "Selecting LiteRT runtime as fallback")
                return liteRtRuntime
            }

            Log.e(TAG, "No suitable runtime available")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error selecting runtime", e)
            return null
        }
    }

    /**
     * Execute inference using the selected runtime
     */
    override suspend fun executeInference(input: ByteArray): ByteArray {
        return withContext(Dispatchers.IO) {
            try {
                if (!isInitialized.get()) {
                    Log.e(TAG, "Local runtime router not initialized")
                    throw IllegalStateException("Local runtime router not initialized")
                }

                val runtime = currentRuntime
                if (runtime == null) {
                    Log.e(TAG, "No runtime selected for inference")
                    throw IllegalStateException("No runtime selected")
                }

                Log.d(TAG, "Executing inference using ${runtime.javaClass.simpleName}")
                runtime.executeInference(input)
            } catch (e: Exception) {
                Log.e(TAG, "Error during inference execution", e)
                
                // Attempt fallback to LiteRT if QNN fails
                if (currentRuntime === qnnRuntime && liteRtRuntime != null) {
                    Log.d(TAG, "Falling back to LiteRT due to QNN error")
                    try {
                        val result = liteRtRuntime?.executeInference(input)
                        if (result != null) {
                            currentRuntime = liteRtRuntime
                            return@withContext result
                        }
                    } catch (fallbackException: Exception) {
                        Log.e(TAG, "Fallback to LiteRT also failed", fallbackException)
                    }
                }
                
                throw e
            }
        }
    }

    /**
     * Clean up all runtime resources
     */
    override fun cleanup() {
        try {
            Log.d(TAG, "Cleaning up local runtime router")
            currentRuntime?.cleanup()
            qnnRuntime?.cleanup()
            liteRtRuntime?.cleanup()
            isInitialized.set(false)
            currentRuntime = null
            qnnRuntime = null
            liteRtRuntime = null
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    /**
     * Check if any runtime is available
     */
    override fun isAvailable(): Boolean {
        return isInitialized.get() && currentRuntime?.isAvailable() == true
    }

    /**
     * Get performance metrics for the current runtime
     */
    override fun getPerformanceMetrics(): Map<String, Any> {
        val metrics = mutableMapOf<String, Any>("router_initialized" to isInitialized.get())
        
        currentRuntime?.let { runtime ->
            metrics.putAll(runtime.getPerformanceMetrics())
        }
        
        return metrics
    }

    /**
     * Switch to a specific runtime (for debugging purposes)
     */
    fun switchToRuntime(runtimeType: RuntimeType): Boolean {
        try {
            when (runtimeType) {
                RuntimeType.QNN -> {
                    if (qnnRuntime != null && qnnRuntime?.initialize() == true) {
                        currentRuntime = qnnRuntime
                        Log.d(TAG, "Switched to QNN runtime")
                        return true
                    }
                }
                RuntimeType.LITE_RT -> {
                    if (liteRtRuntime != null && liteRtRuntime?.initialize() == true) {
                        currentRuntime = liteRtRuntime
                        Log.d(TAG, "Switched to LiteRT runtime")
                        return true
                    }
                }
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error switching runtime", e)
            return false
        }
    }

    /**
     * Runtime type enumeration
     */
    enum class RuntimeType {
        QNN,
        LITE_RT
    }
}