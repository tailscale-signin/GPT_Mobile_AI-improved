package dev.chungjungsoo.gptmobile.data.agent

/** One bounded turn budget shared by discovery, review, recovery, and finalization. */
class ResearchBudget(
    totalLogicalCalls: Int,
    private val reservations: Reservations = Reservations(),
    private val persist: ((Snapshot) -> Unit)? = null,
    private val downloadLimitBytes: Int = 512 * 1024 * 1024
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
        val remainingByPhase: Map<Phase, Int>,
        val downloadedBytes: Int = 0,
        val downloadLimitBytes: Int = 512 * 1024 * 1024,
        val accountingVersion: Int = 1
    )

    private val total = totalLogicalCalls.coerceAtLeast(1)
    private val limits = mapOf(
        Phase.DISCOVERY to (this.total * reservations.discovery).toInt(),
        Phase.VERIFICATION to (this.total * reservations.verification).toInt(),
        Phase.RECOVERY to (this.total * reservations.recovery).toInt(),
        Phase.FINALIZATION to (this.total * reservations.finalization).toInt()
    ).toMutableMap().also { byPhase ->
        byPhase[Phase.DISCOVERY] = total - byPhase.filterKeys { it != Phase.DISCOVERY }.values.sum()
        // Preserve downstream work even when integer rounding would erase its allowance.
        val shares = mapOf(
            Phase.DISCOVERY to reservations.discovery,
            Phase.VERIFICATION to reservations.verification,
            Phase.RECOVERY to reservations.recovery,
            Phase.FINALIZATION to reservations.finalization
        )
        for (phase in listOf(Phase.FINALIZATION, Phase.RECOVERY, Phase.VERIFICATION, Phase.DISCOVERY)) {
            if (shares.getValue(phase) <= 0 || byPhase.getValue(phase) > 0) continue
            val donor = Phase.entries.filter { it != phase && byPhase.getValue(it) > 1 }.maxByOrNull { byPhase.getValue(it) }
                ?: Phase.DISCOVERY.takeIf { it != phase && byPhase.getValue(it) > 0 }
            if (donor != null) {
                byPhase[donor] = byPhase.getValue(donor) - 1
                byPhase[phase] = 1
            }
        }
    }
    private val used = Phase.entries.associateWith { 0 }.toMutableMap()
    private var physicalRequests = 0
    private var downloadedBytes = 0
    private var accountingVersion = 1

    @Synchronized
    fun tryCharge(phase: Phase, physicalRequest: Boolean, cost: Int = 1): Boolean {
        require(cost > 0)
        val phaseUsed = used.getValue(phase)
        // Subtract before comparing so a large batch cannot wrap the counters.
        if (cost > limits.getValue(phase) - phaseUsed || cost > total - used.values.sum() || (physicalRequest && cost > total - physicalRequests)) return false
        used[phase] = phaseUsed + cost
        if (physicalRequest) physicalRequests += cost
        try {
            persist?.invoke(snapshot())
        } catch (failure: Exception) {
            used[phase] = phaseUsed
            if (physicalRequest) physicalRequests -= cost
            throw failure
        }
        return true
    }

    /** Native HTTP attempts debit this counter rather than another logical phase slot. */
    @Synchronized
    fun tryChargeRequests(cost: Int): Boolean {
        require(cost > 0)
        if (physicalRequests.toLong() + cost > total || downloadedBytes >= downloadLimitBytes) return false
        val previousAccountingVersion = accountingVersion
        accountingVersion = 2
        physicalRequests += cost
        try {
            persist?.invoke(snapshot())
        } catch (failure: Exception) {
            accountingVersion = previousAccountingVersion
            physicalRequests -= cost
            throw failure
        }
        return true
    }

    @Synchronized
    fun tryChargeDownloadedBytes(count: Int): Boolean {
        require(count >= 0)
        if (downloadedBytes.toLong() + count > downloadLimitBytes) return false
        downloadedBytes += count
        try {
            persist?.invoke(snapshot())
        } catch (failure: Exception) {
            downloadedBytes -= count
            throw failure
        }
        return true
    }

    @Synchronized
    fun restore(snapshot: Snapshot) {
        require(snapshot.total == total && snapshot.usedLogical in 0..total)
        require(snapshot.remaining == total - snapshot.usedLogical)
        require(snapshot.accountingVersion in 1..2)
        require(snapshot.physicalRequests in 0..if (snapshot.accountingVersion == 1) snapshot.usedLogical else total)
        require(snapshot.remainingByPhase.keys == limits.keys)
        // Validate the entire checkpoint before changing live counters. Never silently
        // clamp corrupt phase data into a fresh allowance after an app restart.
        val restoredUsed = Phase.entries.associateWith { phase ->
            val remaining = snapshot.remainingByPhase.getValue(phase)
            require(remaining in 0..limits.getValue(phase))
            limits.getValue(phase) - remaining
        }
        require(restoredUsed.values.sum() == snapshot.usedLogical)
        require(snapshot.downloadLimitBytes == downloadLimitBytes && snapshot.downloadedBytes in 0..downloadLimitBytes)
        used.putAll(restoredUsed)
        physicalRequests = snapshot.physicalRequests
        downloadedBytes = snapshot.downloadedBytes
        accountingVersion = snapshot.accountingVersion
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val usedTotal = used.values.sum()
        return Snapshot(
            total = total,
            usedLogical = usedTotal,
            physicalRequests = physicalRequests,
            remaining = (total - usedTotal).coerceAtLeast(0),
            remainingByPhase = Phase.entries.associateWith { (limits.getValue(it) - used.getValue(it)).coerceAtLeast(0) },
            downloadedBytes = downloadedBytes,
            downloadLimitBytes = downloadLimitBytes,
            accountingVersion = accountingVersion
        )
    }
}
