package dev.chungjungsoo.gptmobile.data.benchmark

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.audit.AppAuditSelectedModelServices
import dev.chungjungsoo.gptmobile.data.audit.appAuditModelFixture
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
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
class BenchmarkAuditModelQueueTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun reset() {
        context.deleteDatabase("benchmark_v2.db")
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun changedCredentialRevisionBlocksTheQueueBeforeAnyGeneration() = runBlocking { exercise(changedBeforeRun = true) }

    @Test fun configurationChangeDuringResponseRetainsOutputAndNeverDispatchesTheNextCase() = runBlocking { exercise(changedBeforeRun = false) }

    @Test fun outputLimitCannotReplaceTheAuthoritativeConfigurationFailure() = runBlocking { exercise(changedBeforeRun = false, finishReason = "length") }

    private suspend fun exercise(changedBeforeRun: Boolean, finishReason: String = "stop") {
        val fixture = appAuditModelFixture()
        val store = BenchmarkStore(context)
        try {
            var current = if (changedBeforeRun) fixture.target.copy(configurationRevision = "b".repeat(64)) else fixture.target
            val models = mockk<AppAuditSelectedModelServices>()
            coEvery { models.target(fixture.profile.uid) } answers { current }
            val settings = mockk<SettingRepository>()
            coEvery { settings.fetchPlatformV2s() } returns listOf(fixture.profile)
            val chats = mockk<ChatRepository>()
            coEvery { chats.validateBenchmarkProfile(any()) } returns Unit
            coEvery { chats.benchmarkContextTokens(any()) } returns null
            coEvery { chats.supportsBenchmarkTools(any()) } returns false
            coEvery { chats.openBenchmarkSession(any(), any(), any(), any(), any(), any(), any()) } returns object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    emit(ProviderEvent.TextDelta("READY"))
                    current = fixture.target.copy(configurationRevision = "b".repeat(64))
                    emit(ProviderEvent.Usage(outputTokens = 2, finishReason = finishReason))
                    emit(ProviderEvent.Completed)
                }
            }
            val runtime = mockk<LocalRuntime>(relaxed = true)
            every { runtime.loadedEngineSpec() } returns null
            val item = BenchmarkQueueItem(0, fixture.profile, "bound-run", "config", auditModelBinding = fixture.binding)
            val manifest = listOf("first", "second").map { BenchmarkCase(it, it, "task", "Reply READY", "READY") }
            val batch = BenchmarkBatch("bound-batch", BenchmarkMode.QUICK, 10, listOf(item), caseManifest = manifest)
            store.checkpoint(BenchmarkBatch(fixture.run.id, BenchmarkMode.APP_AUDIT, 1, emptyList(), state = BenchmarkBatchState.COMPLETED, audit = fixture.run))
            store.checkpoint(batch)
            BenchmarkBatchRunner(store, chats, settings, runtime, context, auditModels = models).run(batch.id) {}
            val saved = checkNotNull(store.batch(batch.id))
            val run = store.history.value.single { it.id == item.runId }
            val first = run.samples.first()
            assertEquals(BenchmarkBatchState.COMPLETED_WITH_FAILURES, saved.state)
            assertEquals("AUDIT_MODEL_CONFIGURATION_CHANGED", first.failureCategory)
            assertEquals(!changedBeforeRun, first.requestDispatched)
            coVerify(exactly = if (changedBeforeRun) 0 else 1) { chats.openBenchmarkSession(any(), any(), any(), any(), any(), any(), any()) }
            if (!changedBeforeRun) {
                assertEquals("READY", first.canonicalAnswer)
                assertTrue(first.terminalReceived == true)
                assertFalse(first.auditModelConfiguration!!.afterGenerationVerified)
                assertEquals(BenchmarkOutcome.SKIPPED, run.samples.last().outcome)
            }
            assertEquals(fixture.run, store.batch(fixture.run.id)?.audit)
        } finally {
            store.close()
        }
    }
}
