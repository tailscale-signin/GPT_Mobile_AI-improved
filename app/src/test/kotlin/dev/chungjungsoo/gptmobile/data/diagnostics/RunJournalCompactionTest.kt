package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.security.SecretVault
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunJournalCompactionTest {
    private fun TestScope.journal(vault: Vault) = RunDiagnosticJournal(vault, backgroundScope, coalesceMillis = 0).apply {
        enabled = true
        setRunPrivacy("run", false)
    }

    private fun TestScope.fragment(journal: RunDiagnosticJournal, count: Int = 20) {
        repeat(count) {
            journal.record("run", RunDiagnosticKind.REQUEST_SERIALIZED)
            runCurrent()
        }
    }

    @Test fun terminalIsDurableBeforeCompactionAndRepeatedExportsReuseImmutableSegments() = runTest {
        val vault = Vault()
        val journal = journal(vault)
        fragment(journal)
        assertTrue(vault.rows.keys.none(RunJournalCompaction::isCache))
        journal.record("run", RunDiagnosticKind.RUN_TERMINAL, successful = true)
        runCurrent()
        assertEquals(21, vault.rows.keys.count { it.startsWith("journal-") })
        val firstPacked = vault.writes.indexOfFirst { it.startsWith("journalpacked-") }
        assertTrue(firstPacked > vault.writes.indexOfLast { it.startsWith("journal-") })
        val cacheBefore = vault.rows.filterKeys(RunJournalCompaction::isCache).mapValues { it.value.toList() }
        assertTrue(cacheBefore.keys.any { it.startsWith("journalpackindex-") })
        repeat(2) {
            val events = mutableListOf<RunDiagnosticEvent>()
            val coverage = journal.stream("run", {}) { events += it }
            assertEquals((1L..21L).toList(), events.map { it.sequence })
            assertEquals(0L, coverage.missingSequences)
            assertEquals(0, coverage.missingBatches)
            assertTrue(requireNotNull(coverage.decodedBatchCount) < 21L)
        }
        assertEquals(cacheBefore, vault.rows.filterKeys(RunJournalCompaction::isCache).mapValues { it.value.toList() })
        assertTrue(vault.rows.values.all { it.size <= 64 * 1024 })
    }

    @Test fun newGenerationAfterEarlierTerminalDoesNotCompactAndAddedTailStillExports() = runTest {
        val vault = Vault()
        val journal = journal(vault)
        fragment(journal)
        journal.record("run", RunDiagnosticKind.RUN_TERMINAL)
        runCurrent()
        val indexes = vault.rows.keys.filter { it.startsWith("journalpackindex-") }.toSet()
        journal.record("run", RunDiagnosticKind.INVOCATION_STARTED, invocationId = "new-invocation", fence = 2)
        runCurrent()
        assertEquals(indexes, vault.rows.keys.filter { it.startsWith("journalpackindex-") }.toSet())
        val events = mutableListOf<RunDiagnosticEvent>()
        val coverage = journal.stream("run", {}) { events += it }
        assertEquals((1L..22L).toList(), events.map { it.sequence })
        assertEquals("new-invocation", events.last().invocationId)
        assertEquals(0L, coverage.missingSequences)
    }

    @Test fun lateFinishedAndObservationalEventsDoNotStarveAnUnrelatedSettledRun() = runTest {
        val vault = Vault()
        val journal = journal(vault)
        fragment(journal)
        journal.record("run", RunDiagnosticKind.RUN_TERMINAL)
        runCurrent()
        journal.record("run", RunDiagnosticKind.INVOCATION_FINISHED, invocationId = "already-finished", successful = true)
        journal.record("run", RunDiagnosticKind.CACHE_OBSERVATION)
        journal.traceOutput("run", OutputTraceBoundary.PROVIDER_VISIBLE, "retained provider output", invocationId = "already-finished", outputOrigin = OutputOrigin.MODEL_TEXT)
        runCurrent()
        journal.setRunPrivacy("another-run", false)
        repeat(5) {
            journal.record("another-run", RunDiagnosticKind.REQUEST_SERIALIZED)
            runCurrent()
        }
        journal.record("another-run", RunDiagnosticKind.RUN_TERMINAL)
        runCurrent()
        assertTrue(vault.rows.keys.any { it.startsWith("journalpackindex-${RunJournalCompaction.identity("another-run")}-") })
        val events = mutableListOf<RunDiagnosticEvent>()
        val coverage = journal.stream("run", {}) { events += it }
        assertEquals((1L..24L).toList(), events.map { it.sequence })
        assertEquals(0L, coverage.missingSequences)
        assertFalse(coverage.storageFailed)
    }

    @Test fun resumedInvocationsPauseMaintenanceUntilEveryMatchingInvocationFinishes() = runTest {
        val vault = Vault()
        val journal = journal(vault)
        fragment(journal)
        journal.record("run", RunDiagnosticKind.RUN_TERMINAL)
        runCurrent()
        val indexes = vault.rows.keys.filter { it.startsWith("journalpackindex-") }.toSet()
        journal.record("run", RunDiagnosticKind.INVOCATION_STARTED, invocationId = "first-recovery", fence = 2)
        journal.record("run", RunDiagnosticKind.INVOCATION_STARTED, invocationId = "second-recovery", fence = 2)
        runCurrent()
        journal.record("run", RunDiagnosticKind.INVOCATION_FINISHED, invocationId = "unrelated", successful = true)
        journal.record("run", RunDiagnosticKind.INVOCATION_FINISHED, successful = true)
        journal.record("run", RunDiagnosticKind.INVOCATION_FINISHED, invocationId = "first-recovery", successful = true)
        runCurrent()
        assertEquals(indexes, vault.rows.keys.filter { it.startsWith("journalpackindex-") }.toSet())
        journal.record("run", RunDiagnosticKind.INVOCATION_FINISHED, invocationId = "second-recovery", successful = true)
        runCurrent()
        assertTrue(vault.rows.keys.filter { it.startsWith("journalpackindex-") }.toSet().size > indexes.size)
        val events = mutableListOf<RunDiagnosticEvent>()
        val coverage = journal.stream("run", {}) { events += it }
        assertEquals((1L..27L).toList(), events.map { it.sequence })
        assertEquals(0L, coverage.missingSequences)
    }

    @Test fun anInvocationStillFinishingAfterTerminalKeepsMaintenancePaused() = runTest {
        val vault = Vault()
        val journal = journal(vault)
        fragment(journal)
        journal.record("run", RunDiagnosticKind.INVOCATION_STARTED, invocationId = "finishing")
        journal.record("run", RunDiagnosticKind.RUN_TERMINAL)
        runCurrent()
        assertTrue(vault.rows.keys.none(RunJournalCompaction::isCache))
        journal.record("run", RunDiagnosticKind.INVOCATION_FINISHED, invocationId = "finishing", successful = true)
        runCurrent()
        assertTrue(vault.rows.keys.any { it.startsWith("journalpackindex-") })
    }

    @Test fun droppedStartTelemetryCannotAuthorizeCompactionWhileTheInvocationIsActive() = runTest {
        val vault = Vault()
        val journal = journal(vault)
        fragment(journal)
        journal.record("run", RunDiagnosticKind.RUN_TERMINAL)
        runCurrent()
        val indexes = vault.rows.keys.filter { it.startsWith("journalpackindex-") }.toSet()
        repeat(480) { journal.record("run", RunDiagnosticKind.REQUEST_SERIALIZED) }
        assertEquals(null, journal.record("run", RunDiagnosticKind.INVOCATION_STARTED, invocationId = "dropped-start"))
        runCurrent()
        assertEquals(indexes, vault.rows.keys.filter { it.startsWith("journalpackindex-") }.toSet())
        journal.record("run", RunDiagnosticKind.INVOCATION_FINISHED, invocationId = "dropped-start", successful = true)
        runCurrent()
        assertTrue(vault.rows.keys.filter { it.startsWith("journalpackindex-") }.toSet().size > indexes.size)
        val events = mutableListOf<RunDiagnosticEvent>()
        val coverage = journal.stream("run", {}) { events += it }
        assertEquals(1L, coverage.missingSequences)
        assertTrue(events.any { it.kind == RunDiagnosticKind.INVOCATION_FINISHED && it.invocationId == "dropped-start" })
    }

    @Test fun anUnidentifiedStartStaysBusyUntilItsPrivacyScopeIsRevoked() = runTest {
        val vault = Vault()
        val journal = journal(vault)
        fragment(journal)
        journal.record("run", RunDiagnosticKind.INVOCATION_STARTED)
        journal.record("run", RunDiagnosticKind.RUN_TERMINAL)
        journal.record("run", RunDiagnosticKind.INVOCATION_FINISHED)
        runCurrent()
        assertTrue(vault.rows.keys.none(RunJournalCompaction::isCache))
        journal.revokeRun("run")
        journal.setRunPrivacy("another-run", false)
        repeat(5) {
            journal.record("another-run", RunDiagnosticKind.REQUEST_SERIALIZED)
            runCurrent()
        }
        journal.record("another-run", RunDiagnosticKind.RUN_TERMINAL)
        runCurrent()
        assertTrue(vault.rows.keys.any { it.startsWith("journalpackindex-${RunJournalCompaction.identity("another-run")}-") })
        assertTrue(vault.rows.keys.none { it.contains(RunJournalCompaction.identity("run")) })
    }

    @Test fun clearingFromAReadGuardWithSavedCheckpointsCannotDeadlockOrPublish() = runTest {
        invalidationFromCheckpointGuard(revoke = false)
    }

    @Test fun revokingFromAReadGuardWithSavedCheckpointsCannotDeadlockOrPublish() = runTest {
        invalidationFromCheckpointGuard(revoke = true)
    }

    private suspend fun TestScope.invalidationFromCheckpointGuard(revoke: Boolean) {
        val vault = Vault()
        val journal = journal(vault)
        journal.record("run", RunDiagnosticKind.TOOL_RESULT)
        runCurrent()
        assertTrue(vault.rows.keys.any { it.startsWith("journalcheckpoint-") })
        var reads = 0
        val events = mutableListOf<RunDiagnosticEvent>()
        val failure = runCatching {
            withTimeout(1_000) {
                journal.stream("run", {
                    if (++reads == 2) {
                        if (revoke) journal.revokeRun("run") else journal.clear()
                    }
                }) { events += it }
            }
        }.exceptionOrNull()
        assertEquals("JOURNAL_CAPTURE_REVOKED", failure?.message)
        assertTrue(events.isEmpty())
        assertTrue(vault.rows.isEmpty())
    }

    @Test fun boundedCompactionKeepsUnpackedTailAndFallsBackAfterAnAlreadyEmittedPackedPrefix() = runTest {
        val vault = Vault()
        val journal = journal(vault)
        fragment(journal, count = 260)
        journal.record("run", RunDiagnosticKind.RUN_TERMINAL)
        runCurrent()
        val packed = vault.rows.keys.filter { it.startsWith("journalpacked-") }
        assertTrue(packed.size > 1)
        val intact = mutableListOf<Long>()
        journal.stream("run", {}) { intact += it.sequence }
        assertEquals((1L..261L).toList(), intact)
        vault.rows[packed[1]] = "[]".encodeToByteArray()
        val recovered = mutableListOf<Long>()
        val coverage = journal.stream("run", {}) { recovered += it.sequence }
        assertEquals((1L..261L).toList(), recovered)
        assertEquals(0L, coverage.missingSequences)
        assertEquals(0, coverage.missingBatches)
        assertTrue(vault.rows.values.all { it.size <= 64 * 1024 })
    }

    @Test fun newWorkDuringPackingAbortsCachePublicationButKeepsTerminalAndNewWorkDurable() = runTest {
        val vault = Vault()
        val journal = journal(vault)
        fragment(journal)
        vault.beforePut = { reference ->
            if (reference.startsWith("journalpacked-")) {
                vault.beforePut = {}
                journal.record("run", RunDiagnosticKind.INVOCATION_STARTED, invocationId = "new-invocation", fence = 2)
            }
        }
        journal.record("run", RunDiagnosticKind.RUN_TERMINAL)
        runCurrent()
        assertTrue(vault.rows.keys.none(RunJournalCompaction::isCache))
        val events = mutableListOf<RunDiagnosticEvent>()
        val coverage = journal.stream("run", {}) { events += it }
        assertEquals((1L..22L).toList(), events.map { it.sequence })
        assertEquals(RunDiagnosticKind.RUN_TERMINAL, events[20].kind)
        assertEquals("new-invocation", events[21].invocationId)
        assertEquals(0L, coverage.missingSequences)
        assertFalse(coverage.storageFailed)
    }

    @Test fun clearOrPrivacyChangeDuringCompactionCannotPublishItsOldCapture() = runTest {
        for (revoke in listOf(false, true)) {
            val vault = Vault()
            val journal = journal(vault)
            fragment(journal)
            vault.beforeRead = { reference ->
                if (reference.startsWith("journal-")) {
                    vault.beforeRead = {}
                    if (revoke) journal.revokeRun("run") else journal.clear()
                }
            }
            journal.record("run", RunDiagnosticKind.RUN_TERMINAL)
            runCurrent()
            assertTrue(vault.rows.isEmpty())
        }
    }

    @Test fun olderFrozenIndexSurvivesANewerDrawAndCompaction() = runTest {
        val vault = Vault()
        val journal = journal(vault)
        fragment(journal)
        journal.record("run", RunDiagnosticKind.RUN_TERMINAL)
        runCurrent()
        val olderReferences = vault.rows.keys.toSet()
        val events = mutableListOf<RunDiagnosticEvent>()
        val coverage = journal.stream("run", {}) { event ->
            events += event
            if (events.size == 1) {
                journal.firstVisibleDraw(OutputTraceOwner("run", 1, "0", origin = OutputOrigin.APPLICATION_REPORT))
                runCurrent()
            }
        }
        assertEquals((1L..21L).toList(), events.map { it.sequence })
        assertEquals(0L, coverage.missingSequences)
        assertTrue(vault.rows.keys.containsAll(olderReferences))
        val latest = mutableListOf<Long>()
        journal.stream("run", {}) { latest += it.sequence }
        assertEquals((1L..22L).toList(), latest)
    }

    @Test fun corruptedOrMissingPackedSegmentsFallBackWithoutLosingOrDuplicatingEvents() = runTest {
        for (missing in listOf(false, true)) {
            val vault = Vault()
            val journal = journal(vault)
            fragment(journal)
            journal.record("run", RunDiagnosticKind.RUN_TERMINAL)
            runCurrent()
            val packed = vault.rows.keys.first { it.startsWith("journalpacked-") }
            if (missing) vault.rows.remove(packed) else vault.rows[packed] = "[]".encodeToByteArray()
            val events = mutableListOf<RunDiagnosticEvent>()
            val coverage = journal.stream("run", {}) { events += it }
            assertEquals((1L..21L).toList(), events.map { it.sequence })
            assertEquals(0L, coverage.missingSequences)
            assertEquals(0, coverage.missingBatches)
            assertFalse(coverage.storageFailed)
        }
    }

    @Test fun failedIndexPublicationLeavesEveryOriginalReadableAndDoesNotMarkStorageFailed() = runTest {
        val vault = Vault(failIndex = true)
        val journal = journal(vault)
        fragment(journal)
        journal.record("run", RunDiagnosticKind.RUN_TERMINAL)
        runCurrent()
        assertTrue(vault.rows.keys.none(RunJournalCompaction::isCache))
        val events = mutableListOf<RunDiagnosticEvent>()
        val coverage = journal.stream("run", {}) { events += it }
        assertEquals((1L..21L).toList(), events.map { it.sequence })
        assertEquals(0L, coverage.missingSequences)
        assertFalse(coverage.storageFailed)
    }

    @Test fun missingOriginalCannotBeResurrectedByAnOldReadCache() = runTest {
        val vault = Vault()
        val journal = journal(vault)
        fragment(journal)
        journal.record("run", RunDiagnosticKind.RUN_TERMINAL)
        runCurrent()
        vault.rows.remove(vault.rows.keys.first { it.startsWith("journal-") })
        val events = mutableListOf<RunDiagnosticEvent>()
        val coverage = journal.stream("run", {}) { events += it }
        assertEquals((2L..21L).toList(), events.map { it.sequence })
        assertEquals(1L, coverage.missingSequences)
    }

    @Test fun clearAndPrivacyRevocationPurgeCacheAlongWithOriginals() = runTest {
        for (revoke in listOf(false, true)) {
            val vault = Vault()
            val journal = journal(vault)
            fragment(journal)
            journal.record("run", RunDiagnosticKind.RUN_TERMINAL)
            runCurrent()
            assertTrue(vault.rows.keys.any(RunJournalCompaction::isCache))
            if (revoke) journal.revokeRun("run") else journal.clear()
            assertTrue(vault.rows.isEmpty())
            val events = mutableListOf<RunDiagnosticEvent>()
            journal.stream("run", {}) { events += it }
            assertTrue(events.isEmpty())
        }
    }

    @Test fun unchangedRenderTelemetryIsDeduplicatedWithoutLosingLineageOrFirstDraw() = runTest {
        val journal = journal(Vault())
        journal.traceOutput("run", OutputTraceBoundary.PROVIDER_VISIBLE, "answer", invocationId = "provider", outputOrigin = OutputOrigin.MODEL_TEXT)
        repeat(5) {
            assertEquals(OutputLineage(OutputOrigin.MODEL_TEXT, "provider"), journal.traceOutput("run", OutputTraceBoundary.RENDER_INPUT, "answer", fence = 1, partId = "0"))
        }
        journal.firstVisibleDraw(OutputTraceOwner("run", 1, "0", origin = OutputOrigin.MODEL_TEXT, invocationId = "provider"))
        runCurrent()
        val events = journal.snapshot(setOf("run")).events
        assertEquals(3, events.size)
        assertEquals(1, events.count { it.kind == RunDiagnosticKind.FIRST_VISIBLE_TEXT })
        assertEquals((1L..3L).toList(), events.map { it.sequence })
    }

    @Test fun outputChangesAndEveryOwnershipOrRepresentationBoundaryRemainDistinct() = runTest {
        val journal = journal(Vault())
        fun trace(text: String = "A", fence: Long = 1, part: String = "0", origin: OutputOrigin = OutputOrigin.MODEL_TEXT, invocation: String = "first", boundary: OutputTraceBoundary = OutputTraceBoundary.RENDER_TRANSFORM, representation: OutputTextRepresentation = OutputTextRepresentation.VISIBLE_UTF8) = journal.traceOutput("run", boundary, text, fence, invocation, representation, part, origin)
        trace()
        trace("B")
        trace()
        trace(fence = 2)
        trace(part = "1")
        trace(origin = OutputOrigin.APPLICATION_REPORT)
        trace(invocation = "second")
        trace(boundary = OutputTraceBoundary.RENDER_INPUT)
        trace(representation = OutputTextRepresentation.MATH_PLACEHOLDER_MARKDOWN_UTF8)
        runCurrent()
        assertEquals(9, journal.snapshot(setOf("run")).events.size)
        journal.privateSession = true
        journal.privateSession = false
        trace()
        runCurrent()
        assertEquals(10, journal.snapshot(setOf("run")).events.size)
    }

    private class Vault(private val failIndex: Boolean = false) : SecretVault {
        val rows = linkedMapOf<String, ByteArray>()
        val writes = mutableListOf<String>()
        var beforePut: suspend (String) -> Unit = {}
        var beforeRead: suspend (String) -> Unit = {}
        override suspend fun put(secretRef: String, secret: ByteArray) {
            beforePut(secretRef)
            if (failIndex && secretRef.startsWith("journalpackindex-")) error("Interrupted index publication")
            writes += secretRef
            rows[secretRef] = secret.copyOf()
        }
        override suspend fun read(secretRef: String): ByteArray? {
            beforeRead(secretRef)
            return rows[secretRef]?.copyOf()
        }
        override suspend fun delete(secretRef: String) {
            rows.remove(secretRef)
        }
        override suspend fun references() = rows.keys.toSet()
        override suspend fun storedSize(secretRef: String) = rows[secretRef]?.size?.toLong()
    }
}
