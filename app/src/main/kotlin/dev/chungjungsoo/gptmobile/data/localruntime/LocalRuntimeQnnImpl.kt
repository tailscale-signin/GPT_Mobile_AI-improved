package dev.chungjungsoo.gptmobile.data.localruntime

import android.content.Context
import android.util.Log
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Qualcomm QNN (Qualcomm Neural Network) runtime implementation.
 *
 * Designed specifically for Snapdragon chips (such as Snapdragon 8 Gen 3 / 8 Elite / Hexagon NPU).
 * Leverages direct Hexagon Tensor Processor (HTP) execution for low-power, high-throughput weights,
 * passing the native dispatch library directory to LiteRT-LM's Qualcomm backend.
 */
class LocalRuntimeQnnImpl(
    val context: Context,
    private val fallbackLiteRtRuntime: LocalRuntimeImpl = LocalRuntimeImpl(context)
) : LocalRuntime {

    override val deviceRamGb: Long by lazy {
        fallbackLiteRtRuntime.deviceRamGb
    }

    private var isQnnNativeAvailable = false
    private var loadedSpec: LocalEngineSpec? = null
    private var qnnInitializationAttempts = 0

    init {
        // Ensure QNN environment (ADSP_LIBRARY_PATH and LD_LIBRARY_PATH) is set up FIRST
        val probe = QnnEnvironment.initialize(context)

        // Verify Qualcomm runtime readiness via library probe and hardware checks
        isQnnNativeAvailable = try {
            val isQualcommDevice = probe.isQualcommDevice || QnnEnvironment.isQualcommPlatform()
            val ready = probe.isReady || (isQualcommDevice && hasQnnDelegateClass())
            if (ready) {
                Log.i(TAG, "Qualcomm QNN HTP runtime verified ready. Dispatch dir: ${probe.dispatchDir}")
            } else {
                Log.i(TAG, "Qualcomm QNN HTP runtime not available on this platform: ${probe.errorMessage}")
            }
            ready
        } catch (t: Throwable) {
            Log.e(TAG, "Qualcomm QNN probe failed: ${t.message}", t)
            false
        }
    }

    private fun hasQnnDelegateClass(): Boolean = try {
        Class.forName("com.qualcomm.qti.QnnDelegate")
        true
    } catch (t: Throwable) {
        Log.d(TAG, "QnnDelegate class not found: ${t.message}")
        false
    }

    override fun getHardwareState(): DeviceHardwareState =
        fallbackLiteRtRuntime.getHardwareState()

    override fun getAdaptiveThrottlingPolicy(): AdaptiveThrottlingPolicy =
        fallbackLiteRtRuntime.getAdaptiveThrottlingPolicy()

    override suspend fun loadEngine(spec: LocalEngineSpec) {
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Initializing Qualcomm QNN engine for: ${spec.modelPath}")

            when (val validation = LocalModelValidator.validate(spec.modelPath)) {
                is ModelValidationResult.Invalid -> {
                    Log.e(TAG, "Model validation failed for path '${spec.modelPath}': ${validation.reason}")
                    if (validation.reason == ModelValidationResult.Invalid.Reason.NOT_FOUND) {
                        throw FileNotFoundException("Model file not found at path: ${spec.modelPath}")
                    } else {
                        throw IllegalStateException("Model file validation failed (${validation.reason}): ${validation.details}")
                    }
                }
                is ModelValidationResult.Valid -> {
                    Log.i(TAG, "QNN verified model weights: ${validation.file.name}")
                }
            }

            // Target Hexagon NPU unless caller explicitly requested CPU.
            // If caller requested GPU or NPU under QNN backend, prioritize Qualcomm Hexagon NPU.
            val normalizedRequested = LocalAccelerators.normalize(spec.accelerator)
            val targetAccelerator = when {
                normalizedRequested == LocalAccelerators.CPU -> {
                    Log.i(TAG, "Caller explicitly requested CPU; honoring selection.")
                    LocalAccelerators.CPU
                }
                isQnnNativeAvailable -> {
                    Log.i(TAG, "Deploying model graph to Qualcomm Hexagon NPU backend via QNN.")
                    LocalAccelerators.NPU
                }
                else -> {
                    Log.i(TAG, "QNN native library not present; deploying with user accelerator ${spec.accelerator}.")
                    spec.accelerator
                }
            }

            val dispatchDir = QnnEnvironment.getDispatchDir(context)
            val engineConfig = if (targetAccelerator == LocalAccelerators.NPU) {
                spec.copy(
                    accelerator = targetAccelerator,
                    litertDispatchLibDir = spec.litertDispatchLibDir ?: dispatchDir
                )
            } else {
                spec.copy(accelerator = targetAccelerator)
            }

            try {
                fallbackLiteRtRuntime.loadEngine(engineConfig)
                loadedSpec = spec
                Log.i(TAG, "Successfully loaded engine with QNN backend")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load engine with QNN backend, falling back to LiteRT", e)
                // Fallback to LiteRT implementation
                fallbackLiteRtRuntime.loadEngine(spec)
                loadedSpec = spec
                throw e // Re-throw to indicate fallback occurred
            }
        }
    }

    override suspend fun createConversation(config: LocalConversationConfig) {
        fallbackLiteRtRuntime.createConversation(config)
    }

    override fun sendMessage(text: String, images: List<ByteArray>): Flow<LocalRuntimeEvent> =
        fallbackLiteRtRuntime.sendMessage(text, images)

    override fun cancelActive() {
        fallbackLiteRtRuntime.cancelActive()
    }

    override fun hasOpenConversation(): Boolean =
        fallbackLiteRtRuntime.hasOpenConversation()

    override fun isEngineLoaded(spec: LocalEngineSpec): Boolean =
        fallbackLiteRtRuntime.isEngineLoaded(spec)

    override suspend fun closeConversation() {
        fallbackLiteRtRuntime.closeConversation()
    }

    override suspend fun unloadEngine() {
        fallbackLiteRtRuntime.unloadEngine()
        loadedSpec = null
    }

    override suspend fun unloadIfIdle(idleThresholdMs: Long): Boolean =
        fallbackLiteRtRuntime.unloadIfIdle(idleThresholdMs)

    companion object {
        private const val TAG = "LocalRuntimeQnnImpl"
    }
}
