package dev.chungjungsoo.gptmobile.data.diagnostics

data class GroupedAppLog(
    val entry: AppLogEntry,
    val lastTime: Long = entry.time,
    val repetitions: Int = 1,
    val rawDetails: List<AppLogEntry> = listOf(entry)
)

/** Presentation-only compression; the recorder and exported file retain every event. */
internal fun groupAppLogs(entries: List<AppLogEntry>): List<GroupedAppLog> {
    val grouped = mutableListOf<GroupedAppLog>()
    val repeatedEvents = mutableMapOf<String, Int>()
    entries.forEach { entry ->
        val event = when {
            entry.message.startsWith("Tool-result byte budget blocked execution") -> "SEARCH_ENGINE_BLOCKED"
            entry.message.startsWith("Worker timeout circuit") -> "GATEWAY_TIMEOUT"
            entry.message.startsWith("DELEGATE_WATCHDOG_CANCELLED") -> "DELEGATE_WATCHDOG_CANCELLED"
            entry.tag == "Thermal" -> "THERMAL_POLL"
            entry.tag == "Android" && "TransportRuntime.CctTransportBackend" in entry.message -> "BACKGROUND_TELEMETRY"
            else -> null
        }
        val eventKey = event?.let { "${entry.tag}|${entry.level}|$it" }
        val previousIndex = if (eventKey != null) repeatedEvents[eventKey] ?: -1 else grouped.lastIndex
        val previous = grouped.getOrNull(previousIndex)
        val canGroup = previous != null &&
            entry.level == previous.entry.level &&
            entry.tag == previous.entry.tag &&
            if (eventKey != null) entry.time - previous.lastTime in 0..60_000 else entry.time - previous.lastTime in 0..2000 && entry.message == previous.entry.message
        if (canGroup && previous != null) {
            grouped[previousIndex] = previous.copy(lastTime = entry.time, repetitions = previous.repetitions + 1, rawDetails = previous.rawDetails + entry)
        } else {
            eventKey?.let { repeatedEvents[it] = grouped.size }
            grouped.add(GroupedAppLog(entry))
        }
    }
    return grouped.sortedBy { it.lastTime }
}
