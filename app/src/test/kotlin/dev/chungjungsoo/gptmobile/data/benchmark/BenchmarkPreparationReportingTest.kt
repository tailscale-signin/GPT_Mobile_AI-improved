package dev.chungjungsoo.gptmobile.data.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkPreparationReportingTest {
    private val plan = UnifiedProfileBenchmarkSuite.cases(0)
    private val startedAt = 1_000L

    private fun run(samples: List<BenchmarkSample> = emptyList()) = BenchmarkRun(
        "run", "profile", "Profile", "OPENROUTER", "model", "configuration", false,
        BenchmarkMode.STANDARD, startedAt, samples, finished = false, suiteVersion = 14,
        measurementVersion = 4, plannedTrials = plan.size, caseManifest = plan,
        executionPolicy = BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1
    )

    private fun sample(test: BenchmarkCase, outcome: BenchmarkOutcome = BenchmarkOutcome.SKIPPED) = BenchmarkSample(
        test.id, test.label, test.category, outcome, warmup = test.warmup, workload = test.workload,
        requestAdmitted = false, requestDispatched = false, failureCategory = "SKIPPED_DEPENDENCY"
    )

    private fun metric(run: BenchmarkRun, id: String = "planned_task_success") = benchmarkMetricResults(listOf(run)).single { it.id == id }

    @Test fun frozenWarmupPopulationIsStableBeforeDuringAndAfterCancellation() {
        assertEquals(26, plan.size)
        assertEquals(1, plan.count { it.warmup })
        val empty = run()
        val during = run(listOf(sample(plan.first())))
        val stopped = run(plan.map { sample(it) }).copy(finished = true, canceled = true, stoppedReason = "Stopped by user.")
        for (current in listOf(empty, during, stopped)) {
            val result = metric(current)
            assertTrue(result.plannedKnown)
            assertEquals(25, result.planned)
            assertEquals(25, result.denominator)
            assertEquals(0, result.numerator)
            assertNull(result.value)
        }
        val completed = run(plan.map { sample(it, BenchmarkOutcome.PASSED).copy(failureCategory = null) }).copy(finished = true)
        assertEquals(25, metric(completed).denominator)
        assertEquals(100.0, metric(completed).value!!, 0.0)
    }

    @Test fun partialLegacySamplesCannotInventTheUnseenWarmupPopulation() {
        val partial = run(listOf(sample(plan.first()))).copy(caseManifest = null)
        assertFalse(metric(partial).plannedKnown)
        assertNull(metric(partial).value)
        assertEquals(0, metric(partial).denominator)
        assertEquals(1, metric(partial).exclusions["PLANNED_POPULATION_UNKNOWN"])
        val completeLegacy = run(plan.map { sample(it) }).copy(caseManifest = null, finished = true)
        assertEquals(25, metric(completeLegacy).planned)
    }

    @Test fun coverageOutcomesPartitionAccountedCasesAndNotStartedIsASeparateAxis() {
        val samples = BenchmarkOutcome.entries.mapIndexed { index, outcome ->
            BenchmarkSample(
                "case-$index",
                "Case",
                "task",
                outcome,
                requestAdmitted = outcome in setOf(BenchmarkOutcome.PASSED, BenchmarkOutcome.FAILED, BenchmarkOutcome.TIMED_OUT, BenchmarkOutcome.PARTIAL),
                requestDispatched = outcome in setOf(BenchmarkOutcome.PASSED, BenchmarkOutcome.FAILED, BenchmarkOutcome.TIMED_OUT, BenchmarkOutcome.PARTIAL)
            )
        }
        val coverage = benchmarkCoverage(run(samples).copy(caseManifest = null, plannedTrials = samples.size))
        assertEquals(samples.size, coverage.accounted)
        assertEquals(coverage.accounted, coverage.passed + coverage.failed + coverage.partial + coverage.blocked + coverage.skipped + coverage.canceled)
        assertEquals(1, coverage.canceled)
        assertEquals(2, coverage.failed)
        assertEquals(3, coverage.blocked)
        assertEquals(2, coverage.notStarted)
    }

    @Test fun retainedPreflightCancellationIsSetupNotAFailedInstructionFixture() {
        val failure = BenchmarkFailure("CHILD_OPERATION_ABORTED", "preflight_admission", "LazyStandaloneCoroutine was cancelled")
        val samples = plan.mapIndexed { index, test ->
            sample(test).copy(failure = failure).let {
                if (index == 0) {
                    it.copy(outcome = BenchmarkOutcome.CANCELED, failureCategory = failure.code, runtimeStage = failure.stage, durationMs = 55_152, endToEndDurationMs = 55_152)
                } else {
                    it
                }
            }
        }
        val canceled = run(samples).copy(finished = true, canceled = true, cancellationOrigin = BenchmarkStopOrigin.USER_STOP, stoppedReason = "Stopped by user.", endedAt = startedAt + 55_249, preflightDurationMs = 55_152)
        val effort = benchmarkTaskEfficiency(canceled)
        assertEquals(0, effort.recordedTasks)
        assertEquals(0, effort.unsuccessfulTasks)
        assertEquals(1, effort.excludedPreparation)
        assertEquals(24, effort.excludedUnattempted)
        assertNull(effort.elapsedMs)
        assertEquals(55_152L, effort.setupElapsedMs)
        assertEquals(55_249L, effort.allInElapsedMs)
        val coverage = benchmarkCoverage(canceled)
        assertEquals(1, coverage.canceled)
        assertEquals(25, coverage.skipped)
        assertEquals(26, coverage.notStarted)
        val correctness = metric(canceled, "answer_correctness")
        assertNull(correctness.value)
        assertNull(correctness.rootIncidentCount)
        assertEquals(24, correctness.exclusions["SKIPPED_DEPENDENCY"])
        assertEquals(1, correctness.exclusions["CHILD_OPERATION_ABORTED"])
        assertEquals(samples, canceled.samples)
        val legacyWarmup = canceled.copy(samples = listOf(samples.first().copy(warmup = true)), preflightDurationMs = null)
        assertEquals(55_152L, benchmarkTaskEfficiency(legacyWarmup).setupElapsedMs)
        assertEquals(0, benchmarkTaskEfficiency(legacyWarmup).recordedTasks)
    }

    @Test fun preparationReceiptHasOneRootIncidentAndNoFixtureEffort() {
        val root = BenchmarkFailure("USER_STOP", "preflight_admission", "Stopped by user.", owner = "user", eventId = "preparation-stop")
        val skipped = plan.map { test ->
            sample(test).copy(
                dispositionOnly = true,
                causedByEventId = root.eventId,
                failureCategory = "RUN_STOPPED_BY_USER",
                failure = BenchmarkFailure("RUN_STOPPED_BY_USER", "not_started", "Not started because the run was stopped.", owner = "user")
            )
        }
        val canceled = run(skipped).copy(
            finished = true,
            canceled = true,
            stoppedReason = root.message,
            cancellationOrigin = BenchmarkStopOrigin.USER_STOP,
            endedAt = startedAt + 55_249,
            preflightDurationMs = 55_152,
            preparation = BenchmarkPreparationReceipt("preparation", "preflight_admission", "CANCELED", startedAt, startedAt + 55_152, 55_152, failure = root)
        )
        val result = metric(canceled, "answer_correctness")
        assertEquals(1, result.rootIncidentCount)
        assertEquals(listOf("preparation-stop"), result.rootFailureEventIds)
        assertEquals(26, result.affectedCaseCount)
        assertEquals(25, result.exclusions["RUN_STOPPED_BY_USER"])
        val effort = benchmarkTaskEfficiency(canceled)
        assertEquals(0, effort.recordedTasks)
        assertEquals(25, effort.excludedUnattempted)
        assertEquals(55_152L, effort.setupElapsedMs)
        assertEquals(55_249L, effort.allInElapsedMs)
        assertNull(effort.elapsedMsPerSuccessfulTask)
    }

    @Test fun genuineCaseAdmissionRejectionKeepsItsEffort() {
        val rejected = sample(plan.first(), BenchmarkOutcome.ERROR).copy(runtimeStage = "admission", durationMs = 300, failureCategory = "RESOURCE_ADMISSION")
        val result = benchmarkTaskEfficiency(run(listOf(rejected)))
        assertEquals(1, result.recordedTasks)
        assertEquals(1, result.unsuccessfulTasks)
        assertEquals(300L, result.elapsedMs)
        assertEquals(0, result.excludedPreparation)
        assertEquals(0, benchmarkCoverage(run(listOf(rejected))).notStarted)
        assertEquals(plan.size - 1, benchmarkCoverage(run(listOf(rejected))).pending)
    }

    @Test fun resumedSetupRetainsBothMeasuredPreparationsWithoutTreatingUnknownAsZero() {
        val resumed = run().copy(
            preflightDurationMs = 30,
            preparation = BenchmarkPreparationReceipt("preparation", "admission", "COMPLETED", startedAt, startedAt + 12, 12),
            readiness = BenchmarkReadinessReceipt(startedAt + 12, startedAt + 19, emptyList(), emptySet())
        )
        assertEquals(37L, benchmarkTaskEfficiency(resumed).setupElapsedMs)
        val interrupted = resumed.copy(
            preflightDurationMs = null,
            preparation = resumed.preparation!!.copy(status = "INTERRUPTED", endedAt = null, elapsedMs = null, lastObservedElapsedMs = 12)
        )
        assertNull(benchmarkTaskEfficiency(interrupted).setupElapsedMs)
        assertEquals(0, benchmarkTaskEfficiency(interrupted).recordedTasks)
    }

    @Test fun admittedIdentityWithoutDispatchDoesNotBecomeADeliveryFailure() {
        val admitted = sample(plan.first(), BenchmarkOutcome.ERROR).copy(
            requestAdmitted = true,
            physicalRequestIds = listOf("admitted-attempt"),
            runtimeStage = "admission",
            durationMs = 300,
            failureCategory = "RESOURCE_ADMISSION"
        )
        val unstarted = run(listOf(admitted))
        assertEquals(1, benchmarkCoverage(unstarted).admitted)
        assertEquals(0, benchmarkCoverage(unstarted).executed)
        assertEquals(0, metric(unstarted, "delivery_completion").denominator)
        assertNull(metric(unstarted, "delivery_completion").value)
        assertEquals(300L, benchmarkTaskEfficiency(unstarted).elapsedMs)
        val rejected = metric(run(listOf(admitted.copy(providerHttpStatus = 403))), "delivery_completion")
        assertEquals(1, rejected.denominator)
        assertEquals(0.0, rejected.value!!, 0.0)
    }

    @Test fun storedPopulationMembershipCannotImplyMeasuredOrRankedCapability() {
        val current = run()
        val snapshot = DynamicScoreEngine.snapshot(listOf(current), 1, startedAt + 100, mapOf("profile" to "configuration"))
        val selected = benchmarkRunSelection(listOf(current), snapshot, null).single()
        assertTrue(selected.capabilityIncluded)
        assertTrue(selected.operationalIncluded)
        assertFalse(selected.hasMeasuredCapability)
        assertFalse(selected.rankingEligible)
        assertTrue(snapshot.rows.single().explanation.contains("Standard uses the nine-category"))
        assertFalse(snapshot.rows.single().explanation.contains("five eligible trials"))
        assertFalse(snapshot.rows.single().explanation.contains("no verified cohort anchor"))
    }

    @Test fun measuredStandardCapabilityRequiresRetainedValidatorEvidence() {
        val test = plan.first { it.category == "task" }
        val observed = sample(test, BenchmarkOutcome.PASSED).copy(
            requestAdmitted = true, requestDispatched = true, terminalReceived = true,
            failureCategory = null, outputCharacters = 2, preview = "12",
            fixtureContentHash = test.contentHash, validatorVersion = test.validatorVersion,
            validation = BenchmarkValidationResult(test.validatorVersion, true), evidenceScope = "SYNTHETIC_BENCHMARK"
        )
        fun selection(source: BenchmarkRun): BenchmarkRunSelection = benchmarkRunSelection(
            listOf(source),
            DynamicScoreEngine.snapshot(listOf(source), 1, startedAt + 100, mapOf("profile" to "configuration")),
            null
        ).single()
        val partial = selection(run(listOf(observed)))
        assertTrue(partial.hasMeasuredCapability)
        assertFalse(partial.rankingEligible)
        assertFalse(selection(run(listOf(observed.copy(validation = null)))).hasMeasuredCapability)
        assertFalse(selection(run(plan.map { sample(it) }).copy(finished = true)).hasMeasuredCapability)
    }
}
