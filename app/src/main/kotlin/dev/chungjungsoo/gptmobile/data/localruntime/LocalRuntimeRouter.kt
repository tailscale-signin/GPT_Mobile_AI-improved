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

    private suspend fun getActiveBackend(): LocalRuntimeBackend {
        return try {
            settingRepository.getLocalRuntimeBackend()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed reading runtime backend preference, falling back to QUALCOMM_QNN", t)
            LocalRuntimeBackend.QUALCOMM_QNN
        }
    }

    private suspend fun getActiveRuntime(): LocalRuntime {
        return when (getActiveBackend()) {
            LocalRuntimeBackend.QUALCOMM_QNN -> qnnRuntime
            LocalRuntimeBackend.LITERT_LM -> liteRtRuntime
        }
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

    override suspend fun sendMessage(prompt: String, images: List<ByteArray>): Flow<String> {
        return getActiveRuntime().sendMessage(prompt, images)
    }

    override fun close() {
        qnnRuntime.close()
        liteRtRuntime.close()
    }

    companion object {
        private const val TAG = "LocalRuntimeRouter"
    }
}
