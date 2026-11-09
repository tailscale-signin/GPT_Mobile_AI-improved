package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.security.SecretVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunDiagnosticJournalTest {
    @Test fun multiRunExportIndexesStorageOnceAndKeepsOneFrozenBoundaryAcrossLaterWrites() = runTest {
        val vault = MemoryVault()
        val journal = RunDiagnosticJournal(vault, backgroundScope).apply { enabled = true }
        for (run in listOf("first", "second", "unselected")) {
            journal.setRunPrivacy(run, false)
            repeat(40) { journal.record(run, RunDiagnosticKind.REQUEST_SERIALIZED) }
        }
        runCurrent()
        vault.referenceCount = 0
        val events = mutableListOf<RunDiagnosticEvent>()
        val coverages = linkedMapOf<String, RunJournalExportCoverage>()
        var advanced = false
        journal.streamRuns(linkedSetOf("first", "second"), {}, { event ->
            events += event
            if (!advanced) {
                advanced = true
                journal.record("second", RunDiagnosticKind.REQUEST_SERIALIZED)
                runCurrent()
            }
        }) { run, coverage -> coverages[run] = coverage }
        assertEquals(1, vault.referenceCount)
        assertEquals(setOf("first", "second"), events.map { it.runId }.toSet())
        assertEquals((1L..40L).toList(), events.filter { it.runId == "first" }.map { it.sequence })
        assertEquals((1L..40L).toList(), events.filter { it.runId == "second" }.map { it.sequence })
        assertTrue(coverages.values.all { it.eventCount == 40L && it.missingSequences == 0L && it.missingBatches == 0 })
        assertEquals(1, coverages.values.sumOf { it.referenceEnumerations ?: 0 })
        assertEquals(1, coverages.values.map { it.capturedAtMillis }.distinct().size)
        val next = mutableListOf<Long>()
        journal.stream("second", {}) { next += it.sequence }
        assertEquals((1L..41L).toList(), next)
    }

    @Test fun multiRunExportDoesNotPublishALaterRunAfterPrivacyRevokesTheFrozenCapture() = runTest {
        val journal = RunDiagnosticJournal(MemoryVault(), backgroundScope).apply { enabled = true }
        for (run in listOf("first", "second")) {
            journal.setRunPrivacy(run, false)
            journal.record(run, RunDiagnosticKind.REQUEST_SERIALIZED)
        }
        runCurrent()
        val retained = mutableListOf<String>()
        val failure = runCatching {
            journal.streamRuns(linkedSetOf("first", "second"), {}, { retained += it.runId }) { _, _ -> journal.privateSession = true }
        }.exceptionOrNull()
        assertEquals("JOURNAL_CAPTURE_REVOKED", failure?.message)
        assertEquals(listOf("first"), retained)
    }

    @Test fun precheckFailureIsSystemOutputAtCommitRenderAndDrawWithoutAProviderInvocation() = runTest {
        val vault = MemoryVault()
        val journal = RunDiagnosticJournal(vault, backgroundScope).apply {
            enabled = true
            setRunPrivacy("run", false)
        }
        val body = "private-precheck-canary: select an available profile"
        val committed = journal.traceOutput("run", OutputTraceBoundary.COMMITTED, body, fence = 1, outputOrigin = OutputOrigin.SYSTEM_ERROR)
        val rendered = journal.traceOutput("run", OutputTraceBoundary.RENDER_INPUT, body, fence = 1, representation = OutputTextRepresentation.DISPLAY_FILTERED_MARKDOWN_UTF8, partId = "0")
        val transformed = journal.traceOutput("run", OutputTraceBoundary.RENDER_TRANSFORM, "transformed $body", fence = 1, representation = OutputTextRepresentation.MATH_PLACEHOLDER_MARKDOWN_UTF8, partId = "0", outputOrigin = rendered.origin, invocationId = rendered.invocationId)
        journal.firstVisibleDraw(OutputTraceOwner("run", 1, "0", origin = rendered.origin, invocationId = rendered.invocationId))
        runCurrent()
        val events = journal.snapshot(setOf("run")).events
        assertEquals(OutputLineage(OutputOrigin.SYSTEM_ERROR), committed)
        assertEquals(committed, rendered)
        assertEquals(rendered, transformed)
        assertEquals(4, events.size)
        assertTrue(events.all { it.outputOrigin == OutputOrigin.SYSTEM_ERROR && it.invocationId == null })
        assertTrue(events.none { it.kind in setOf(RunDiagnosticKind.INVOCATION_STARTED, RunDiagnosticKind.FIRST_TEXT) })
        assertEquals(null, linkedParsedToDrawMillis(events))
        assertTrue(vault.rows.values.none { it.decodeToString().contains("private-precheck-canary") })
    }

    @Test fun exactProviderOutputCarriesLineageWhileProgressDoesNotSuppressItsDraw() = runTest {
        val journal = RunDiagnosticJournal(MemoryVault(), backgroundScope).apply {
            enabled = true
            setRunPrivacy("run", false)
        }
        journal.firstVisibleDraw(OutputTraceOwner("run", 1, "0", origin = OutputOrigin.PROGRESS_NOTICE))
        journal.record("run", RunDiagnosticKind.INVOCATION_STARTED, invocationId = "invocation")
        journal.record("run", RunDiagnosticKind.FIRST_TEXT, invocationId = "invocation", outputOrigin = OutputOrigin.MODEL_TEXT)
        journal.record("run", RunDiagnosticKind.OUTPUT_CHECKPOINT, invocationId = "invocation", outputBoundary = OutputTraceBoundary.PROVIDER_VISIBLE, outputRepresentation = OutputTextRepresentation.VISIBLE_UTF8, outputFingerprint = outputFingerprint("answer"), outputOrigin = OutputOrigin.MODEL_TEXT)
        val committed = journal.traceOutput("run", OutputTraceBoundary.COMMITTED, "answer", fence = 1)
        val rendered = journal.traceOutput("run", OutputTraceBoundary.RENDER_INPUT, "answer", fence = 1, representation = OutputTextRepresentation.DISPLAY_FILTERED_MARKDOWN_UTF8, partId = "0")
        repeat(2) { journal.firstVisibleDraw(OutputTraceOwner("run", 1, "0", origin = rendered.origin, invocationId = rendered.invocationId)) }
        val events = journal.snapshot(setOf("run")).events
        assertEquals(OutputLineage(OutputOrigin.MODEL_TEXT, "invocation"), committed)
        assertEquals(committed, rendered)
        assertEquals(listOf(OutputOrigin.PROGRESS_NOTICE, OutputOrigin.MODEL_TEXT), events.filter { it.kind == RunDiagnosticKind.FIRST_VISIBLE_TEXT }.map { it.outputOrigin })
        assertTrue(linkedParsedToDrawMillis(events) != null)
    }

    @Test fun mixedAndAmbiguousTextStayUnlinkedAndPrivacyTransitionClearsLineage() = runTest {
        val journal = RunDiagnosticJournal(MemoryVault(), backgroundScope).apply {
            enabled = true
            setRunPrivacy("run", false)
        }
        for (invocation in listOf("first", "latest")) {
            journal.traceOutput("run", OutputTraceBoundary.PROVIDER_VISIBLE, "answer", invocationId = invocation, outputOrigin = OutputOrigin.MODEL_TEXT)
        }
        assertEquals(OutputLineage(OutputOrigin.MODEL_TEXT), journal.traceOutput("run", OutputTraceBoundary.COMMITTED, "answer"))
        assertEquals(OutputLineage(), journal.traceOutput("run", OutputTraceBoundary.COMMITTED, "answer\nConnection failed"))
        // A transform cannot acquire ownership by accidentally matching a different source.
        assertEquals(OutputLineage(), journal.traceOutput("run", OutputTraceBoundary.RENDER_TRANSFORM, "answer"))
        journal.traceOutput("run", OutputTraceBoundary.COMMITTED, "setup", outputOrigin = OutputOrigin.SYSTEM_ERROR)
        journal.privateSession = true
        journal.privateSession = false
        assertEquals(OutputLineage(), journal.traceOutput("run", OutputTraceBoundary.RENDER_INPUT, "setup"))
        journal.traceOutput("run", OutputTraceBoundary.COMMITTED, "setup", outputOrigin = OutputOrigin.SYSTEM_ERROR)
        journal.clear()
        assertEquals(OutputLineage(), journal.traceOutput("run", OutputTraceBoundary.RENDER_INPUT, "setup"))
    }

    @Test fun anInvocationIdentifierAloneCannotDeclareUnknownTextToBeModelOutput() = runTest {
        val journal = RunDiagnosticJournal(MemoryVault(), backgroundScope).apply {
            enabled = true
            setRunPrivacy("run", false)
        }
        journal.record("run", RunDiagnosticKind.FIRST_TEXT, invocationId = "invocation")
        journal.traceOutput("run", OutputTraceBoundary.PROVIDER_VISIBLE, "unattributed", invocationId = "invocation")
        val events = journal.snapshot(setOf("run")).events
        assertTrue(events.all { it.outputOrigin == OutputOrigin.UNKNOWN })
        assertEquals(OutputLineage(), journal.traceOutput("run", OutputTraceBoundary.RENDER_INPUT, "unattributed"))
        assertEquals(null, linkedParsedToDrawMillis(events))
    }

    @Test fun boundedSnapshotCannotPublishCapturedEventsOrWatermarksAfterClearOrPrivateSession() = runTest {
        for (clear in listOf(true, false)) {
            val storage = MemoryVault()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var intercept = false
            val vault = object : SecretVault by storage {
                override suspend fun read(secretRef: String): ByteArray? {
                    val bytes = storage.read(secretRef)
                    if (intercept && secretRef.startsWith("journal-")) {
                        entered.complete(Unit)
                        release.await()
                    }
                    return bytes
                }
            }
            val journal = RunDiagnosticJournal(vault, backgroundScope).apply {
                enabled = true
                setRunPrivacy("run", false)
            }
            journal.record("run", RunDiagnosticKind.REQUEST_SERIALIZED)
            runCurrent()
            intercept = true
            val pending = async { journal.snapshot(setOf("run")) }
            entered.await()
            if (clear) journal.clear() else journal.privateSession = true
            release.complete(Unit)
            val snapshot = pending.await()
            assertTrue(snapshot.events.isEmpty())
            assertTrue(snapshot.highWatermarks.isEmpty())
            assertEquals("capture_revoked", snapshot.coverageScope)
        }
    }

    @Test fun exportCanFreezeBetweenPhysicalWritesWithoutLosingTheInFlightBatch() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vault = MemoryVault(gate)
        val journal = RunDiagnosticJournal(vault, backgroundScope).apply {
            enabled = true
            setRunPrivacy("run", false)
        }
        repeat(40) { journal.record("run", RunDiagnosticKind.REQUEST_SERIALIZED) }
        runCurrent()
        val retained = mutableListOf<Long>()
        val export = async { journal.stream("run", {}) { retained += it.sequence } }
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        val coverage = export.await()
        assertEquals((1L..40L).toList(), retained)
        assertEquals(0L, coverage.missingSequences)
        // The queued export acquires the lock after the checkpoint write, before
        // any encoded event chunk. Its immutable in-flight snapshot fills the gap.
        assertEquals(0L, coverage.decodedBatchCount)
        val restored = mutableListOf<Long>()
        journal.stream("run", {}) { restored += it.sequence }
        assertEquals(retained, restored)
    }

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
        var referenceCount = 0
        override suspend fun references(): Set<String> {
            referenceCount++
            return rows.keys.toSet()
        }
    }
}
