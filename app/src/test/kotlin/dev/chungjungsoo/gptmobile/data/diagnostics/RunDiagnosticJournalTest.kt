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
