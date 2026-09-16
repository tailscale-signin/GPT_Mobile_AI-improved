package dev.chungjungsoo.gptmobile.data.localruntime

import android.util.Log
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Composite [LocalRuntime] router that dynamically dispatches to either the
 * Qualcomm QNN runtime engine or the LiteRT-LM runtime engine according to user preference,
 * defaulting to Qualcomm QNN, and automatically falling back to LiteRT-LM if Qualcomm QNN fails.
 */
class LocalRuntimeRouter(
    private val settingRepository: SettingRepository,
    private val qnnRuntime: LocalRuntime,
    private val liteRtRuntime: LocalRuntime
) : LocalRuntime {

    @Volatile
    private var activeLoadedRuntime: LocalRuntime? = null

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
        get() = (activeLoadedRuntime ?: qnnRuntime).deviceRamGb

    override fun getHardwareState(): DeviceHardwareState =
        (activeLoadedRuntime ?: qnnRuntime).getHardwareState()

    override fun getAdaptiveThrottlingPolicy(): AdaptiveThrottlingPolicy =
        (activeLoadedRuntime ?: qnnRuntime).getAdaptiveThrottlingPolicy()

    override suspend fun loadEngine(spec: LocalEngineSpec) {
        val preferredBackend = getActiveBackend()
        Log.i(TAG, "Loading engine using preferred backend: ${preferredBackend.name}")

        when (preferredBackend) {
            LocalRuntimeBackend.QUALCOMM_QNN -> {
                try {
                    qnnRuntime.loadEngine(spec)
                    activeLoadedRuntime = qnnRuntime
                    Log.i(TAG, "Successfully loaded engine using QUALCOMM_QNN backend")
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (qnnError: Exception) {
                    Log.w(
                        TAG,
                        "Failed to load engine using QUALCOMM_QNN backend, falling back to LITERT_LM",
                        qnnError
                    )
                    // Verify that we can actually use the QNN environment before falling back
                    if (QnnEnvironment.verifyQnnLibraries((qnnRuntime as? LocalRuntimeQnnImpl)?.context ?: return)) {
                        // If QNN environment is actually available, we should try to use it
                        // but since we already failed, we'll fall back to LiteRT
                        Log.w(TAG, "QNN environment is available but engine failed to load, falling back to LiteRT")
                    }
                    liteRtRuntime.loadEngine(spec)
                    activeLoadedRuntime = liteRtRuntime
                }
            }
            LocalRuntimeBackend.LITERT_LM -> {
                liteRtRuntime.loadEngine(spec)
                activeLoadedRuntime = liteRtRuntime
            }
        }
    }

    override suspend fun createConversation(config: LocalConversationConfig) {
        val runtime = activeLoadedRuntime ?: getActiveRuntime()
        runtime.createConversation(config)
    }

    override fun sendMessage(text: String, images: List<ByteArray>): Flow<LocalRuntimeEvent> {
        val runtime = when {
            qnnRuntime.hasOpenConversation() -> qnnRuntime
            liteRtRuntime.hasOpenConversation() -> liteRtRuntime
            activeLoadedRuntime != null -> activeLoadedRuntime!!
            else -> qnnRuntime
        }
        return runtime.sendMessage(text, images)
    }

    override fun cancelActive() {
        qnnRuntime.cancelActive()
        liteRtRuntime.cancelActive()
    }

    override fun hasOpenConversation(): Boolean =
        qnnRuntime.hasOpenConversation() || liteRtRuntime.hasOpenConversation()

    override fun isEngineLoaded(spec: LocalEngineSpec): Boolean =
        activeLoadedRuntime?.isEngineLoaded(spec)
            ?: (qnnRuntime.isEngineLoaded(spec) || liteRtRuntime.isEngineLoaded(spec))

    override suspend fun closeConversation() {
        qnnRuntime.closeConversation()
        liteRtRuntime.closeConversation()
    }

    override suspend fun unloadEngine() {
        activeLoadedRuntime = null
        qnnRuntime.unloadEngine()
        liteRtRuntime.unloadEngine()
    }

    override suspend fun unloadIfIdle(idleThresholdMs: Long): Boolean {
        val qnnUnloaded = qnnRuntime.unloadIfIdle(idleThresholdMs)
        val liteRtUnloaded = liteRtRuntime.unloadIfIdle(idleThresholdMs)
        if (qnnUnloaded && liteRtUnloaded) {
            activeLoadedRuntime = null
        }
        return qnnUnloaded || liteRtUnloaded
    }

    companion object {
        private const val TAG = "LocalRuntimeRouter"
    }
}