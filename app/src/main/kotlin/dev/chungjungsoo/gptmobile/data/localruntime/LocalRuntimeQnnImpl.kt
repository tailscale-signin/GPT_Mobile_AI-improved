package dev.chungjungsoo.gptmobile.data.localruntime

import android.content.Context
import dev.chungjungsoo.gptmobile.BuildConfig
import java.io.File
import kotlinx.coroutines.flow.Flow

internal interface QnnLoadGuard {
    fun beforeLoad(spec: LocalEngineSpec)
    fun loadFinished()
}

/**
 * A native signal cannot be caught by Kotlin. Leave a marker only while QAIRT is
 * initializing or generating so the next process can quarantine the same crashing tuple.
 */
internal class QnnInitializationCrashGuard(context: Context) : QnnLoadGuard {
    private val app = context.applicationContext
    private val marker = File(app.noBackupFilesDir, "qnn_dispatch/native-init.marker")
    private val journal = NativeOperationJournal(File(app.noBackupFilesDir, "qnn_dispatch/operations"))

    override fun beforeLoad(spec: LocalEngineSpec) {
        val model = File(spec.modelPath)
        val signature = listOf(
            BuildConfig.LITERT_LM_VERSION,
            BuildConfig.QAIRT_VERSION,
            dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest.validateInstalled(model),
            android.os.Build.SOC_MODEL,
            android.os.Build.FINGERPRINT,
            spec.accelerator
        ).joinToString("|")
        if (marker.isFile) {
            // Migrate the previous marker, dropping app-install fields and paths.
            journal.quarantine(marker.readText().split('|').takeLast(8).take(6).joinToString("|"))
            check(marker.delete())
        }
        journal.before(signature)
    }

    override fun loadFinished() {
        journal.finished()
    }
}

/** Qualcomm NPU execution through LiteRT-LM's dispatch API. No hidden CPU/GPU fallback. */
internal class LocalRuntimeQnnImpl(
    private val runtime: LocalRuntime,
    private val loadGuard: QnnLoadGuard,
    private val probeEnvironment: () -> QnnEnvironment.QnnProbeStatus,
    private val enabled: Boolean = !BuildConfig.GENIEX_ENABLED
) : LocalRuntime {
    constructor(context: Context) : this(
        runtime = LocalRuntimeImpl(context),
        loadGuard = QnnInitializationCrashGuard(context),
        probeEnvironment = { QnnEnvironment.prepareForExecution(context) }
    )

    private var requestedSpec: LocalEngineSpec? = null
    private var dispatchedSpec: LocalEngineSpec? = null

    override suspend fun inspectModel(modelPath: String): LocalModelCapabilities? = runtime.inspectModel(modelPath)

    override val deviceRamGb: Long get() = runtime.deviceRamGb
    override fun getHardwareState(): DeviceHardwareState = runtime.getHardwareState()
    override fun getAdaptiveThrottlingPolicy(): AdaptiveThrottlingPolicy = runtime.getAdaptiveThrottlingPolicy()
    override fun loadedEngineSpec(): LocalEngineSpec? = runtime.loadedEngineSpec() ?: dispatchedSpec

    override suspend fun loadEngine(spec: LocalEngineSpec) {
        check(enabled) { "LiteRT/QNN is disabled in the GenieX preview APK to keep native QAIRT dependencies separate. Use the regular build for LiteRT NPU models." }
        check(LocalAccelerators.normalize(spec.accelerator) == LocalAccelerators.NPU) {
            "QNN requires the NPU accelerator and a matching SoC model. Use LiteRT-LM for CPU/GPU."
        }
        val probe = probeEnvironment()
        check(probe.isReady) { probe.errorMessage ?: "Qualcomm NPU libraries are unavailable on this device" }
        val effectiveSpec = spec.copy(
            accelerator = LocalAccelerators.NPU,
            litertDispatchLibDir = spec.litertDispatchLibDir ?: probe.dispatchDir
        )
        requestedSpec = null
        dispatchedSpec = null
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadGuard.beforeLoad(effectiveSpec) }
        try {
            runtime.loadEngine(effectiveSpec)
        } catch (error: Throwable) {
            loadGuard.loadFinished()
            throw error
        }
        loadGuard.loadFinished()
        requestedSpec = spec
        dispatchedSpec = effectiveSpec
    }

    override suspend fun isEngineLoaded(spec: LocalEngineSpec): Boolean =
        requestedSpec == spec && dispatchedSpec?.let { runtime.isEngineLoaded(it) } == true

    override suspend fun createConversation(config: LocalConversationConfig) {
        val spec = checkNotNull(dispatchedSpec) { "NPU model is not loaded" }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadGuard.beforeLoad(spec) }
        try {
            runtime.createConversation(config)
        } finally {
            loadGuard.loadFinished()
        }
    }
    override fun sendMessage(text: String, images: List<ByteArray>): Flow<LocalRuntimeEvent> = kotlinx.coroutines.flow.flow {
        val spec = checkNotNull(dispatchedSpec) { "NPU model is not loaded" }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadGuard.beforeLoad(spec) }
        try {
            runtime.sendMessage(text, images).collect { emit(it) }
        } finally {
            // A native process abort cannot execute this. A normal error/cancellation can.
            loadGuard.loadFinished()
        }
    }
    override fun cancelActive() = runtime.cancelActive()
    override fun hasOpenConversation(): Boolean = runtime.hasOpenConversation()
    override suspend fun closeConversation() = runtime.closeConversation()

    override suspend fun unloadEngine() {
        requestedSpec = null
        dispatchedSpec = null
        runtime.unloadEngine()
    }
}
