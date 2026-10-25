package dev.chungjungsoo.gptmobile.data.diagnostics

data class GroupedAppLog(
    val entry: AppLogEntry,
    val lastTime: Long = entry.time,
    val repetitions: Int = 1,
    val rawDetails: List<AppLogEntry> = listOf(entry),
    val incidentIds: Set<String> = emptySet()
)

/** Display compression and causal issue counts are separate; every captured record remains available. */
internal fun groupAppLogs(entries: List<AppLogEntry>): List<GroupedAppLog> {
    val grouped = mutableListOf<GroupedAppLog>()
    val repeatedEvents = mutableMapOf<String, Int>()
    val causalGroups = mutableMapOf<String, Int>()
    val incidents = DiagnosticIncidentTracker()
    entries.forEach { entry ->
        val association = incidents.accept(entry.line().lineSequence().first(), entry.eventId, null)
        val actionable = classifyDiagnostic(entry.level, entry.tag, entry.message).actionable
        val issueIds = if (actionable) setOf(association.id) else emptySet()
        val event = when {
            entry.message.startsWith("Tool-result byte budget blocked execution") -> "SEARCH_ENGINE_BLOCKED"
            entry.message.startsWith("Worker timeout circuit") -> "GATEWAY_TIMEOUT"
            entry.message.startsWith("DELEGATE_WATCHDOG_CANCELLED") -> "DELEGATE_WATCHDOG_CANCELLED"
            entry.tag == "Thermal" -> "THERMAL_POLL"
            entry.tag == "Android" && "TransportRuntime.CctTransportBackend" in entry.message -> "BACKGROUND_TELEMETRY"
            else -> null
        }
        val eventKey = event?.let { "${entry.tag}|${entry.level}|$it" }
        // The tracker returns the first event's ID for trace continuation, and a scoped
        // operation ID for related retries/recovery. Similar text alone is not causality.
        val causalIndex = causalGroups[association.id]?.takeIf { association.id != entry.eventId }
        val previousIndex = causalIndex ?: if (eventKey != null) repeatedEvents[eventKey] ?: -1 else grouped.lastIndex
        val previous = grouped.getOrNull(previousIndex)
        val canGroup = causalIndex != null ||
            previous != null &&
            entry.level == previous.entry.level &&
            entry.tag == previous.entry.tag &&
            if (eventKey != null) entry.time - previous.lastTime in 0..60_000 else !actionable && entry.time - previous.lastTime in 0..2000 && entry.message == previous.entry.message
        if (canGroup && previous != null) {
            val display = if (previous.incidentIds.isEmpty() && actionable) entry else previous.entry
            grouped[previousIndex] = previous.copy(entry = display, lastTime = maxOf(previous.lastTime, entry.time), repetitions = previous.repetitions + 1, rawDetails = previous.rawDetails + entry, incidentIds = previous.incidentIds + issueIds)
            causalGroups[association.id] = previousIndex
        } else {
            eventKey?.let { repeatedEvents[it] = grouped.size }
            causalGroups[association.id] = grouped.size
            grouped.add(GroupedAppLog(entry, incidentIds = issueIds))
        }
    }
    return grouped.sortedBy { it.lastTime }
}
