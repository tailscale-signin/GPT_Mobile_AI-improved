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
        val version: Int = 5,
        val fingerprint: String,
        val limits: ResearchUserLimits,
        val logical: Long = 0,
        val physical: Long = 0,
        val downloaded: Long = 0,
        val activeMillis: Long = 0,
        val limitReason: String? = null,
        val delegationLimits: Map<String, Int> = emptyMap(),
        val delegationReservations: Map<String, Set<String>> = emptyMap(),
        val readingReservation: Long = 0,
        val metadataReservation: Long = 0,
        val acquisitionConfigured: Boolean = false,
        val acquisitionUsed: Map<String, Long> = emptyMap(),
        val acquisitionReservations: Map<String, Pair<String, Long>> = emptyMap()
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
    fun charge(logical: Long = 0, physical: Long = 0, downloaded: Long = 0, activeMillis: Long = 0, acquisitionLane: AcquisitionLane? = null, reservationId: String? = null): Boolean {
        require(listOf(logical, physical, downloaded, activeMillis).all { it >= 0 })
        if (state.limitReason != null) return false
        val next = state.copy(
            logical = Math.addExact(state.logical, logical),
            physical = Math.addExact(state.physical, physical),
            downloaded = Math.addExact(state.downloaded, downloaded),
            activeMillis = Math.addExact(state.activeMillis, activeMillis),
            acquisitionUsed = if (acquisitionLane == null) state.acquisitionUsed else state.acquisitionUsed + (acquisitionLane.name to Math.addExact(state.acquisitionUsed[acquisitionLane.name] ?: 0, physical)),
            acquisitionReservations = if (reservationId == null || acquisitionLane == null) state.acquisitionReservations else state.acquisitionReservations + (reservationId to (acquisitionLane.name to physical))
        )
        val reason = when {
            limits.maxRequests?.let { next.physical > it } == true -> "user_request_limit"
            limits.maxDownloadedBytes?.let { next.downloaded > it } == true -> "user_download_limit"
            limits.maxActiveMillis?.let { next.activeMillis > it } == true -> "user_time_limit"
            next.physical > SAFETY_REQUESTS -> "safety_request_limit"
            next.downloaded > SAFETY_BYTES -> "safety_download_limit"
            next.activeMillis > SAFETY_MILLIS -> "safety_time_limit"
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
        require(saved.limitReason == null || saved.limitReason in setOf("user_request_limit", "user_download_limit", "user_time_limit", "safety_request_limit", "safety_download_limit", "safety_time_limit"))
        require(saved.version in 3..5 && saved.fingerprint == fingerprint && saved.limits == limits)
        require(listOf(saved.logical, saved.physical, saved.downloaded, saved.activeMillis).all { it >= 0 })
        require(limits.maxRequests?.let { saved.physical <= it } != false)
        require(limits.maxDownloadedBytes?.let { saved.downloaded <= it } != false)
        require(limits.maxActiveMillis?.let { saved.activeMillis <= it } != false)
        require(saved.delegationLimits.keys.all { it in DELEGATION_DIMENSIONS })
        require(saved.delegationLimits.values.all { it in 1..48 })
        require(saved.delegationReservations.all { (key, ids) -> key in DELEGATION_DIMENSIONS && ids.size <= (saved.delegationLimits[key] ?: 0) && ids.all { it.length in 1..128 } })
        require(saved.acquisitionUsed.values.sum() <= saved.physical)
        require(saved.acquisitionReservations.all { (id, reservation) -> id.length in 1..256 && reservation.first in AcquisitionLane.entries.map { it.name } && reservation.second > 0 })
        require(saved.readingReservation >= 0 && saved.metadataReservation >= 0 && saved.acquisitionUsed.all { (lane, used) -> lane in AcquisitionLane.entries.map { it.name } && used >= 0 })
        state = saved.copy(version = 5)
        durable = state
    }

    enum class AcquisitionLane { SEARCH, METADATA, READ }

    /** Protected allocations are planned once per accepted task and survive process restart. */
    @Synchronized fun configureAcquisition(sourceGoal: Int) {
        require(sourceGoal >= 0)
        if (state.acquisitionConfigured) return
        val remaining = (minOf(limits.maxRequests ?: SAFETY_REQUESTS, SAFETY_REQUESTS) - state.physical).coerceAtLeast(0)
        val read = minOf(sourceGoal.toLong() * 2, remaining * 3 / 5)
        val metadata = minOf(sourceGoal.toLong(), remaining / 5)
        val next = state.copy(acquisitionConfigured = true, readingReservation = read, metadataReservation = metadata)
        persist(durable.copy(acquisitionConfigured = true, readingReservation = read, metadataReservation = metadata))
        state = next
        durable = durable.copy(acquisitionConfigured = true, readingReservation = read, metadataReservation = metadata)
    }

    @Synchronized fun acquisitionAvailable(lane: AcquisitionLane): Long {
        if (state.limitReason != null) return 0
        val remaining = (minOf(limits.maxRequests ?: SAFETY_REQUESTS, SAFETY_REQUESTS) - state.physical).coerceAtLeast(0)
        if (!state.acquisitionConfigured) return remaining
        val protectedReads = (state.readingReservation - (state.acquisitionUsed[AcquisitionLane.READ.name] ?: 0)).coerceAtLeast(0)
        val protectedMetadata = (state.metadataReservation - (state.acquisitionUsed[AcquisitionLane.METADATA.name] ?: 0)).coerceAtLeast(0)
        return (
            remaining - when (lane) {
                AcquisitionLane.SEARCH -> protectedReads + protectedMetadata
                AcquisitionLane.METADATA -> protectedReads
                AcquisitionLane.READ -> protectedMetadata
            }
            ).coerceAtLeast(0)
    }

    /** A denied lane is not a sent request or a permanent hard-limit failure. */
    @Synchronized fun chargeAcquisition(lane: AcquisitionLane, count: Long): Boolean {
        require(count > 0)
        if (count > acquisitionAvailable(lane)) return false
        return charge(physical = count, acquisitionLane = lane)
    }

    @Synchronized fun reserveAcquisition(id: String, lane: AcquisitionLane, upper: Long): Boolean {
        require(id.length in 1..256 && upper > 0)
        state.acquisitionReservations[id]?.let { return it == (lane.name to upper) }
        if (upper > acquisitionAvailable(lane)) return false
        return charge(physical = upper, acquisitionLane = lane, reservationId = id)
    }

    /** Unknown outcomes retain their upper reservation. Known pre-dispatch denials settle to zero. */
    @Synchronized fun settleAcquisition(id: String, actual: Long?) {
        val reservation = state.acquisitionReservations[id] ?: return
        if (actual == null) return
        require(actual in 0..reservation.second) { "GATEWAY_ACCOUNTING_EXCEEDS_RESERVATION" }
        val refund = reservation.second - actual
        val next = state.copy(physical = state.physical - refund, acquisitionUsed = state.acquisitionUsed + (reservation.first to ((state.acquisitionUsed[reservation.first] ?: 0) - refund)), acquisitionReservations = state.acquisitionReservations - id)
        val reserved = durable.copy(physical = durable.physical - refund, acquisitionUsed = next.acquisitionUsed, acquisitionReservations = next.acquisitionReservations)
        persist(reserved)
        durable = reserved
        state = next
    }

    @Synchronized fun snapshot() = state

    @Synchronized fun discoveryAllowed(): Boolean = state.limitReason == null &&
        state.physical < minOf(limits.maxRequests ?: SAFETY_REQUESTS, SAFETY_REQUESTS) * 3 / 4 &&
        state.downloaded < minOf(limits.maxDownloadedBytes ?: SAFETY_BYTES, SAFETY_BYTES) * 3 / 4 &&
        state.activeMillis < minOf(limits.maxActiveMillis ?: SAFETY_MILLIS, SAFETY_MILLIS) * 3 / 4

    companion object {
        const val SAFETY_REQUESTS = 2400L
        const val SAFETY_BYTES = 512L * 1024 * 1024
        const val SAFETY_MILLIS = 3_600_000L
        val DELEGATION_DIMENSIONS = setOf("research_operations", "worker_calls", "model_invocations", "http_attempts")
    }
}
