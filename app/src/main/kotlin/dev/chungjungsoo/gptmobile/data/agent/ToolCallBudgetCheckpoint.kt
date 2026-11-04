package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A protected per-request allowance. Reserve before dispatch; an uncertain slot stays spent. */
class ToolCallBudgetCheckpoint(
    scope: String,
    private val read: suspend () -> JsonObject?,
    private val write: suspend (JsonObject) -> Unit,
    private val hasRecord: suspend () -> Boolean = { false }
) {
    private val lock = locks[(scope.hashCode() and Int.MAX_VALUE) % locks.size]

    suspend fun initialize(limit: Int, explicitRenewalId: String? = null, previouslyDispatched: Int = 0): Int = lock.withLock {
        require(explicitRenewalId == null || explicitRenewalId.length <= 256)
        val previous = read()
        check(previous != null || !hasRecord()) { "The existing tool allowance is unreadable; it cannot be reset automatically." }
        val renewed = explicitRenewalId != null && previous?.get("renewal") != JsonPrimitive(explicitRenewalId)
        val state = if (previous == null || renewed) {
            buildJsonObject {
                put("version", 1)
                put("limit", limit.coerceAtLeast(0))
                put("used", if (renewed) 0 else previouslyDispatched.coerceAtLeast(0))
                explicitRenewalId?.let { put("renewal", it) }
            }.also { write(it) }
        } else {
            previous
        }
        used(state)
    }

    suspend fun acquire(limit: Int): Int? = lock.withLock {
        val state = checkNotNull(read()) { "Tool budget checkpoint is unavailable; no action may be dispatched." }
        val used = used(state)
        val accepted = requireNotNull((state["limit"] as? JsonPrimitive)?.intOrNull).also { require(it >= 0) }
        if (used >= minOf(accepted, limit.coerceAtLeast(0))) return@withLock null
        val next = used + 1
        write(JsonObject(state + ("used" to JsonPrimitive(next))))
        next
    }

    private fun used(state: JsonObject): Int {
        require(state["version"] == JsonPrimitive(1)) { "Unsupported tool budget checkpoint." }
        return requireNotNull((state["used"] as? JsonPrimitive)?.intOrNull).also { require(it >= 0) }
    }

    companion object {
        private val locks = Array(64) { Mutex() }
    }
}
