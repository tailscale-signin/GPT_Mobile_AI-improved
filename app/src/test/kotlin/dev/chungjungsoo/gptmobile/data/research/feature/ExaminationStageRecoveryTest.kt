package dev.chungjungsoo.gptmobile.data.research.feature

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicEvidence
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicNativeClient
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicPaper
import dev.chungjungsoo.gptmobile.data.research.researchHash
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Provider
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExaminationStageRecoveryTest {
    @Test fun exhaustedCompletionBudgetStopsWithoutSchedulingAnIdenticalNetworkRetry() = runBlocking {
        fixture { item ->
            item.respond = { _, _ -> flowOf(ProviderEvent.Failed("Backend exhausted the output allowance without a usable completion", errorCode = "completion_repair_budget_exhausted")) }
            val stopped = item.start()
            val checkpoint = Json.decodeFromString<ExaminationCheckpoint>(stopped.checkpointJson)
            assertEquals(ExaminationStage.WAITING_CAPACITY, stopped.stage)
            assertFalse(requireNotNull(checkpoint.failure).retryable)
            assertEquals(1, item.requests.get())
            assertEquals(0, checkpoint.nextChunk)
            assertNotNull(checkpoint.sourceValidation)
            assertEquals(item.jobId, item.coordinator.userExamine(requireNotNull(item.request).copy(resumeJobId = item.jobId)))
            assertEquals(1, item.requests.get())
            coVerify(exactly = 1) { item.academic.read(any(), any(), any(), any(), any(), any()) }
        }
    }

    @Test fun completedArtifactIsSavedBeforePermissionBlockAndResumesWithoutModelReplay() = runBlocking {
        fixture { item ->
            item.respond = { _, _ ->
                flow {
                    emit(ProviderEvent.TextDelta(REPLY))
                    emit(ProviderEvent.Completed)
                    item.features.set(item.acceptedFeatures.copy(researchFeature = item.acceptedFeatures.researchFeature.copy(enabled = false)))
                }
            }
            val paused = item.start()
            val saved = Json.decodeFromString<ExaminationCheckpoint>(paused.checkpointJson)
            assertEquals(ExaminationStage.PAUSED, paused.stage)
            assertTrue(requireNotNull(saved.modelProgress).complete)
            assertEquals(0, saved.nextChunk)
            assertEquals(1, item.requests.get())
            item.features.set(item.acceptedFeatures)
            item.respond = { _, _ -> error("A completed artifact must not be dispatched again") }
            val done = item.resume(paused)
            assertEquals(ExaminationStage.COMPLETE_PARTIAL, done.stage)
            assertEquals(1, Json.decodeFromString<ExaminationCheckpoint>(done.checkpointJson).nextChunk)
            assertEquals(1, item.requests.get())
            assertTrue(item.database.research().passages(requireNotNull(saved.versionId)).isNotEmpty())
            coVerify(exactly = 1) { item.academic.read(any(), any(), any(), any(), any(), any()) }
        }
    }

    @Test fun policyCancellationRetainsPartialOutputAndOnlyContinuesItsExactArtifact() = runBlocking {
        fixture { item ->
            val prefix = REPLY.take(40)
            item.respond = { _, _ ->
                flow {
                    emit(ProviderEvent.TextDelta(prefix))
                    item.features.set(item.acceptedFeatures.copy(researchFeature = item.acceptedFeatures.researchFeature.copy(enabled = false)))
                    awaitCancellation()
                }
            }
            val paused = item.start()
            val saved = Json.decodeFromString<ExaminationCheckpoint>(paused.checkpointJson)
            val progress = requireNotNull(saved.modelProgress)
            assertEquals(ExaminationStage.PAUSED, paused.stage)
            assertFalse(progress.complete)
            assertEquals(prefix, item.sessions.loadPartial("examination:${item.jobId}", progress.checkpointId, 0, "public", ExaminationArtifactBudget.storageStage("research-appraisal", progress.requestKey), item.profile.uid, "examination:${item.jobId}"))
            assertEquals(listOf("physical-1"), saved.invocationIds)
            item.features.set(item.acceptedFeatures)
            item.respond = { _, prompt ->
                assertTrue(prompt.contains("Continue this exact JSON artifact"))
                flowOf(ProviderEvent.TextDelta(REPLY.drop(prefix.length)), ProviderEvent.Completed)
            }
            val done = item.resume(paused)
            assertEquals(ExaminationStage.COMPLETE_PARTIAL, done.stage)
            assertEquals(2, item.requests.get())
            coVerify(exactly = 1) { item.academic.read(any(), any(), any(), any(), any(), any()) }
        }
    }

    @Test fun repeatedExplicitResumeCannotResetTheSavedModelAttemptAllowance() = runBlocking {
        fixture { item ->
            item.respond = { _, _ -> flow { throw java.io.IOException("Synthetic disconnected provider") } }
            var stopped = item.start()
            repeat(ExaminationArtifactBudget.MAX_ATTEMPTS - 1) {
                assertEquals(ExaminationStage.WAITING_NETWORK, stopped.stage)
                stopped = item.resume(stopped)
            }
            stopped = item.resume(stopped)
            assertEquals(ExaminationStage.WAITING_CAPACITY, stopped.stage)
            val saved = Json.decodeFromString<ExaminationCheckpoint>(stopped.checkpointJson)
            assertEquals("ADMITTED_CAPACITY_EXHAUSTED", saved.failure?.code)
            assertFalse(requireNotNull(saved.failure).retryable)
            assertEquals(ExaminationArtifactBudget.MAX_ATTEMPTS, item.requests.get())
            assertEquals(listOf(ExaminationArtifactBudget.MAX_ATTEMPTS), saved.modelAttempts.values.toList())
        }
    }

    @Test fun splittingAnUnfinishedCompactSliceNeverBorrowsItsPreviousPartialJson() = runBlocking {
        val body = BODY + " The report describes study limitations and methods.".repeat(16)
        fixture(sourceBody = body, outputTokens = 1024) { item ->
            item.respond = { number, prompt ->
                if (number == 1) {
                    flowOf(ProviderEvent.TextDelta("{\"schemaVersion\":1,"), ProviderEvent.Failed("Backend exhausted the output allowance", errorCode = "completion_repair_budget_exhausted"))
                } else {
                    assertFalse(prompt.contains("Continue this exact JSON artifact"))
                    val chunk = requireNotNull(Regex("<article_chunk offset=\"([0-9]+)\">\\n([\\s\\S]*?)\\n</article_chunk>").find(prompt))
                    val offset = chunk.groupValues[1].toInt()
                    val quote = JsonPrimitive(chunk.groupValues[2].take(100)).toString()
                    val passageId = if (offset == 0) "P1" else "P25"
                    val reply = """{"schemaVersion":1,"design":"UNKNOWN","passages":[{"id":"$passageId","quote":$quote,"locator":"Available source text","start":$offset}],"fields":[],"judgments":[],"limitations":["Uncertain effect"]}"""
                    flowOf(ProviderEvent.TextDelta(reply), ProviderEvent.Completed)
                }
            }
            val done = item.start()
            assertEquals(ExaminationStage.COMPLETE_PARTIAL, done.stage)
            val saved = Json.decodeFromString<ExaminationCheckpoint>(done.checkpointJson)
            assertEquals(2, saved.nextChunk)
            assertEquals(3, item.requests.get())
            assertEquals(3, saved.modelAttempts.size)
        }
    }

    private suspend fun fixture(sourceBody: String = BODY, outputTokens: Int? = null, block: suspend (Fixture) -> Unit) {
        val item = Fixture(sourceBody, outputTokens)
        try {
            block(item)
        } finally {
            item.jobId?.let { item.coordinator.cancel(it) }
            delay(100)
            item.database.close()
            item.chats.close()
            item.directory.deleteRecursively()
        }
    }

    private class Fixture(private val sourceBody: String, outputTokens: Int?) {
        private val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = kotlin.io.path.createTempDirectory("examination-stage-recovery").toFile()
        private val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
        }
        val database = Room.inMemoryDatabaseBuilder(app, ResearchDatabase::class.java).allowMainThreadQueries().build()
        val chats = Room.inMemoryDatabaseBuilder(app, ChatDatabaseV2::class.java).allowMainThreadQueries().build()
        val sessions = ResearchSessionStore(context, database)
        private val archive = ResearchArchiveRepository(database, sessions)
        val profile = PlatformV2(uid = "examiner", name = "Synthetic examiner", model = "scripted", enabled = true, maxTokens = outputTokens)
        val acceptedFeatures = AppFeatureSettings(
            delegation = ModelDelegationSettings(reviewMode = dev.chungjungsoo.gptmobile.data.delegation.DelegationReviewMode.OFF, deepResearch = DeepResearchSettings(academicSources = true)),
            researchFeature = ResearchFeatureSettings(backgroundExamination = true)
        ).withToolPluginEnabled(ToolPluginId.DEEP_RESEARCH, true).withProfileToolPluginEnabled(profile.uid, ToolPluginId.DEEP_RESEARCH, true)
        val features = AtomicReference(acceptedFeatures)
        private val settings = mockk<SettingRepository>()
        val academic = mockk<AcademicNativeClient>()
        private val models = mockk<ChatRepository>()
        val requests = AtomicInteger()
        var respond: (Int, String) -> Flow<ProviderEvent> = { _, _ -> error("No synthetic response configured") }
        var jobId: String? = null
        var request: ExaminationRequest? = null
        val coordinator: ArticleExaminationCoordinator

        init {
            coEvery { settings.getFeatureSettings() } answers { features.get() }
            coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
            val paper = AcademicPaper("doi:10.1234/synthetic", "Synthetic study", "https://example.org/study", doi = "10.1234/synthetic")
            coEvery { academic.getPaper(any(), any(), any(), any()) } returns paper
            coEvery { academic.read(any(), any(), any(), any(), any(), any()) } returns AcademicEvidence(paper.paperId, "revision", paper.canonicalUrl, 2000, researchHash(sourceBody), sourceBody, "read")
            coEvery { models.openResearchSession(any(), any(), any(), any(), any(), any(), any()) } answers {
                val number = requests.incrementAndGet()
                arg<(String) -> Unit>(6).invoke("physical-$number")
                val events = respond(number, arg(1))
                object : AgentProviderSession {
                    override val handlesToolsInternally = false
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = events
                }
            }
            coordinator = ArticleExaminationCoordinator(archive, settings, chats, academic, models, Provider { error("No gateway authorized") }, sessions)
        }

        suspend fun start(): ExaminationJob {
            val source = ResearchSource("fixture", "https://example.org/study", "Synthetic study", doi = "10.1234/synthetic", status = "Metadata only", retrievalQuality = "METADATA", retrievedAt = 1000)
            val version = archive.capture(source, "Metadata record", "public", true, -1, "fixture", true, 0, ownerKind = "import", ownerId = "fixture-import")
            request = ExaminationRequest(version.workId, profile.uid, versionId = version.versionId)
            jobId = coordinator.userExamine(requireNotNull(request))
            return terminal(0)
        }

        suspend fun resume(previous: ExaminationJob): ExaminationJob {
            withTimeout(15_000) {
                do {
                    delay(10)
                    assertEquals(jobId, coordinator.userExamine(requireNotNull(request).copy(resumeJobId = jobId)))
                } while (requireNotNull(database.research().job(requireNotNull(jobId))).lease <= previous.lease)
            }
            return terminal(previous.lease)
        }

        private suspend fun terminal(previousLease: Long): ExaminationJob = withTimeout(15_000) {
            while (true) {
                val job = requireNotNull(database.research().job(requireNotNull(jobId)))
                if (job.lease > previousLease && job.stage !in ExaminationLifecycle.active) return@withTimeout job
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE")
            error("Unreachable")
        }
    }

    private companion object {
        const val BODY = "Methods: randomized 100 participants. Results: uncertain effect."
        const val REPLY = """{"schemaVersion":1,"design":"RANDOMIZED_TRIAL","passages":[{"id":"P1","quote":"Methods: randomized 100 participants.","locator":"Methods","start":0}],"fields":[],"judgments":[],"limitations":["Uncertain effect"]}"""
    }
}
