package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticExportSummaryTest {
    @Test fun failedTypedAdmissionCountsItsExplicitNotDispatchedEvidenceForEachInvocation() {
        val summary = DiagnosticExportSummary()
        repeat(4) { index ->
            val message = structuredDiagnosticEventMessage(
                "INVOCATION_FAILED",
                mapOf("status" to "FAILED", "invocationId" to "invoke-$index", "errorCode" to "REASONING_CAPABILITY_UNAVAILABLE", "failedStage" to "provider_admission", "dispatched" to false)
            )
            val line = structuredDiagnosticLine("2026-11-09T03:37:41Z E/Model: $message")
            summary.accept(line)
            summary.accept(line)
        }
        val counts = Json.parseToJsonElement(summary.line(0)).jsonObject
        assertEquals(JsonPrimitive(4), counts["failedModelAttempts"])
        assertEquals(JsonPrimitive(4), counts["notDispatchedModelAttempts"])
    }

    @Test fun failedStatusOrUnknownOutcomeAloneDoesNotInventAnUnsentRequest() {
        val summary = DiagnosticExportSummary()
        listOf("dispatched=true", "dispatchState=UNKNOWN_OUTCOME", "errorCode=TIMEOUT").forEachIndexed { index, evidence ->
            summary.accept(structuredDiagnosticLine("2026-11-09T03:37:41Z E/Model: INVOCATION_FAILED · invocationId=invoke-$index · status=FAILED · $evidence"))
        }
        val counts = Json.parseToJsonElement(summary.line(0)).jsonObject
        assertEquals(JsonPrimitive(3), counts["failedModelAttempts"])
        assertEquals(JsonPrimitive(0), counts["notDispatchedModelAttempts"])
    }

    @Test fun oneRejectedAttemptRetainsEveryFailureObservationWithoutTriplingIncidentCount() {
        val tracker = DiagnosticIncidentTracker()
        val summary = DiagnosticExportSummary()
        fun event(id: String, tag: String, level: String, name: String, fields: Map<String, Any?>): DiagnosticIncidentTracker.Association {
            val line = "2026-11-08T14:19:40Z $level/$tag: eventId=$id · " + structuredDiagnosticEventMessage(name, fields)
            val association = tracker.accept(line, id, null)
            summary.accept(structuredDiagnosticLine(line, "current.log", captureIdentity = id, incidentId = association.id, incidentRoot = association.first))
            return association
        }
        val readiness = event("readiness", "LocalConnection", "W", "LLAMA_READINESS", mapOf("runId" to "run-1", "invocationId" to "invoke-1", "gatewayReady" to false))
        val model = event("finish", "Model", "W", "INVOCATION_FINISHED", mapOf("runId" to "run-1", "invocationId" to "invoke-1", "incidentId" to "invoke-1", "status" to "NOT_DISPATCHED"))
        val terminal = event("terminal", "Agent", "I", "RUN_TERMINAL", mapOf("runId" to "run-1", "invocationId" to "invoke-1", "causalIncidentId" to "invoke-1", "status" to "FAILED", "reason" to "REASONING_OFF_UNSUPPORTED"))
        assertTrue(readiness.first)
        assertFalse(model.first)
        assertFalse(terminal.first)
        assertEquals(readiness.id, model.id)
        assertEquals(readiness.id, terminal.id)
        summary.accept("""{"kind":"duplicate_reference_batch","referenceCount":1,"references":[]}""")
        val counts = Json.parseToJsonElement(summary.line(4)).jsonObject
        assertEquals(JsonPrimitive(3L), counts["observedFailureRecords"])
        assertEquals(JsonPrimitive(1), counts["correlatedIncidents"])
        assertEquals(JsonPrimitive(1), counts["failedRunAttempts"])
        assertEquals(JsonPrimitive(1), counts["failedModelAttempts"])
        assertEquals(JsonPrimitive(1), counts["notDispatchedModelAttempts"])
        assertEquals(JsonPrimitive(true), counts["sourceCountsReconcile"])
        assertEquals(JsonNull, counts["independentDefectCount"])
    }

    @Test fun separatePhysicalAttemptsStaySeparateAndSimilarTextDoesNotInventACausalLink() {
        val tracker = DiagnosticIncidentTracker()
        fun record(id: String, message: String) = tracker.accept("2026-11-08T14:19:40Z E/Model: $message", id, null)
        val first = record("first", "Segment invoke-1 · status=FAILED · connection failed")
        val finish = record("finish", "Finished invoke-1 · incidentId=invoke-1 · status=FAILED · parentRunId=run-1")
        val second = record("second", "Segment invoke-2 · status=FAILED · connection failed")
        val unlinked = tracker.accept("2026-11-08T14:19:40Z I/Agent: RUN_TERMINAL · runId=run-1 · status=FAILED · reason=CONNECTION", "unlinked", null)
        assertEquals(first.id, finish.id)
        assertFalse(finish.first)
        assertTrue(second.first)
        assertTrue(unlinked.first)
        assertEquals("unlinked", unlinked.id)
    }

    @Test fun identityLimitIsExplicitRatherThanClaimingAnExactFailureCount() {
        val summary = DiagnosticExportSummary(maximumIdentities = 1)
        repeat(2) { index ->
            summary.accept(structuredDiagnosticLine("2026-11-08T14:19:40Z I/Agent: RUN_TERMINAL · runId=run-$index · status=FAILED · reason=CONNECTION"))
        }
        val counts = Json.parseToJsonElement(summary.line(0)).jsonObject
        assertEquals(JsonPrimitive("IDENTITY_LIMIT_LOWER_BOUND"), counts["countCoverage"])
        assertEquals(JsonPrimitive(1), counts["failedRunAttempts"])
    }

    @Test fun missingAttemptIdentitiesAreReportedAsUnknownInsteadOfInventingCounts() {
        val summary = DiagnosticExportSummary()
        summary.accept(structuredDiagnosticLine("2026-11-08T14:19:40Z I/Agent: RUN_TERMINAL · status=FAILED · reason=CONNECTION"))
        summary.accept(structuredDiagnosticLine("2026-11-08T14:19:40Z W/Model: INVOCATION_FINISHED · status=FAILED"))
        val counts = Json.parseToJsonElement(summary.line(0)).jsonObject
        assertEquals(JsonPrimitive("UNIDENTIFIED_ATTEMPTS_LOWER_BOUND"), counts["countCoverage"])
        assertEquals(JsonPrimitive(0), counts["failedRunAttempts"])
        assertEquals(JsonPrimitive(0), counts["failedModelAttempts"])
        assertEquals(JsonPrimitive(1L), counts["unidentifiedFailedRunRecords"])
        assertEquals(JsonPrimitive(1L), counts["unidentifiedFailedModelRecords"])
    }
}
