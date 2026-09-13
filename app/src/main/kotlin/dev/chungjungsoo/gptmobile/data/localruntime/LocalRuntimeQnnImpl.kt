package dev.chungjungsoo.gptmobile.data.localruntime

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Qualcomm QNN (Qualcomm Neural Network) runtime implementation.
 *
 * Designed specifically for Snapdragon chips (such as Snapdragon 8 Gen 3 / 8 Elite / Adreno / Hexagon NPU).
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
        // Sequentially probe Qualcomm QNN & LiteRT Qualcomm delegate shared libraries
        isQnnNativeAvailable = try {
            val isQualcommDevice = isQualcommPlatform()
            // Load base QNN libraries in dependency order if present
            val qnnLibs = listOf(
                "QnnSystem",
                "QnnIr",
                "QnnSaver",
                "QnnHtpV79Stub",
                "QnnHtp",
                "LiteRtCompilerPlugin_Qualcomm",
                "LiteRtDispatch_Qualcomm"
            )
            var loadedAny = false
            for (lib in qnnLibs) {
                try {
                    System.loadLibrary(lib)
                    loadedAny = true
                    Log.d(TAG, "Loaded native library: $lib")
                } catch (t: UnsatisfiedLinkError) {
                    Log.d(TAG, "Optional or dependent lib $lib not loaded directly: ${t.message}")
                }
            }

            // Verify Qualcomm runtime readiness: either QnnHtp or LiteRtDispatch_Qualcomm loaded,
            // or on Qualcomm hardware with QnnDelegate class present
            val htpOrDispatchLoaded = try {
                System.loadLibrary("LiteRtDispatch_Qualcomm")
                true
            } catch (t: UnsatisfiedLinkError) {
                try {
                    System.loadLibrary("QnnHtp")
                    true
                } catch (t2: UnsatisfiedLinkError) {
                    false
                }
            }

            val ready = htpOrDispatchLoaded || (isQualcommDevice && hasQnnDelegateClass())
            if (ready) {
                Log.i(TAG, "Qualcomm QNN HTP runtime verified ready.")
            } else {
                Log.i(TAG, "Qualcomm QNN HTP runtime not available on this platform.")
            }
            ready
        } catch (t: Throwable) {
            Log.i(TAG, "Qualcomm QNN probe: ${t.message}; using fallback integration.")
            false
        }
    }

    private fun isQualcommPlatform(): Boolean {
        val manufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER
        } else {
            ""
        }
        val hardware = Build.HARDWARE
        return manufacturer.contains("qualcomm", ignoreCase = true) ||
            hardware.contains("qcom", ignoreCase = true)
    }

    private fun hasQnnDelegateClass(): Boolean = try {
        Class.forName("com.qualcomm.qti.QnnDelegate")
        true
    } catch (t: Throwable) {
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

            // Determine target accelerator: respect explicit CPU/GPU selections, and only target
            // Hexagon NPU when requested or when auto-selecting with native QNN available.
            val normalizedRequested = LocalAccelerators.normalize(spec.accelerator)
            val targetAccelerator = when {
                normalizedRequested == LocalAccelerators.CPU || normalizedRequested == LocalAccelerators.GPU -> {
                    Log.i(TAG, "Caller explicitly requested accelerator $normalizedRequested; honoring selection.")
                    normalizedRequested
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
