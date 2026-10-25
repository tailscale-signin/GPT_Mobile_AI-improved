package dev.chungjungsoo.gptmobile.data.benchmark

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.localruntime.InferenceAdmission
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BenchmarkAuditBatchIntegrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun reset() {
        context.deleteDatabase("benchmark_v2.db")
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun failedReadinessKeepsItsOwnEvidenceAndTheNextProfileRunsItsEntireFrozenManifest() = runBlocking {
        val store = BenchmarkStore(context)
        try {
            val profiles = listOf("bad", "good").map { PlatformV2(uid = it, name = it, model = "frozen-model", temperature = .8f) }
            val settings = mockk<SettingRepository>()
            coEvery { settings.fetchPlatformV2s() } returns profiles.map { it.copy(model = "later-edit") }
            val chats = mockk<ChatRepository>()
            coEvery { chats.validateBenchmarkProfile(any()) } returns Unit
            coEvery { chats.benchmarkContextTokens(any()) } returns null
            coEvery { chats.supportsBenchmarkTools(any()) } returns false
            coEvery { chats.benchmarkReadiness(any(), any(), any()) } answers {
                BenchmarkReadinessReceipt(1, null, listOf(BenchmarkReadinessCheck("configuration", BenchmarkReadinessStatus.PASSED), BenchmarkReadinessCheck("model_header", BenchmarkReadinessStatus.NOT_REQUIRED)), setOf("configuration"))
            }
            val requests = mutableMapOf<String, BenchmarkRequestContext>()
            val batch = BenchmarkBatch("audit-batch", BenchmarkMode.FULL, 1, profiles.mapIndexed { index, profile -> BenchmarkQueueItem(index, profile.benchmarkSnapshot(), "run-${profile.uid}", "not-trusted-caller-key") }, executionPolicy = BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1, fixtureRotationSeed = 2)
            store.checkpoint(batch, event = "MANIFEST_SAVED")
            val frozen = checkNotNull(store.batch(batch.id))
            val plan = checkNotNull(frozen.caseManifest)
            assertEquals(50, plan.size)
            coEvery { chats.openBenchmarkSession(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                assertTrue(InferenceAdmission.isExclusiveBusy)
                val profile = firstArg<PlatformV2>()
                assertEquals("frozen-model", profile.model)
                val request = checkNotNull(currentCoroutineContext()[BenchmarkRequestContext])
                assertEquals(BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1, request.executionPolicy)
                val id = arg<String>(3)
                requests[id] = request
                arg<(String) -> Unit>(6).invoke("physical-$id")
                val prompt = secondArg<List<ConversationTurn>>().last().userMessage.content
                val fixture = plan.firstOrNull { it.prompt == prompt }
                val answer = when {
                    request.readinessProbe -> prompt.substringAfter("prefix: ")
                    fixture?.category == "speed" -> (listOf("evaporation", "condensation", "cloud", "precipitation") + List(186) { "water" }).joinToString(" ")
                    fixture != null -> fixture.expected ?: "complete"
                    else -> error("Unexpected benchmark prompt")
                }
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = if (profile.uid == "bad") {
                        flowOf(ProviderEvent.TextDelta("{\"{\"status\":"), ProviderEvent.Failed("Malformed native JSON", errorCode = "STRUCTURED_OUTPUT_MALFORMED"))
                    } else {
                        flowOf(ProviderEvent.TextDelta(answer), ProviderEvent.Completed)
                    }
                }
            }
            val runtime = mockk<LocalRuntime>(relaxed = true)
            every { runtime.loadedEngineSpec() } returns null
            BenchmarkBatchRunner(store, chats, settings, runtime, context).run(batch.id) {}
            val saved = checkNotNull(store.batch(batch.id))
            assertEquals(BenchmarkBatchState.COMPLETED_WITH_FAILURES, saved.state)
            assertTrue(saved.items.all { it.terminal })
            val failed = store.history.value.single { it.id == "run-bad" }
            val completed = store.history.value.single { it.id == "run-good" }
            assertEquals(50, failed.samples.size)
            assertTrue(failed.samples.all { it.outcome == BenchmarkOutcome.SKIPPED && it.requestDispatched == false })
            assertEquals(1, failed.readiness!!.probes.size)
            assertEquals("STRUCTURED_OUTPUT_MALFORMED", failed.readiness.probes.single().sample?.failureCategory)
            assertEquals("{\"{\"status\":", failed.readiness.probes.single().sample?.canonicalAnswer)
            assertFalse(failed.readiness.ready)
            assertEquals(50, completed.samples.size)
            assertEquals(plan, completed.caseManifest)
            assertTrue(completed.readiness!!.ready)
            assertEquals(6, completed.readiness.probes.size)
            assertEquals(BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1, completed.executionPolicy)
            val backup = Json { ignoreUnknownKeys = true }.decodeFromString<BenchmarkBackupPayload>(store.exportJson())
            val attempts = backup.attempts.filter { it.runId == completed.id }
            assertEquals(50, attempts.size)
            attempts.forEach { attempt ->
                val sample = checkNotNull(attempt.sample)
                assertEquals(benchmarkCaseDeadlineMs(BenchmarkMode.FULL, plan.single { it.id == sample.testId }, 10), attempt.deadlineAt - attempt.startedAt)
                assertEquals(attempt.deadlineAt - attempt.startedAt, sample.deadlineReceipt!!.totalRequestMs)
                requests[attempt.logicalRequestId]?.let { assertEquals(sample.deadlineReceipt.totalRequestMs, it.requestDeadlineMs) }
            }
            assertEquals(100, backup.attempts.size)
            assertEquals(7, backup.runs.sumOf { it.readiness?.probes?.size ?: 0 })
            assertNotNull(completed.fixtureManifestHash)
        } finally {
            store.close()
        }
    }

    @Test fun nativePerformanceModeNeverDispatchesToARemoteProfile() = runBlocking {
        val store = BenchmarkStore(context)
        try {
            val profile = PlatformV2(uid = "remote", name = "Remote", model = "model")
            val settings = mockk<SettingRepository>()
            coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
            val chats = mockk<ChatRepository>(relaxed = true)
            val runtime = mockk<LocalRuntime>(relaxed = true)
            every { runtime.loadedEngineSpec() } returns null
            val batch = BenchmarkBatch("remote-performance", BenchmarkMode.PERFORMANCE, 1, listOf(BenchmarkQueueItem(0, profile, "remote-run", "key")))
            store.checkpoint(batch, event = "MANIFEST_SAVED")
            BenchmarkBatchRunner(store, chats, settings, runtime, context).run(batch.id) {}
            coVerify(exactly = 0) { chats.openBenchmarkSession(any(), any(), any(), any(), any(), any(), any()) }
            val run = store.history.value.single()
            assertEquals(21, run.samples.size)
            assertTrue(run.samples.none { it.requestDispatched == true })
            assertEquals(BenchmarkQueueState.BLOCKED, store.batch(batch.id)!!.items.single().state)
        } finally {
            store.close()
        }
    }
}
