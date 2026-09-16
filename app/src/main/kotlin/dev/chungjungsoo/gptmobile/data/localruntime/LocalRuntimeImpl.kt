package dev.chungjungsoo.gptmobile.data.localruntime

import kotlinx.coroutines.flow.Flow

class LocalRuntimeImpl(
    private val context: android.content.Context
) : LocalRuntime {
    
    override val deviceRamGb: Long = 8L

    override fun getHardwareState(): DeviceHardwareState {
        return DeviceHardwareState()
    }

    override fun getAdaptiveThrottlingPolicy(): AdaptiveThrottlingPolicy {
        return AdaptiveThrottlingPolicy(
            streamPublishIntervalMillis = 33L,
            topKReductionRatio = 1.0f
        )
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