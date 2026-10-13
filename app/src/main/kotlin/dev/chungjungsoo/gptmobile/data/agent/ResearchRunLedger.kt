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
        val version: Int = 4,
        val fingerprint: String,
        val limits: ResearchUserLimits,
        val logical: Long = 0,
        val physical: Long = 0,
        val downloaded: Long = 0,
        val activeMillis: Long = 0,
        val limitReason: String? = null,
        val delegationLimits: Map<String, Int> = emptyMap(),
        val delegationReservations: Map<String, Set<String>> = emptyMap()
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

    /** Distinct logical operations, model sessions and HTTP attempts share the root owner. */
    @Synchronized fun reserveDelegation(dimension: String, reservationId: String, limit: Int, reserveForReview: Int = 0): Boolean {
        require(dimension in DELEGATION_DIMENSIONS && reservationId.length in 1..128 && limit in 1..48 && reserveForReview >= 0)
        val originalLimit = minOf(state.delegationLimits[dimension] ?: limit, limit)
        val used = state.delegationReservations[dimension].orEmpty()
        if (reservationId in used) return true
        if (used.size >= (originalLimit - reserveForReview).coerceAtLeast(0)) return false
        val next = state.copy(
            delegationLimits = state.delegationLimits + (dimension to originalLimit),
            delegationReservations = state.delegationReservations + (dimension to (used + reservationId))
        )
        val reserved = durable.copy(delegationLimits = next.delegationLimits, delegationReservations = next.delegationReservations)
        persist(reserved)
        durable = reserved
        state = next
        return true
    }

    @Synchronized
    fun restore(saved: State) {
        require(saved.limitReason == null || saved.limitReason in setOf("user_request_limit", "user_download_limit", "user_time_limit"))
        require(saved.version in 3..4 && saved.fingerprint == fingerprint && saved.limits == limits)
        require(listOf(saved.logical, saved.physical, saved.downloaded, saved.activeMillis).all { it >= 0 })
        require(limits.maxRequests?.let { saved.physical <= it } != false)
        require(limits.maxDownloadedBytes?.let { saved.downloaded <= it } != false)
        require(limits.maxActiveMillis?.let { saved.activeMillis <= it } != false)
        require(saved.delegationLimits.keys.all { it in DELEGATION_DIMENSIONS })
        require(saved.delegationLimits.values.all { it in 1..48 })
        require(saved.delegationReservations.all { (key, ids) -> key in DELEGATION_DIMENSIONS && ids.size <= (saved.delegationLimits[key] ?: 0) && ids.all { it.length in 1..128 } })
        state = saved.copy(version = 4)
        durable = state
    }

    @Synchronized fun snapshot() = state

    private companion object {
        val DELEGATION_DIMENSIONS = setOf("research_operations", "worker_calls", "model_invocations", "http_attempts")
    }
}
