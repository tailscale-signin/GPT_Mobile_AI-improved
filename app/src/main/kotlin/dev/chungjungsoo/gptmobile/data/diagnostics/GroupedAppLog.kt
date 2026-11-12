package dev.chungjungsoo.gptmobile.data.diagnostics

data class GroupedAppLog(
    val entry: AppLogEntry,
    val lastTime: Long = entry.time,
    val repetitions: Int = 1,
    val rawDetails: List<AppLogEntry> = listOf(entry),
    val incidentIds: Set<String> = emptySet(),
    val unresolvedIncidentIds: Set<String> = incidentIds
)

private data class LogGroupMember(val entry: AppLogEntry, val incidentId: String, val actionable: Boolean)

private class MutableLogGroup {
    val members = ArrayDeque<LogGroupMember>()
    val recovered = mutableSetOf<String>()
    private var cached: GroupedAppLog? = null
    private var representativeMember: LogGroupMember? = null
    var lastTime: Long = Long.MIN_VALUE
        private set
    val representative: AppLogEntry get() = requireNotNull(representativeMember).entry

    fun append(member: LogGroupMember, outcome: String?) {
        members.addLast(member)
        if (representativeMember == null || representativeMember?.actionable == false && member.actionable) representativeMember = member
        lastTime = maxOf(lastTime, member.entry.time)
        if (outcome == "RECOVERED_METADATA_READ") recovered.add(member.incidentId)
        cached = null
    }

    fun removeFirst() {
        val removed = members.removeFirst()
        if (representativeMember === removed) representativeMember = members.firstOrNull { it.actionable } ?: members.firstOrNull()
        if (removed.entry.time == lastTime) lastTime = members.maxOfOrNull { it.entry.time } ?: Long.MIN_VALUE
        cached = null
    }

    fun snapshot(): GroupedAppLog = cached ?: run {
        val ids = members.filter { it.actionable }.mapTo(linkedSetOf()) { it.incidentId }
        GroupedAppLog(representative, lastTime, members.size, members.map { it.entry }, ids, ids - recovered).also { cached = it }
    }
}

/**
 * Live publication contains overlapping immutable ring snapshots. Parse each new event once,
 * reuse unchanged rows, and evict expired members without rebuilding every line and JSON field.
 * A replaced, cleared or frozen snapshot resets the parser; it never imports an absent event.
 */
internal class IncrementalAppLogGroups {
    private var previous = emptyList<AppLogEntry>()
    private var incidents = DiagnosticIncidentTracker()
    private val groups = linkedSetOf<MutableLogGroup>()
    private val retained = ArrayDeque<Pair<LogGroupMember, MutableLogGroup>>()
    private val repeatedEvents = mutableMapOf<String, MutableLogGroup>()
    private val causalGroups = mutableMapOf<String, MutableLogGroup>()

    fun update(entries: List<AppLogEntry>): List<GroupedAppLog> {
        if (entries === previous) return snapshot()
        val offset = entries.firstOrNull()?.let { first -> previous.indexOfFirst { it === first || it == first } } ?: -1
        val overlap = if (offset >= 0) previous.size - offset else 0
        val extendsRing = offset >= 0 && overlap <= entries.size && (0 until overlap).all { previous[offset + it] == entries[it] }
        if (!extendsRing) {
            previous = emptyList()
            incidents = DiagnosticIncidentTracker()
            groups.clear()
            retained.clear()
            repeatedEvents.clear()
            causalGroups.clear()
        } else {
            repeat(offset) {
                val (member, group) = retained.removeFirst()
                group.removeFirst()
                if (group.members.none { it.incidentId == member.incidentId }) {
                    if (causalGroups[member.incidentId] === group) causalGroups.remove(member.incidentId)
                    group.recovered.remove(member.incidentId)
                }
                if (group.members.isEmpty()) {
                    groups.remove(group)
                    repeatedEvents.entries.removeAll { it.value === group }
                }
            }
        }
        val start = if (extendsRing) overlap else 0
        for (index in start until entries.size) append(entries[index])
        previous = entries
        return snapshot()
    }

    private fun append(entry: AppLogEntry) {
        val association = incidents.accept(entry.line().lineSequence().first(), entry.eventId, null)
        val actionable = classifyDiagnostic(entry.level, entry.tag, entry.message).actionable
        val member = LogGroupMember(entry, association.id, actionable)
        val event = when {
            entry.message.startsWith("Tool-result byte budget blocked execution") -> "SEARCH_ENGINE_BLOCKED"
            entry.message.startsWith("Worker timeout circuit") -> "GATEWAY_TIMEOUT"
            entry.message.startsWith("DELEGATE_WATCHDOG_CANCELLED") -> "DELEGATE_WATCHDOG_CANCELLED"
            entry.tag == "Thermal" -> "THERMAL_POLL"
            entry.tag == "Android" && "TransportRuntime.CctTransportBackend" in entry.message -> "BACKGROUND_TELEMETRY"
            else -> null
        }
        val eventKey = event?.let { "${entry.tag}|${entry.level}|$it" }
        val causal = causalGroups[association.id]?.takeIf { association.id != entry.eventId }
        val prior = causal ?: if (eventKey != null) repeatedEvents[eventKey] else groups.lastOrNull()
        val canGroup = causal != null ||
            (
                prior != null &&
                    entry.level == prior.representative.level &&
                    entry.tag == prior.representative.tag &&
                    (if (eventKey != null) entry.time - prior.lastTime in 0..60_000 else !actionable && entry.time - prior.lastTime in 0..2000 && entry.message == prior.representative.message)
                )
        val group = if (canGroup && prior != null) {
            prior
        } else {
            MutableLogGroup().also {
                groups.add(it)
                eventKey?.let { key -> repeatedEvents[key] = it }
            }
        }
        group.append(member, association.outcome)
        causalGroups[association.id] = group
        retained.addLast(member to group)
    }

    private fun snapshot(): List<GroupedAppLog> = groups.map { it.snapshot() }.sortedBy { it.lastTime }
}

/** Display compression and causal issue counts are separate; every captured record remains available. */
internal fun groupAppLogs(entries: List<AppLogEntry>): List<GroupedAppLog> = IncrementalAppLogGroups().update(entries)
