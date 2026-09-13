package dev.chungjungsoo.gptmobile.data.localruntime

import android.util.Log
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import kotlinx.coroutines.flow.Flow

/**
 * Composite [LocalRuntime] router that dynamically dispatches to either the
 * Qualcomm QNN runtime engine or the LiteRT-LM runtime engine according to user preference,
 * defaulting to Qualcomm QNN.
 */
class LocalRuntimeRouter(
    private val settingRepository: SettingRepository,
    private val qnnRuntime: LocalRuntime,
    private val liteRtRuntime: LocalRuntime
) : LocalRuntime {

    private suspend fun getActiveBackend(): LocalRuntimeBackend = try {
        settingRepository.getLocalRuntimeBackend()
    } catch (t: Throwable) {
        Log.w(TAG, "Failed reading runtime backend preference, falling back to QUALCOMM_QNN", t)
        LocalRuntimeBackend.QUALCOMM_QNN
    }

    private suspend fun getActiveRuntime(): LocalRuntime = when (getActiveBackend()) {
        LocalRuntimeBackend.QUALCOMM_QNN -> qnnRuntime
        LocalRuntimeBackend.LITERT_LM -> liteRtRuntime
    }

    override val deviceRamGb: Long
        get() = qnnRuntime.deviceRamGb

    override fun getHardwareState(): DeviceHardwareState =
        qnnRuntime.getHardwareState()

    override fun getAdaptiveThrottlingPolicy(): AdaptiveThrottlingPolicy =
        qnnRuntime.getAdaptiveThrottlingPolicy()

    override suspend fun loadEngine(spec: LocalEngineSpec) {
        val runtime = getActiveRuntime()
        Log.i(TAG, "Loading engine using backend: ${getActiveBackend().name}")
        runtime.loadEngine(spec)
    }

    override suspend fun createConversation(config: LocalConversationConfig) {
        getActiveRuntime().createConversation(config)
    }

    override fun sendMessage(text: String, images: List<ByteArray>): Flow<LocalRuntimeEvent> =
        if (qnnRuntime.hasOpenConversation()) {
            qnnRuntime.sendMessage(text, images)
        } else {
            liteRtRuntime.sendMessage(text, images)
        }

    override fun cancelActive() {
        qnnRuntime.cancelActive()
        liteRtRuntime.cancelActive()
    }

    override fun hasOpenConversation(): Boolean =
        qnnRuntime.hasOpenConversation() || liteRtRuntime.hasOpenConversation()

    override fun isEngineLoaded(spec: LocalEngineSpec): Boolean =
        qnnRuntime.isEngineLoaded(spec) || liteRtRuntime.isEngineLoaded(spec)

    override suspend fun closeConversation() {
        qnnRuntime.closeConversation()
        liteRtRuntime.closeConversation()
    }

    override suspend fun unloadEngine() {
        qnnRuntime.unloadEngine()
        liteRtRuntime.unloadEngine()
    }

    override suspend fun unloadIfIdle(idleThresholdMs: Long): Boolean {
        val qnnUnloaded = qnnRuntime.unloadIfIdle(idleThresholdMs)
        val liteRtUnloaded = liteRtRuntime.unloadIfIdle(idleThresholdMs)
        return qnnUnloaded || liteRtUnloaded
    }

    companion object {
        private const val TAG = "LocalRuntimeRouter"
    }
}
