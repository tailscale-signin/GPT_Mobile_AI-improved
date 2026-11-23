package dev.chungjungsoo.gptmobile.data.diagnostics

import java.time.Instant

/** Keeps every record while counting one issue per evidenced exception or physical operation. */
internal class DiagnosticIncidentTracker {
    data class Association(val id: String, val first: Boolean, val outcome: String? = null)
    private val counted = linkedSetOf<String>()
    private val associations = linkedMapOf<String, String>()
    private val outcomes = linkedMapOf<String, String>()
    private val identityValue = Regex("[A-Za-z0-9_.:-]{1,128}")
    private val crashes = DiagnosticCrashCorrelation()

    // Only a record carrying both identities proves an alias. Ambiguous aliases
    // remain separate; a repeated incident label cannot merge distinct dispatches.
    private val operationAliases = linkedMapOf<String, String?>()
    private var aliasesLimited = false
    private var indexing = false
    val correlationLimited: Boolean get() = crashes.limited || aliasesLimited
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
        val canonicalOutcomes = outcomes.entries.associate { (id, outcome) -> canonical(id) to outcome }
        outcomes.clear()
        outcomes.putAll(canonicalOutcomes)
        counted.clear()
        associations.clear()
        traces.clear()
        lastTrace = null
    }

    fun accept(line: String, eventId: String, parentEventId: String?): Association {
        associations[eventId]?.let { return Association(it, false, outcomes[it]) }
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
        val scope = listOf(
            "session" to identity("processSessionId"),
            "process" to (identity("processId") ?: identity("process")),
            "worker" to identity("workerGenerationId")
        ).mapNotNull { (key, value) -> value?.let { "$key:${it.length}:$it" } }.takeIf { it.isNotEmpty() }?.joinToString(":")
        fun scoped(id: String) = scope?.let { "incident:$it:$id" } ?: id
        val network = if (tag in setOf("Network", "FreeProviderFailure")) identity("networkEpisodeId") ?: identity("episode") else null
        val causalExplicit = identity("causalIncidentId") ?: identity("failureIncidentId")
        val direct = identity("incidentId") ?: identity("incident_id")
        val explicit = (causalExplicit ?: direct ?: network?.let { "network:$it" } ?: identity("failureId"))?.let(::scoped)
        // The native history writer and Kotlin runtime use different names for
        // the same operation receipt. Normalize the alias before constructing
        // the process-scoped incident identity.
        val physical = (identity("nativeOperationId") ?: identity("operationId") ?: identity("operation_id"))?.let { "operationId:$it" }
            ?: listOf("physicalAttemptId", "runtimeRequestId").firstNotNullOfOrNull { key -> identity(key)?.let { "$key:$it" } }
        val operation = physical?.let { "operation:${scope ?: "unscoped"}:$it" }
        if (scope != null && direct != null && operation != null && causalExplicit == null) {
            val alias = scoped(direct)
            when {
                alias in operationAliases -> if (operationAliases[alias] != operation) operationAliases[alias] = null
                operationAliases.size < 4096 -> operationAliases[alias] = operation
                else -> aliasesLimited = true
            }
        }
        val causal = if (scope != null && causalExplicit == null && direct != null) operation ?: explicit else explicit ?: operation
        val incident = causal ?: diagnosticCorrelation(fields, content, tag).invocationId
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
        if (incident != null) {
            if (tag == "Gateway" && content.startsWith("METADATA_READ_OUTCOME")) {
                val outcomeOwner = if (indexing) incident else canonical(incident)
                when (fields.diagnosticText("outcome")) {
                    "COMPLETED" -> outcomes[outcomeOwner] = "RECOVERED_METADATA_READ"
                    "RETRY_EXHAUSTED" -> outcomes[outcomeOwner] = "METADATA_RETRY_EXHAUSTED"
                }
                while (outcomes.size > 2048) outcomes.remove(outcomes.keys.first())
            }
            if (begins) setTrace(incident, at, source, fatalHeader)
            return remember(eventId, incident, classification.actionable)
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
        val owner = if (indexing) incidentId else canonical(incidentId)
        associations[eventId] = owner
        val first = actionable && counted.add(owner)
        while (counted.size > 2048) counted.remove(counted.first())
        while (associations.size > 4096) associations.remove(associations.keys.first())
        return Association(owner, first, outcomes[owner])
    }

    private fun canonical(id: String): String = crashes.canonical(operationAliases[id] ?: id)
}
