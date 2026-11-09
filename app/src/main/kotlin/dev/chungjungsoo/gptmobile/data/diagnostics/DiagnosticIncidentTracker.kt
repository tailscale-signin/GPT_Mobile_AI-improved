package dev.chungjungsoo.gptmobile.data.diagnostics

import java.time.Instant

/** Keeps every record while counting one issue per evidenced exception or physical operation. */
internal class DiagnosticIncidentTracker {
    data class Association(val id: String, val first: Boolean)
    private val counted = linkedSetOf<String>()
    private val associations = linkedMapOf<String, String>()
    private val identityValue = Regex("[A-Za-z0-9_.:-]{1,128}")
    private val crashes = DiagnosticCrashCorrelation()
    private var indexing = false
    val correlationLimited: Boolean get() = crashes.limited
    private data class Trace(val id: String, val at: Instant?, val hasFatalHeader: Boolean)

    // Android logcat interleaves framework and application streams. A record from
    // another origin must neither steal a stack nor end a still-active stack.
    private val traces = linkedMapOf<String, Trace>()
    private var lastTrace: String? = null
    private val record = Regex("^(\\S+) ([VDIWEF])/([^:]+): (.*)$")
    private val exceptionStart = Regex("^(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*(?:Exception|Error)(?::|$)")

    fun index(line: String, eventId: String, parentEventId: String?) {
        indexing = true
        if (eventId in associations) return
        val association = accept(line, eventId, parentEventId)
        crashes.observe(association.id, line)
    }

    /** Resolve aliases before exporting countAsIssue; later frames cannot undo an emitted root. */
    fun finishIndex() {
        indexing = false
        crashes.finish()
        counted.clear()
        associations.clear()
        traces.clear()
        lastTrace = null
    }

    fun accept(line: String, eventId: String, parentEventId: String?): Association {
        associations[eventId]?.let { return Association(it, false) }
        val match = record.matchEntire(line)
        if (match == null) {
            val owner = parentEventId?.let { associations[it] ?: it } ?: lastTrace ?: eventId
            return remember(eventId, owner, false)
        }
        val (timestamp, level, tag, message) = match.destructured
        val at = runCatching { Instant.parse(timestamp) }.getOrNull()
        val classification = classifyDiagnostic(level, tag, message)
        val content = diagnosticEventMessage(message).trimStart()
        val fields = diagnosticFields(message)
        fun identity(key: String) = fields.diagnosticText(key)?.takeIf(identityValue::matches)?.takeUnless { it in setOf("-1", "0") }
        val network = if (tag in setOf("Network", "FreeProviderFailure")) identity("networkEpisodeId") ?: identity("episode") else null
        val explicit = identity("causalIncidentId") ?: identity("failureIncidentId") ?: identity("incidentId") ?: network?.let { "network:$it" } ?: identity("failureId")
        // The native history writer and Kotlin runtime use different names for
        // the same operation receipt. Normalize the alias before constructing
        // the process-scoped incident identity.
        val physical = (identity("nativeOperationId") ?: identity("operationId"))?.let { "operationId:$it" }
            ?: listOf("physicalAttemptId", "runtimeRequestId").firstNotNullOfOrNull { key -> identity(key)?.let { "$key:$it" } }
        val operation = physical?.let { "operation:${identity("workerGenerationId") ?: identity("processId") ?: identity("process") ?: "unscoped"}:$it" }
        val causal = explicit ?: operation ?: diagnosticCorrelation(fields, content, tag).invocationId
        val source = if (tag == "Android") {
            diagnosticAndroidSource(message) ?: "$tag:${identity("processId") ?: "unscoped"}"
        } else {
            "$tag:${identity("processId") ?: identity("process") ?: "unscoped"}"
        }
        val trace = traces[source]?.takeIf { candidate ->
            at != null && candidate.at != null && java.time.Duration.between(candidate.at, at).toMillis() in 0..3000
        }
        if (trace == null) traces.remove(source)
        lastTrace = null
        val fatalHeader = content.startsWith("FATAL EXCEPTION", true) ||
            content.startsWith("Fatal signal", true) ||
            content.startsWith("type=") ||
            tag == "Android" &&
            content == "Error was captured in composition." ||
            tag == "Android" &&
            content.startsWith("Uncaught exception thrown by finalizer", true)
        val begins = fatalHeader || exceptionStart.containsMatchIn(content)
        val continuation = content.startsWith("at ") ||
            content.startsWith("Caused by:") ||
            content.startsWith("Suppressed:") ||
            content.startsWith("... ") ||
            trace?.hasFatalHeader == true &&
            content.startsWith("Process:") ||
            classification.category == "fatal" &&
            !begins ||
            trace?.hasFatalHeader == true &&
            !fatalHeader &&
            exceptionStart.containsMatchIn(content)
        if (causal != null) {
            if (begins) setTrace(causal, at, source, fatalHeader)
            return remember(eventId, causal, classification.actionable)
        }
        if (trace != null && continuation) {
            setTrace(trace.id, at, source, trace.hasFatalHeader && !exceptionStart.containsMatchIn(content))
            return remember(eventId, trace.id, false)
        }
        if (begins || classification.category == "fatal") {
            setTrace(eventId, at, source, fatalHeader)
            return remember(eventId, eventId, classification.actionable)
        }
        traces.remove(source)
        return remember(eventId, eventId, classification.actionable)
    }

    private fun setTrace(id: String, at: Instant?, source: String, fatalHeader: Boolean) {
        traces[source] = Trace(id, at, fatalHeader)
        lastTrace = id
        while (traces.size > 64) traces.remove(traces.keys.first())
    }

    private fun remember(eventId: String, incidentId: String, actionable: Boolean): Association {
        val owner = if (indexing) incidentId else crashes.canonical(incidentId)
        associations[eventId] = owner
        val first = actionable && counted.add(owner)
        while (counted.size > 2048) counted.remove(counted.first())
        while (associations.size > 4096) associations.remove(associations.keys.first())
        return Association(owner, first)
    }
}
