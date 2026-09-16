package dev.chungjungsoo.gptmobile.data.localruntime

import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import kotlinx.coroutines.flow.Flow

class LocalRuntimeRouter(
    private val settingRepository: SettingRepository,
    private val qnnRuntime: LocalRuntime,
    private val liteRtRuntime: LocalRuntime
) : LocalRuntime {

    override val deviceRamGb: Long
        get() = qnnRuntime.deviceRamGb

    override fun getHardwareState(): DeviceHardwareState {
        return qnnRuntime.getHardwareState()
    }

    override fun getAdaptiveThrottlingPolicy(): AdaptiveThrottlingPolicy {
        return qnnRuntime.getAdaptiveThrottlingPolicy()
    }

    override suspend fun loadEngine(spec: LocalEngineSpec) {
        // Implementation would go here
    }

    override suspend fun createConversation(config: LocalConversationConfig) {
        // Implementation would go here
    }

    override fun sendMessage(text: String, images: List<ByteArray>): Flow<LocalRuntimeEvent> {
        // Implementation would go here
        TODO("Not yet implemented")
    }

    override fun cancelActive() {
        // Implementation would go here
    }

    override fun hasOpenConversation(): Boolean {
        return false
    }

    override fun isEngineLoaded(spec: LocalEngineSpec): Boolean {
        return false
    }

    override suspend fun closeConversation() {
        // Implementation would go here
    }

    override suspend fun unloadEngine() {
        // Implementation would go here
    }

    override suspend fun unloadIfIdle(idleThresholdMs: Long): Boolean {
        return false
    }
}