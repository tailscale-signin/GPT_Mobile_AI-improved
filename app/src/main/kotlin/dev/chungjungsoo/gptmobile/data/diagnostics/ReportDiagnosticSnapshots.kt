package dev.chungjungsoo.gptmobile.data.diagnostics

/** Source-owned observations only. A manual report explicitly selects an area before reading one. */
internal object ReportDiagnosticSnapshots {
    private val cache = ReportDiagnosticSnapshotCache(canRetain = { LocalDiagnosticsPolicy.enabled && !AppLogRecorder.privateSessionActive && !IssueReportIncidents.privateSession.value })

    fun retain(snapshot: IssueReportDiagnostics) = cache.retain(snapshot)
    fun latest(area: String, nowMillis: Long = System.currentTimeMillis()): IssueReportDiagnostics? = cache.latest(area, nowMillis)
    fun clear() = cache.clear()
}

/** Memory-only, bounded and short-lived; no history scan, disk read, network probe or cross-area join. */
internal class ReportDiagnosticSnapshotCache(
    private val canRetain: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val snapshots = linkedMapOf<String, IssueReportDiagnostics>()

    @Synchronized
    fun retain(snapshot: IssueReportDiagnostics) {
        if (!canRetain()) return
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
        if (!canRetain()) {
            snapshots.clear()
            return null
        }
        expire(nowMillis)
        return snapshots[area]?.validated()
    }

    @Synchronized
    fun clear() = snapshots.clear()

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
