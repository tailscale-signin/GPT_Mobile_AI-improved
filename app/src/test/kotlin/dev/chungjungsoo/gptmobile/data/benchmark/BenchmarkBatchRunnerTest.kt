package dev.chungjungsoo.gptmobile.data.benchmark

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    private fun runner(store: BenchmarkStore, validate: suspend (PlatformV2) -> Unit): BenchmarkBatchRunner {
        val settings = mockk<SettingRepository>()
        // Saved edits never replace the accepted snapshot's model or order.
        coEvery { settings.fetchPlatformV2s() } returns profiles.reversed().map { it.copy(model = "edited-model") }
        val chat = mockk<ChatRepository>()
        coEvery { chat.validateBenchmarkProfile(any()) } coAnswers { validate(firstArg()) }
        coEvery { chat.benchmarkContextTokens(any()) } returns null
        coEvery { chat.supportsBenchmarkTools(any()) } returns false
        coEvery { chat.openBenchmarkSession(any(), any(), any(), any(), any(), any(), any()) } returns object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.TextDelta("A deliberately wrong but complete answer."), ProviderEvent.Completed)
        }
        coEvery { chat.runDelegationBenchmark(any(), any(), any(), any(), any()) } answers {
            val test = secondArg<BenchmarkCase>()
            BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.FAILED, preview = "Wrong", outputCharacters = 5, requestDispatched = true, workload = test.workload)
        }
        val runtime = mockk<LocalRuntime>(relaxed = true)
        every { runtime.loadedEngineSpec() } returns null
        return BenchmarkBatchRunner(store, chat, settings, runtime, context)
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
                assertEquals(15, backup.attempts.count { it.batchId == batch.id })
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
}
