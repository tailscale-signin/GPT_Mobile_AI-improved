package dev.chungjungsoo.gptmobile.data.diagnostics

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private val eventIdPattern = Regex("(?:^|: | · )eventId=([a-zA-Z0-9_.:-]+)")
private val recordPattern = Regex("^(\\S+) ([VDIWEF])/([^:]+): (.*)$")
private val fieldPattern = Regex("(?:^| · |(?<=\\s))([A-Za-z][A-Za-z0-9_]*)=")
private val boundedTokenFields = setOf(
    "eventId", "parentEventId", "incidentId", "incident_id", "operation_id", "retry_attempt", "causalIncidentId", "runId", "parentRunId", "invocationId", "callId", "toolCallId",
    "monoNs", "bootMs", "processId", "processName", "processSessionId", "truncatedAtSource", "reasonTruncated", "dispatched", "retryable",
    "tool", "action"
)
private val acceptanceSchemas = setOf(JsonPrimitive("github.acceptance-criterion.v1"), JsonPrimitive("github.acceptance-summary.v1"))

/** Keep every record and continuation. A duplicate is not the same thing as a non-issue. */
internal fun diagnosticEventIdentity(line: String): String = eventIdPattern.find(line)?.groupValues?.get(1)
    ?: DiagnosticRecordChunks.sha256(redactLogMessage(line))

internal fun structuredDiagnosticLine(
    line: String,
    captureSource: String = "unknown",
    duplicate: Boolean = false,
    parentEventId: String? = null,
    sequence: Int = 0,
    captureIdentity: String? = null,
    incidentId: String? = null,
    incidentRoot: Boolean = true,
    exportedAtMillis: Long? = null,
    incidentOutcome: String? = null
): String {
    val limit = if (captureSource == "runtime_incident_history") 32 * 1024 else 8000
    val sourceTruncated = "truncatedAtSource=true" in line || "reasonTruncated=true" in line || Regex("(?i)\\[truncated\\]").containsMatchIn(line)
    val safe = redactLogMessage(line).take(limit)
    val match = recordPattern.matchEntire(safe)
    val message = match?.groupValues?.get(4) ?: safe
    val classification = classifyDiagnostic(match?.groupValues?.get(2), match?.groupValues?.get(3), message)
    val parsedFields = diagnosticFields(message)
    fun number(key: String) = (parsedFields[key] as? JsonPrimitive)?.longOrNull
    fun text(key: String) = parsedFields.diagnosticText(key)
    val eventMessage = diagnosticEventMessage(message)
    val eventName = diagnosticStructuredEvent(message)?.name ?: eventMessage.substringBefore(" · ").trim().take(128)
    val typedPayload = diagnosticTypedPayload(eventMessage, message, match?.groupValues?.get(3))
    val trace = typedPayload?.takeIf { it["schema"] == JsonPrimitive("github.trace.v1") }
    val acceptance = typedPayload?.takeIf { it["schema"] in acceptanceSchemas }
    val execution = diagnosticExecutionEvidence(parsedFields, eventName, eventMessage)
    val correlation = diagnosticCorrelation(parsedFields, eventMessage, match?.groupValues?.get(3), acceptance ?: typedPayload)
    val toolIdentity = diagnosticToolIdentity(parsedFields, eventMessage, match?.groupValues?.get(3), typedPayload)
    val recordTime = match?.groupValues?.get(1)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    val occurredAt = number("occurredAtMillis") ?: recordTime
    val historical = captureSource in setOf("runtime_incident_history", "android_exit_history", "provider_failure")
    val historicalExit = captureSource == "android_exit_history" || match?.groupValues?.get(3) == "ProcessExit"
    val exitEvidence = number("reasonCode")?.takeIf { historicalExit && it in 0..Int.MAX_VALUE.toLong() }?.toInt()?.let(::androidProcessExitEvidence)
    val capturedAt = number("capturedAtMillis") ?: recordTime.takeUnless { historical }
    val exitConfirmed = (parsedFields["workerExitConfirmed"] as? JsonPrimitive)?.booleanOrNull
        ?: (parsedFields["exitConfirmed"] as? JsonPrimitive)?.booleanOrNull
    val identity = captureIdentity ?: diagnosticEventIdentity(line)
    return buildJsonObject {
        put("schema", 7)
        put("incidentId", incidentId ?: text("incidentId") ?: text("incident_id") ?: parentEventId ?: identity)
        put("incidentRoot", incidentRoot)
        put("eventId", identity)
        parentEventId?.let { put("parentEventId", it) }
        put("sequence", sequence)
        put("captureSource", captureSource)
        put("duplicate", duplicate)
        put("observedFailure", !duplicate && match != null && classification.actionable)
        put("countAsIssue", !duplicate && incidentRoot && match != null && classification.actionable)
        put("incidentOutcome", incidentOutcome?.takeIf { it == "RECOVERED_METADATA_READ" })
        put("unresolvedIssue", !duplicate && incidentRoot && match != null && classification.actionable && incidentOutcome != "RECOVERED_METADATA_READ")
        put("issueCountingUnit", "CORRELATED_INCIDENT")
        put(
            "classification",
            when {
                match == null || duplicate || !incidentRoot && classification.actionable -> "SYMPTOM"
                classification.actionable -> "OBSERVED_FAILURE"
                classification.category in setOf("compatibility_probe", "model_compatibility", "connectivity_progress", "platform_job_hint", "input_lifecycle", "memory_trim_lifecycle", "deferred_work", "recovery") -> "EXPECTED_LIFECYCLE"
                else -> "INFORMATIONAL"
            }
        )
        put("causalAttribution", "UNVERIFIED")
        put("category", classification.category)
        put("severity", classification.severity)
        put("stage", execution.stage)
        put("errorCode", execution.errorCode)
        put("dispatched", execution.dispatched)
        put("dispatchState", execution.state)
        put("runId", correlation.runId)
        put("invocationId", correlation.invocationId)
        toolIdentity.name?.let { put("toolName", it) }
        toolIdentity.callId?.let { put("toolCallId", it) }
        toolIdentity.action?.let { put("toolAction", it) }
        put("causalIncidentId", text("causalIncidentId"))
        put("retryable", (parsedFields["retryable"] as? JsonPrimitive)?.booleanOrNull)
        put("recovery", classification.recovery)
        put("occurredAtMillis", occurredAt)
        put("capturedAtMillis", capturedAt)
        put("stageObservedAtMillis", number("stageObservedAtMillis"))
        put("exportedAtMillis", exportedAtMillis)
        put("timeEvidence", text("occurrenceTimeSource") ?: if (historical) "historical_record" else "recorder_capture")
        put("monotonicNanos", number("monoNs") ?: number("monotonicNanos"))
        put("monotonicClock", "SYSTEM_NANO_TIME")
        put("bootMillis", number("bootMs"))
        put("processId", number("processId") ?: number("process"))
        put("processName", text("processName"))
        put("processSessionId", text("processSessionId"))
        put("workerGenerationId", text("workerGenerationId"))
        put("nativeOperationId", text("nativeOperationId") ?: text("operationId"))
        if (match?.groupValues?.get(3) == "VoiceTiming" && eventName.startsWith("kokoro_")) {
            put("voicePlaybackId", text("playbackId")?.takeIf { it.matches(Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")) })
        }
        put("operationId", text("operation_id") ?: text("operationId"))
        put("retryAttempt", number("retry_attempt"))
        put("terminationRequested", (parsedFields["terminationRequested"] as? JsonPrimitive)?.booleanOrNull)
        put("workerExitConfirmed", exitConfirmed)
        put("workerExitEvidence", text("workerExitEvidence"))
        put("osExitReason", text("osExitReason") ?: text("reasonCode").takeIf { historicalExit })
        put("osExitReasonName", text("osExitReasonName") ?: exitEvidence?.name)
        put("processCrashConfirmed", (parsedFields["processCrashConfirmed"] as? JsonPrimitive)?.booleanOrNull ?: exitEvidence?.crashConfirmed)
        put("truncated", line.length > limit || sourceTruncated)
        put("truncatedAtSource", sourceTruncated)
        put("truncatedAtExport", line.length > limit)
        put("originalLength", line.length)
        put("retainedLength", safe.length)
        put("kind", if (match == null) "continuation" else "event")
        put("message", message)
        put(
            "eventName",
            if (trace != null) {
                "GITHUB_TOOL_COMPLETED"
            } else if (acceptance != null) {
                "GITHUB_ACCEPTANCE"
            } else {
                eventName
            }
        )
        if (trace != null) put("trace", diagnosticJsonValue(trace))
        if (acceptance != null) put("acceptance", diagnosticJsonValue(acceptance))
        if (match != null) {
            put("timestamp", match.groupValues[1])
            put("level", match.groupValues[2])
            put("tag", match.groupValues[3])
            put("fields", parsedFields)
        }
    }.toString()
}

/** Accept current raw typed records and recover the bounded payload from legacy acceptance prefixes. */
private fun diagnosticTypedPayload(eventMessage: String, message: String, tag: String?): JsonObject? {
    runCatching { Json.parseToJsonElement(eventMessage) as? JsonObject }.getOrNull()?.let { return it }
    if (tag != "GitHubAcceptance") return null
    val start = message.indexOf('{').takeIf { it >= 0 } ?: return null
    return runCatching { Json.parseToJsonElement(message.substring(start)) as? JsonObject }.getOrNull()
        ?.takeIf { it["schema"] in acceptanceSchemas }
}

internal fun diagnosticFields(message: String): JsonObject {
    // Embedded JSON can contain prose such as "error code=429". Only recognize
    // field separators outside JSON objects, arrays and quoted strings.
    val topLevel = BooleanArray(message.length)
    var depth = 0
    var quoted = false
    var escaped = false
    message.forEachIndexed { index, character ->
        topLevel[index] = depth == 0 && !quoted
        if (quoted) {
            if (escaped) {
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else if (character == '"') {
                quoted = false
            }
        } else {
            when (character) {
                '"' -> quoted = true
                '{', '[' -> depth++
                '}', ']' -> depth = (depth - 1).coerceAtLeast(0)
            }
        }
    }
    val fields = fieldPattern.findAll(message).filter { topLevel[it.groups[1]!!.range.first] }.toList()
    val typed = diagnosticStructuredEvent(message)
    val legacyReadiness = typed == null && diagnosticEventMessage(message).startsWith("LLAMA_READINESS ·")
    return buildJsonObject {
        typed?.fields?.forEach { (key, value) -> put(key, diagnosticJsonValue(value)) }
        fields.take(64).forEachIndexed { index, field ->
            val key = field.groupValues[1]
            val end = fields.getOrNull(index + 1)?.range?.first ?: message.length
            // Payload redaction removes the following private field, including its
            // key. Its marker must not turn the preceding safe Boolean/number into prose.
            val formattedValue = message.substring(field.range.last + 1, end).trim().removeSuffix("[private payload redacted]").trim().trimEnd('·').trim().let {
                if (legacyReadiness) it.removeSuffix(";").trimEnd() else it
            }
            val value = if (key in boundedTokenFields) formattedValue.takeWhile { !it.isWhitespace() && it != '·' } else formattedValue
            val nested = if (value.startsWith('{') || value.startsWith('[')) runCatching { Json.parseToJsonElement(value) }.getOrNull() else null
            when {
                nested is JsonObject || nested is JsonArray -> put(key, diagnosticJsonValue(nested))
                value == "true" || value == "false" -> put(key, value.toBooleanStrict())
                value == "null" -> put(key, null as String?)
                (key == "processId" || !key.endsWith("Id", ignoreCase = true)) && value.toLongOrNull() != null -> put(key, value.toLong())
                else -> put(key, JsonPrimitive(value))
            }
        }
        if (fields.size > 64) {
            put("_fieldCountTotal", fields.size)
            put("_fieldCountRetained", 64)
            put("_fieldCountOmitted", fields.size - 64)
        }
    }
}

/** Historical incidents retain their recorded event time even when exported days later. */
internal fun runtimeIncidentLine(record: JsonObject): String {
    val at = (record["occurredAtMillis"] as? JsonPrimitive)?.longOrNull ?: (record["time"] as? JsonPrimitive)?.longOrNull
    val fields = (record["fields"] as? JsonObject).orEmpty() + record.filterKeys { it !in setOf("event", "fields", "time") }
    val identity = (record["eventId"] as? JsonPrimitive)?.content
    return buildString {
        append(at?.let { Instant.ofEpochMilli(it).toString() } ?: "unknown")
        append(" I/LocalRuntimeIncident: ")
        identity?.let { append("eventId=$it · ") }
        append((record["event"] as? JsonPrimitive)?.content ?: "UNKNOWN_NATIVE_INCIDENT")
        append(" · occurrenceTimeSource=")
        append(
            (record["occurrenceTimeSource"] as? JsonPrimitive)?.content ?: if (at != null && "occurredAtMillis" in record) {
                "operation_recorded"
            } else if (at != null) {
                "historical_record_time"
            } else {
                "unknown"
            }
        )
        fields.filterKeys { it !in setOf("eventId", "occurrenceTimeSource") }.forEach { (key, value) ->
            append(" · $key=")
            val plain = (value as? JsonPrimitive)?.content
            append(if (plain != null && '\n' !in plain && '\r' !in plain) plain else value.toString())
        }
    }
}

/** Correlation remains machine-readable even when the complete failure record is JSON. */
internal fun providerFailureLine(record: dev.chungjungsoo.gptmobile.data.free.FreeProviderFailureRecord): String {
    val payload = kotlinx.serialization.json.Json.encodeToString(dev.chungjungsoo.gptmobile.data.free.FreeProviderFailureRecord.serializer(), record)
    val eventId = record.failureId ?: diagnosticEventIdentity(payload)
    val incidentId = record.networkEpisodeId?.let { "network:$it" } ?: eventId
    return "${Instant.ofEpochMilli(record.time)} W/FreeProviderFailure: eventId=$eventId · PROVIDER_FAILED · incidentId=$incidentId · failureId=$eventId · stage=${record.stage} · provider=${record.provider} · record=$payload"
}
