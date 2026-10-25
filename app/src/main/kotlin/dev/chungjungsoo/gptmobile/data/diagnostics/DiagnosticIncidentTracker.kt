package dev.chungjungsoo.gptmobile.data.diagnostics

import java.time.Instant

/** Keeps every record while counting one issue per evidenced exception or physical operation. */
internal class DiagnosticIncidentTracker {
    data class Association(val id: String, val first: Boolean)
    private val counted = linkedSetOf<String>()
    private val associations = linkedMapOf<String, String>()
    private val identityField = Regex("(?:^| · |(?<=\\s))(incidentId|causalIncidentId|failureIncidentId|nativeOperationId|physicalAttemptId|runtimeRequestId|operationId|failureId|networkEpisodeId|episode|processId|process|workerGenerationId)=([A-Za-z0-9_.:-]+)")
    private var trace: String? = null
    private var traceAt: Instant? = null
    private var traceTag: String? = null
    private var traceHasFatalHeader = false
    private val record = Regex("^(\\S+) ([VDIWEF])/([^:]+): (.*)$")
    private val exceptionStart = Regex("^(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*(?:Exception|Error)(?::|$)")

    fun accept(line: String, eventId: String, parentEventId: String?): Association {
        associations[eventId]?.let { return Association(it, false) }
        val match = record.matchEntire(line)
        if (match == null) {
            val owner = parentEventId?.let { associations[it] ?: it } ?: trace ?: eventId
            return remember(eventId, owner, false)
        }
        val (timestamp, level, tag, message) = match.destructured
        val at = runCatching { Instant.parse(timestamp) }.getOrNull()
        val classification = classifyDiagnostic(level, tag, message)
        val content = diagnosticEventMessage(message).trimStart()
        val fields = identityField.findAll(message).associate { it.groupValues[1] to it.groupValues[2] }
        fun identity(key: String) = fields[key]?.takeUnless { it in setOf("none", "unknown", "null", "-1", "0") }
        val network = if (tag in setOf("Network", "FreeProviderFailure")) identity("networkEpisodeId") ?: identity("episode") else null
        val explicit = identity("incidentId") ?: identity("causalIncidentId") ?: identity("failureIncidentId") ?: network?.let { "network:$it" } ?: identity("failureId")
        // The native history writer and Kotlin runtime use different names for
        // the same operation receipt. Normalize the alias before constructing
        // the process-scoped incident identity.
        val physical = (identity("nativeOperationId") ?: identity("operationId"))?.let { "operationId:$it" }
            ?: listOf("physicalAttemptId", "runtimeRequestId").firstNotNullOfOrNull { key -> identity(key)?.let { "$key:$it" } }
        val operation = physical?.let { "operation:${identity("workerGenerationId") ?: identity("processId") ?: identity("process") ?: "unscoped"}:$it" }
        val causal = explicit ?: operation
        val withinTrace = trace != null &&
            at != null &&
            traceAt != null &&
            tag == traceTag &&
            java.time.Duration.between(traceAt, at).toMillis() in 0..3000
        val fatalHeader = content.startsWith("FATAL EXCEPTION", true) || content.startsWith("Fatal signal", true) || content.startsWith("type=")
        val begins = fatalHeader || exceptionStart.containsMatchIn(content)
        val continuation = content.startsWith("at ") ||
            content.startsWith("Caused by:") ||
            content.startsWith("Suppressed:") ||
            content.startsWith("... ") ||
            classification.category == "fatal" &&
            !begins ||
            traceHasFatalHeader &&
            !fatalHeader &&
            exceptionStart.containsMatchIn(content)
        if (causal != null) {
            if (begins) setTrace(causal, at, tag, fatalHeader)
            return remember(eventId, causal, classification.actionable)
        }
        if (withinTrace && continuation) return remember(eventId, trace!!, false)
        if (begins || classification.category == "fatal") {
            setTrace(eventId, at, tag, fatalHeader)
            return remember(eventId, eventId, classification.actionable)
        }
        trace = null
        traceAt = null
        traceTag = null
        traceHasFatalHeader = false
        return remember(eventId, eventId, classification.actionable)
    }

    private fun setTrace(id: String, at: Instant?, tag: String, fatalHeader: Boolean) {
        trace = id
        traceAt = at
        traceTag = tag
        traceHasFatalHeader = fatalHeader
    }

    private fun remember(eventId: String, incidentId: String, actionable: Boolean): Association {
        associations[eventId] = incidentId
        val first = actionable && counted.add(incidentId)
        while (counted.size > 2048) counted.remove(counted.first())
        while (associations.size > 4096) associations.remove(associations.keys.first())
        return Association(incidentId, first)
    }
}
