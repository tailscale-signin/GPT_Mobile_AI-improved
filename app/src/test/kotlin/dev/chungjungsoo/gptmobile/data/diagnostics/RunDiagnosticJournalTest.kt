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
    @Test fun failureReceiptsSurviveDisabledVerboseCaptureWithoutRawIdentityOrErrorContent() = runTest {
        val vault = MemoryVault()
        val journal = RunDiagnosticJournal(vault, backgroundScope)
        journal.setRunPrivacy("normal", false)
        journal.setRunPrivacy("private", true)
        val profile = dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(uid = "private-profile-canary", name = "Private name", model = "private-model-canary", token = "private-token-canary", compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.LLAMA)
        val failure = dev.chungjungsoo.gptmobile.data.agent.ProviderEvent.Failed("private-prompt-canary", errorCode = "GITHUB_REVIEW_INVALID_VERDICT", transportComplete = true, failureDetails = kotlinx.serialization.json.buildJsonObject { put("http_status", kotlinx.serialization.json.JsonPrimitive(403)) })
        val receipt = ProviderFailureReceipt.from(profile, "github-review", failure, dev.chungjungsoo.gptmobile.data.agent.ProviderEvent.Usage(outputTokens = 4096, reasoningTokens = 3072, finishReason = "length"))
        journal.record("normal", RunDiagnosticKind.FAILURE_RECEIPT, providerFailure = receipt)
        journal.record("private", RunDiagnosticKind.FAILURE_RECEIPT, providerFailure = receipt)
        journal.record("normal", RunDiagnosticKind.FAILURE_RECEIPT, providerFailure = receipt.copy(errorCode = "private-token-canary"))
        journal.record("normal", RunDiagnosticKind.FAILURE_RECEIPT, providerFailure = receipt.copy(errorCode = "PRIVATE_TOKEN_CANARY"))
        runCurrent()
        val snapshot = journal.snapshot(setOf("normal", "private"))
        assertEquals(1, snapshot.events.size)
        val retained = requireNotNull(snapshot.events.single().providerFailure)
        assertEquals(ProviderFailureStage.ANSWER_REVIEW, retained.stage)
        assertEquals(ProviderFinishCategory.LENGTH, retained.finishReason)
        assertEquals(403, retained.httpStatus)
        assertTrue(retained.outputLimitReached)
        assertEquals(3072, retained.reasoningTokens)
        assertFalse(snapshot.completeProviderWireCapture)
        assertTrue(vault.rows.values.none { it.decodeToString().contains("canary") })
    }

    @Test fun untrustedProviderCodesCannotBecomeMandatoryDiagnosticText() {
        val profile = dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(name = "Selected", model = "selected")
        val remote = dev.chungjungsoo.gptmobile.data.agent.ProviderEvent.Failed("untrusted message", errorCode = "PRIVATE_TOKEN_CANARY")
        val receipt = ProviderFailureReceipt.from(profile, "primary", remote)
        assertEquals("UNCLASSIFIED_PROVIDER_FAILURE", receipt.errorCode)
        val http = ProviderFailureReceipt.from(profile, "primary", remote.copy(errorCode = "403"))
        assertEquals("HTTP_ERROR", http.errorCode)
        assertEquals(403, http.httpStatus)
        val extraction = ProviderFailureReceipt.from(profile, "github-delivery", remote.copy(errorCode = "GITHUB_EXTRACTION_TIMEOUT"))
        assertEquals(ProviderFailureStage.CLAIM_VERIFICATION, extraction.stage)
        assertEquals(ProviderResponseFormat.CLAIM_JSON, extraction.responseFormat)
    }

    @Test fun minimalLifecyclePersistsWithVerboseCaptureOffAndHonorsPrivacy() = runTest {
        val journal = RunDiagnosticJournal(MemoryVault(), backgroundScope)
        journal.setRunPrivacy("normal", false)
        journal.setRunPrivacy("private", true)
        journal.record("normal", RunDiagnosticKind.INTENT_SUBSTANTIVE)
        journal.record("normal", RunDiagnosticKind.TOOL_ADMITTED)
        journal.record("normal", RunDiagnosticKind.TOOL_RESULT, successful = true)
        journal.record("normal", RunDiagnosticKind.CONTINUATION_SELECTED)
        journal.record("normal", RunDiagnosticKind.VERIFICATION_REJECTED)
        journal.record("normal", RunDiagnosticKind.RUN_TERMINAL, successful = false)
        journal.record("private", RunDiagnosticKind.INTENT_SUBSTANTIVE)
        journal.record("normal", RunDiagnosticKind.REQUEST_SERIALIZED)
        runCurrent()
        val snapshot = journal.snapshot(setOf("normal", "private"))
        assertEquals(6, snapshot.events.size)
        assertEquals("minimal_lifecycle", snapshot.captureLevel)
        assertFalse(snapshot.captureEnabled == true)
        assertTrue(snapshot.events.all { it.runId == "normal" })
        assertTrue(snapshot.events.all { it.outputFingerprint == null })
    }

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
        repeat(3) { journal.firstVisibleDraw(OutputTraceOwner("answer", 1, "0:pending")) }
        journal.firstVisibleDraw(OutputTraceOwner("answer", 2, "0:1"))
        val snapshot = journal.snapshot(setOf("answer"))
        val draws = snapshot.events.filter { it.kind == RunDiagnosticKind.FIRST_VISIBLE_TEXT }
        assertEquals(listOf(1L, 2L), draws.map { it.fence })
        assertTrue(draws.all { it.measured == true && it.invocationId == null && it.elapsedMillis == null && it.monotonicNanos != null })
        assertEquals(outputTracePartId("0:pending"), draws.first().outputPartId)
        assertEquals("0:1", draws.last().outputPartId)
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

    @Test fun retentionSizesBatchesWithoutDecryptingAndRetainsSequenceCheckpoint() = runTest {
        val vault = MemoryVault()
        var time = 1_000_000L
        val journal = RunDiagnosticJournal(vault, backgroundScope, now = { time }, maxStoredBytes = 1).apply {
            enabled = true
            setRunPrivacy("run", false)
        }
        repeat(5) {
            time += 60_001
            journal.record("run", RunDiagnosticKind.REQUEST_SERIALIZED)
            runCurrent()
        }
        assertEquals(0, vault.batchReadCount)
        assertTrue(vault.rows.keys.none { it.startsWith("journal-") })
        assertEquals(1, vault.rows.keys.count { it.startsWith("journalcheckpoint-") })
        assertEquals(5L, journal.stream("run", {}) {}.missingSequences)
    }

    @Test fun snapshotReportsOmittedBatchesInsteadOfExhaustingHeap() = runTest {
        val vault = MemoryVault()
        val journal = RunDiagnosticJournal(vault, backgroundScope, maxSnapshotBytes = 1).apply {
            enabled = true
            setRunPrivacy("run", false)
        }
        journal.record("run", RunDiagnosticKind.REQUEST_SERIALIZED)
        runCurrent()
        val snapshot = journal.snapshot(setOf("run"))
        assertTrue(snapshot.events.isEmpty())
        assertEquals(1, snapshot.missingBatches)
        assertEquals(1L, snapshot.missingSequences)
    }

    @Test fun snapshotKeepsNewestRunInsteadOfSortingByHashedRunId() = runTest {
        val vault = MemoryVault()
        var time = 1_000_000L
        val journal = RunDiagnosticJournal(vault, backgroundScope, now = { time }, maxSnapshotEvents = 1).apply {
            enabled = true
            setRunPrivacy("old", false)
            setRunPrivacy("new", false)
        }
        journal.record("old", RunDiagnosticKind.REQUEST_SERIALIZED)
        runCurrent()
        time++
        journal.record("new", RunDiagnosticKind.REQUEST_SERIALIZED)
        runCurrent()
        val snapshot = journal.snapshot(setOf("old", "new"))
        assertEquals(listOf("new"), snapshot.events.map { it.runId })
        assertEquals(1L, snapshot.missingSequences)
    }

    @Test fun snapshotEventCapIncludesPartialBatchesAndUnpersistedEvents() = runTest {
        val vault = MemoryVault()
        val journal = RunDiagnosticJournal(vault, backgroundScope, maxSnapshotEvents = 3).apply {
            enabled = true
            setRunPrivacy("run", false)
        }
        repeat(4) { journal.record("run", RunDiagnosticKind.REQUEST_SERIALIZED) }
        runCurrent()
        journal.record("run", RunDiagnosticKind.RUN_TERMINAL, successful = true)
        val snapshot = journal.snapshot(setOf("run"))
        assertEquals(listOf(3L, 4L, 5L), snapshot.events.map { it.sequence })
        assertEquals(2L, snapshot.missingSequences)
    }

    @Test fun callerSnapshotLimitsIncludeQueuedEventsAndExposeOmittedEvidence() = runTest {
        val vault = MemoryVault()
        val journal = RunDiagnosticJournal(vault, backgroundScope).apply {
            enabled = true
            setRunPrivacy("run", false)
        }
        repeat(4) { journal.record("run", RunDiagnosticKind.REQUEST_SERIALIZED) }
        runCurrent()
        journal.record("run", RunDiagnosticKind.RUN_TERMINAL, successful = true)
        val limited = journal.snapshot(setOf("run"), byteLimit = 64L * 1024, eventLimit = 2)
        assertEquals(listOf(4L, 5L), limited.events.map { it.sequence })
        assertEquals(3L, limited.missingSequences)
        val empty = journal.snapshot(setOf("run"), byteLimit = 1, eventLimit = 10)
        assertTrue(empty.events.isEmpty())
        assertTrue(empty.missingBatches > 0)
        assertEquals(5L, empty.missingSequences)
    }

    private class MemoryVault(val gate: CompletableDeferred<Unit>? = null, val failWrites: Boolean = false) : SecretVault {
        val rows = linkedMapOf<String, ByteArray>()
        override suspend fun put(secretRef: String, secret: ByteArray) {
            gate?.await()
            check(!failWrites)
            rows[secretRef] = secret.copyOf()
        }
        var readCount = 0
        var batchReadCount = 0
        override suspend fun storedSize(secretRef: String) = rows[secretRef]?.size?.toLong()
        override suspend fun read(secretRef: String): ByteArray? {
            readCount++
            if (secretRef.startsWith("journal-")) batchReadCount++
            return rows[secretRef]?.copyOf()
        }
        override suspend fun delete(secretRef: String) {
            rows.remove(secretRef)
        }
        override suspend fun references() = rows.keys.toSet()
    }
}
