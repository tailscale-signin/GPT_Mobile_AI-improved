package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.security.SecretVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunDiagnosticJournalTest {
    @Test fun compositeRendererPartsRetainCorrelatedReceiptsWithoutRawIdentifiers() = runTest {
        val vault = MemoryVault()
        val journal = RunDiagnosticJournal(vault, backgroundScope).apply {
            enabled = true
            setRunPrivacy("run", false)
        }
        val parts = listOf("10:pending", "source:profile-private:payload-private:block:0:abcdef", "10:0")
        parts.forEach { part ->
            journal.traceOutput("run", OutputTraceBoundary.RENDER_INPUT, "body", partId = part)
            journal.traceOutput("run", OutputTraceBoundary.RENDER_TRANSFORM, "body", partId = part)
        }
        runCurrent()
        val snapshot = journal.snapshot(setOf("run"))
        assertEquals(0L, snapshot.droppedEvents)
        assertEquals(6, snapshot.events.size)
        assertEquals(3, snapshot.events.map { it.outputPartId }.distinct().size)
        snapshot.events.chunked(2).forEach { assertEquals(it[0].outputPartId, it[1].outputPartId) }
        assertTrue(snapshot.events.any { it.outputPartId == "10:0" })
        assertTrue(vault.rows.values.none { it.decodeToString().contains("profile-private") || it.decodeToString().contains("pending") })
    }

    @Test fun repeatedRendererMountsPublishOneFirstVisibleReceiptPerOwnedAnswer() = kotlinx.coroutines.test.runTest {
        val journal = RunDiagnosticJournal(MemoryVault(), backgroundScope).apply {
            enabled = true
            setRunPrivacy("answer", false)
        }
        repeat(3) { journal.record("answer", RunDiagnosticKind.FIRST_VISIBLE_TEXT, fence = 1) }
        journal.record("answer", RunDiagnosticKind.FIRST_VISIBLE_TEXT, fence = 2)
        val snapshot = journal.snapshot(setOf("answer"))
        assertEquals(listOf(1L, 2L), snapshot.events.filter { it.kind == RunDiagnosticKind.FIRST_VISIBLE_TEXT }.map { it.fence })
    }

    @Test fun outputTracingStoresRepresentationBoundFingerprintsWithoutContentOrPrivateRuns() = runTest {
        val vault = MemoryVault()
        val journal = RunDiagnosticJournal(vault, backgroundScope).apply {
            enabled = true
            setRunPrivacy("run", isTemporary = false)
            setRunPrivacy("private", isTemporary = true)
        }
        val body = "private-body-canary 😀"
        journal.traceOutput("private", OutputTraceBoundary.COMMITTED, body)
        journal.traceOutput("run", OutputTraceBoundary.COMMITTED, body, fence = 3)
        journal.record("run", RunDiagnosticKind.OUTPUT_CHECKPOINT, outputBoundary = OutputTraceBoundary.PROVIDER_VISIBLE, outputRepresentation = OutputTextRepresentation.VISIBLE_UTF8, outputFingerprint = OutputFingerprint(10, "private-secret"))
        runCurrent()
        val snapshot = journal.snapshot(setOf("run", "private"))
        assertEquals(1, snapshot.events.size)
        assertEquals(OutputTextRepresentation.VISIBLE_UTF8, snapshot.events.single().outputRepresentation)
        assertEquals(outputFingerprint(body), snapshot.events.single().outputFingerprint)
        assertEquals(1L, snapshot.droppedEvents)
        assertTrue(vault.rows.values.none { it.decodeToString().contains("private-body-canary") || it.decodeToString().contains("private-secret") })
    }

    @Test fun criticalTerminalAndDeadlineRetainReservedQueueSpace() = runTest {
        val journal = RunDiagnosticJournal(MemoryVault(), backgroundScope).apply {
            enabled = true
            setRunPrivacy("run", isTemporary = false)
        }
        repeat(600) { journal.record("run", RunDiagnosticKind.REQUEST_SERIALIZED) }
        journal.record("run", RunDiagnosticKind.PROGRESS_DEADLINE, progressStage = dev.chungjungsoo.gptmobile.data.agent.ObservedProgressStage.PREFILL, progressDeadline = ProgressDeadlineCode.MODEL_PROGRESS_TIMEOUT)
        journal.record("run", RunDiagnosticKind.RUN_TERMINAL, successful = false)
        journal.record("run", RunDiagnosticKind.RUN_TERMINAL, successful = false)
        val snapshot = journal.snapshot(setOf("run"))
        assertEquals(1, snapshot.events.count { it.kind == RunDiagnosticKind.RUN_TERMINAL })
        assertEquals(ProgressDeadlineCode.MODEL_PROGRESS_TIMEOUT, snapshot.events.single { it.kind == RunDiagnosticKind.PROGRESS_DEADLINE }.progressDeadline)
        assertEquals(120L, snapshot.droppedEvents)
        assertEquals(120L, snapshot.missingSequences)
        assertTrue(snapshot.events.size <= 512)
    }

    @Test fun metadataIsOptInPrivateSessionsDoNotCaptureAndTerminalIsExactlyOnce() = runTest {
        val vault = MemoryVault()
        val journal = RunDiagnosticJournal(vault, backgroundScope)
        journal.record("run", RunDiagnosticKind.INVOCATION_STARTED)
        assertTrue(journal.snapshot(setOf("run")).events.isEmpty())
        journal.setRunPrivacy("run", isTemporary = false)
        journal.enabled = true
        journal.privateSession = true
        journal.record("run", RunDiagnosticKind.INVOCATION_STARTED)
        assertTrue(journal.snapshot(setOf("run")).events.isEmpty())
        journal.privateSession = false
        journal.record("run", RunDiagnosticKind.INVOCATION_STARTED)
        journal.record("run", RunDiagnosticKind.RUN_TERMINAL, fence = 7, successful = true)
        journal.record("run", RunDiagnosticKind.RUN_TERMINAL)
        journal.record("run", RunDiagnosticKind.REDUCER_CHECKPOINT)
        runCurrent()
        val saved = journal.snapshot(setOf("run"))
        assertEquals(listOf(1L, 2L), saved.events.map { it.sequence })
        assertEquals(1, saved.events.count { it.kind == RunDiagnosticKind.RUN_TERMINAL })
        assertEquals(0L, saved.missingSequences)
        assertFalse(saved.completeProviderWireCapture)
    }

    @Test fun snapshotIncludesAnInFlightWriteAndRejectsAccidentalPayloadIdentifiers() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vault = MemoryVault(gate)
        val journal = RunDiagnosticJournal(vault, backgroundScope).apply {
            enabled = true
            setRunPrivacy("run", isTemporary = false)
        }
        journal.record("Bearer super-secret", RunDiagnosticKind.REQUEST_SERIALIZED)
        journal.record("run", RunDiagnosticKind.REQUEST_SERIALIZED, inputTokens = 31, outputTokens = 64)
        runCurrent()
        val frozen = journal.snapshot(setOf("run"))
        assertEquals(1, frozen.events.size)
        assertEquals(1L, frozen.droppedEvents)
        assertEquals(0L, frozen.missingSequences)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, journal.snapshot(setOf("run")).events.size)
        assertTrue(vault.rows.values.none { it.decodeToString().contains("super-secret") })
    }

    @Test fun storageFailureIsVisibleAndDoesNotThrowIntoGeneration() = runTest {
        val vault = MemoryVault(failWrites = true)
        val journal = RunDiagnosticJournal(vault, backgroundScope).apply {
            enabled = true
            setRunPrivacy("run", isTemporary = false)
        }
        journal.record("run", RunDiagnosticKind.INVOCATION_FINISHED, successful = false)
        runCurrent()
        val snapshot = journal.snapshot(setOf("run"))
        assertTrue(snapshot.storageFailed)
        assertEquals(1L, snapshot.missingSequences)
    }

    @Test fun privacyRevocationPurgesPriorCaptureAndCannotBeBroadenedForTheSameRun() = runTest {
        val vault = MemoryVault()
        val journal = RunDiagnosticJournal(vault, backgroundScope).apply {
            enabled = true
            setRunPrivacy("run", isTemporary = false)
        }
        journal.record("run", RunDiagnosticKind.INVOCATION_STARTED)
        runCurrent()
        assertTrue(vault.rows.isNotEmpty())
        journal.revokeRun("run")
        journal.setRunPrivacy("run", isTemporary = false)
        journal.record("run", RunDiagnosticKind.REQUEST_SERIALIZED)
        runCurrent()
        assertTrue(vault.rows.isEmpty())
        assertTrue(journal.snapshot(setOf("run")).events.isEmpty())
    }

    private class MemoryVault(val gate: CompletableDeferred<Unit>? = null, val failWrites: Boolean = false) : SecretVault {
        val rows = linkedMapOf<String, ByteArray>()
        override suspend fun put(secretRef: String, secret: ByteArray) {
            gate?.await()
            check(!failWrites)
            rows[secretRef] = secret.copyOf()
        }
        override suspend fun read(secretRef: String) = rows[secretRef]?.copyOf()
        override suspend fun delete(secretRef: String) {
            rows.remove(secretRef)
        }
        override suspend fun references() = rows.keys.toSet()
    }
}
