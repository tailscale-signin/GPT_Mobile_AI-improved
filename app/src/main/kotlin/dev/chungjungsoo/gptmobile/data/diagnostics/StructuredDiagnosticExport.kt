package dev.chungjungsoo.gptmobile.data.diagnostics

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private val eventIdPattern = Regex("(?:^|: | · )eventId=([a-zA-Z0-9_.:-]+)")
private val recordPattern = Regex("^(\\S+) ([VDIWEF])/([^:]+): (.*)$")
private val fieldPattern = Regex("(?:^| · |(?<=\\s))([A-Za-z][A-Za-z0-9_]*)=")

/** Keep every record and continuation. A duplicate is not the same thing as a non-issue. */
internal fun diagnosticEventIdentity(line: String): String = eventIdPattern.find(line)?.groupValues?.get(1)
    ?: java.security.MessageDigest.getInstance("SHA-256").digest(redactLogMessage(line).toByteArray()).joinToString("") { "%02x".format(it) }

internal fun structuredDiagnosticLine(
    line: String,
    captureSource: String = "unknown",
    duplicate: Boolean = false,
    parentEventId: String? = null,
    sequence: Int = 0,
    captureIdentity: String? = null,
    incidentId: String? = null,
    incidentRoot: Boolean = true,
    exportedAtMillis: Long? = null
): String {
    val limit = if (captureSource == "runtime_incident_history") 32 * 1024 else 8000
    val safe = redactLogMessage(line).take(limit)
    val match = recordPattern.matchEntire(safe)
    val message = match?.groupValues?.get(4) ?: safe
    val classification = classifyDiagnostic(match?.groupValues?.get(2), match?.groupValues?.get(3), message)
    val parsedFields = diagnosticFields(message)
    fun number(key: String) = (parsedFields[key] as? JsonPrimitive)?.longOrNull
    fun text(key: String) = (parsedFields[key] as? JsonPrimitive)?.content?.takeUnless { it in setOf("unknown", "null", "none") }
    val recordTime = match?.groupValues?.get(1)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    val occurredAt = number("occurredAtMillis") ?: recordTime
    val historical = captureSource in setOf("runtime_incident_history", "android_exit_history", "provider_failure")
    val capturedAt = number("capturedAtMillis") ?: recordTime.takeUnless { historical }
    val exitConfirmed = (parsedFields["workerExitConfirmed"] as? JsonPrimitive)?.booleanOrNull
        ?: (parsedFields["exitConfirmed"] as? JsonPrimitive)?.booleanOrNull
    return buildJsonObject {
        put("schema", 6)
        put("incidentId", incidentId ?: text("incidentId") ?: parentEventId ?: captureIdentity ?: diagnosticEventIdentity(line))
        put("incidentRoot", incidentRoot)
        put("eventId", captureIdentity ?: diagnosticEventIdentity(line))
        parentEventId?.let { put("parentEventId", it) }
        put("sequence", sequence)
        put("captureSource", captureSource)
        put("duplicate", duplicate)
        put("countAsIssue", !duplicate && incidentRoot && match != null && classification.actionable)
        put("category", classification.category)
        put("severity", classification.severity)
        put("stage", text("stage") ?: text("runtimeStage"))
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
        put("workerGenerationId", text("workerGenerationId"))
        put("nativeOperationId", text("nativeOperationId") ?: text("operationId"))
        put("terminationRequested", (parsedFields["terminationRequested"] as? JsonPrimitive)?.booleanOrNull)
        put("workerExitConfirmed", exitConfirmed)
        put("workerExitEvidence", text("workerExitEvidence"))
        put("osExitReason", text("osExitReason") ?: text("reasonCode"))
        put("truncated", line.length > limit || "truncatedAtSource=true" in line)
        put("truncatedAtSource", "truncatedAtSource=true" in line)
        put("truncatedAtExport", line.length > limit)
        put("originalLength", line.length)
        put("retainedLength", safe.length)
        put("kind", if (match == null) "continuation" else "event")
        put("message", message)
        put("eventName", diagnosticEventMessage(message).substringBefore(" · ").trim())
        if (match != null) {
            put("timestamp", match.groupValues[1])
            put("level", match.groupValues[2])
            put("tag", match.groupValues[3])
            put("fields", parsedFields)
        }
    }.toString()
}

private fun diagnosticFields(message: String): JsonObject {
    val fields = fieldPattern.findAll(message).take(64).toList()
    return buildJsonObject {
        fields.forEachIndexed { index, field ->
            val key = field.groupValues[1]
            val end = fields.getOrNull(index + 1)?.range?.first ?: message.length
            val formattedValue = message.substring(field.range.last + 1, end).trim().trimEnd('·').trim()
            val value = if (key in setOf("eventId", "parentEventId", "monoNs", "bootMs", "processId", "processName")) formattedValue.takeWhile { !it.isWhitespace() && it != '·' } else formattedValue
            when {
                value == "true" || value == "false" -> put(key, value.toBooleanStrict())
                value == "null" -> put(key, null as String?)
                (key == "processId" || !key.endsWith("Id", ignoreCase = true)) && value.toLongOrNull() != null -> put(key, value.toLong())
                else -> put(key, JsonPrimitive(value))
            }
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
