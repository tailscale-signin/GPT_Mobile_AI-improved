package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatch
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatchState
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStopOrigin
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStorageFailure
import dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditRunnerTest {
    private fun entry(id: String, network: Boolean = false) = AppAuditPlanEntry(
        id = id,
        sourceId = "fixture",
        probeId = id,
        componentId = "benchmark",
        label = "Fixture check",
        sourceAreaIds = listOf("benchmark"),
        evidenceKind = if (network) AppAuditEvidenceKind.LIVE_METADATA else AppAuditEvidenceKind.LOCAL_CONTRACT,
        safetyClass = if (network) AppAuditSafetyClass.NETWORK_READ else AppAuditSafetyClass.LOCAL_READ
    )

    private class Source(val entries: List<AppAuditPlanEntry>, val invoke: suspend (AppAuditPlanEntry, AppAuditProbeContext) -> AppAuditObservation) : AppAuditProbeSource {
        override val sourceId = "fixture"
        override suspend fun plan(options: AppAuditOptions) = entries
        override suspend fun execute(entry: AppAuditPlanEntry, context: AppAuditProbeContext) = invoke(entry, context)
    }

    private fun passed(entry: AppAuditPlanEntry) = AppAuditObservation(
        AppAuditOutcome.PASS,
        "FIXTURE_PASSED",
        evidenceKind = entry.evidenceKind,
        observationSource = if (entry.safetyClass == AppAuditSafetyClass.NETWORK_READ) AppAuditObservationSource.LIVE else AppAuditObservationSource.FIXTURE,
        dispatchEvidence = if (entry.safetyClass == AppAuditSafetyClass.NETWORK_READ) AppAuditDispatchEvidence.CONFIRMED else AppAuditDispatchEvidence.NOT_DISPATCHED
    )

    private class Harness(val catalog: AppAuditCatalog, initial: AppAuditRun) {
        var now = initial.startedAtEpochMs
        var batch = BenchmarkBatch(initial.id, BenchmarkMode.APP_AUDIT, now, emptyList(), audit = initial)
        val events = mutableListOf<String>()
        var failEvent: String? = null
        val runner = AppAuditRunner(
            catalog,
            read = { batch },
            persist = { value, event ->
                if (failEvent == event) {
                    failEvent = null
                    error("Fixture storage unavailable.")
                }
                batch = value
                events += event
            },
            epochMs = { now },
            monotonicMs = { now }
        )
        val audit: AppAuditRun get() = checkNotNull(batch.audit)
        suspend fun run() = runner.run(batch.id) {}
        fun resume() {
            batch = batch.copy(state = BenchmarkBatchState.QUEUED, stopOrigin = null, endedAt = null)
        }
    }

    private suspend fun harness(source: AppAuditProbeSource, network: Boolean = false, limits: AppAuditLimits = AppAuditLimits()): Harness {
        val catalog = AppAuditCatalog(setOf(source))
        return Harness(catalog, catalog.create(AppAuditOptions(network), limits, id = "audit-fixture", now = 1_000))
    }

    @Test fun aProfileIndependentPlanAccountsForEverySourceAreaWithoutLineCoverage() = runBlocking {
        val catalog = AppAuditCatalog(emptySet())
        val run = catalog.create(id = "no-profile", now = 1_000)
        assertTrue(run.options.selectedProfileIds.isEmpty())
        assertEquals(AppAuditSourceInventory.areas.map { it.id }.toSet(), run.plan.flatMap { it.sourceAreaIds }.toSet())
        assertEquals(AppAuditSourceInventory.areas, run.sourceAreas)
        assertEquals(AppAuditSourceInventory.sourceDigest, run.sourceInventoryDigest)
        assertFalse(run.lineCoverageAvailable)
        val h = Harness(catalog, run)
        h.run()
        assertEquals(run.plan.size, appAuditSummary(h.audit).untested)
        assertEquals(BenchmarkBatchState.COMPLETED, h.batch.state)
        assertNull(appAuditScore(h.audit).score)
    }

    @Test fun probeEntryAndDispatchAreCommittedBeforeTheActualRead() = runBlocking {
        lateinit var h: Harness
        var calls = 0
        val source = Source(listOf(entry("live", true))) { e, context ->
            assertEquals(e.id, h.audit.activeProbeId)
            assertEquals("AUDIT_PROBE_STARTED", h.events.last())
            context.beforeDispatch()
            assertEquals("AUDIT_DISPATCH_STARTED", h.events.last())
            assertEquals(AppAuditDispatchEvidence.ATTEMPTED, h.audit.activeDispatchEvidence)
            calls++
            passed(e)
        }
        h = harness(source, network = true)
        h.run()
        assertEquals(1, calls)
        assertEquals(1, h.audit.networkProbesDispatched)
        assertEquals(AppAuditDispatchEvidence.CONFIRMED, h.audit.results.first().dispatchEvidence)
        assertEquals(1, appAuditSummary(h.audit).exercised)
        assertEquals(1, appAuditScore(h.audit).measuredWeight)
    }

    @Test fun defaultOptionsNeverDispatchRemoteOrModelWork() = runBlocking {
        var calls = 0
        val network = entry("remote", true)
        val model = entry("model").copy(safetyClass = AppAuditSafetyClass.MODEL_REQUEST, evidenceKind = AppAuditEvidenceKind.MODEL_EXECUTION)
        val h = harness(
            Source(listOf(network, model)) { e, _ ->
                calls++
                passed(e)
            }
        )
        h.run()
        assertEquals(0, calls)
        assertEquals(AppAuditOutcome.SKIPPED, h.audit.results.first { it.entryId == "remote" }.outcome)
        assertEquals(AppAuditOutcome.UNTESTED, h.audit.results.first { it.entryId == "model" }.outcome)
        assertNull(appAuditScore(h.audit).score)
    }

    @Test fun prerequisitesAndUnselectedChecksRemainSeparateOutcomes() = runBlocking {
        val entries = listOf(
            entry("missing").copy(prerequisite = AppAuditPrerequisite.NOT_CONFIGURED),
            entry("permission").copy(prerequisite = AppAuditPrerequisite.PERMISSION_REQUIRED),
            entry("external").copy(prerequisite = AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED),
            entry("unselected").copy(selected = false)
        )
        val h = harness(Source(entries) { _, _ -> error("A rejected probe ran.") })
        h.run()
        assertEquals(listOf(AppAuditOutcome.UNAVAILABLE, AppAuditOutcome.BLOCKED, AppAuditOutcome.UNTESTED, AppAuditOutcome.SKIPPED), h.audit.results.take(4).map { it.outcome })
        assertTrue(h.audit.results.all { !it.attempted })
        assertNull(appAuditScore(h.audit).score)
    }

    @Test fun callerCancellationRetainsUnknownAndResumeNeverReplaysIt() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val visited = mutableListOf<String>()
        val source = Source(listOf(entry("first", true), entry("second", true))) { e, context ->
            context.beforeDispatch()
            visited += e.id
            if (e.id == "first") {
                entered.complete(Unit)
                awaitCancellation()
            }
            passed(e)
        }
        val h = harness(source, network = true)
        val worker = launch { h.run() }
        entered.await()
        h.batch = h.batch.copy(stopOrigin = BenchmarkStopOrigin.USER_STOP)
        worker.cancelAndJoin()
        assertTrue(worker.isCancelled)
        assertEquals(BenchmarkBatchState.STOPPED, h.batch.state)
        assertTrue(h.batch.canResume)
        val retained = h.audit.results.single()
        assertEquals(AppAuditOutcome.CANCELED, retained.outcome)
        assertEquals(AppAuditDispatchEvidence.UNKNOWN, retained.dispatchEvidence)
        h.resume()
        h.run()
        assertEquals(listOf("first", "second"), visited)
        assertEquals(retained, h.audit.results.first())
        assertEquals(2, h.audit.executedProbes)
        assertEquals(2, h.audit.networkProbesDispatched)
    }

    @Test fun dispatchCheckpointFailurePreventsTheReadAndRecoveryDoesNotReplayTheStartedProbe() = runBlocking {
        var reads = 0
        val h = harness(
            Source(listOf(entry("first", true), entry("second", true))) { e, context ->
                context.beforeDispatch()
                reads++
                passed(e)
            },
            network = true
        )
        h.failEvent = "AUDIT_DISPATCH_STARTED"
        try {
            h.run()
            error("Expected storage failure")
        } catch (_: BenchmarkStorageFailure) {
            assertEquals(0, reads)
        }
        h.batch = recoverAppAuditBatch(h.batch, h.now)
        assertEquals(AppAuditDispatchEvidence.NOT_DISPATCHED, h.audit.results.first().dispatchEvidence)
        h.resume()
        h.run()
        assertEquals(1, reads)
        assertEquals(listOf("first", "second"), h.audit.results.take(2).map { it.entryId })
    }

    @Test fun resultCheckpointFailureRetainsUnknownInsteadOfReplayingSuccessfulRead() = runBlocking {
        var reads = 0
        val h = harness(
            Source(listOf(entry("first", true), entry("second", true))) { e, context ->
                context.beforeDispatch()
                reads++
                passed(e)
            },
            network = true
        )
        h.failEvent = "AUDIT_PROBE_FINISHED"
        try {
            h.run()
            error("Expected storage failure")
        } catch (_: BenchmarkStorageFailure) {
            assertEquals(1, reads)
        }
        h.batch = recoverAppAuditBatch(h.batch, h.now)
        assertEquals(AppAuditDispatchEvidence.UNKNOWN, h.audit.results.first().dispatchEvidence)
        assertNull(h.audit.results.first().durationMs)
        h.resume()
        h.run()
        assertEquals(2, reads)
        assertEquals(2, h.audit.networkProbesDispatched)
    }

    @Test fun policyFailureAfterAReadPreservesUnknownAndStopsAllRemainingChecks() = runBlocking {
        var reads = 0
        val h = harness(
            Source(listOf(entry("first", true), entry("second", true))) { _, context ->
                context.beforeDispatch()
                reads++
                throw DispatchPolicyChangedException(listOf("profile"))
            },
            network = true
        )
        h.run()
        assertEquals(1, reads)
        val actual = h.audit.results.first()
        assertEquals(AppAuditOutcome.BLOCKED, actual.outcome)
        assertEquals("execution", actual.observation.stage)
        assertEquals(AppAuditDispatchEvidence.UNKNOWN, actual.dispatchEvidence)
        assertEquals("DISPATCH_POLICY_CHANGED", actual.reasonCode)
        assertTrue(h.audit.results.drop(1).all { it.reasonCode == "CONFIGURATION_CHANGED" && !it.attempted })
    }

    @Test fun networkProbeAllowanceIsNotResetAcrossAnInterruptedRun() = runBlocking {
        var reads = 0
        val h = harness(
            Source(listOf(entry("first", true), entry("second", true))) { e, context ->
                context.beforeDispatch()
                reads++
                passed(e)
            },
            network = true,
            limits = AppAuditLimits(maxNetworkProbes = 1)
        )
        h.run()
        assertEquals(1, reads)
        assertEquals("NETWORK_BUDGET_EXHAUSTED", h.audit.results.first { it.entryId == "second" }.reasonCode)
        assertEquals(1, h.audit.networkProbesDispatched)
    }

    @Test fun totalDeadlineAndExecutionAllowanceLeaveExplicitUntestedEntries() = runBlocking {
        var calls = 0
        val h = harness(
            Source(listOf(entry("first"), entry("second"))) { e, _ ->
                calls++
                passed(e)
            },
            limits = AppAuditLimits(maxExecutedProbes = 1)
        )
        h.run()
        assertEquals(1, calls)
        assertEquals("RUN_BUDGET_EXHAUSTED", h.audit.results.first { it.entryId == "second" }.reasonCode)
        val expired = harness(
            Source(listOf(entry("never"))) { e, _ ->
                calls++
                passed(e)
            }
        )
        expired.now = expired.audit.deadlineAtEpochMs
        expired.run()
        assertEquals(1, calls)
        assertTrue(expired.audit.results.all { it.outcome == AppAuditOutcome.UNTESTED && it.reasonCode == "RUN_DEADLINE_EXCEEDED" })
    }

    @Test fun changedSourceOrAcceptedLimitsNeverDispatchOrAppendANewPlan() = runBlocking {
        var calls = 0
        for (changeSource in listOf(true, false)) {
            val h = harness(
                Source(listOf(entry("never"))) { e, _ ->
                    calls++
                    passed(e)
                }
            )
            val originalPlan = h.audit.plan
            h.batch = h.batch.copy(audit = if (changeSource) h.audit.copy(sourceInventoryDigest = "changed") else h.audit.copy(limits = h.audit.limits.copy(maxExecutedProbes = 2)))
            h.run()
            assertEquals(BenchmarkBatchState.COMPLETED_WITH_FAILURES, h.batch.state)
            assertEquals(originalPlan, h.audit.plan)
            assertTrue(h.audit.results.all { it.outcome == AppAuditOutcome.BLOCKED && !it.attempted })
        }
        assertEquals(0, calls)
    }

    @Test fun aMissingAuditPayloadIsAnExplicitFailure() = runBlocking {
        val h = harness(Source(emptyList()) { _, _ -> error("No probe") })
        h.batch = h.batch.copy(audit = null)
        h.run()
        assertEquals(BenchmarkBatchState.COMPLETED_WITH_FAILURES, h.batch.state)
        assertNotNull(h.batch.storageWarning)
    }

    @Test fun liveEvidenceWithoutADurableDispatchMarkerIsUnknownAndStopsTheQueue() = runBlocking {
        var calls = 0
        val h = harness(
            Source(listOf(entry("first", true), entry("second", true))) { e, _ ->
                calls++
                passed(e)
            },
            network = true
        )
        h.run()
        assertEquals(1, calls)
        assertEquals("DISPATCH_RECEIPT_MISSING", h.audit.results.first().reasonCode)
        assertEquals(AppAuditDispatchEvidence.UNKNOWN, h.audit.results.first().dispatchEvidence)
        assertEquals("PROBE_CONTRACT_INVALID", h.audit.results[1].reasonCode)
        assertNull(appAuditScore(h.audit).score)
    }

    @Test fun aBoundedProbeTimeoutRetainsUnknownWithoutCancelingTheParent() = runTest {
        val h = harness(
            Source(listOf(entry("timeout", true))) { _, context ->
                context.beforeDispatch()
                awaitCancellation()
            },
            network = true,
            limits = AppAuditLimits(perProbeTimeoutMs = 10)
        )
        h.run()
        val result = h.audit.results.first()
        assertEquals(AppAuditOutcome.FAIL, result.outcome)
        assertEquals("PROBE_TIMEOUT", result.reasonCode)
        assertEquals(AppAuditDispatchEvidence.UNKNOWN, result.dispatchEvidence)
        assertEquals(1, h.audit.networkProbesDispatched)
    }

    @Test fun aPlanningTimeoutBecomesAnUnavailableInventoryButCallerCancellationPropagates() = runTest {
        val source = object : AppAuditProbeSource {
            override val sourceId = "slow"
            override suspend fun plan(options: AppAuditOptions): List<AppAuditPlanEntry> = awaitCancellation()
            override suspend fun execute(entry: AppAuditPlanEntry, context: AppAuditProbeContext): AppAuditObservation = error("Unavailable")
        }
        val run = AppAuditCatalog(setOf(source)).create(now = 1_000)
        assertEquals("INVENTORY_UNAVAILABLE", run.plan.first().reasonCode)
        val worker = launch {
            AppAuditCatalog(setOf(source)).create(now = 1_000)
            error("Canceled planning returned")
        }
        testScheduler.runCurrent()
        worker.cancelAndJoin()
        assertTrue(worker.isCancelled)
    }
}
