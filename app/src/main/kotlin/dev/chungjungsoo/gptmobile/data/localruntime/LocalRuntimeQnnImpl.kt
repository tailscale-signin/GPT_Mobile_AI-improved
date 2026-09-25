package dev.chungjungsoo.gptmobile.data.localruntime

import android.content.Context
import kotlinx.coroutines.flow.Flow

/** Qualcomm NPU execution through LiteRT-LM's dispatch API. No hidden CPU/GPU fallback. */
class LocalRuntimeQnnImpl(
    context: Context,
    private val runtime: LocalRuntime = LocalRuntimeImpl(context),
    private val probeEnvironment: () -> QnnEnvironment.QnnProbeStatus = { QnnEnvironment.getProbeStatus(context) }
) : LocalRuntime {
    private var requestedSpec: LocalEngineSpec? = null
    private var dispatchedSpec: LocalEngineSpec? = null

    override val deviceRamGb: Long get() = runtime.deviceRamGb
    override fun getHardwareState(): DeviceHardwareState = runtime.getHardwareState()
    override fun getAdaptiveThrottlingPolicy(): AdaptiveThrottlingPolicy = runtime.getAdaptiveThrottlingPolicy()
    override fun loadedEngineSpec(): LocalEngineSpec? = runtime.loadedEngineSpec() ?: dispatchedSpec

    override suspend fun loadEngine(spec: LocalEngineSpec) {
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
        runtime.loadEngine(effectiveSpec)
        requestedSpec = spec
        dispatchedSpec = effectiveSpec
    }

    override suspend fun isEngineLoaded(spec: LocalEngineSpec): Boolean =
        requestedSpec == spec && dispatchedSpec?.let { runtime.isEngineLoaded(it) } == true

    override suspend fun createConversation(config: LocalConversationConfig) = runtime.createConversation(config)
    override fun sendMessage(text: String, images: List<ByteArray>): Flow<LocalRuntimeEvent> = runtime.sendMessage(text, images)
    override fun cancelActive() = runtime.cancelActive()
    override fun hasOpenConversation(): Boolean = runtime.hasOpenConversation()
    override suspend fun closeConversation() = runtime.closeConversation()

    override suspend fun unloadEngine() {
        requestedSpec = null
        dispatchedSpec = null
        runtime.unloadEngine()
    }
}
