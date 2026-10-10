package dev.chungjungsoo.gptmobile.data.network.gateway

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayCancellationSemanticsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun requestedCancellationIsNotReportedAsConfirmed() {
        val result = json.decodeFromString<GatewayCancelResult>(
            """{"job_id":"job-1","canceled":true,"status":"cancellation_requested"}"""
        )
        assertEquals(GatewayCancellationOutcome.REQUESTED, result.outcome)
    }

    @Test fun terminalCanceledAndAlreadyCompletedJobsRemainDistinct() {
        val canceled = json.decodeFromString<GatewayCancelResult>(
            """{"job_id":"job-1","canceled":true,"status":"cancelled"}"""
        )
        val completed = json.decodeFromString<GatewayCancelResult>(
            """{"job_id":"job-1","canceled":false,"status":"completed"}"""
        )
        assertEquals(GatewayCancellationOutcome.CONFIRMED, canceled.outcome)
        assertEquals(GatewayCancellationOutcome.NOT_CANCELED, completed.outcome)
    }

    @Test fun legacyBooleanWithoutStatusIsConservative() {
        val result = json.decodeFromString<GatewayCancelResult>("""{"job_id":"job-1","canceled":true}""")
        assertEquals(GatewayCancellationOutcome.UNKNOWN, result.outcome)
        val unresolved = json.decodeFromString<GatewayCancelResult>("""{"job_id":"job-1","canceled":false}""")
        assertEquals(GatewayCancellationOutcome.UNKNOWN, unresolved.outcome)
    }

    @Test fun explicitOutcomeExtensionTakesPrecedence() {
        val result = json.decodeFromString<GatewayCancelResult>(
            """{"job_id":"job-1","canceled":false,"status":"running","cancellationRequested":true,"cancellationOutcome":"requested"}"""
        )
        assertEquals(GatewayCancellationOutcome.REQUESTED, result.outcome)
    }
}
