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
        assertEquals(2, result.fixtureEvidence?.attempted)
        assertEquals(2, result.fixtureEvidence?.passed)
        assertTrue(entry.fixtureHash?.matches(Regex("[a-f0-9]{64}")) == true)
    }

    @Test fun unexpectedExecutionFailureCannotBecomeSuccessOrLeakExceptionValues() = runBlocking {
        val source = source {
            verify(true)
            error("fixture-private-error-canary")
        }
        val result = source.execute(source.plan(AppAuditOptions()).single(), context())
        assertEquals(AppAuditOutcome.FAIL, result.outcome)
        assertEquals("LOCAL_FIXTURE_EXECUTION_FAILED", result.reasonCode)
        assertEquals(AppAuditFixtureStatus.EXECUTION_FAILED, result.fixtureEvidence?.status)
        assertEquals(1.0, result.metrics.single().value, 0.0)
        assertFalse(result.toString().contains("fixture-private-error-canary"))
    }

    @Test fun anEmptyFixtureCannotGrantAPassingResult() = runBlocking {
        val source = source {}
        val result = source.execute(source.plan(AppAuditOptions()).single(), context())
        assertEquals(AppAuditOutcome.BLOCKED, result.outcome)
        assertEquals("LOCAL_FIXTURE_SETUP_FAILED", result.reasonCode)
        assertEquals(AppAuditFixtureStatus.SETUP_BLOCKED, result.fixtureEvidence?.status)
        assertEquals(0.0, result.metrics.single().value, 0.0)
    }

    @Test fun assertionFailureRetainsItsFixedBoundaryAndDoesNotCountTheFailureAsPassed() = runBlocking {
        val expected = AppAuditFixtureState(AppAuditFixtureCode.SUCCESS, dispatched = true, usedCalls = 1)
        val observed = AppAuditFixtureState(AppAuditFixtureCode.BUDGET_EXHAUSTED, dispatched = false, usedCalls = 0)
        val source = source {
            verify(true)
            verify(false, AppAuditAssertionCode.ACCEPTED_CALL_RESERVES_BUDGET, expected, observed)
            verify(true)
        }
        val result = source.execute(source.plan(AppAuditOptions()).single(), context())
        assertEquals(AppAuditOutcome.FAIL, result.outcome)
        assertEquals("LOCAL_CONTRACT_FAILED", result.reasonCode)
        val evidence = checkNotNull(result.fixtureEvidence)
        assertEquals(2, evidence.attempted)
        assertEquals(1, evidence.passed)
        assertEquals(AppAuditAssertionEvidence(2, AppAuditAssertionCode.ACCEPTED_CALL_RESERVES_BUDGET, false, expected, observed), evidence.assertions.last())
    }

    @Test fun policyBlockedFixtureRetainsTheAttemptedBoundaryWithoutBecomingAMeasuredFailure() = runBlocking {
        val source = source {
            verify(
                false,
                AppAuditAssertionCode.ACCEPTED_CALL_RESERVES_BUDGET,
                AppAuditFixtureState(AppAuditFixtureCode.SUCCESS, dispatched = true),
                AppAuditFixtureState(AppAuditFixtureCode.PRIVACY_EXTERNAL_TOOL_BLOCKED, dispatched = false, admission = AppAuditNetworkAdmission.NOT_ADMITTED)
            )
        }
        val entry = source.plan(AppAuditOptions()).single()
        val result = source.execute(entry, context())
        assertEquals(AppAuditOutcome.BLOCKED, result.outcome)
        assertEquals("LOCAL_FIXTURE_POLICY_BLOCKED", result.reasonCode)
        val evidence = checkNotNull(result.fixtureEvidence)
        assertEquals(AppAuditFixtureStatus.SETUP_BLOCKED, evidence.status)
        assertEquals(1, evidence.attempted)
        assertEquals(0, evidence.passed)
        assertTrue(evidence.assertions.single().blocked)
        assertEquals(AppAuditAssertionCode.ACCEPTED_CALL_RESERVES_BUDGET, evidence.assertions.single().code)
        assertEquals(AppAuditFixtureCode.PRIVACY_EXTERNAL_TOOL_BLOCKED, evidence.assertions.single().observed?.code)
        val run = AppAuditRun(
            "fixture", 1, "catalog", "source", "1", "debug", "inventory", 0, 0, listOf(entry),
            results = listOf(AppAuditResult(entry.id, result, 1, 2, 1, true))
        )
        assertEquals(0, appAuditScore(run).measuredWeight)
        assertEquals(0, appAuditScore(run).passedWeight)
    }

    @Test fun longFixtureRetainsTheFailingBoundaryAndHonestOmittedAssertionCount() = runBlocking {
        val source = source {
            repeat(100) { verify(true) }
            verify(false, AppAuditAssertionCode.RUNNER_BUDGET_ISOLATED)
        }
        val result = source.execute(source.plan(AppAuditOptions()).single(), context())
        val evidence = checkNotNull(result.fixtureEvidence)
        assertEquals(101, evidence.attempted)
        assertEquals(100, evidence.passed)
        assertEquals(64, evidence.assertions.size)
        assertEquals(38, evidence.assertions.first().ordinal)
        assertEquals(101, evidence.assertions.last().ordinal)
        assertFalse(evidence.assertions.last().passed)
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
