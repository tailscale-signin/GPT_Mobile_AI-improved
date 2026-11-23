package dev.chungjungsoo.gptmobile.data.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkRestoreOwnershipTest {
    @Test fun pendingRequestProtectsItsWholeFrozenBatchIncludingFutureSiblingIds() {
        val canonical = canonical()
        val ownership = benchmarkRestoreOwnership(canonical)
        assertEquals(canonical.batches.single().items.map { it.runId }.toSet(), ownership.runIds)
        assertEquals(setOf(canonical.batches.single().id), ownership.batchIds)
        val future = canonical.batches.single().items.last().runId
        val incoming = BenchmarkRestoreState(runs = listOf(canonical.runs.first().copy(id = future, samples = emptyList())))
        assertEquals(canonical, benchmarkMergeRestoreOwnership(canonical, incoming))
    }

    @Test fun incomingMatchingIdsCannotReplaceTheOriginalOwnerOrItsReceipts() {
        val canonical = canonical()
        val incoming = canonical.copy(
            runs = canonical.runs.map { it.copy(model = "imported-model", samples = emptyList(), finished = true) },
            batches = canonical.batches.map { it.copy(engineeringPlan = null, items = emptyList()) },
            attempts = canonical.attempts.map { it.copy(logicalRequestId = "different-request", sample = null) },
            events = canonical.events.map { it.copy(type = "IMPORT") }
        )
        assertEquals(canonical, benchmarkMergeRestoreOwnership(canonical, incoming))
    }

    @Test fun foreignBatchCannotAliasAnOwnedRunOrItsFrozenSibling() {
        val canonical = canonical()
        for (ownedId in listOf(canonical.runs.first().id, canonical.batches.single().items.last().runId)) {
            val foreign = foreign(canonical)
            val incoming = foreign.copy(batches = foreign.batches.map { batch -> batch.copy(items = batch.items + batch.items.first().copy(ordinal = 1, runId = ownedId)) })
            assertEquals(canonical, benchmarkMergeRestoreOwnership(canonical, incoming))
        }
    }

    @Test fun globalAttemptIdCannotBeReboundToAnotherImportedBatch() {
        val canonical = canonical()
        val foreign = foreign(canonical)
        val incoming = foreign.copy(attempts = foreign.attempts.map { it.copy(id = canonical.attempts.single().id) })
        assertEquals(canonical, benchmarkMergeRestoreOwnership(canonical, incoming))
    }

    @Test fun sampleOrEventCannotAliasAnOriginalAttempt() {
        val canonical = canonical()
        val foreign = foreign(canonical)
        val attemptId = canonical.attempts.single().id
        val sampleAlias = foreign.copy(runs = foreign.runs.map { run -> run.copy(samples = run.samples.map { it.copy(attemptId = attemptId) }) })
        val eventAlias = foreign.copy(events = foreign.events.map { it.copy(attemptId = attemptId) })
        assertEquals(canonical, benchmarkMergeRestoreOwnership(canonical, sampleAlias))
        assertEquals(canonical, benchmarkMergeRestoreOwnership(canonical, eventAlias))
    }

    @Test fun unrelatedHistoryIsRestoredWhileUnprotectedLocalHistoryCanBeReplaced() {
        val canonical = canonical()
        val unrelatedLocal = canonical.runs.single { run -> run.samples.none { it.engineeringCleanupPending() } }.copy(id = "replaceable-local", batchId = null)
        val incoming = foreign(canonical)
        val merged = benchmarkMergeRestoreOwnership(canonical.copy(runs = canonical.runs + unrelatedLocal), incoming)
        assertEquals((canonical.runs + incoming.runs).map { it.id }.toSet(), merged.runs.map { it.id }.toSet())
        assertEquals((canonical.batches + incoming.batches).map { it.id }.toSet(), merged.batches.map { it.id }.toSet())
        assertFalse(merged.runs.any { it.id == unrelatedLocal.id })
        assertTrue(merged.attempts.containsAll(canonical.attempts + incoming.attempts))
        assertTrue(merged.events.containsAll(canonical.events + incoming.events))
    }

    @Test fun confirmedCleanupAndOrdinaryHistoryKeepNormalReplacementSemantics() {
        val canonical = canonical()
        for (receipt in listOf("gateway:terminal_readback_confirmed", "gateway:no_submission_dispatched")) {
            val settled = canonical.copy(runs = canonical.runs.map { run -> run.copy(samples = run.samples.map { it.copy(cleanupReceipt = receipt) }) })
            val incoming = foreign(canonical)
            assertEquals(incoming, benchmarkMergeRestoreOwnership(settled, incoming))
        }
        val ordinary = canonical.copy(runs = canonical.runs.map { run -> run.copy(samples = emptyList()) })
        assertEquals(BenchmarkRestoreState(), benchmarkMergeRestoreOwnership(ordinary, BenchmarkRestoreState()))
    }

    @Test fun missingOwnerMetadataIsPreservedWithoutAdoptingAnImportedReplacement() {
        val canonical = canonical()
        val orphan = canonical.runs.single { run -> run.samples.any { it.engineeringCleanupPending() } }.copy(id = "orphan", batchId = "missing-original-batch")
        val incoming = foreign(canonical).copy(batches = listOf(canonical.batches.single().copy(id = "missing-original-batch")))
        val merged = benchmarkMergeRestoreOwnership(canonical.copy(runs = canonical.runs + orphan), incoming)
        assertTrue(orphan in merged.runs)
        assertFalse(merged.batches.any { it.id == "missing-original-batch" })
    }

    @Test fun linkedImportedAliasesAreExcludedWithoutChangingTheirOriginalOwner() {
        val canonical = canonical()
        val foreign = foreign(canonical)
        var link = canonical.runs.first().id
        val aliases = (0 until 500).map { index ->
            val runId = "alias-$index"
            foreign.batches.single().copy(id = "alias-batch-$index", items = listOf(foreign.batches.single().items.first().copy(runId = link), foreign.batches.single().items.first().copy(ordinal = 1, runId = runId))).also { link = runId }
        }.reversed()
        assertEquals(canonical, benchmarkMergeRestoreOwnership(canonical, BenchmarkRestoreState(batches = aliases)))
    }

    private fun canonical(): BenchmarkRestoreState {
        val fixture = BenchmarkEngineeringTestFixtures(lane = BenchmarkEngineeringLane.LIVE_GITHUB, families = 1, repeats = 2)
        val queue = BenchmarkEngineeringRegistry.queue(fixture.profiles, fixture.plan)
        val runs = queue.take(2).map { item ->
            fixture.runs().first { it.profileUid == item.profile.uid && it.engineeringRepeatIndex == item.engineeringRepeatIndex }.copy(id = item.runId, queueOrdinal = item.ordinal)
        }
        val first = runs.first()
        val sample = first.samples.single().copy(
            outcome = BenchmarkOutcome.PARTIAL,
            engineeringVerification = null,
            cleanupReceipt = "gateway:submission_outcome_unknown",
            attemptId = "original-attempt",
            logicalRequestId = "original-request",
            engineeringAttempts = listOf(BenchmarkEngineeringAttemptReceipt(0, "original-request", benchmarkHash("candidate"), null, "VERIFICATION_PENDING", false, 17, 23, 100))
        )
        val pending = first.copy(samples = listOf(sample), finished = false, endedAt = null, state = "AWAITING_REMOTE_RECONCILIATION")
        val batch = BenchmarkBatch(fixture.plan.id, BenchmarkMode.GITHUB_INTEGRATION, first.startedAt, queue, state = BenchmarkBatchState.INTERRUPTED, caseManifest = fixture.cases, engineeringPlan = fixture.plan)
        val attempt = BenchmarkAttempt("original-attempt", batch.id, 0, pending.id, sample.testId, 0, 1, 100, sample = sample, logicalRequestId = "original-request")
        return BenchmarkRestoreState(listOf(runs.last(), pending).sortedByDescending { it.startedAt }, listOf(batch), listOf(attempt), listOf(BenchmarkBatchEvent(batch.id, 1, "GRADE_PENDING", 2, pending.id, attempt.id)))
    }

    private fun foreign(canonical: BenchmarkRestoreState): BenchmarkRestoreState {
        val run = canonical.runs.first().copy(id = "foreign-run", batchId = "foreign-batch", samples = canonical.runs.first().samples.map { it.copy(attemptId = "foreign-attempt") })
        val batch = canonical.batches.single().copy(id = "foreign-batch", items = listOf(canonical.batches.single().items.first().copy(runId = run.id)))
        val attempt = canonical.attempts.single().copy(id = "foreign-attempt", batchId = batch.id, runId = run.id)
        return BenchmarkRestoreState(listOf(run), listOf(batch), listOf(attempt), listOf(BenchmarkBatchEvent(batch.id, 1, "IMPORT", 3, run.id, attempt.id)))
    }
}
