package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Counts observation records, correlated incidents and attempts separately in a bounded export pass. */
internal class DiagnosticExportSummary(private val maximumIdentities: Int = 16_384, private val incidentCorrelationLimited: Boolean = false) {
    private var eventRecords = 0L
    private var continuationRecords = 0L
    private var duplicateBatches = 0L
    private var duplicateOccurrences = 0L
    private var failureRecords = 0L
    private var representedSourceRecords = 0L
    private var unidentifiedFailedRunRecords = 0L
    private var unidentifiedFailedModelRecords = 0L
    private var identitiesLimited = false
    private val incidents = hashSetOf<String>()
    private val recoveredIncidents = hashSetOf<String>()
    private val failedRuns = hashSetOf<String>()
    private val failedInvocations = hashSetOf<String>()
    private val unsentInvocations = hashSetOf<String>()

    fun accept(line: String) {
        val row = Json.parseToJsonElement(line) as? JsonObject ?: return
        when (row.diagnosticText("kind")) {
            "duplicate_reference_batch" -> {
                duplicateBatches++
                val occurrences = (row["referenceCount"] as? JsonPrimitive)?.longOrNull ?: 0L
                duplicateOccurrences += occurrences
                representedSourceRecords += occurrences
            }
            "event", "continuation" -> {
                if (row.diagnosticText("kind") == "event") eventRecords++ else continuationRecords++
                if (row.diagnosticText("captureSource") in setOf("current.log", "previous.log", "critical.log", "critical-previous.log")) representedSourceRecords++
                if ((row["duplicate"] as? JsonPrimitive)?.booleanOrNull == true) return
                if (row.diagnosticText("incidentOutcome") == "RECOVERED_METADATA_READ") add(recoveredIncidents, row.diagnosticText("incidentId"))
                if ((row["observedFailure"] as? JsonPrimitive)?.booleanOrNull == true) failureRecords++
                if ((row["countAsIssue"] as? JsonPrimitive)?.booleanOrNull == true) add(incidents, row.diagnosticText("incidentId"))
                val fields = row["fields"] as? JsonObject ?: return
                val status = fields.diagnosticText("status")
                if (row.diagnosticText("eventName") == "RUN_TERMINAL" && status in setOf("FAILED", "PARTIAL", "BLOCKED", "EVIDENCE_MISSING")) {
                    val runId = row.diagnosticText("runId") ?: fields.diagnosticText("runId")
                    if (runId == null) unidentifiedFailedRunRecords++ else add(failedRuns, runId)
                }
                if (status in setOf("FAILED", "PARTIAL", "NOT_DISPATCHED")) {
                    val invocationId = row.diagnosticText("invocationId")
                    if (invocationId == null && row.diagnosticText("tag") == "Model") unidentifiedFailedModelRecords++
                    add(failedInvocations, invocationId)
                    // A failed admission retains status=FAILED while its typed
                    // dispatch evidence proves that no request left the process.
                    val execution = diagnosticExecutionEvidence(fields, row.diagnosticText("eventName").orEmpty())
                    if (((row["dispatched"] as? JsonPrimitive)?.booleanOrNull ?: execution.dispatched) == false) {
                        add(unsentInvocations, invocationId)
                    }
                }
            }
        }
    }

    private fun add(values: MutableSet<String>, id: String?) {
        if (id == null || id in values) return
        if (values.size < maximumIdentities) values.add(id) else identitiesLimited = true
    }

    fun line(retainedSourceOccurrences: Long): String = buildJsonObject {
        put("schema", 7)
        put("kind", "diagnostic_export_summary")
        put("countAsIssue", false)
        put("scope", "RETAINED_EXPORT")
        put(
            "countCoverage",
            when {
                identitiesLimited -> "IDENTITY_LIMIT_LOWER_BOUND"
                incidentCorrelationLimited -> "INCIDENT_CORRELATION_WINDOW_LIMIT"
                unidentifiedFailedRunRecords > 0 || unidentifiedFailedModelRecords > 0 -> "UNIDENTIFIED_ATTEMPTS_LOWER_BOUND"
                else -> "COMPLETE_FOR_EXPORTED_RECORDS"
            }
        )
        put("eventRecords", eventRecords)
        put("continuationRecords", continuationRecords)
        put("duplicateReferenceBatches", duplicateBatches)
        put("duplicateOccurrences", duplicateOccurrences)
        put("observedFailureRecords", failureRecords)
        put("correlatedIncidents", incidents.size)
        val recovered = incidents.count { it in recoveredIncidents }
        put("recoveredIncidents", recovered)
        put("unresolvedIncidents", incidents.size - recovered)
        put("failedRunAttempts", failedRuns.size)
        put("failedModelAttempts", failedInvocations.size)
        put("notDispatchedModelAttempts", unsentInvocations.size)
        put("runAttemptCountingUnit", "RUN_ID")
        put("modelAttemptCountingUnit", "INVOCATION_ID")
        put("unidentifiedFailedRunRecords", unidentifiedFailedRunRecords)
        put("unidentifiedFailedModelRecords", unidentifiedFailedModelRecords)
        put("retainedSourceOccurrences", retainedSourceOccurrences)
        put("representedSourceOccurrences", representedSourceRecords)
        put("sourceCountsReconcile", representedSourceRecords == retainedSourceOccurrences)
        put("independentDefectCount", null as Int?)
        put("countingNotes", "A failed run can contain failed model invocations and several failure records. Physical retries may share an invocation ID. Recovered metadata reads retain their observed failure records and correlated incident, but are excluded from unresolved incidents. These counts overlap and are not a count of independent bugs.")
    }.toString()
}
