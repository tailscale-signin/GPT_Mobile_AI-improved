package dev.chungjungsoo.gptmobile.data.localruntime

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Qualcomm QNN (Qualcomm Neural Network) runtime implementation.
 *
 * Designed specifically for Snapdragon chips (such as Snapdragon 8 Elite / Oryon / Adreno / Hexagon NPU).
 * Leverages direct Hexagon Tensor Processor (HTP) execution for low-power, high-throughput INT4/INT8
 * weights, passing the native dispatch library directory to LiteRT-LM's Qualcomm backend.
 */
class LocalRuntimeQnnImpl(
    private val context: Context,
    private val fallbackLiteRtRuntime: LocalRuntimeImpl = LocalRuntimeImpl(context)
) : LocalRuntime {

    private val activityManager: ActivityManager? by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    }

    override val deviceRamGb: Long by lazy {
        fallbackLiteRtRuntime.deviceRamGb
    }

    private var isQnnNativeAvailable = false
    private var loadedSpec: LocalEngineSpec? = null

    init {
        // Probe Qualcomm dispatch libraries, QNN HTP shared libraries, or native directory
        isQnnNativeAvailable = checkQnnAvailability()
    }

    private fun checkQnnAvailability(): Boolean {
        // 1. First probe if LiteRtDispatch_Qualcomm or QnnHtp can be loaded via standard classloader
        val dispatchLoaded = try {
            System.loadLibrary("LiteRtDispatch_Qualcomm")
            Log.i(TAG, "LiteRtDispatch_Qualcomm loaded successfully.")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }

        val qnnHtpLoaded = try {
            System.loadLibrary("QnnHtp")
            Log.i(TAG, "libQnnHtp.so loaded successfully.")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }

        if (dispatchLoaded || qnnHtpLoaded) {
            return true
        }

        // 2. Check if the libraries exist in the extracted nativeLibraryDir (useLegacyPackaging = true)
        val nativeDir = runCatching { File(context.applicationInfo.nativeLibraryDir) }.getOrNull()
        if (nativeDir != null && nativeDir.isDirectory) {
            val hasDispatch = File(nativeDir, "libLiteRtDispatch_Qualcomm.so").exists()
            val hasHtp = File(nativeDir, "libQnnHtp.so").exists()
            if (hasDispatch || hasHtp) {
                Log.i(TAG, "Found QNN/LiteRT dispatch libraries in nativeLibraryDir: ${nativeDir.absolutePath}")
                return true
            }
        }

        // 3. Check for Qualcomm QnnDelegate
        return try {
            Class.forName("com.qualcomm.qti.QnnDelegate")
            Log.i(TAG, "Qualcomm QnnDelegate class available.")
            true
        } catch (t: Throwable) {
            Log.i(TAG, "Qualcomm QNN dispatch runtime not present in app namespace; fallback to specified accelerator.")
            false
        }
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

            // When QNN dispatch is present, direct to NPU accelerator and pass nativeLibraryDir
            val targetAccelerator = if (isQnnNativeAvailable) {
                Log.i(TAG, "Deploying model graph to Qualcomm Hexagon NPU backend via QNN.")
                LocalAccelerators.NPU
            } else {
                Log.i(TAG, "QNN native dispatch not present; deploying with user accelerator ${spec.accelerator}.")
                spec.accelerator
            }

            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val engineConfig = if (targetAccelerator == LocalAccelerators.NPU) {
                spec.copy(
                    accelerator = targetAccelerator,
                    litertDispatchLibDir = spec.litertDispatchLibDir ?: nativeLibDir
                )
            } else {
                spec.copy(accelerator = targetAccelerator)
            }

            fallbackLiteRtRuntime.loadEngine(engineConfig)
            loadedSpec = spec
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
