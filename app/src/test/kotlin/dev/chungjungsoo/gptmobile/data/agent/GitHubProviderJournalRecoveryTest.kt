package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.network.GitHubProviderCheckpoint
import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the real disk journal and output session across two rejected provider attempts. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class GitHubProviderJournalRecoveryTest {
    private val taskBinding = "a".repeat(64)
    private val scope = "2:17:profile"
    private val originalBinding = "$scope:configured:first-parent"
    private val tool = AgentToolDefinition("github_write", "Write an owned test fixture", JsonObject(emptyMap()))
    private val call = ProviderEvent.ToolCall("already-written", tool.name, buildJsonObject { put("branch", "codex-self-test/owned") })

    @Test fun completedWriteThenTwoRateLimitsResumesFromDiskWithoutRepeatingTheWrite() = runBlocking {
        val directory = Files.createTempDirectory("github-provider-journal").toFile()
        try {
            var mutations = 0
            var modelRounds = 0
            val outputFile = File(directory, "output.json")
            fun readOutput() = Json.parseToJsonElement(outputFile.readText()) as JsonObject
            val journal = ToolBatchCheckpoint.forTurn(directory, 2, originalBinding, persistent = true)
            journal.enqueue(listOf(call))
            journal.execute(call) {
                mutations++
                AgentToolResult(call.callId, ToolResultContent.Text("The owned fixture was committed."), false)
            }
            val context = buildJsonObject {
                put("task_binding", taskBinding)
                put("tool_checkpoint_binding", originalBinding)
            }
            fun provider(rejected: Boolean) = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    modelRounds++
                    if (rejected) {
                        emit(
                            ProviderEvent.Failed(
                                "Provider cooldown",
                                errorCode = "429",
                                providerLimit = ProviderLimitDecision("OPENROUTER", "profile", "model", 30_000L * modelRounds, "provider-scope", "PROVIDER_RATE_LIMITED", true)
                            )
                        )
                    } else {
                        emit(ProviderEvent.TextDelta("Finished using the retained fixture receipt."))
                        emit(ProviderEvent.Completed)
                    }
                }
            }
            val initial = GitHubOutputContinuationSession(
                provider(rejected = true),
                continuationRevision = "same-selected-profile",
                recoveryRevision = "original-run",
                checkpointContext = context,
                toolCheckpointBinding = originalBinding,
                saveCheckpoint = { outputFile.writeText(it.toString()) }
            ) { _, _, _ -> error("The durable provider wait owns continuation") }
                .streamRound(listOf(tool), journal.recoverySnapshot().completedExchanges).toList()
            var expected = requireNotNull(initial.filterIsInstance<ProviderEvent.Failed>().single().providerLimit?.gitHubCheckpoint)

            for (attempt in 1..2) {
                val retained = readOutput()
                val resolved = GitHubProviderToolCheckpoint.resolve(directory, 2, scope, "configured:resumed-parent-$attempt", expected, retained, persistent = true)
                assertEquals(originalBinding, resolved.binding)
                assertEquals((retained.getValue("provider_evidence_digest") as JsonPrimitive).content, resolved.evidenceDigest)
                val restoredJournal = ToolBatchCheckpoint.forTurn(
                    directory,
                    2,
                    resolved.binding,
                    persistent = true,
                    expectedProviderEvidenceDigest = resolved.evidenceDigest,
                    expectedProviderJournalDigest = resolved.journalDigest
                )
                val recovered = restoredJournal.recoverySnapshot()
                assertEquals(listOf(call), recovered.completedExchanges.flatMap { it.calls })
                assertTrue(restoredJournal.execute(call) { error("A completed mutation must never be replayed") }.sharedResult)
                val resumed = GitHubOutputContinuationSession(
                    delegate = object : AgentProviderSession {
                        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("A restart must continue the exact saved provider boundary")
                    },
                    continuationRevision = "same-selected-profile",
                    recoveryRevision = "original-run",
                    expectedProviderCheckpoint = expected,
                    checkpointContext = context,
                    toolCheckpointBinding = resolved.binding,
                    toolCheckpointEvidenceDigest = resolved.evidenceDigest,
                    toolCheckpointJournalDigest = resolved.journalDigest,
                    readCheckpoint = { readOutput() },
                    saveCheckpoint = { outputFile.writeText(it.toString()) }
                ) { draft, exchanges, definitions ->
                    assertEquals("", draft)
                    assertEquals(recovered.completedExchanges, exchanges)
                    assertEquals(listOf(tool), definitions)
                    provider(rejected = attempt == 1)
                }.streamRound(listOf(tool), recovered.completedExchanges).toList()
                assertTrue(resumed.none { it is ProviderEvent.ToolCall })
                if (attempt == 1) {
                    expected = requireNotNull(resumed.filterIsInstance<ProviderEvent.Failed>().single().providerLimit?.gitHubCheckpoint)
                    assertTrue(expected.matches(readOutput()))
                } else {
                    assertEquals(ProviderEvent.Completed, resumed.last())
                    assertEquals("Finished using the retained fixture receipt.", resumed.filterIsInstance<ProviderEvent.TextDelta>().single().text)
                }
            }
            assertEquals(1, mutations)
            assertEquals(3, modelRounds)
            assertEquals(JsonPrimitive("COMPLETED"), readOutput()["state"])
            assertEquals(JsonPrimitive(false), readOutput()["tool_actions_replayed"])
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun anUnrelatedTurnCannotOpenTheRetainedJournalEvenWithTheExactProviderCheckpoint() {
        val saved = buildJsonObject {
            put("state", "WAITING_PROVIDER_LIMIT")
            put("resume_allowed", true)
            put(
                "context",
                buildJsonObject {
                    put("task_binding", taskBinding)
                    put("tool_checkpoint_binding", originalBinding)
                }
            )
        }
        val expected = GitHubProviderCheckpoint(taskBinding, dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook.digest(saved.toString()))
        for (otherScope in listOf("2:18:profile", "2:17:another-profile", "3:17:profile")) {
            val failure = runCatching { GitHubProviderToolCheckpoint.binding(otherScope, "new-parent", expected, saved) }.exceptionOrNull()
            assertEquals("PROVIDER_WAIT_CHECKPOINT_CHANGED", failure?.message)
        }
    }

    @Test fun legacyCheckpointRestoresTheUniqueExactJournalWithoutChangingItsEvidence() = runBlocking {
        val directory = Files.createTempDirectory("github-legacy-journal").toFile()
        try {
            val journal = ToolBatchCheckpoint.forTurn(directory, 2, originalBinding, persistent = true)
            journal.enqueue(listOf(call))
            journal.execute(call) { AgentToolResult(call.callId, ToolResultContent.Text("Retained write receipt"), false) }
            val saved = legacyCheckpoint(journal.recoverySnapshot().completedExchanges)
            val expected = expected(saved)
            val resolved = GitHubProviderToolCheckpoint.resolve(directory, 2, scope, "configured:resumed-parent", expected, saved, persistent = true)
            assertEquals(originalBinding, resolved.binding)
            assertEquals((saved.getValue("provider_evidence_digest") as JsonPrimitive).content, resolved.evidenceDigest)
            assertEquals(originalBinding, ToolBatchCheckpoint.legacyProviderBinding(directory, 2, scope, expected, saved))
            assertNull(ToolBatchCheckpoint.legacyProviderBinding(directory, 2, "2:18:profile", expected, saved))
            assertNull(ToolBatchCheckpoint.legacyProviderBinding(directory, 3, "3:17:profile", expected, saved))
            assertNull(ToolBatchCheckpoint.legacyProviderBinding(directory, 2, scope, expected, JsonObject(saved + ("draft" to JsonPrimitive("changed")))))
            val restored = ToolBatchCheckpoint.forTurn(directory, 2, resolved.binding, persistent = true, expectedProviderEvidenceDigest = resolved.evidenceDigest)
            assertEquals(listOf(call), restored.recoverySnapshot().completedExchanges.flatMap { it.calls })
            assertTrue(restored.execute(call) { error("A legacy receipt must not dispatch again") }.sharedResult)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun legacyRecoveryRejectsAmbiguousJournalsAndPendingOrChangedEvidence() = runBlocking {
        val directory = Files.createTempDirectory("github-ambiguous-journal").toFile()
        try {
            val journal = ToolBatchCheckpoint.forTurn(directory, 2, originalBinding, persistent = true)
            journal.enqueue(listOf(call))
            journal.execute(call) { AgentToolResult(call.callId, ToolResultContent.Text("Retained write receipt"), false) }
            val saved = legacyCheckpoint(journal.recoverySnapshot().completedExchanges)
            val expected = expected(saved)
            val alternative = ToolBatchCheckpoint.forTurn(directory, 2, "$scope:configured:another-parent", persistent = true)
            alternative.enqueue(listOf(call))
            alternative.execute(call) { AgentToolResult(call.callId, ToolResultContent.Text("Different receipt"), false) }
            assertEquals(originalBinding, ToolBatchCheckpoint.legacyProviderBinding(directory, 2, scope, expected, saved))
            val pending = call.copy(callId = "not-yet-dispatched")
            journal.enqueue(listOf(pending))
            assertNull(ToolBatchCheckpoint.legacyProviderBinding(directory, 2, scope, expected, saved))
            assertUnrestorableLegacyCheckpoint(directory, saved)

            val ambiguousDirectory = Files.createTempDirectory("github-duplicate-journal").toFile()
            try {
                for (key in listOf(originalBinding, "$scope:configured:another-parent")) {
                    val duplicate = ToolBatchCheckpoint.forTurn(ambiguousDirectory, 2, key, persistent = true)
                    duplicate.enqueue(listOf(call))
                    duplicate.execute(call) { AgentToolResult(call.callId, ToolResultContent.Text("Retained write receipt"), false) }
                }
                assertNull(ToolBatchCheckpoint.legacyProviderBinding(ambiguousDirectory, 2, scope, expected, saved))
                assertUnrestorableLegacyCheckpoint(ambiguousDirectory, saved)
            } finally {
                ambiguousDirectory.deleteRecursively()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun compactedProviderReplayRestoresTheCompleteProtectedJournalWithoutRepeatingWrites() = runBlocking {
        for (legacy in listOf(false, true)) {
            val directory = Files.createTempDirectory("github-evicted-provider-replay").toFile()
            try {
                var writes = 0
                val written = call.copy(arguments = buildJsonObject { put("content", "Saved fixture content ".repeat(500)) })
                val laterRead = ProviderEvent.ToolCall("later-read", "github_read", buildJsonObject { put("path", "README.md") })
                val journal = ToolBatchCheckpoint.forTurn(directory, 2, originalBinding, persistent = true)
                journal.enqueue(listOf(written))
                journal.execute(written) {
                    writes++
                    AgentToolResult(written.callId, ToolResultContent.Text("Fixture saved"), false)
                }
                journal.enqueue(listOf(laterRead))
                journal.execute(laterRead) { AgentToolResult(laterRead.callId, ToolResultContent.Text("Read completed"), false) }
                val complete = journal.recoverySnapshot().completedExchanges
                val replay = ToolExchangeCompactor.compact(complete, maxReplayTokens = 128, maxResultTokens = 64)
                assertEquals(listOf(laterRead.callId), replay.flatMap { it.calls }.map { it.callId })
                val replayDigest = GitHubProviderReplayEvidence.digest(GitHubProviderReplayEvidence.receipts(replay))
                assertFalse(replayDigest == GitHubProviderReplayEvidence.digest(GitHubProviderReplayEvidence.receipts(complete)))
                val saved = JsonObject(
                    (if (legacy) legacyCheckpoint(replay) else currentCheckpoint(replay)) +
                        ("completed_results" to JsonArray(GitHubProviderReplayEvidence.receipts(complete).map { it.json() }))
                )
                val protectedBytes = saved.toString()
                val resolved = GitHubProviderToolCheckpoint.resolve(directory, 2, scope, "configured:resumed-parent", expected(saved), saved, persistent = true)
                assertEquals(originalBinding, resolved.binding)
                assertEquals(replayDigest, resolved.evidenceDigest)
                assertEquals(GitHubProviderReplayEvidence.journalDigest(GitHubProviderReplayEvidence.receipts(complete)), resolved.journalDigest)
                assertTrue(resolved.journalDigest != null)
                val restored = ToolBatchCheckpoint.forTurn(directory, 2, resolved.binding, persistent = true, expectedProviderEvidenceDigest = resolved.evidenceDigest, expectedProviderJournalDigest = resolved.journalDigest)
                assertEquals(complete, restored.recoverySnapshot().completedExchanges)
                assertTrue(
                    restored.execute(written) {
                        writes++
                        error("A completed write cannot be repeated")
                    }.sharedResult
                )
                assertEquals(1, writes)
                assertEquals(protectedBytes, saved.toString())
                assertEquals(JsonPrimitive(replayDigest), saved["provider_evidence_digest"])
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test fun protectedJournalBoundaryRejectsChangedIdentitiesAndUnsettledOperationsBeforeDispatch() = runBlocking {
        for (change in listOf("missing", "extra", "renamed", "source", "dispatched", "error", "unknown", "pending")) {
            val directory = Files.createTempDirectory("github-changed-journal-boundary").toFile()
            try {
                val later = call.copy(callId = "later-read", name = "github_read")
                suspend fun populate(target: File, changed: Boolean): ToolBatchCheckpoint {
                    val journal = ToolBatchCheckpoint.forTurn(target, 2, originalBinding, persistent = true)
                    val first = if (changed && change == "renamed") call.copy(callId = "renamed-write", originalInvocationFingerprint = ToolHandoffReceipt.fingerprint(call)) else call
                    for (entry in listOf(first, later)) {
                        if (changed && change == "missing" && entry == later) continue
                        journal.enqueue(listOf(entry))
                        journal.execute(entry) {
                            val altered = changed && entry == first
                            AgentToolResult(
                                entry.callId,
                                ToolResultContent.Text(if (altered && change == "source") "Changed receipt" else "Retained receipt"),
                                isError = altered && change in setOf("error", "unknown"),
                                dispatched = !(altered && change == "dispatched"),
                                errorCode = if (altered && change == "error") {
                                    "TOOL_FAILED"
                                } else if (altered && change == "unknown") {
                                    "TOOL_OUTCOME_UNKNOWN"
                                } else {
                                    null
                                }
                            )
                        }
                    }
                    if (changed && change in setOf("extra", "pending")) {
                        val extra = call.copy(callId = "unexpected-write")
                        journal.enqueue(listOf(extra))
                        if (change == "extra") journal.execute(extra) { AgentToolResult(extra.callId, ToolResultContent.Text("Unexpected receipt"), false) }
                    }
                    return journal
                }
                val original = populate(directory, changed = false).recoverySnapshot().completedExchanges
                val receipts = GitHubProviderReplayEvidence.receipts(original)
                val saved = JsonObject(currentCheckpoint(original) + ("completed_results" to JsonArray(receipts.map { it.json() })))
                val resolved = GitHubProviderToolCheckpoint.resolve(directory, 2, scope, "configured:resumed-parent", expected(saved), saved, persistent = true)
                assertTrue(resolved.journalDigest != null)
                val replacement = File(directory, "replacement")
                val changed = populate(replacement, changed = true).recoverySnapshot().completedExchanges
                if (change == "renamed") {
                    // A retained invocation fingerprint alone does not bind the current call ID.
                    assertEquals(resolved.evidenceDigest, GitHubProviderReplayEvidence.digest(GitHubProviderReplayEvidence.receipts(changed)))
                }
                val fileName = File(directory, "tool-batches/2").listFiles()!!.single { it.extension == "json" }.name
                File(replacement, "tool-batches/2/$fileName").copyTo(File(directory, "tool-batches/2/$fileName"), overwrite = true)
                val guarded = ToolBatchCheckpoint.forTurn(directory, 2, resolved.binding, persistent = true, expectedProviderEvidenceDigest = resolved.evidenceDigest, expectedProviderJournalDigest = resolved.journalDigest)
                assertEquals(change, "PROVIDER_WAIT_CHECKPOINT_CHANGED", runCatching { guarded.recoverySnapshot() }.exceptionOrNull()?.message)
                assertEquals(change, "PROVIDER_WAIT_CHECKPOINT_CHANGED", runCatching { guarded.execute(call) { error("Changed journal must never dispatch") } }.exceptionOrNull()?.message)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test fun incompleteOrMalformedProtectedReceiptsCannotAuthorizeCompactedJournalRestoration() = runBlocking {
        val directory = Files.createTempDirectory("github-incomplete-journal-boundary").toFile()
        try {
            val journal = ToolBatchCheckpoint.forTurn(directory, 2, originalBinding, persistent = true)
            journal.enqueue(listOf(call))
            journal.execute(call) { AgentToolResult(call.callId, ToolResultContent.Text("Retained receipt"), false) }
            val receipt = GitHubProviderReplayEvidence.receipts(journal.recoverySnapshot().completedExchanges).single().json()
            val invalid = listOf(
                JsonObject(receipt - "source_result_sha256"),
                JsonObject(receipt + ("source_hash_scope" to JsonPrimitive("REPLAY_PROJECTION_PAYLOAD_UTF8"))),
                JsonObject(receipt + ("dispatched" to JsonPrimitive("true"))),
                JsonObject(receipt - "requires_reconciliation"),
                JsonObject(receipt + ("requires_reconciliation" to JsonPrimitive(true))),
                JsonObject(receipt + ("invocation_fingerprint" to JsonPrimitive("not-a-sha"))),
                JsonObject(receipt + ("error_code" to JsonPrimitive(429)))
            ).map { JsonArray(listOf(it)) } + listOf(JsonArray(listOf(receipt, receipt)), JsonArray(listOf(JsonPrimitive("not-a-receipt"))))
            for (boundary in invalid) {
                val saved = JsonObject(currentCheckpoint(emptyList()) + ("completed_results" to boundary))
                val resolved = GitHubProviderToolCheckpoint.resolve(directory, 2, scope, "configured:resumed-parent", expected(saved), saved, persistent = true)
                assertNull(resolved.journalDigest)
                val guarded = ToolBatchCheckpoint.forTurn(directory, 2, resolved.binding, persistent = true, expectedProviderEvidenceDigest = resolved.evidenceDigest, expectedProviderJournalDigest = resolved.journalDigest)
                assertEquals("PROVIDER_WAIT_CHECKPOINT_CHANGED", runCatching { guarded.recoverySnapshot() }.exceptionOrNull()?.message)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun anEmptyCompletedBoundaryCannotOverrideEvidenceOfMissingJournalReceipts() = runBlocking {
        val directory = Files.createTempDirectory("github-missing-protected-journal").toFile()
        try {
            val completed = listOf(AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Text("Missing retained write"), false))))
            for (legacy in listOf(false, true)) {
                val saved = JsonObject((if (legacy) legacyCheckpoint(completed) else currentCheckpoint(completed)) + ("completed_results" to JsonArray(emptyList())))
                val resolved = GitHubProviderToolCheckpoint.resolve(directory, 2, scope, "configured:resumed-parent", expected(saved), saved, persistent = true)
                assertNull(resolved.journalDigest)
                val guarded = ToolBatchCheckpoint.forTurn(directory, 2, resolved.binding, persistent = true, expectedProviderEvidenceDigest = resolved.evidenceDigest, expectedProviderJournalDigest = resolved.journalDigest)
                assertEquals("PROVIDER_WAIT_CHECKPOINT_CHANGED", runCatching { guarded.recoverySnapshot() }.exceptionOrNull()?.message)
                assertEquals("PROVIDER_WAIT_CHECKPOINT_CHANGED", runCatching { guarded.execute(call) { error("Missing journal cannot authorize a repeated write") } }.exceptionOrNull()?.message)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun legacyCheckpoint(exchanges: List<AgentToolExchange>) = buildJsonObject {
        put("state", "WAITING_PROVIDER_LIMIT")
        put("resume_allowed", true)
        put("provider_evidence_digest", GitHubProviderReplayEvidence.digest(GitHubProviderReplayEvidence.receipts(exchanges)))
        put("context", buildJsonObject { put("task_binding", taskBinding) })
    }

    @Test fun aJournalChangedAfterLookupIsRejectedBeforeRestoringOrDispatchingPendingCalls() = runBlocking {
        val directory = Files.createTempDirectory("github-journal-fence").toFile()
        try {
            val journal = ToolBatchCheckpoint.forTurn(directory, 2, originalBinding, persistent = true)
            journal.enqueue(listOf(call))
            journal.execute(call) { AgentToolResult(call.callId, ToolResultContent.Text("Retained write receipt"), false) }
            val saved = legacyCheckpoint(journal.recoverySnapshot().completedExchanges)
            val resolved = GitHubProviderToolCheckpoint.resolve(directory, 2, scope, "configured:resumed-parent", expected(saved), saved, persistent = true)
            assertEquals(originalBinding, resolved.binding)
            val unexpected = call.copy(callId = "concurrent-pending-write")
            journal.enqueue(listOf(unexpected))
            val guarded = ToolBatchCheckpoint.forTurn(
                directory,
                2,
                resolved.binding,
                persistent = true,
                expectedProviderEvidenceDigest = resolved.evidenceDigest
            )
            val changedJournal = runCatching { guarded.recoverySnapshot() }.exceptionOrNull()
            assertTrue(changedJournal is ProviderRecoveryFailure)
            assertEquals("PROVIDER_WAIT_CHECKPOINT_CHANGED", changedJournal?.message)
            assertEquals(JsonPrimitive(false), (changedJournal as ProviderRecoveryFailure).event().failureDetails?.get("dispatched"))
            var mutations = 0
            assertEquals(
                "PROVIDER_WAIT_CHECKPOINT_CHANGED",
                runCatching {
                    guarded.execute(unexpected) {
                        mutations++
                        AgentToolResult(unexpected.callId, ToolResultContent.Text("Unexpected write"), false)
                    }
                }.exceptionOrNull()?.message
            )
            assertEquals(0, mutations)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun aFreshRequestUsesItsCurrentBindingWithoutAdoptingSavedProviderEvidence() = runBlocking {
        val directory = Files.createTempDirectory("github-fresh-journal").toFile()
        try {
            val resolved = GitHubProviderToolCheckpoint.resolve(
                directory,
                2,
                scope,
                "configured:new-parent",
                expected = null,
                checkpoint = buildJsonObject { put("provider_evidence_digest", "unrelated") },
                persistent = true
            )
            assertEquals("$scope:configured:new-parent", resolved.binding)
            assertNull(resolved.evidenceDigest)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun resolverRejectsMissingMalformedEvidenceAndTaskBindingMismatch() = runBlocking {
        val directory = Files.createTempDirectory("github-invalid-provider-evidence").toFile()
        try {
            val saved = currentCheckpoint(emptyList())
            val context = saved.getValue("context") as JsonObject
            val invalid = listOf(
                JsonObject(saved - "provider_evidence_digest"),
                JsonObject(saved + ("provider_evidence_digest" to JsonNull)),
                JsonObject(saved + ("provider_evidence_digest" to JsonPrimitive(true))),
                JsonObject(saved + ("provider_evidence_digest" to Json.parseToJsonElement("1".repeat(64)))),
                JsonObject(saved + ("provider_evidence_digest" to JsonPrimitive("b".repeat(63)))),
                JsonObject(saved + ("provider_evidence_digest" to JsonPrimitive("B".repeat(64)))),
                JsonObject(saved + ("provider_evidence_digest" to JsonArray(emptyList()))),
                JsonObject(saved - "context"),
                JsonObject(saved + ("context" to JsonObject(context - "task_binding"))),
                JsonObject(saved + ("context" to JsonObject(context + ("task_binding" to JsonPrimitive("b".repeat(64)))))),
                JsonObject(legacyCheckpoint(emptyList()) + ("context" to buildJsonObject { put("task_binding", "b".repeat(64)) }))
            )
            for (checkpoint in invalid) {
                val failure = runCatching {
                    GitHubProviderToolCheckpoint.resolve(directory, 2, scope, "configured:new-parent", expected(checkpoint), checkpoint, persistent = true)
                }.exceptionOrNull()
                assertEquals("Malformed evidence must be rejected before any journal is selected: $checkpoint", "PROVIDER_WAIT_CHECKPOINT_CHANGED", failure?.message)
                assertTrue(failure is ProviderRecoveryFailure)
            }
            for (checkpoint in listOf(null, JsonObject(saved + ("draft" to JsonPrimitive("changed after scheduling"))))) {
                val failure = runCatching {
                    GitHubProviderToolCheckpoint.resolve(directory, 2, scope, "configured:new-parent", expected(saved), checkpoint, persistent = true)
                }.exceptionOrNull()
                assertEquals("PROVIDER_WAIT_CHECKPOINT_CHANGED", failure?.message)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun legacyResolverDoesNotBorrowMissingUnrelatedChangedOrTemporaryJournals() = runBlocking {
        val directory = Files.createTempDirectory("github-unavailable-legacy-journal").toFile()
        try {
            val result = AgentToolResult(call.callId, ToolResultContent.Text("Retained write receipt"), false)
            val saved = legacyCheckpoint(listOf(AgentToolExchange(listOf(call), listOf(result))))
            assertUnrestorableLegacyCheckpoint(directory, saved)

            val unrelated = ToolBatchCheckpoint.forTurn(directory, 2, "2:18:profile:configured:other-parent", persistent = true)
            unrelated.enqueue(listOf(call))
            unrelated.execute(call) { result }
            assertUnrestorableLegacyCheckpoint(directory, saved)

            val changed = ToolBatchCheckpoint.forTurn(directory, 2, "$scope:configured:changed-parent", persistent = true)
            changed.enqueue(listOf(call))
            changed.execute(call) { result.copy(content = ToolResultContent.Text("A different receipt")) }
            assertUnrestorableLegacyCheckpoint(directory, saved)

            val journal = ToolBatchCheckpoint.forTurn(directory, 2, originalBinding, persistent = true)
            journal.enqueue(listOf(call))
            journal.execute(call) { result }
            assertEquals(originalBinding, GitHubProviderToolCheckpoint.resolve(directory, 2, scope, "configured:resumed-parent", expected(saved), saved, persistent = true).binding)
            assertUnrestorableLegacyCheckpoint(directory, saved, persistent = false)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun successfulProviderRestoreAllowsLaterWritesAndUnfencedDurableReceiptLookup() = runBlocking {
        val directory = Files.createTempDirectory("github-provider-handoff").toFile()
        try {
            val journal = ToolBatchCheckpoint.forTurn(directory, 2, originalBinding, persistent = true)
            journal.enqueue(listOf(call))
            journal.execute(call) { AgentToolResult(call.callId, ToolResultContent.Text("Retained write receipt"), false) }
            var saved = currentCheckpoint(journal.recoverySnapshot().completedExchanges)
            val resolved = GitHubProviderToolCheckpoint.resolve(directory, 2, scope, "configured:resumed-parent", expected(saved), saved, persistent = true)
            val restored = ToolBatchCheckpoint.forTurn(directory, 2, resolved.binding, persistent = true, expectedProviderEvidenceDigest = resolved.evidenceDigest)
            val exchanges = restored.recoverySnapshot().completedExchanges
            val nextCall = call.copy(callId = "new-authorized-write")
            val durableLookupFingerprints = mutableListOf<Set<String>>()
            val continued = GitHubOutputContinuationSession(
                delegate = object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("The saved provider boundary must reopen")
                },
                expectedProviderCheckpoint = expected(saved),
                checkpointContext = saved.getValue("context") as JsonObject,
                toolCheckpointBinding = resolved.binding,
                toolCheckpointEvidenceDigest = resolved.evidenceDigest,
                durableToolReceipts = { fingerprints ->
                    durableLookupFingerprints += fingerprints.toSet()
                    // Match the production callback: later lookups use the same
                    // binding without the initial provider snapshot guard.
                    ToolBatchCheckpoint.forTurn(directory, 2, resolved.binding, persistent = true).handoffReceipts(fingerprints)
                },
                readCheckpoint = { saved },
                saveCheckpoint = { saved = it }
            ) { _, retained, _ ->
                assertEquals(exchanges, retained)
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                        emit(nextCall)
                        emit(ProviderEvent.Completed)
                    }
                }
            }
            val events = continued.streamRound(listOf(tool), exchanges).toList()
            assertEquals(listOf(nextCall), events.filterIsInstance<ProviderEvent.ToolCall>())
            assertEquals(JsonPrimitive("TOOL_HANDOFF"), saved["state"])
            assertTrue(durableLookupFingerprints.any { ToolHandoffReceipt.fingerprint(call) in it })
            val lookupsBeforeNewWrite = durableLookupFingerprints.size

            var mutations = 0
            restored.enqueue(listOf(nextCall))
            val result = restored.execute(nextCall) {
                mutations++
                AgentToolResult(nextCall.callId, ToolResultContent.Text("New authorized write receipt"), false)
            }
            continued.observeToolResult(nextCall, result)
            assertEquals(1, mutations)
            assertTrue(durableLookupFingerprints.drop(lookupsBeforeNewWrite).any { ToolHandoffReceipt.fingerprint(nextCall) in it })
            assertEquals(JsonPrimitive("TOOL_RESULTS_READY"), saved["state"])
            assertTrue(GitHubToolHandoff.pending(saved).isEmpty())
            assertEquals(listOf(call.callId, nextCall.callId), restored.recoverySnapshot().completedExchanges.flatMap { it.calls }.map { it.callId })

            val staleGuard = ToolBatchCheckpoint.forTurn(directory, 2, resolved.binding, persistent = true, expectedProviderEvidenceDigest = resolved.evidenceDigest)
            assertEquals("PROVIDER_WAIT_CHECKPOINT_CHANGED", runCatching { staleGuard.recoverySnapshot() }.exceptionOrNull()?.message)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun cancellationAtTheFinalIoReturnLeavesTheProviderRestoreGuardArmed() = runBlocking {
        val directory = Files.createTempDirectory("github-cancelled-provider-restore").toFile()
        try {
            val journal = ToolBatchCheckpoint.forTurn(directory, 2, originalBinding, persistent = true)
            journal.enqueue(listOf(call))
            journal.execute(call) { AgentToolResult(call.callId, ToolResultContent.Text("Retained write receipt"), false) }
            val saved = currentCheckpoint(journal.recoverySnapshot().completedExchanges)
            val resolved = GitHubProviderToolCheckpoint.resolve(directory, 2, scope, "configured:resumed-parent", expected(saved), saved, persistent = true)
            val guarded = ToolBatchCheckpoint.forTurn(directory, 2, resolved.binding, persistent = true, expectedProviderEvidenceDigest = resolved.evidenceDigest)
            val returns = LinkedBlockingQueue<Runnable>()
            val parent = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    returns.add(block)
                }
            }
            fun nextReturn() = checkNotNull(returns.poll(10, TimeUnit.SECONDS)) { "The checkpoint did not return to its parent dispatcher." }
            val workerEntries = LinkedBlockingQueue<ContinuationInterceptor>()
            val workerPermits = Semaphore(0)
            val closing = AtomicBoolean(false)
            val workerGate = object : ThreadContextElement<Unit> {
                override val key = object : CoroutineContext.Key<ThreadContextElement<Unit>> {}
                override fun updateThreadContext(context: CoroutineContext) {
                    val dispatcher = context[ContinuationInterceptor]
                    if (!closing.get() && (dispatcher === Dispatchers.IO || dispatcher === Dispatchers.Default)) {
                        workerEntries.add(dispatcher)
                        workerPermits.acquireUninterruptibly()
                    }
                }
                override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) = Unit
            }
            fun completeWorkerAfterParentSuspends(dispatcher: CoroutineDispatcher) {
                nextReturn().run()
                assertEquals(dispatcher, workerEntries.poll(10, TimeUnit.SECONDS))
                workerPermits.release()
            }
            var snapshotDelivered = false
            val restoration = CoroutineScope(parent + workerGate).launch {
                guarded.recoverySnapshot()
                snapshotDelivered = true
            }
            try {
                // Fast withContext work may finish before its parent suspends and
                // omit a return dispatch. Hold each real worker until that parent
                // turn has returned, then release IO, snapshot parsing, and recheck.
                completeWorkerAfterParentSuspends(Dispatchers.IO)
                completeWorkerAfterParentSuspends(Dispatchers.Default)
                completeWorkerAfterParentSuspends(Dispatchers.IO)
                val finalReturn = nextReturn()
                restoration.cancel()
                finalReturn.run()
                restoration.join()
                assertFalse(snapshotDelivered)

                val pending = call.copy(callId = "pending-after-cancelled-restore")
                journal.enqueue(listOf(pending))
                assertEquals("PROVIDER_WAIT_CHECKPOINT_CHANGED", runCatching { guarded.recoverySnapshot() }.exceptionOrNull()?.message)
                var mutations = 0
                assertEquals(
                    "PROVIDER_WAIT_CHECKPOINT_CHANGED",
                    runCatching {
                        guarded.execute(pending) {
                            mutations++
                            AgentToolResult(pending.callId, ToolResultContent.Text("Unexpected write"), false)
                        }
                    }.exceptionOrNull()?.message
                )
                assertEquals(0, mutations)
            } finally {
                // A worker racing cleanup either sees closing or consumes this
                // permit; it cannot remain blocked after an assertion failure.
                closing.set(true)
                workerPermits.release()
                restoration.cancel()
                while (!restoration.isCompleted) nextReturn().run()
                while (true) (returns.poll() ?: break).run()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun currentCheckpoint(exchanges: List<AgentToolExchange>): JsonObject {
        val legacy = legacyCheckpoint(exchanges)
        return JsonObject(
            legacy + (
                "context" to buildJsonObject {
                    put("task_binding", taskBinding)
                    put("tool_checkpoint_binding", originalBinding)
                }
                )
        )
    }

    private suspend fun assertUnrestorableLegacyCheckpoint(directory: File, saved: JsonObject, persistent: Boolean = true) {
        val resolved = GitHubProviderToolCheckpoint.resolve(directory, 2, scope, "configured:resumed-parent", expected(saved), saved, persistent)
        assertEquals("$scope:configured:resumed-parent", resolved.binding)
        val guarded = ToolBatchCheckpoint.forTurn(directory, 2, resolved.binding, persistent, expectedProviderEvidenceDigest = resolved.evidenceDigest)
        assertEquals("PROVIDER_WAIT_CHECKPOINT_CHANGED", runCatching { guarded.recoverySnapshot() }.exceptionOrNull()?.message)
    }

    private fun expected(checkpoint: JsonObject) = GitHubProviderCheckpoint(taskBinding, dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook.digest(checkpoint.toString()))
}
