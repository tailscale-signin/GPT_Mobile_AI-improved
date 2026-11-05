package dev.chungjungsoo.gptmobile.data.llama

import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.util.concurrent.atomic.AtomicBoolean

/** Actual assistant stream progress owns an activity lease; cancellation and EOF always release it. */
internal object LlamaGenerationActivity {
    data class Snapshot(val count: Int, val requestId: String?)
    private data class Active(val key: String, val requestId: String?)
    private var sequence = 0L
    private val streams = mutableMapOf<Long, Active>()

    @Synchronized
    fun started(config: ProviderRequestConfig, model: String, requestId: String?): AutoCloseable? {
        if (config.providerType != "LLAMA" || streams.size >= 64) return null
        val id = ++sequence
        streams[id] = Active(key(config, model), requestId?.takeUnless { it == "unavailable" })
        val released = AtomicBoolean()
        return AutoCloseable {
            if (released.compareAndSet(false, true)) synchronized(this) { streams.remove(id) }
        }
    }

    @Synchronized
    fun snapshot(config: ProviderRequestConfig, model: String): Snapshot {
        val active = streams.values.filter { it.key == key(config, model) }
        return Snapshot(active.size, active.lastOrNull()?.requestId)
    }

    private fun key(config: ProviderRequestConfig, model: String) = researchHash(
        "${config.apiUrl.trim().trimEnd('/')}|${researchHash(config.token.orEmpty())}|$model|${LocalServiceHealth.networkEpoch}"
    )
}
