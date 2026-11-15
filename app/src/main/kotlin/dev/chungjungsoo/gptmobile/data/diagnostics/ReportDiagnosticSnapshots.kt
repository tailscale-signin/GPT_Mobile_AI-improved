package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Source-owned observations only. A manual report explicitly selects an area before reading one. */
internal object ReportDiagnosticSnapshots {
    private val cache = ReportDiagnosticSnapshotCache(canRetain = { LocalDiagnosticsPolicy.enabled && !AppLogRecorder.privateSessionActive && !IssueReportIncidents.privateSession.value })

    val originEpoch = cache.originEpoch
    fun captureOrigin(): ReportDiagnosticSnapshotCache.Origin? = cache.captureOrigin()
    fun isCurrent(origin: ReportDiagnosticSnapshotCache.Origin): Boolean = cache.isCurrent(origin)
    fun retain(snapshot: IssueReportDiagnostics) = cache.retain(snapshot)
    fun retain(snapshot: IssueReportDiagnostics, origin: ReportDiagnosticSnapshotCache.Origin) = cache.retain(snapshot, origin)
    fun latest(area: String, nowMillis: Long = System.currentTimeMillis()): IssueReportDiagnostics? = cache.latest(area, nowMillis)
    fun clear() = cache.clear()
}

/** Memory-only, bounded and short-lived; no history scan, disk read, network probe or cross-area join. */
internal class ReportDiagnosticSnapshotCache(
    private val canRetain: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val snapshots = linkedMapOf<String, IssueReportDiagnostics>()
    private val identity = Any()
    private val mutableOriginEpoch = MutableStateFlow(Any())
    private var originIssued = false
    val originEpoch = mutableOriginEpoch.asStateFlow()

    /** Issued only for public, enabled capture; it cannot acquire authority later. */
    class Origin internal constructor(internal val owner: Any, internal val epoch: Any)

    @Synchronized
    fun captureOrigin(): Origin? {
        if (!captureAllowed()) return null
        originIssued = true
        return Origin(identity, mutableOriginEpoch.value)
    }

    @Synchronized
    fun isCurrent(origin: Origin): Boolean = captureAllowed() && origin.owner === identity && origin.epoch === mutableOriginEpoch.value

    @Synchronized
    fun retain(snapshot: IssueReportDiagnostics, origin: Origin) {
        if (isCurrent(origin)) retain(snapshot)
    }

    @Synchronized
    fun retain(snapshot: IssueReportDiagnostics) {
        if (!captureAllowed()) return
        val now = clock()
        expire(now)
        val safe = snapshot.validated()?.takeIf { it.captureMode != "NO_CAPTURE" && it.values.isNotEmpty() && fresh(it, now) } ?: return
        val prior = snapshots[safe.area]
        if (prior != null && prior.capturedAtMillis > safe.capturedAtMillis) return
        snapshots.remove(safe.area)
        snapshots[safe.area] = safe
        while (snapshots.size > MAX_SNAPSHOTS) snapshots.remove(snapshots.keys.first())
    }

    @Synchronized
    fun latest(area: String, nowMillis: Long = clock()): IssueReportDiagnostics? {
        if (!captureAllowed()) return null
        expire(nowMillis)
        return snapshots[area]?.validated()
    }

    @Synchronized
    fun clear() {
        snapshots.clear()
        originIssued = false
        mutableOriginEpoch.value = Any()
    }

    private fun captureAllowed(): Boolean {
        if (canRetain()) return true
        if (originIssued || snapshots.isNotEmpty()) clear()
        return false
    }

    private fun expire(now: Long) {
        snapshots.entries.removeAll { !fresh(it.value, now) }
    }

    private fun fresh(snapshot: IssueReportDiagnostics, now: Long): Boolean =
        snapshot.capturedAtMillis >= 0 && now >= snapshot.capturedAtMillis && now - snapshot.capturedAtMillis <= MAX_AGE_MILLIS

    companion object {
        internal const val MAX_AGE_MILLIS = 10 * 60 * 1000L
        private const val MAX_SNAPSHOTS = 12
    }
}
