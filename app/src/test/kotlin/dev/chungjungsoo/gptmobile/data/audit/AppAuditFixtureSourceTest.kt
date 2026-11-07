package dev.chungjungsoo.gptmobile.data.audit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditFixtureSourceTest {
    private fun source(exercise: suspend AppAuditAssertions.() -> Unit) = object : AppAuditFixtureSource(
        "fixture_test",
        listOf(AppAuditFixture("check_v1", "Fixture wrapper contract", listOf("benchmark"), exercise))
    ) {}

    private fun context() = AppAuditProbeContext(AppAuditLimits()) { error("A local fixture must not cross a network dispatch boundary") }

    @Test fun countsOnlyAssertionsActuallyEvaluatedAndMarksFixtureEvidence() = runBlocking {
        val source = source {
            verify(true)
            verify(2 + 2 == 4)
        }
        val entry = source.plan(AppAuditOptions()).single()
        val result = source.execute(entry, context())
        assertEquals(AppAuditOutcome.PASS, result.outcome)
        assertEquals(AppAuditObservationSource.FIXTURE, result.observationSource)
        assertEquals(AppAuditEvidenceKind.LOCAL_CONTRACT, result.evidenceKind)
        assertEquals(AppAuditDispatchEvidence.NOT_DISPATCHED, result.dispatchEvidence)
        assertEquals(2.0, result.metrics.single().value, 0.0)
        assertTrue(entry.fixtureHash?.matches(Regex("[a-f0-9]{64}")) == true)
    }

    @Test fun assertionFailureCannotBecomeSuccessOrLeakExceptionValues() = runBlocking {
        val source = source {
            verify(true)
            error("fixture-private-error-canary")
        }
        val result = source.execute(source.plan(AppAuditOptions()).single(), context())
        assertEquals(AppAuditOutcome.FAIL, result.outcome)
        assertEquals("LOCAL_CONTRACT_FAILED", result.reasonCode)
        assertEquals(1.0, result.metrics.single().value, 0.0)
        assertFalse(result.toString().contains("fixture-private-error-canary"))
    }

    @Test fun anEmptyFixtureCannotGrantAPassingResult() = runBlocking {
        val source = source {}
        val result = source.execute(source.plan(AppAuditOptions()).single(), context())
        assertEquals(AppAuditOutcome.FAIL, result.outcome)
        assertEquals(0.0, result.metrics.single().value, 0.0)
    }

    @Test fun changedDefinitionIsBlockedBeforeItsFixtureExecutes() = runBlocking {
        var executions = 0
        val source = source {
            executions++
            verify(true)
        }
        val entry = source.plan(AppAuditOptions()).single().copy(safetyClass = AppAuditSafetyClass.NETWORK_READ)
        val result = source.execute(entry, context())
        assertEquals(AppAuditOutcome.BLOCKED, result.outcome)
        assertEquals("AUDIT_PROBE_CHANGED", result.reasonCode)
        assertEquals(AppAuditEvidenceKind.LOCAL_CONTRACT, result.evidenceKind)
        assertTrue(result.configChanged)
        assertEquals(0, executions)
    }

    @Test fun callerCancellationIsPropagatedWithoutAResult() = runBlocking {
        val source = source { throw CancellationException("cancelled fixture") }
        val error = runCatching { source.execute(source.plan(AppAuditOptions()).single(), context()) }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }
}
