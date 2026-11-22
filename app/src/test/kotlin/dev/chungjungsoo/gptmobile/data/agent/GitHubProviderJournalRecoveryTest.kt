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
    private val originalProjection = "Local validation rejected the original source selector."
    private val changedProjection = "This different body was never saved by the original execution."

    @Test fun intactAndLegacySourceLessProjectionsSurviveTheProtectedProviderRestart() = runBlocking {
        for (legacyProjection in listOf(false, true)) {
            val observed = restartWithProjection(damageBody = false, sourceLess = true, legacyProjection = legacyProjection)
            assertNull(observed.failure)
            assertEquals(1, observed.reopened)
            assertEquals(1, observed.providerRounds)
            assertEquals(originalProjection, observed.providerBody)
            assertTrue(observed.protectedOutputStillMatches)
            assertTrue(observed.projectionMatchesBody)
            assertEquals(0, observed.toolRedispatches)
        }
    }

    @Test fun changedJournalBodyIsRejectedBeforeReceiptsRestorationOrProviderReopen() = runBlocking {
        for (sourceLess in listOf(false, true)) {
            val observed = restartWithProjection(damageBody = true, sourceLess = sourceLess)
            assertTrue(observed.protectedOutputStillMatches)
            assertFalse(observed.projectionMatchesBody)
            assertEquals(0, observed.reopened)
            assertEquals(0, observed.providerRounds)
            assertEquals(0, observed.toolRedispatches)
            assertNull(observed.providerBody)
            for (failure in observed.unfencedReadFailures + observed.failure) {
                assertTrue(failure is ProviderRecoveryFailure)
                assertEquals("PROVIDER_WAIT_CHECKPOINT_CHANGED", failure?.message)
                val correlation = (failure as ProviderRecoveryFailure).correlation
                assertEquals(JsonPrimitive("TOOL_JOURNAL"), correlation["checkpoint_validation_stage"])
                assertEquals(JsonPrimitive("TOOL_JOURNAL_CHANGED"), correlation["checkpoint_mismatch_reason"])
                assertFalse("observed_tool_evidence_digest" in correlation)
                assertFalse("observed_tool_journal_digest" in correlation)
                assertFalse("settled_tool_count" in correlation)
                assertFalse("unsettled_tool_count" in correlation)
                assertEquals(JsonPrimitive(false), failure.event().failureDetails?.get("dispatched"))
                assertFalse(correlation.toString().contains(originalProjection))
                assertFalse(correlation.toString().contains(changedProjection))
            }
        }
    }

    @Test fun recapturedCorrectedSourceCallKeepsItsOriginalIdentityThroughDiskRecovery() = runBlocking {
        val directory = Files.createTempDirectory("github-corrected-source-journal").toFile()
        try {
            val original = ProviderEvent.ToolCall(
                "source-read",
                "github_read",
                Json.parseToJsonElement("""{"evidence_id":"ev_aaaaaaaaaaaaaaaaaaaaaaaa","ranges":[{"path":"src/Main.kt","start_line":1,"max_lines":20}]}""") as JsonObject
            )
            val pinned = original.captureProviderInvocation().copy(
                arguments = JsonObject(
                    original.arguments - "evidence_id" + buildJsonObject {
                        put("owner", "owner")
                        put("repo", "repo")
                        put("commit_sha", "b".repeat(40))
                    }
                )
            )
            val admitted = pinned.captureProviderInvocation()
            var executions = 0
            val journal = ToolBatchCheckpoint.forTurn(directory, 2, originalBinding, persistent = true)
            journal.enqueue(listOf(admitted))
            journal.execute(admitted) {
                executions++
                AgentToolResult(admitted.callId, ToolResultContent.Text("Pinned source read."), false)
            }
            val receipts = journal.providerWaitReceipts()
            assertEquals(ToolHandoffReceipt.fingerprint(original), receipts.single().invocationFingerprint)
            val restored = ToolBatchCheckpoint.forTurn(
                directory,
                2,
                originalBinding,
                persistent = true,
                expectedProviderJournalDigest = requireNotNull(GitHubProviderReplayEvidence.journalDigest(receipts))
            )
            val retained = restored.recoverySnapshot().completedExchanges.single().calls.single()
            assertEquals(original.providerInvocation(), retained.providerInvocation())
            assertEquals(pinned.arguments, retained.arguments)
            assertTrue(restored.execute(retained) { error("A recovered source operation must not be repeated") }.sharedResult)
            assertEquals(1, executions)
            for (invalid in listOf(
                pinned.copy(callId = "substituted-call"),
                pinned.copy(originalInvocation = ProviderToolInvocation("github_write", original.arguments)),
                pinned.copy(originalInvocation = ProviderToolInvocation(original.name, pinned.arguments)),
                pinned.copy(originalInvocationFingerprint = null),
                pinned.copy(originalInvocation = null)
            )) {
                assertTrue(runCatching { invalid.captureProviderInvocation() }.isFailure)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun ordinaryRecoveryRestoresOnlyACompletePreviouslyConfirmedJournalForTheSameTask() = runBlocking {
        val directory = Files.createTempDirectory("github-settled-handoff").toFile()
        try {
            val saved = buildJsonObject {
                put("state", "TOOL_HANDOFF")
                put(
                    "context",
                    buildJsonObject {
                        put("task_binding", taskBinding)
                        put("tool_checkpoint_binding", originalBinding)
                    }
                )
                put("pending_calls", JsonArray(listOf(JsonPrimitive(ToolHandoffReceipt.fingerprint(call)))))
                put("completed_results", JsonArray(emptyList()))
            }
            val journal = ToolBatchCheckpoint.forTurn(directory, 2, originalBinding, persistent = true)
            val current = "$scope:resumed"
            suspend fun lookup(task: String? = taskBinding, persistent: Boolean = true) =
                GitHubProviderToolCheckpoint.resolve(directory, 2, scope, "resumed", null, saved, persistent, task)
            journal.enqueue(listOf(call))
            assertEquals(current, lookup().binding)
            var executions = 0
            journal.execute(call) {
                executions++
                AgentToolResult(call.callId, ToolResultContent.Text("original receipt"), false)
            }
            assertEquals(current, lookup(null).binding)
            assertEquals(current, lookup("a different task").binding)
            assertEquals(current, lookup(persistent = false).binding)
            val resolved = lookup()
            assertEquals(originalBinding, resolved.binding)
            val restored = ToolBatchCheckpoint.forTurn(directory, 2, resolved.binding, persistent = true, expectedProviderJournalDigest = requireNotNull(resolved.journalDigest))
            assertEquals(listOf(call), restored.recoverySnapshot().completedExchanges.flatMap { it.calls })
            restored.execute(call) { error("A restored write must never be repeated") }
            assertEquals(1, executions)
            val extra = call.copy(callId = "another-call")
            journal.enqueue(listOf(extra))
            journal.execute(extra) { AgentToolResult(extra.callId, ToolResultContent.Text("other"), false) }
            assertEquals(current, lookup().binding)
            val fenced = ToolBatchCheckpoint.forTurn(directory, 2, resolved.binding, persistent = true, expectedProviderJournalDigest = resolved.journalDigest)
            assertTrue(runCatching { fenced.recoverySnapshot() }.exceptionOrNull() is ProviderRecoveryFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

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

    @Test fun rateLimitCapturesAll103SettledCallsIncluding21AutomaticReadsAndRestartsWithoutRepeatingAWrite() = runBlocking {
        val directory = Files.createTempDirectory("github-103-call-provider-wait").toFile()
        try {
            val journal = ToolBatchCheckpoint.forTurn(directory, 2, originalBinding, persistent = true)
            var writes = 0
            val primaryCalls = (0 until 82).map { index ->
                if (index == 0) call else ProviderEvent.ToolCall("primary-$index", "github_read", buildJsonObject { put("path", "source-$index.kt") })
            }
            val automaticReads = (0 until 21).map { index ->
                ProviderEvent.ToolCall("remote-result-consume-$index", "github_search", buildJsonObject { put("index_cursor", index * 4) })
            }
            for ((index, next) in (primaryCalls + automaticReads).withIndex()) {
                journal.enqueue(listOf(next))
                journal.execute(next) {
                    if (next == call) writes++
                    val rejected = index in 76..81
                    AgentToolResult(
                        next.callId,
                        ToolResultContent.Text(if (rejected) "Local validation rejection. ".repeat(300) else "Recorded result $index"),
                        isError = rejected,
                        dispatched = !rejected,
                        errorCode = if (rejected) "INVALID_ARGUMENTS" else null,
                        sharedResult = rejected
                    )
                }
            }
            val complete = journal.recoverySnapshot().completedExchanges
            val primaryReplay = complete.filter { it.calls.single() in primaryCalls }
            assertEquals(103, complete.size)
            assertEquals(82, primaryReplay.size)
            val outputFile = File(directory, "output.json")
            fun saved() = Json.parseToJsonElement(outputFile.readText()) as JsonObject
            val context = buildJsonObject {
                put("task_binding", taskBinding)
                put("tool_checkpoint_binding", originalBinding)
            }
            val rateLimit = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    emit(ProviderEvent.Failed("Provider cooldown", errorCode = "429", providerLimit = ProviderLimitDecision("OPENROUTER", "profile", "model", 30_000, "provider-scope", "PROVIDER_RATE_LIMITED", true)))
                }
            }
            val initial = GitHubOutputContinuationSession(
                rateLimit,
                checkpointContext = context,
                toolCheckpointBinding = originalBinding,
                durableToolReceipts = journal::handoffReceipts,
                durableProviderJournal = journal::providerWaitReceipts,
                saveCheckpoint = { outputFile.writeText(it.toString()) }
            ) { _, _, _ -> error("The durable provider wait owns resumption") }
                .streamRound(listOf(tool), primaryReplay).toList()
            val expectedCheckpoint = requireNotNull(initial.filterIsInstance<ProviderEvent.Failed>().single().providerLimit?.gitHubCheckpoint)
            val retained = saved()
            assertTrue(expectedCheckpoint.matches(retained))
            assertEquals(JsonPrimitive(103), retained["tool_journal_receipt_count"])
            assertEquals(JsonPrimitive(true), retained["tool_journal_complete"])
            assertEquals(103, (retained["completed_results"] as JsonArray).size)
            assertEquals(JsonPrimitive(GitHubProviderReplayEvidence.digest(GitHubProviderReplayEvidence.receipts(primaryReplay))), retained["provider_evidence_digest"])

            // A new journal and session model process restart. Replay is compacted
            // independently, retaining six local results without original sources.
            val resolved = GitHubProviderToolCheckpoint.resolve(directory, 2, scope, "new-parent", expectedCheckpoint, retained, persistent = true)
            assertEquals(retained["tool_journal_digest"], JsonPrimitive(requireNotNull(resolved.journalDigest)))
            val restored = ToolBatchCheckpoint.forTurn(directory, 2, resolved.binding, persistent = true, expectedProviderEvidenceDigest = resolved.evidenceDigest, expectedProviderJournalDigest = resolved.journalDigest)
            val recovered = restored.recoverySnapshot()
            assertEquals(103, recovered.completedExchanges.size)
            val replay = ToolExchangeCompactor.compact(recovered.completedExchanges.filter { it.calls.single().callId in primaryCalls.takeLast(7).map { next -> next.callId } }, maxReplayTokens = 2048, maxResultTokens = 64)
            assertTrue(GitHubProviderReplayEvidence.receipts(replay).any { it.sourceResultSha256 == null && it.journalProjectionSha256 != null })
            var resumedRounds = 0
            val resumed = GitHubOutputContinuationSession(
                delegate = object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("A restart must use its saved task")
                },
                expectedProviderCheckpoint = expectedCheckpoint,
                checkpointContext = context,
                toolCheckpointBinding = resolved.binding,
                toolCheckpointEvidenceDigest = resolved.evidenceDigest,
                toolCheckpointJournalDigest = resolved.journalDigest,
                durableToolReceipts = restored::handoffReceipts,
                durableProviderJournal = restored::providerWaitReceipts,
                readCheckpoint = { saved() },
                saveCheckpoint = { outputFile.writeText(it.toString()) }
            ) { _, retainedReplay, definitions ->
                assertEquals(replay, retainedReplay)
                assertEquals(listOf(tool), definitions)
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                        resumedRounds++
                        emit(ProviderEvent.TextDelta("Continued from the retained task and complete journal."))
                        emit(ProviderEvent.Completed)
                    }
                }
            }.streamRound(listOf(tool), replay).toList()
            assertEquals(ProviderEvent.Completed, resumed.last())
            assertEquals(1, resumedRounds)
            assertEquals(JsonPrimitive("COMPLETED"), saved()["state"])
            assertTrue(
                restored.execute(call) {
                    writes++
                    error("Never repeat a settled mutation")
                }.sharedResult
            )
            assertEquals(1, writes)

            // Missing old proof cannot authorize ignoring automatic or unknown work.
            val legacy = JsonObject(retained - setOf("tool_journal_complete", "tool_journal_digest", "tool_journal_receipt_count") + ("completed_results" to JsonArray(GitHubProviderReplayEvidence.receipts(primaryReplay).map { it.json() })))
            val legacyResolved = GitHubProviderToolCheckpoint.resolve(directory, 2, scope, "new-parent", expected(legacy), legacy, persistent = true)
            val legacyJournal = ToolBatchCheckpoint.forTurn(directory, 2, legacyResolved.binding, persistent = true, expectedProviderEvidenceDigest = legacyResolved.evidenceDigest, expectedProviderJournalDigest = legacyResolved.journalDigest)
            assertEquals("PROVIDER_WAIT_CHECKPOINT_CHANGED", runCatching { legacyJournal.recoverySnapshot() }.exceptionOrNull()?.message)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun completeWaitJournalRejectsChangedMetadataAndUnknownWritesBeforeRestoration() = runBlocking {
        val directory = Files.createTempDirectory("github-provider-complete-boundary").toFile()
        try {
            val journal = ToolBatchCheckpoint.forTurn(directory, 2, originalBinding, persistent = true)
            journal.enqueue(listOf(call))
            journal.execute(call) { AgentToolResult(call.callId, ToolResultContent.Text("Confirmed receipt"), false) }
            val receipts = journal.providerWaitReceipts()
            val saved = JsonObject(
                currentCheckpoint(journal.recoverySnapshot().completedExchanges) + buildJsonObject {
                    put("completed_results", JsonArray(receipts.map { it.json() }))
                    put("tool_journal_complete", true)
                    put("tool_journal_receipt_count", 1)
                    put("tool_journal_digest", requireNotNull(GitHubProviderReplayEvidence.journalDigest(receipts)))
                }
            )
            for (changed in listOf(
                JsonObject(saved + ("tool_journal_complete" to JsonPrimitive(false))),
                JsonObject(saved + ("tool_journal_receipt_count" to JsonPrimitive(2))),
                JsonObject(saved + ("tool_journal_digest" to JsonPrimitive("f".repeat(64))))
            )) {
                assertEquals("PROVIDER_WAIT_CHECKPOINT_CHANGED", runCatching { GitHubProviderToolCheckpoint.resolve(directory, 2, scope, "new-parent", expected(changed), changed, persistent = true) }.exceptionOrNull()?.message)
            }
            val resolved = GitHubProviderToolCheckpoint.resolve(directory, 2, scope, "new-parent", expected(saved), saved, persistent = true)
            val unknown = call.copy(callId = "unknown-write")
            journal.enqueue(listOf(unknown))
            assertTrue(runCatching { journal.execute(unknown) { throw java.io.IOException("Lost response after dispatch") } }.exceptionOrNull() is java.io.IOException)
            assertEquals("PROVIDER_WAIT_OUTCOME_UNKNOWN", runCatching { journal.providerWaitReceipts() }.exceptionOrNull()?.message)
            val restored = ToolBatchCheckpoint.forTurn(directory, 2, resolved.binding, persistent = true, expectedProviderEvidenceDigest = resolved.evidenceDigest, expectedProviderJournalDigest = resolved.journalDigest)
            assertEquals("PROVIDER_WAIT_OUTCOME_UNKNOWN", runCatching { restored.recoverySnapshot() }.exceptionOrNull()?.message)
            assertEquals("PROVIDER_WAIT_OUTCOME_UNKNOWN", runCatching { restored.execute(unknown) { error("An uncertain write must not replay") } }.exceptionOrNull()?.message)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun changedStoredProjectionCannotAuthorizeProviderWaitOrReplay() = runBlocking {
        for (shared in listOf(false, true)) {
            for (change in listOf("body", "hash")) {
                val directory = Files.createTempDirectory("github-projection-integrity").toFile()
                try {
                    var dispatches = 0
                    val journal = ToolBatchCheckpoint.forTurn(directory, 2, originalBinding, persistent = true)
                    journal.enqueue(listOf(call))
                    journal.execute(call) {
                        dispatches++
                        AgentToolResult(call.callId, ToolResultContent.Text("Retained result"), shared, dispatched = !shared, errorCode = if (shared) "INVALID_ARGUMENTS" else null, sharedResult = shared)
                    }
                    val receipts = journal.providerWaitReceipts()
                    assertEquals(shared, receipts.single().sourceResultSha256 == null)
                    val digest = requireNotNull(GitHubProviderReplayEvidence.journalDigest(receipts))
                    rewriteStoredResult(directory, call.callId) { result ->
                        val altered = if (change == "body") "body" to JsonPrimitive("Changed retained result") else "projectionSha256" to JsonPrimitive("not-a-sha")
                        JsonObject(result + altered)
                    }
                    val restored = ToolBatchCheckpoint.forTurn(directory, 2, originalBinding, persistent = true, expectedProviderJournalDigest = digest)
                    val message = "PROVIDER_WAIT_CHECKPOINT_CHANGED"
                    assertEquals(message, runCatching { journal.providerWaitReceipts() }.exceptionOrNull()?.message)
                    assertEquals(message, runCatching { restored.recoverySnapshot() }.exceptionOrNull()?.message)
                    assertEquals(
                        message,
                        runCatching {
                            restored.execute(call) {
                                dispatches++
                                error("Changed retained data must not authorize dispatch")
                            }
                        }.exceptionOrNull()?.message
                    )
                    assertEquals(1, dispatches)
                } finally {
                    directory.deleteRecursively()
                }
            }
        }
    }

    @Test fun legacyAbsentProjectionHashesStillRestoreAndDeduplicateSettledCalls() = runBlocking {
        for (shared in listOf(false, true)) {
            val directory = Files.createTempDirectory("github-legacy-projection").toFile()
            try {
                var dispatches = 0
                val journal = ToolBatchCheckpoint.forTurn(directory, 2, originalBinding, persistent = true)
                journal.enqueue(listOf(call))
                journal.execute(call) {
                    dispatches++
                    AgentToolResult(call.callId, ToolResultContent.Text("Retained result"), shared, dispatched = !shared, errorCode = if (shared) "INVALID_ARGUMENTS" else null, sharedResult = shared)
                }
                val receipts = journal.providerWaitReceipts()
                val digest = requireNotNull(GitHubProviderReplayEvidence.journalDigest(receipts))
                rewriteStoredResult(directory, call.callId) { JsonObject(it - "projectionSha256") }
                val restored = ToolBatchCheckpoint.forTurn(directory, 2, originalBinding, persistent = true, expectedProviderJournalDigest = digest)
                assertEquals(receipts, restored.providerWaitReceipts())
                assertEquals(ToolResultContent.Text("Retained result"), restored.recoverySnapshot().completedExchanges.single().results.single().content)
                assertTrue(
                    restored.execute(call) {
                        dispatches++
                        error("A settled call must not dispatch again")
                    }.sharedResult
                )
                assertEquals(1, dispatches)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test fun changedPriorBudgetProjectionCannotAuthorizeAnExplicitRetry() = runBlocking {
        val directory = Files.createTempDirectory("github-budget-projection-integrity").toFile()
        try {
            val journal = ToolBatchCheckpoint.forTurn(directory, 2, originalBinding, persistent = true)
            journal.enqueue(listOf(call))
            journal.complete(call, AgentToolResult(call.callId, ToolResultContent.Text("Budget rejected"), true, dispatched = false, errorCode = "BUDGET_EXHAUSTED", sharedResult = true))
            journal.prepareExplicitBudgetRetry()
            rewriteStoredResult(directory, call.callId, "priorBudgetRejection") { JsonObject(it + ("body" to JsonPrimitive("Changed refusal"))) }
            var dispatches = 0
            val restored = ToolBatchCheckpoint.forTurn(directory, 2, originalBinding, persistent = true)
            assertEquals(
                "PROVIDER_WAIT_CHECKPOINT_CHANGED",
                runCatching {
                    restored.execute(call) {
                        dispatches++
                        error("A changed retained refusal must not authorize dispatch")
                    }
                }.exceptionOrNull()?.message
            )
            assertEquals(0, dispatches)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun rewriteStoredResult(directory: File, callId: String, field: String = "result", transform: (JsonObject) -> JsonObject) {
        val file = File(directory, "tool-batches/2").listFiles()!!.single { it.extension == "json" }
        val saved = Json.parseToJsonElement(file.readText()) as JsonObject
        val entries = saved.getValue("entries") as JsonObject
        val entry = entries.getValue(callId) as JsonObject
        val result = entry.getValue(field) as JsonObject
        file.writeText(JsonObject(saved + ("entries" to JsonObject(entries + (callId to JsonObject(entry + (field to transform(result))))))).toString())
    }

    @Test fun protectedJournalBoundaryRejectsChangedIdentitiesAndUnsettledOperationsBeforeDispatch() = runBlocking {
        for (change in listOf("missing", "extra", "renamed", "source", "dispatched", "error", "unknown", "pending", "started")) {
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
                    if (changed && change in setOf("extra", "pending", "started")) {
                        val extra = call.copy(callId = "unexpected-write")
                        journal.enqueue(listOf(extra))
                        if (change == "extra") journal.execute(extra) { AgentToolResult(extra.callId, ToolResultContent.Text("Unexpected receipt"), false) }
                        if (change == "started") {
                            val stopped = runCatching { journal.execute(extra) { throw java.io.IOException("No acknowledgement") } }.exceptionOrNull()
                            assertTrue(stopped is java.io.IOException)
                        }
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
                val unknownOutcome = change in setOf("unknown", "started")
                val reason = if (unknownOutcome) "PROVIDER_WAIT_OUTCOME_UNKNOWN" else "PROVIDER_WAIT_CHECKPOINT_CHANGED"
                val failure = runCatching { guarded.recoverySnapshot() }.exceptionOrNull() as ProviderRecoveryFailure
                assertEquals(change, reason, failure.code)
                assertEquals(JsonPrimitive(unknownOutcome), failure.event().failureDetails?.get("requires_reconciliation"))
                assertEquals(change, reason, runCatching { guarded.execute(call) { error("Changed journal must never dispatch") } }.exceptionOrNull()?.message)
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

    private suspend fun restartWithProjection(damageBody: Boolean, sourceLess: Boolean, legacyProjection: Boolean = false): ProjectionRestart {
        val directory = Files.createTempDirectory("github-projection-integrity").toFile()
        try {
            val journal = ToolBatchCheckpoint.forTurn(directory, 2, originalBinding, persistent = true)
            journal.enqueue(listOf(call))
            journal.execute(call) {
                AgentToolResult(call.callId, ToolResultContent.Text(originalProjection), isError = sourceLess, errorCode = if (sourceLess) "INVALID_ARGUMENTS" else null, dispatched = !sourceLess, sharedResult = sourceLess)
            }
            val journalFile = File(directory, "tool-batches/2/${combinedDigest(originalBinding)}.json")
            val originalState = Json.parseToJsonElement(journalFile.readText()) as JsonObject
            val entries = originalState.getValue("entries") as JsonObject
            val entry = entries.getValue(call.callId) as JsonObject
            val result = entry.getValue("result") as JsonObject
            val digest = dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook.digest(originalProjection)
            assertEquals(if (sourceLess) JsonNull else JsonPrimitive(digest), result["sourceResultSha256"])
            assertEquals(JsonPrimitive(originalProjection), result["body"])
            assertEquals(JsonPrimitive(digest), result["projectionSha256"])
            assertTrue((originalState.getValue("deliveredResultDigests") as JsonObject).isEmpty())
            fun writeResult(updated: JsonObject) = journalFile.writeText(JsonObject(originalState + ("entries" to JsonObject(entries + (call.callId to JsonObject(entry + ("result" to updated)))))).toString())
            if (legacyProjection) writeResult(JsonObject(result + ("projectionSha256" to JsonNull)))

            val outputFile = File(directory, "protected-output.json")
            fun output() = Json.parseToJsonElement(outputFile.readText()) as JsonObject
            val context = buildJsonObject {
                put("task_binding", taskBinding)
                put("tool_checkpoint_binding", originalBinding)
            }
            val initial = GitHubOutputContinuationSession(
                delegate = object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                        emit(ProviderEvent.Failed("Provider cooldown", errorCode = "429", providerLimit = ProviderLimitDecision("OPENROUTER", "profile", "model", 30_000, "provider-scope", "PROVIDER_RATE_LIMITED", true)))
                    }
                },
                checkpointContext = context,
                toolCheckpointBinding = originalBinding,
                durableToolReceipts = journal::handoffReceipts,
                durableProviderJournal = journal::providerWaitReceipts,
                saveCheckpoint = { outputFile.writeText(it.toString()) }
            ) { _, _, _ -> error("The initial attempt must save its provider wait") }
                .streamRound(listOf(tool), journal.recoverySnapshot().completedExchanges).toList()
            val expected = requireNotNull(initial.filterIsInstance<ProviderEvent.Failed>().single().providerLimit?.gitHubCheckpoint)
            val protectedBytes = outputFile.readBytes()
            assertTrue(expected.matches(output()))
            assertEquals(JsonPrimitive(true), output()["tool_journal_complete"])
            assertEquals(JsonPrimitive(1), output()["tool_journal_receipt_count"])

            // Leave all receipt identities, flags and the protected output checkpoint
            // unchanged. The saved projection is the only content-integrity boundary.
            if (damageBody) writeResult(JsonObject(result + ("body" to JsonPrimitive(changedProjection))))
            val protectedOutputStillMatches = outputFile.readBytes().contentEquals(protectedBytes) && expected.matches(output())
            val stored = Json.parseToJsonElement(journalFile.readText()) as JsonObject
            val storedEntry = (stored.getValue("entries") as JsonObject).getValue(call.callId) as JsonObject
            val storedResult = storedEntry.getValue("result") as JsonObject
            val storedBody = (storedResult.getValue("body") as JsonPrimitive).content
            val projectionMatchesBody = storedResult["projectionSha256"] == JsonNull || storedResult["projectionSha256"] == JsonPrimitive(dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook.digest(storedBody))
            var reopened = 0
            var providerRounds = 0
            var providerBody: String? = null
            var toolRedispatches = 0
            val unfencedReadFailures = if (damageBody) {
                val reader = ToolBatchCheckpoint.forTurn(directory, 2, originalBinding, persistent = true)
                listOf(
                    runCatching { reader.handoffReceipts(setOf(ToolHandoffReceipt.fingerprint(call))) }.exceptionOrNull(),
                    runCatching { reader.providerWaitReceipts() }.exceptionOrNull(),
                    runCatching { reader.recoverySnapshot() }.exceptionOrNull(),
                    runCatching {
                        reader.execute(call) {
                            toolRedispatches++
                            error("A completed operation must not be repeated")
                        }
                    }.exceptionOrNull()
                )
            } else {
                emptyList()
            }
            val failure = runCatching {
                val resolved = GitHubProviderToolCheckpoint.resolve(directory, 2, scope, "new-parent", expected, output(), persistent = true)
                val restarted = ToolBatchCheckpoint.forTurn(directory, 2, resolved.binding, persistent = true, expectedProviderEvidenceDigest = resolved.evidenceDigest, expectedProviderJournalDigest = resolved.journalDigest)
                val recovered = restarted.recoverySnapshot()
                restarted.execute(call) {
                    toolRedispatches++
                    error("A completed operation must not be repeated")
                }
                val resumed = GitHubOutputContinuationSession(
                    delegate = object : AgentProviderSession {
                        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("A restart must use its saved provider boundary")
                    },
                    expectedProviderCheckpoint = expected,
                    checkpointContext = context,
                    toolCheckpointBinding = resolved.binding,
                    toolCheckpointEvidenceDigest = resolved.evidenceDigest,
                    toolCheckpointJournalDigest = resolved.journalDigest,
                    durableToolReceipts = restarted::handoffReceipts,
                    durableProviderJournal = restarted::providerWaitReceipts,
                    readCheckpoint = { output() },
                    saveCheckpoint = { outputFile.writeText(it.toString()) }
                ) { _, replay, definitions ->
                    reopened++
                    assertEquals(listOf(tool), definitions)
                    providerBody = ToolHandoffReceipt.payload(replay.single().results.single().content)
                    object : AgentProviderSession {
                        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                            providerRounds++
                            emit(ProviderEvent.TextDelta("Continued from the unchanged retained receipt."))
                            emit(ProviderEvent.Completed)
                        }
                    }
                }.streamRound(listOf(tool), recovered.completedExchanges).toList()
                assertEquals(ProviderEvent.Completed, resumed.last())
            }.exceptionOrNull()
            return ProjectionRestart(failure, reopened, providerRounds, providerBody, toolRedispatches, protectedOutputStillMatches, projectionMatchesBody, unfencedReadFailures)
        } finally {
            directory.deleteRecursively()
        }
    }

    private data class ProjectionRestart(val failure: Throwable?, val reopened: Int, val providerRounds: Int, val providerBody: String?, val toolRedispatches: Int, val protectedOutputStillMatches: Boolean, val projectionMatchesBody: Boolean, val unfencedReadFailures: List<Throwable?>)

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
