package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.ResearchUserLimits
import kotlinx.serialization.Serializable

/** Long accounting with absent limits represented explicitly, never by a numeric sentinel. */
class ResearchRunLedger(
    val fingerprint: String,
    val limits: ResearchUserLimits,
    private val persist: (State) -> Unit = {}
) {
    @Serializable
    data class State(
        val version: Int = 3,
        val fingerprint: String,
        val limits: ResearchUserLimits,
        val logical: Long = 0,
        val physical: Long = 0,
        val downloaded: Long = 0,
        val activeMillis: Long = 0,
        val limitReason: String? = null
    )
    private var state = State(fingerprint = fingerprint, limits = limits)
    private var durable = state
    private var activeUsers = 0
    private var lastTick: Long? = null

    @Synchronized fun beginActive(now: Long) {
        if (activeUsers++ == 0) lastTick = now
    }

    @Synchronized fun endActive(now: Long) {
        try {
            tick(now)
        } finally {
            activeUsers = (activeUsers - 1).coerceAtLeast(0)
            if (activeUsers == 0) lastTick = null
        }
    }

    @Synchronized fun tick(now: Long): Boolean {
        val previous = lastTick
        if (activeUsers == 0 || previous == null) return state.limitReason == null
        val elapsed = (now - previous).coerceAtLeast(0)
        val allowed = charge(activeMillis = elapsed)
        if (now > previous) lastTick = now
        return allowed
    }
    private fun reserve(value: Long, chunk: Long, limit: Long?): Long {
        val extra = (chunk - value % chunk) % chunk
        return minOf(if (value > Long.MAX_VALUE - extra) Long.MAX_VALUE else value + extra, limit ?: Long.MAX_VALUE)
    }

    init {
        limits.validate()
    }

    @Synchronized
    fun charge(logical: Long = 0, physical: Long = 0, downloaded: Long = 0, activeMillis: Long = 0): Boolean {
        require(listOf(logical, physical, downloaded, activeMillis).all { it >= 0 })
        if (state.limitReason != null) return false
        val next = state.copy(
            logical = Math.addExact(state.logical, logical),
            physical = Math.addExact(state.physical, physical),
            downloaded = Math.addExact(state.downloaded, downloaded),
            activeMillis = Math.addExact(state.activeMillis, activeMillis)
        )
        val reason = when {
            limits.maxRequests?.let { next.physical > it } == true -> "user_request_limit"
            limits.maxDownloadedBytes?.let { next.downloaded > it } == true -> "user_download_limit"
            limits.maxActiveMillis?.let { next.activeMillis > it } == true -> "user_time_limit"
            else -> null
        }
        if (reason != null) {
            val stopped = state.copy(limitReason = reason)
            persist(durable.copy(limitReason = reason))
            state = stopped
            return false
        }
        // Persist the reservation before dispatch; a crash cannot refund dispatched work.
        if (next.logical > durable.logical || next.physical > durable.physical || next.downloaded > durable.downloaded || next.activeMillis > durable.activeMillis) {
            val reservation = next.copy(
                downloaded = maxOf(durable.downloaded, reserve(next.downloaded, 256 * 1024, limits.maxDownloadedBytes)),
                activeMillis = maxOf(durable.activeMillis, reserve(next.activeMillis, 1000, limits.maxActiveMillis))
            )
            persist(reservation)
            durable = reservation
        }
        state = next
        return true
    }

    @Synchronized
    fun restore(saved: State) {
        require(saved.limitReason == null || saved.limitReason in setOf("user_request_limit", "user_download_limit", "user_time_limit"))
        require(saved.version == 3 && saved.fingerprint == fingerprint && saved.limits == limits)
        require(listOf(saved.logical, saved.physical, saved.downloaded, saved.activeMillis).all { it >= 0 })
        require(limits.maxRequests?.let { saved.physical <= it } != false)
        require(limits.maxDownloadedBytes?.let { saved.downloaded <= it } != false)
        require(limits.maxActiveMillis?.let { saved.activeMillis <= it } != false)
        state = saved
        durable = saved
    }

    @Synchronized fun snapshot() = state
}
