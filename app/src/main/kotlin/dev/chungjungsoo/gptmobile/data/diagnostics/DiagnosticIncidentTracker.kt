package dev.chungjungsoo.gptmobile.data.diagnostics

import java.time.Instant

/** Associates trace rows without dropping records or merging later crash occurrences. */
internal class DiagnosticIncidentTracker {
    data class Association(val id: String, val first: Boolean)
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
        val begins = Regex("(?i)^(?:FATAL EXCEPTION|Fatal signal|type=)").containsMatchIn(message)
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
        return Association(eventId, true)
    }
}
