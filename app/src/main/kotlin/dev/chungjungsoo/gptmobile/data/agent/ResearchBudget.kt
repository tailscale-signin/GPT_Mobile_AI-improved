package dev.chungjungsoo.gptmobile.data.agent

/** One bounded turn budget shared by discovery, review, recovery, and finalization. */
class ResearchBudget(
    totalLogicalCalls: Int,
    private val reservations: Reservations = Reservations()
) {
    enum class Phase { DISCOVERY, VERIFICATION, RECOVERY, FINALIZATION }

    data class Reservations(
        val discovery: Double = 0.65,
        val verification: Double = 0.15,
        val recovery: Double = 0.10,
        val finalization: Double = 0.10
    ) {
        init {
            require(discovery >= 0 && verification >= 0 && recovery >= 0 && finalization >= 0)
            require(kotlin.math.abs(discovery + verification + recovery + finalization - 1.0) < 0.0001)
        }
    }

    data class Snapshot(
        val total: Int,
        val usedLogical: Int,
        val physicalRequests: Int,
        val remaining: Int,
        val remainingByPhase: Map<Phase, Int>
    )

    private val total = totalLogicalCalls.coerceAtLeast(1)
    private val limits = mapOf(
        Phase.DISCOVERY to (this.total * reservations.discovery).toInt(),
        Phase.VERIFICATION to (this.total * reservations.verification).toInt(),
        Phase.RECOVERY to (this.total * reservations.recovery).toInt(),
        Phase.FINALIZATION to (this.total * reservations.finalization).toInt()
    ).toMutableMap().also { byPhase ->
        // Rounding belongs to discovery; reserved phases retain their minimum share.
        byPhase[Phase.DISCOVERY] = total - byPhase.filterKeys { it != Phase.DISCOVERY }.values.sum()
    }
    private val used = Phase.entries.associateWith { 0 }.toMutableMap()
    private var physicalRequests = 0

    @Synchronized
    fun tryCharge(phase: Phase, physicalRequest: Boolean): Boolean {
        val phaseUsed = used.getValue(phase)
        if (phaseUsed >= limits.getValue(phase) || used.values.sum() >= total) return false
        used[phase] = phaseUsed + 1
        if (physicalRequest) physicalRequests++
        return true
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val usedTotal = used.values.sum()
        return Snapshot(
            total = total,
            usedLogical = usedTotal,
            physicalRequests = physicalRequests,
            remaining = (total - usedTotal).coerceAtLeast(0),
            remainingByPhase = Phase.entries.associateWith { (limits.getValue(it) - used.getValue(it)).coerceAtLeast(0) }
        )
    }
}
