package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Normalizes bounded metadata only. An observation never manufactures a terminal transition. */
internal object DiagnosticTimeline {
    private val terminalRuns = setOf("COMPLETED", "FAILED", "CANCELED", "INTERRUPTED", "PARTIAL", "EVIDENCE_MISSING", "BLOCKED")
    private val terminalInvocations = setOf("COMPLETED", "FAILED", "CANCELED", "INTERRUPTED", "NOT_DISPATCHED")

    fun database(row: JsonObject, observedAtMillis: Long): JsonObject? {
        val fields = row["fields"] as? JsonObject ?: return null
        fun text(key: String) = (fields[key] as? JsonPrimitive)?.contentOrNull
        fun number(key: String) = (fields[key] as? JsonPrimitive)?.longOrNull
        fun seconds(key: String) = number(key)?.takeIf { it in 0..Long.MAX_VALUE / 1000 }?.times(1000)
        val table = (row["table"] as? JsonPrimitive)?.contentOrNull ?: return null
        val invocation = table == "model_invocations"
        val id = text(
            if (invocation) {
                "id"
            } else if (table == "agent_runs") {
                "run_id"
            } else {
                "event_id"
            }
        ) ?: return null
        val run = text(if (invocation) "parentRunId" else "run_id") ?: return null
        val status = text("status") ?: return null
        val parentStatus = text("parentRunStatus")
        val started = if (invocation) number("startedAt") else seconds("started_at")
        val duration = number("durationMs")?.takeIf { it >= 0 }
        val completed = if (!invocation) {
            seconds("completed_at")
        } else if (status in terminalInvocations && started != null && duration != null && started <= Long.MAX_VALUE - duration) {
            started + duration
        } else {
            null
        }
        return buildJsonObject {
            put("kind", "lifecycle_observation")
            put("eventId", "$table:$id")
            put("sourceEventId", id)
            put("source", table)
            put("runId", run)
            if (invocation) put("invocationId", id)
            text("call_id")?.let { put("callId", it) }
            number("sequence")?.let { put("sequence", it) }
            put("status", status)
            put("parentRunStatus", parentStatus?.let(::JsonPrimitive) ?: JsonNull)
            put("observedAtMillis", observedAtMillis)
            put("timestampMillis", (completed ?: started ?: seconds("created_at"))?.let(::JsonPrimitive) ?: JsonNull)
            put("startedAtMillis", started?.let(::JsonPrimitive) ?: JsonNull)
            put("completedAtMillis", completed?.let(::JsonPrimitive) ?: JsonNull)
            put(
                "completionTimestampSource",
                if (completed == null) {
                    "UNAVAILABLE"
                } else if (invocation) {
                    "START_PLUS_RECORDED_DURATION"
                } else {
                    "RECORDED_EPOCH_SECONDS"
                }
            )
            put("reconciliation", if (status == "RUNNING" && parentStatus in terminalRuns) "REQUIRES_LIFECYCLE_RECONCILIATION" else "OBSERVED")
            row["rowId"]?.let { put("sourceRowId", it) }
            row["tableHighWatermark"]?.let { put("sourceHighWatermark", it) }
        }
    }

    fun journal(event: RunDiagnosticEvent): JsonObject = buildJsonObject {
        put("kind", "lifecycle_observation")
        put("eventId", "run_journal:${event.eventId}")
        put("sourceEventId", event.eventId)
        put("source", "run_journal")
        put("runId", event.runId)
        event.invocationId?.let { put("invocationId", it) }
        put("streamId", event.streamId)
        put("sequence", event.sequence)
        put("timestampMillis", event.capturedAtMillis)
        put("observation", event.kind.name)
        put("sourceFile", "run-journal.jsonl")
        put("processSession", event.processSession)
        event.monotonicNanos?.let { put("monotonicNanos", it) }
        event.fence?.let { put("ownershipFence", it) }
        event.outputPartId?.let { put("outputPartId", it) }
        put("outputOrigin", event.outputOrigin.name)
        drawScope(event.kind.name, event.measured, event.invocationId, event.outputOrigin.name)
    }

    /** Captured JSON is already redacted; select metadata without decoding payload receipts again. */
    fun journal(row: JsonObject): JsonObject? {
        fun text(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        val id = text("eventId") ?: return null
        val run = text("runId") ?: return null
        val stream = text("streamId") ?: return null
        val sequence = (row["sequence"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return null
        val capturedAt = (row["capturedAtMillis"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 } ?: return null
        val kind = text("kind") ?: return null
        return buildJsonObject {
            put("kind", "lifecycle_observation")
            put("eventId", "run_journal:$id")
            put("sourceEventId", id)
            put("source", "run_journal")
            put("runId", run)
            text("invocationId")?.let { put("invocationId", it) }
            put("streamId", stream)
            put("sequence", sequence)
            put("timestampMillis", capturedAt)
            put("observation", kind)
            put("sourceFile", "run-journal.jsonl")
            text("processSession")?.let { put("processSession", it) }
            (row["monotonicNanos"] as? JsonPrimitive)?.longOrNull?.let { put("monotonicNanos", it) }
            (row["fence"] as? JsonPrimitive)?.longOrNull?.let { put("ownershipFence", it) }
            text("outputPartId")?.let { put("outputPartId", it) }
            val origin = text("outputOrigin")?.takeIf { name -> OutputOrigin.entries.any { it.name == name } } ?: OutputOrigin.UNKNOWN.name
            put("outputOrigin", origin)
            drawScope(kind, (row["measured"] as? JsonPrimitive)?.booleanOrNull, text("invocationId"), origin)
        }
    }

    private fun JsonObjectBuilder.drawScope(kind: String, measured: Boolean?, invocationId: String?, origin: String) {
        if (kind != "FIRST_VISIBLE_TEXT") return
        put("measurementScope", "rendered_run_stream")
        put("measured", measured?.let(::JsonPrimitive) ?: JsonNull)
        put("observedBoundary", if (measured == true) "COMPOSE_DRAW_CONTENT_RETURNED" else "LEGACY_DRAW_OBSERVATION")
        put("clockOrigin", "processSession_monotonicNanos")
        put("invocationAssociation", if (invocationId == null) "UNLINKED" else "EXPLICIT")
        put("providerLatencyCandidate", measured == true && origin == OutputOrigin.MODEL_TEXT.name && invocationId != null)
        put("providerLatencyRequires", "MATCHED_INVOCATION_STARTED_AND_FIRST_TEXT_IN_SAME_PROCESS")
        put("displayPresentationConfirmed", JsonNull)
    }
}
