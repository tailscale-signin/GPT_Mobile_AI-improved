package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.security.SecretVault
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RunJournalCoverageTest {
    @Test fun completeExportStreamsBeyondRecentWindowWithoutReportingArtificialLoss() = runTest {
        val vault = Vault()
        val journal = RunDiagnosticJournal(vault, backgroundScope, maxSnapshotEvents = 5, sessionId = "session").apply { setRunPrivacy("run", false) }
        repeat(20) {
            repeat(40) { journal.record("run", RunDiagnosticKind.TOOL_RESULT) }
            runCurrent()
        }
        val retained = mutableListOf<Long>()
        val coverage = journal.stream("run", {}) { retained += it.sequence }
        assertEquals((1L..800L).toList(), retained.sorted())
        assertEquals(800L, coverage.eventCount)
        assertEquals(0L, coverage.missingSequences)
        assertEquals(0, coverage.missingBatches)
        assertEquals(0, coverage.unknownTailStreams)
        assertTrue(coverage.missingRanges.isEmpty())
        assertEquals(5, journal.snapshot(setOf("run")).events.size)
    }

    @Test fun durableCheckpointExposesDeletedTailAfterProcessRestart() = runTest {
        val vault = Vault()
        val first = RunDiagnosticJournal(vault, backgroundScope, sessionId = "old").apply { setRunPrivacy("run", false) }
        repeat(3) { first.record("run", RunDiagnosticKind.TOOL_RESULT) }
        runCurrent()
        vault.rows.keys.filter { it.startsWith("journal-") }.forEach { vault.rows.remove(it) }
        val restarted = RunDiagnosticJournal(vault, backgroundScope, sessionId = "new")
        val coverage = restarted.stream("run", {}) { error("No retained events expected") }
        assertEquals(3L, coverage.missingSequences)
        assertEquals(listOf(RunJournalMissingRange("old:run", 1, 3)), coverage.missingRanges)
        assertEquals(0, coverage.unknownTailStreams)
    }

    @Test fun failedEventBatchLeavesDurableMissingTailCheckpointAfterRestart() = runTest {
        val vault = Vault().apply { failEventWrites = true }
        val journal = RunDiagnosticJournal(vault, backgroundScope, sessionId = "old").apply { setRunPrivacy("run", false) }
        repeat(3) { journal.record("run", RunDiagnosticKind.TOOL_RESULT) }
        runCurrent()
        vault.failEventWrites = false
        val coverage = RunDiagnosticJournal(vault, backgroundScope, sessionId = "new").stream("run", {}) { error("Failed batch unexpectedly retained") }
        assertEquals(3L, coverage.missingSequences)
        assertEquals(listOf(RunJournalMissingRange("old:run", 1, 3)), coverage.missingRanges)
    }

    @Test fun frozenBoundaryIncludesQueuedEventsExactlyOnceAndExcludesLaterWrites() = runTest {
        val vault = Vault()
        val journal = RunDiagnosticJournal(vault, backgroundScope, sessionId = "session").apply { setRunPrivacy("run", false) }
        journal.record("run", RunDiagnosticKind.TOOL_RESULT)
        runCurrent()
        journal.record("run", RunDiagnosticKind.TOOL_RESULT)
        val exported = mutableListOf<Long>()
        val coverage = journal.stream("run", {}) {
            exported += it.sequence
            journal.record("run", RunDiagnosticKind.TOOL_RESULT)
            runCurrent()
        }
        assertEquals(listOf(1L, 2L), exported.sorted())
        assertEquals(0L, coverage.missingSequences)
        assertEquals(2L, coverage.highWatermarks["session:run"])
    }

    @Test fun checkpointValuesRemainFrozenWhenAnotherWriterAdvancesBeforeBatchRead() = runTest {
        val vault = Vault()
        val writer = RunDiagnosticJournal(vault, backgroundScope, sessionId = "old").apply { setRunPrivacy("run", false) }
        writer.record("run", RunDiagnosticKind.TOOL_RESULT)
        runCurrent()
        val reader = RunDiagnosticJournal(vault, backgroundScope, sessionId = "new")
        var reads = 0
        val coverage = reader.stream("run", {
            reads++
            if (reads == 3) {
                writer.record("run", RunDiagnosticKind.TOOL_RESULT)
                runCurrent()
            }
        }) {}
        assertEquals(1L, coverage.eventCount)
        assertEquals(1L, coverage.highWatermarks["old:run"])
        assertEquals(0L, coverage.missingSequences)
    }

    @Test fun clearedOrRevokedEmptyExportCannotPublishStaleCompleteness() = runTest {
        val journal = RunDiagnosticJournal(Vault(), backgroundScope)
        var reads = 0
        val cleared = runCatching {
            journal.stream("run", {
                if (++reads == 2) journal.clear()
            }) {}
        }
        assertEquals("JOURNAL_CAPTURE_REVOKED", cleared.exceptionOrNull()?.message)
        reads = 0
        val revoked = runCatching {
            journal.stream("run", {
                if (++reads == 2) journal.setRunPrivacy("run", true)
            }) {}
        }
        assertEquals("JOURNAL_CAPTURE_REVOKED", revoked.exceptionOrNull()?.message)
    }

    @Test fun privacyRevocationRemovesCheckpointsAndSuppressesExport() = runTest {
        val vault = Vault()
        val journal = RunDiagnosticJournal(vault, backgroundScope).apply { setRunPrivacy("run", false) }
        journal.record("run", RunDiagnosticKind.TOOL_RESULT)
        runCurrent()
        assertTrue(vault.rows.keys.any { it.startsWith("journalcheckpoint-") })
        journal.revokeRun("run")
        assertTrue(vault.rows.isEmpty())
        assertTrue(journal.stream("run", {}) { error("Private event leaked") }.privacySuppressed)
    }

    @Test fun intervalCoverageDeduplicatesOutOfOrderBatchesAndBoundsGapManifest() {
        val coverage = RunJournalSequenceCoverage()
        listOf(5L, 1, 3, 2, 4).forEach { assertTrue(coverage.add("run", it)) }
        assertFalse(coverage.add("run", 3))
        assertEquals(5L, coverage.count("run"))
        assertEquals(listOf(RunJournalMissingRange("run", 6, 7)), coverage.gaps(mapOf("run" to 7L), 8).first)
        val fragmented = RunJournalSequenceCoverage()
        (2L..400L step 2).forEach { fragmented.add("other", it) }
        val gaps = fragmented.gaps(mapOf("other" to 400L), 3)
        assertEquals(3, gaps.first.size)
        assertTrue(gaps.second)
    }

    private class Vault : SecretVault {
        val rows = linkedMapOf<String, ByteArray>()
        var failEventWrites = false
        override suspend fun put(secretRef: String, secret: ByteArray) {
            if (failEventWrites && secretRef.startsWith("journal-")) throw java.io.IOException("storage unavailable")
            check(secret.size <= 64 * 1024)
            rows[secretRef] = secret.copyOf()
        }
        override suspend fun read(secretRef: String) = rows[secretRef]?.copyOf()
        override suspend fun delete(secretRef: String) {
            rows.remove(secretRef)
        }
        override suspend fun references() = rows.keys.toSet()
        override suspend fun storedSize(secretRef: String) = rows[secretRef]?.size?.toLong()
    }
}
