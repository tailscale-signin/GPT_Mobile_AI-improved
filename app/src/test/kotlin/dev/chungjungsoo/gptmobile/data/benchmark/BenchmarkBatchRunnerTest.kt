package dev.chungjungsoo.gptmobile.data.benchmark

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.localruntime.InferenceAdmission
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.localruntime.ResourceRequest
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BenchmarkBatchRunnerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val profiles = listOf("A", "B", "C").map { PlatformV2(uid = it, name = it, model = "frozen-model") }

    @Before fun reset() {
        context.deleteDatabase("benchmark_v2.db")
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun batch(id: String) = BenchmarkBatch(id, BenchmarkMode.QUICK, 1, profiles.mapIndexed { index, profile -> BenchmarkQueueItem(index, profile.benchmarkSnapshot(), "$id-${profile.uid}", benchmarkConfigKey(profile)) })

    private fun runner(
        store: BenchmarkStore,
        savedProfiles: List<PlatformV2> = profiles.reversed().map { it.copy(model = "edited-model") },
        onExecution: () -> Unit = {},
        engineeringExecutor: BenchmarkEngineeringExternalExecutor? = null,
        withTestCredentials: Boolean = true,
        validate: suspend (PlatformV2) -> Unit
    ): BenchmarkBatchRunner {
        val settings = mockk<SettingRepository>()
        // Saved edits never replace the accepted snapshot's model or order.
        coEvery { settings.fetchPlatformV2s() } returns savedProfiles.map { if (withTestCredentials) it.copy(token = it.token ?: "benchmark-test-key") else it }
        val chat = mockk<ChatRepository>()
        coEvery { chat.validateBenchmarkProfile(any()) } coAnswers { validate(firstArg()) }
        coEvery { chat.benchmarkContextTokens(any()) } returns null
        coEvery { chat.supportsBenchmarkTools(any()) } returns false
        coEvery { chat.openBenchmarkSession(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            onExecution()
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.TextDelta("A deliberately wrong but complete answer."), ProviderEvent.Completed)
            }
        }
        coEvery { chat.runDelegationBenchmark(any(), any(), any(), any(), any()) } answers {
            onExecution()
            val test = secondArg<BenchmarkCase>()
            BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.FAILED, preview = "Wrong", outputCharacters = 5, requestDispatched = true, workload = test.workload)
        }
        val runtime = mockk<LocalRuntime>(relaxed = true)
        every { runtime.loadedEngineSpec() } returns null
        return BenchmarkBatchRunner(store, chat, settings, runtime, context, engineeringExecutor = engineeringExecutor)
    }

    @Test fun contextManifestFreezeReachesEveryAcceptedArmWithoutChangingTheProfile() = runBlocking {
        val fixture = BenchmarkEngineeringTestFixtures(families = 1, repeats = 1)
        val profile = fixture.profiles.first()
        val task = requireNotNull(fixture.cases.single().engineeringTaskId)
        val accepted = fixture.plan.copy(
            sourceCommit = dev.chungjungsoo.gptmobile.BuildConfig.APP_COMMIT,
            profileUids = listOf(profile.uid),
            profileConfigKeys = fixture.plan.profileConfigKeys.filterKeys { it == profile.uid },
            externalFixtureDigests = emptyMap(),
            graderDigest = null,
            externalRouteDigests = emptyMap(),
            contextExperiment = BenchmarkContextExperiment(listOf("CURRENT", "TASK"))
        ).frozen()
        val prepared = accepted.copy(
            externalFixtureDigests = fixture.plan.externalFixtureDigests,
            graderDigest = fixture.plan.graderDigest,
            externalRouteDigests = fixture.plan.externalRouteDigests.filterKeys { it == profile.uid },
            contextManifestDigests = mapOf(task to benchmarkHash("public manifest")),
            contextSourceIdentities = mapOf(task to BenchmarkContextSourceIdentity(benchmarkHash("public source"), benchmarkHash("public reference"), 1, 2))
        ).frozen()
        val batch = BenchmarkBatch(
            accepted.id,
            BenchmarkMode.GITHUB_INTEGRATION,
            1,
            BenchmarkEngineeringRegistry.queue(listOf(profile), accepted),
            executionPolicy = accepted.executionPolicy,
            caseManifest = fixture.cases,
            engineeringPlan = accepted
        )
        val executor = mockk<BenchmarkEngineeringExternalExecutor>()
        coEvery { executor.preparePlan(accepted, fixture.cases, any()) } returns prepared
        val store = BenchmarkStore(context)
        try {
            store.checkpoint(batch, event = "MANIFEST_SAVED")
            var preflights = 0
            runner(store, savedProfiles = listOf(profile), engineeringExecutor = executor) {
                assertEquals(profile.uid, it.uid)
                assertEquals(profile.model, it.model)
                preflights++
                // Stop at the generation boundary after verifying real queue admission.
                throw IllegalArgumentException("Deliberate provider preflight boundary")
            }.run(batch.id) {}
            assertEquals(2, preflights)
            assertEquals(prepared, store.batch(batch.id)?.engineeringPlan)
            assertEquals(setOf("CURRENT", "TASK"), store.history.value.map { it.engineeringContextVariant }.toSet())
            assertTrue(store.history.value.none { run -> run.samples.any { it.failureCategory == "ENGINEERING_PREREQUISITE_UNAVAILABLE" } })
        } finally {
            store.close()
        }
    }

    @Test fun freshBlankProviderDefaultsStillReachTheAcceptedRoute() = runBlocking {
        val defaults = listOf(ClientType.GROQ, ClientType.NVIDIA).map { PlatformV2(uid = it.name, name = it.name, compatibleType = it, model = "frozen-model") }
        val accepted = BenchmarkBatch("default-routes", BenchmarkMode.QUICK, 1, defaults.mapIndexed { index, profile -> BenchmarkQueueItem(index, profile, "default-${profile.uid}", "key") })
        val store = BenchmarkStore(context)
        try {
            store.checkpoint(accepted)
            val validated = mutableSetOf<String>()
            var executions = 0
            runner(store, savedProfiles = defaults.map { it.copy(apiUrl = "https://changed.example/v1") }, onExecution = { executions++ }) {
                assertTrue(it.apiUrl.isEmpty())
                validated += it.uid
            }.run(accepted.id) {}
            assertEquals(defaults.map { it.uid }.toSet(), validated)
            assertTrue(executions > 0)
            assertTrue(store.history.value.filter { it.batchId == accepted.id }.none { it.samples.any { sample -> sample.failureCategory == "BENCHMARK_ENDPOINT_REDACTED" } })
        } finally {
            store.close()
        }
    }

    @Test fun restoredRemoteRoutesBlockBeforePreflightWithBlankOrChangedCurrentEndpoints() = runBlocking {
        val store = BenchmarkStore(context)
        try {
            for (type in listOf(ClientType.GROQ, ClientType.NVIDIA)) {
                for ((index, currentUrl) in listOf("", "https://changed.example/v1").withIndex()) {
                    val profile = PlatformV2(uid = type.name, name = type.name, compatibleType = type, model = "model")
                    val accepted = BenchmarkBatch("redacted-${type.name}-$index", BenchmarkMode.QUICK, 1, listOf(BenchmarkQueueItem(0, profile, "run-${type.name}-$index", "key")), redactedEndpointProfileUids = setOf(profile.uid))
                    store.checkpoint(accepted)
                    var preflights = 0
                    var executions = 0
                    runner(store, savedProfiles = listOf(profile.copy(apiUrl = currentUrl)), onExecution = { executions++ }) { preflights++ }.run(accepted.id) {}
                    assertEquals(0, preflights)
                    assertEquals(0, executions)
                    val restored = checkNotNull(store.batch(accepted.id))
                    assertEquals(BenchmarkQueueState.BLOCKED, restored.items.single().state)
                    val run = store.history.value.single { it.batchId == accepted.id }
                    val failure = checkNotNull(run.preparation?.failure)
                    assertEquals("BENCHMARK_ENDPOINT_REDACTED", failure.code)
                    assertFalse(failure.retryable)
                    assertTrue(run.samples.all { it.requestDispatched == false })
                    assertEquals(0, benchmarkCoverage(run).executed)
                }
            }
        } finally {
            store.close()
        }
    }

    @Test fun redactedDelegationPrimaryOrReviewerBlocksEveryExecution() = runBlocking {
        val store = BenchmarkStore(context)
        try {
            val target = profiles[0]
            val primary = profiles[1]
            val reviewer = profiles[2]
            for (redacted in listOf(primary, reviewer)) {
                val accepted = BenchmarkBatch("delegation-redacted-${redacted.uid}", BenchmarkMode.DELEGATION, 1, listOf(BenchmarkQueueItem(0, target, "run-${redacted.uid}", "key")), delegationPrimary = primary, delegationReviewer = reviewer, delegationSettings = dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings(enabled = true), delegationPlan = BenchmarkMode.QUICK, redactedEndpointProfileUids = setOf(redacted.uid))
                store.checkpoint(accepted)
                var preflights = 0
                var executions = 0
                runner(store, onExecution = { executions++ }) { preflights++ }.run(accepted.id) {}
                assertEquals(0, preflights)
                assertEquals(0, executions)
                assertEquals(BenchmarkQueueState.BLOCKED, checkNotNull(store.batch(accepted.id)).items.single().state)
                val run = store.history.value.single { it.batchId == accepted.id }
                assertEquals("BENCHMARK_ENDPOINT_REDACTED", run.preparation?.failure?.code)
                assertTrue(run.samples.all { it.requestDispatched == false })
            }
        } finally {
            store.close()
        }
    }

    @Test fun durableDeadlinesMatchOrdinaryDelegationAndPairedExecutionLimits() = runBlocking {
        val store = BenchmarkStore(context)
        try {
            val tracks = listOf(Triple(BenchmarkMode.QUICK, false, 90_000L), Triple(BenchmarkMode.DELEGATION, false, 180_000L), Triple(BenchmarkMode.DELEGATION, true, 180_000L))
            for ((index, track) in tracks.withIndex()) {
                val accepted = batch("deadline-$index").let { batch ->
                    batch.copy(mode = track.first, items = batch.items.take(1), delegationSettings = if (track.first == BenchmarkMode.DELEGATION) dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings(enabled = true) else null, delegationPlan = BenchmarkMode.QUICK, paired = track.second)
                }
                store.checkpoint(accepted)
                runner(store) {}.run(accepted.id) {}
                val attempts = Json { ignoreUnknownKeys = true }.decodeFromString<BenchmarkBackupPayload>(store.exportJson()).attempts.filter { it.batchId == accepted.id }
                assertTrue(attempts.isNotEmpty())
                assertEquals(5, attempts.count { it.sample?.workload != "delegation-paired" })
                assertEquals(if (track.second) 3 else 0, attempts.count { it.sample?.workload == "delegation-paired" })
                attempts.forEach { attempt ->
                    val expected = if (attempt.sample?.workload == "delegation-paired") 360_000L else track.third
                    assertEquals("${accepted.id}/${attempt.caseId} keeps its execution deadline", expected, attempt.deadlineAt - attempt.startedAt)
                }
                assertTrue(attempts.all { it.state == "TERMINAL" })
            }
        } finally {
            store.close()
        }
    }

    @Test fun firstMiddleAndLastChildAbortsRetainRowsAndContinueTheFrozenOrder() = runBlocking {
        val store = BenchmarkStore(context)
        try {
            for (failure in profiles.indices) {
                val visited = mutableListOf<String>()
                val runner = runner(store) { profile ->
                    assertEquals("frozen-model", profile.model)
                    visited += profile.uid
                    if (profile.uid == profiles[failure].uid) throw CancellationException("provider child cancelled")
                }
                val batch = batch("batch-$failure")
                store.checkpoint(batch, event = "MANIFEST_SAVED")
                runner.run(batch.id) {}
                val saved = checkNotNull(store.batch(batch.id))
                assertEquals(listOf("A", "B", "C"), visited)
                assertEquals(BenchmarkBatchState.COMPLETED_WITH_FAILURES, saved.state)
                assertEquals(BenchmarkQueueState.BLOCKED, saved.items[failure].state)
                assertEquals(2, saved.items.count { it.state == BenchmarkQueueState.COMPLETED })
                assertTrue(saved.items.all { it.terminal })
                val records = store.history.value.filter { it.batchId == batch.id }
                assertEquals(3, records.size)
                assertTrue(records.all { it.finished && it.samples.size == 5 })
                assertFalse(records.any { it.canceled })
                val backup = Json { ignoreUnknownKeys = true }.decodeFromString<BenchmarkBackupPayload>(store.exportJson())
                assertEquals(10, backup.attempts.count { it.batchId == batch.id })
                assertTrue(backup.attempts.filter { it.batchId == batch.id }.all { it.state in setOf("TERMINAL", "SKIPPED") })
            }
        } finally {
            store.close()
        }
    }

    @Test fun stopKeepsPendingProfilesAndResumeDoesNotReplayTheInterruptedProfile() = runBlocking {
        val store = BenchmarkStore(context)
        try {
            val entered = CompletableDeferred<Unit>()
            val runner = runner(store) { profile ->
                if (profile.uid == "A") {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val batch = batch("stop-and-resume")
            store.checkpoint(batch)
            val running = launch { runner.run(batch.id) {} }
            entered.await()
            assertFalse(runner.requestStop("obsolete-session", BenchmarkStopOrigin.USER_STOP))
            assertTrue(runner.requestStop(batch.id, BenchmarkStopOrigin.USER_STOP))
            running.cancelAndJoin()
            val stopped = checkNotNull(store.batch(batch.id))
            assertEquals(BenchmarkBatchState.STOPPED, stopped.state)
            assertEquals(BenchmarkQueueState.INTERRUPTED, stopped.items[0].state)
            assertEquals(listOf("B", "C"), stopped.pending.map { it.profile.uid })
            assertTrue(stopped.canResume)
            val visited = mutableListOf<String>()
            val resumed = runner(store) { visited += it.uid }
            store.checkpoint(stopped.copy(state = BenchmarkBatchState.QUEUED, stopOrigin = null), event = "RESUMED")
            resumed.run(batch.id) {}
            assertEquals(listOf("B", "C"), visited)
            assertEquals(BenchmarkBatchState.COMPLETED_WITH_FAILURES, store.batch(batch.id)?.state)
        } finally {
            store.close()
        }
    }

    @Test fun githubQuickAuditPreflightFailureKeepsOnlySelectedFourteenCases() = runBlocking {
        val store = BenchmarkStore(context)
        try {
            val selected = GitHubProfileAuditSuite.cases(allActions = false)
            assertEquals(14, selected.size)
            val profile = profiles.first()
            val accepted = BenchmarkBatch(
                "github-quick-preflight-failure",
                BenchmarkMode.GITHUB_INTEGRATION,
                1,
                listOf(BenchmarkQueueItem(0, profile.benchmarkSnapshot(), "github-quick-failure", benchmarkConfigKey(profile))),
                caseManifest = selected
            )
            store.checkpoint(accepted, event = "MANIFEST_SAVED")
            runner(store, savedProfiles = listOf(profile)) {
                throw CancellationException("provider preflight child cancelled")
            }.run(accepted.id) {}
            val run = store.history.value.single { it.batchId == accepted.id }
            assertEquals(14, run.plannedTrials)
            assertEquals(selected.map { it.id }, run.caseManifest?.map { it.id })
            assertEquals(selected.map { it.id }, run.samples.map { it.testId })
            assertEquals(0, benchmarkCoverage(run).executed)
            assertEquals(14, benchmarkCoverage(run).accounted)
            assertTrue(run.samples.all { it.requestDispatched == false })
        } finally {
            store.close()
        }
    }

    @Test fun githubQuickAuditUserSkipDoesNotAddDuplicateOrPretendProviderFailure() = runBlocking {
        val store = BenchmarkStore(context)
        try {
            val selected = GitHubProfileAuditSuite.cases(allActions = false)
            val profile = profiles.first()
            val accepted = BenchmarkBatch(
                "github-quick-user-skip",
                BenchmarkMode.GITHUB_INTEGRATION,
                1,
                listOf(BenchmarkQueueItem(0, profile.benchmarkSnapshot(), "github-quick-skip-run", benchmarkConfigKey(profile))),
                caseManifest = selected
            )
            store.checkpoint(accepted, event = "MANIFEST_SAVED")
            val entered = CompletableDeferred<Unit>()
            val batchRunner = runner(store, savedProfiles = listOf(profile)) {
                entered.complete(Unit)
                awaitCancellation()
            }
            val running = launch { batchRunner.run(accepted.id) {} }
            entered.await()
            assertTrue(batchRunner.requestStop(accepted.id, BenchmarkStopOrigin.USER_SKIP))
            running.join()
            val run = store.history.value.single { it.batchId == accepted.id }
            assertEquals(BenchmarkQueueState.SKIPPED, checkNotNull(store.batch(accepted.id)).items.single().state)
            assertEquals(BenchmarkStopOrigin.USER_SKIP, run.cancellationOrigin)
            assertEquals(14, run.plannedTrials)
            assertEquals(selected.map { it.id }, run.caseManifest?.map { it.id })
            assertEquals(selected.map { it.id }, run.samples.map { it.testId })
            assertEquals(14, benchmarkCoverage(run).accounted)
            assertEquals(0, benchmarkCoverage(run).executed)
            assertEquals(BenchmarkOutcome.SKIPPED, run.samples.first().outcome)
        } finally {
            store.close()
        }
    }

    @Test fun userStopWhileQueuedRetainsOnePreparationIncidentAndNoFixtureAttempts() = runBlocking {
        val store = BenchmarkStore(context)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val privateOwner = "private-chat-owner@example.invalid"
        val holder = launch {
            InferenceAdmission.shared(ResourceRequest.local(privateOwner)) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        try {
            val selected = batch("queued-stop").copy(items = batch("queued-stop").items.take(1))
            store.checkpoint(selected)
            var validations = 0
            var executions = 0
            val batchRunner = runner(store, onExecution = { executions++ }) { validations++ }
            val running = launch { batchRunner.run(selected.id) {} }
            val waiting = withTimeout(5_000) {
                store.history.first { records -> records.any { it.batchId == selected.id && it.preparation?.status == "WAITING" && it.preparation.blockerLabels.isNotEmpty() } }
            }.single { it.batchId == selected.id }
            assertEquals("admission", waiting.preparation?.stage)
            assertEquals(BENCHMARK_ADMISSION_QUEUE_TIMEOUT_MS, waiting.preparation?.queueDeadlineMs)
            assertFalse(waiting.preparation!!.blockerLabels.any { it.contains(privateOwner) || it.contains("@") })
            assertEquals(0, validations)
            assertEquals(0, executions)
            assertTrue(batchRunner.requestStop(selected.id, BenchmarkStopOrigin.USER_STOP))
            running.cancelAndJoin()
            val run = store.history.value.single { it.batchId == selected.id }
            val preparation = checkNotNull(run.preparation)
            assertEquals("CANCELED", preparation.status)
            assertEquals("USER_STOP", preparation.failure?.code)
            assertEquals(BenchmarkStopOrigin.USER_STOP, run.cancellationOrigin)
            assertTrue(preparation.elapsedMs != null && preparation.queueWaitMs != null)
            assertTrue(run.samples.all { it.dispositionOnly && it.outcome == BenchmarkOutcome.SKIPPED && it.attemptId == null && it.causedByEventId == preparation.eventId })
            assertEquals(0, benchmarkCoverage(run).executed)
            val backup = Json { ignoreUnknownKeys = true }.decodeFromString<BenchmarkBackupPayload>(store.exportJson())
            assertTrue(backup.attempts.none { it.runId == run.id })
            assertTrue(holder.isActive)
        } finally {
            release.complete(Unit)
            holder.join()
            store.close()
        }
    }

    @Test fun invalidConfigurationFailsBeforeAnOccupiedInferenceLease() = runBlocking {
        val store = BenchmarkStore(context)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch {
            InferenceAdmission.shared(ResourceRequest.local("busy-owner")) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        try {
            for (profile in listOf(PlatformV2(uid = "blank-gateway", name = "Blank Gateway", compatibleType = ClientType.LLAMA, model = ""), PlatformV2(uid = "no-credential", name = "No credential", model = "selected-model"))) {
                val selected = BenchmarkBatch("invalid-${profile.uid}", BenchmarkMode.QUICK, 1, listOf(BenchmarkQueueItem(0, profile, "invalid-run-${profile.uid}", "config")))
                store.checkpoint(selected)
                var validated = false
                var executed = false
                withTimeout(5_000) {
                    runner(store, savedProfiles = listOf(profile), onExecution = { executed = true }, withTestCredentials = false) { validated = true }.run(selected.id) {}
                }
                val run = store.history.value.single { it.batchId == selected.id }
                assertFalse(validated)
                assertFalse(executed)
                assertEquals("configuration", run.preparation?.stage)
                assertEquals("PROFILE_CONFIGURATION_INVALID", run.preparation?.failure?.code)
                assertEquals(null, run.preparation?.queueWaitMs)
                assertTrue(run.samples.all { it.dispositionOnly })
            }
        } finally {
            release.complete(Unit)
            holder.join()
            store.close()
        }
    }

    @Test fun nestedPreflightTimeoutRemainsAChildIncidentAndContinuesOtherProfiles() = runBlocking {
        val store = BenchmarkStore(context)
        try {
            val accepted = batch("child-timeout")
            store.checkpoint(accepted)
            val visited = mutableListOf<String>()
            runner(store) { profile ->
                visited += profile.uid
                if (profile.uid == profiles.first().uid) withTimeout(1) { awaitCancellation() }
            }.run(accepted.id) {}
            assertEquals(profiles.map { it.uid }, visited)
            val failed = store.history.value.single { it.id == accepted.items.first().runId }
            assertEquals("CHILD_OPERATION_ABORTED", failed.preparation?.failure?.code)
            assertEquals("FAILED", failed.preparation?.status)
            assertFalse(failed.canceled)
            assertTrue(failed.samples.all { it.dispositionOnly })
            assertEquals(2, store.batch(accepted.id)?.items?.count { it.state == BenchmarkQueueState.COMPLETED })
        } finally {
            store.close()
        }
    }
}
