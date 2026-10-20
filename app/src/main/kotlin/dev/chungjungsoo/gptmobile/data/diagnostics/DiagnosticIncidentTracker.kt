package dev.chungjungsoo.gptmobile.data.diagnostics

import java.time.Instant

/** Associates trace rows without dropping records or merging later crash occurrences. */
internal class DiagnosticIncidentTracker {
    data class Association(val id: String, val first: Boolean)
    private val causal = linkedSetOf<String>()
    private val incident = Regex("(?:^| · )incidentId=([A-Za-z0-9-]+)")
    private var fatal: String? = null
    private var fatalAt: Instant? = null
    private var fatalTag: String? = null
    private val record = Regex("^(\\S+) ([VDIWEF])/([^:]+): (.*)$")

    fun accept(line: String, eventId: String, parentEventId: String?): Association {
        val match = record.matchEntire(line)
        if (match == null) return Association(fatal ?: parentEventId ?: eventId, parentEventId == null && fatal == null)
        val (timestamp, level, tag, message) = match.destructured
        val at = runCatching { Instant.parse(timestamp) }.getOrNull()
        val classification = classifyDiagnostic(level, tag, message)
        val content = message.replace(Regex("^(?:(?:eventId=[A-Za-z0-9-]+|monoNs=[0-9]+|bootMs=(?:[0-9]+|unknown)|processId=-?[0-9]+) · )+"), "")
        val begins = Regex("(?i)^(?:FATAL EXCEPTION|Fatal signal|type=)").containsMatchIn(content)
        val withinTrace = fatal != null &&
            at != null &&
            fatalAt != null &&
            java.time.Duration.between(fatalAt, at).toMillis() in 0..3000 &&
            tag == fatalTag
        if (classification.category == "fatal") {
            if (!begins && withinTrace) return Association(fatal!!, false)
            fatal = eventId
            fatalAt = at
            fatalTag = tag
            return Association(eventId, true)
        }
        fatal = null
        fatalAt = null
        fatalTag = null
        val cause = incident.find(message)?.groupValues?.get(1)?.takeUnless { it in setOf("none", "unknown") }
        if (cause != null) {
            val first = if (classification.actionable) causal.add(cause) else false
            while (causal.size > 512) causal.remove(causal.first())
            return Association(cause, first)
        }
        return Association(eventId, true)
    }
}
