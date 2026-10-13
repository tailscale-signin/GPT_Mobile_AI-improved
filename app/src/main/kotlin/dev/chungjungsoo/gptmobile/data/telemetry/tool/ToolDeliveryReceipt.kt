package dev.chungjungsoo.gptmobile.data.telemetry.tool

import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Main-process receipt collector. Tool success is committed before IPC; delivery needs receiver acknowledgement. */
class ToolDeliveryReceipt : AbstractCoroutineContextElement(Key) {
    private val calls = ConcurrentHashMap<String, ToolStatisticsRepository>()
    fun pending(id: String, repository: ToolStatisticsRepository) {
        calls[id] = repository
    }
    suspend fun acknowledge() {
        calls.forEach { (id, repository) -> repository.delivered(id) }
        calls.clear()
    }
    companion object Key : CoroutineContext.Key<ToolDeliveryReceipt>
}
