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
import javax.inject.Provider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArticleExaminationRecoveryTest {
    @Test fun failedAppraiserResumesSavedSourceAndPreservesValidOutputAfterStreamFailure() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = kotlin.io.path.createTempDirectory("article-recovery").toFile()
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
        }
        val database = Room.inMemoryDatabaseBuilder(app, ResearchDatabase::class.java).allowMainThreadQueries().build()
        val chats = Room.inMemoryDatabaseBuilder(app, ChatDatabaseV2::class.java).allowMainThreadQueries().build()
        var coordinator: ArticleExaminationCoordinator? = null
        var jobId: String? = null
        try {
            val sessions = ResearchSessionStore(context, database)
            val archive = ResearchArchiveRepository(database, sessions)
            val profile = PlatformV2(uid = "fixture", name = "Synthetic appraiser", model = "scripted")
            val features = AppFeatureSettings(delegation = ModelDelegationSettings(reviewMode = dev.chungjungsoo.gptmobile.data.delegation.DelegationReviewMode.OFF, deepResearch = DeepResearchSettings(academicSources = true)), researchFeature = ResearchFeatureSettings(backgroundExamination = true))
                .withToolPluginEnabled(ToolPluginId.DEEP_RESEARCH, true).withProfileToolPluginEnabled(profile.uid, ToolPluginId.DEEP_RESEARCH, true)
            val settings = mockk<SettingRepository>()
            coEvery { settings.getFeatureSettings() } returns features
            coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
            val source = ResearchSource("S1", "https://example.org/trial", "Synthetic trial", doi = "10.1234/trial", status = "Read", retrievedAt = 1000)
            val original = archive.capture(source, "Metadata record", "public", true, 0, "original", true, 0)
            val body = "Methods: randomized 100 participants. Results: uncertain effect."
            val paper = AcademicPaper("doi:10.1234/trial", source.title, source.canonicalUrl, doi = source.doi)
            val academic = mockk<AcademicNativeClient>()
            coEvery { academic.getPaper(any(), any(), any(), any()) } returns paper
            coEvery { academic.read(any(), any(), any(), any(), any(), any()) } returns AcademicEvidence(paper.paperId, "revision-one", source.canonicalUrl, 2000, researchHash(body), body, "read")
            val models = mockk<ChatRepository>()
            var attempts = 0
            coEvery { models.openResearchSession(any(), any(), any(), any(), any(), any(), any()) } answers {
                attempts++
                arg<(String) -> Unit>(6).invoke("physical-$attempts")
                if (attempts == 1) error("Synthetic session construction failure")
                val reply = if (attempts == 2) "{" else """{"schemaVersion":1,"design":"RANDOMIZED_TRIAL","passages":[{"id":"P1","quote":"Methods: randomized 100 participants.","locator":"Methods","start":0}],"fields":[],"judgments":[],"limitations":["Uncertain effect"]}"""
                object : AgentProviderSession {
                    override val handlesToolsInternally = false
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.TextDelta(reply), ProviderEvent.Failed("Synthetic stream reset"))
                }
            }
            coordinator = ArticleExaminationCoordinator(archive, settings, chats, academic, models, Provider { error("No gateway authorized") }, sessions)
            val request = ExaminationRequest(original.workId, profile.uid, versionId = original.versionId)
            jobId = coordinator.userExamine(request)
            suspend fun awaitStage(expected: ExaminationStage): ExaminationJob = withTimeout(15000) {
                while (true) {
                    val row = requireNotNull(database.research().job(requireNotNull(jobId)))
                    if (row.stage == expected) return@withTimeout row
                    delay(10)
                }
                @Suppress("UNREACHABLE_CODE")
                error("Unreachable")
            }
            val failed = awaitStage(ExaminationStage.FAILED)
            val saved = Json.decodeFromString<ExaminationCheckpoint>(failed.checkpointJson)
            assertNotNull(saved.versionId)
            assertEquals(failed.terminalReason, listOf("physical-1"), saved.invocationIds)
            suspend fun resume(previous: ExaminationJob) = withTimeout(15000) {
                // Terminal state is persisted before the old coroutine releases ownership.
                do {
                    delay(10)
                    assertEquals(jobId, requireNotNull(coordinator).userExamine(request))
                } while (requireNotNull(database.research().job(requireNotNull(jobId))).lease <= previous.lease)
            }
            resume(failed)
            val malformed = awaitStage(ExaminationStage.FAILED)
            assertEquals(malformed.terminalReason, listOf("physical-1", "physical-2"), Json.decodeFromString<ExaminationCheckpoint>(malformed.checkpointJson).invocationIds)
            resume(malformed)
            val done = awaitStage(ExaminationStage.COMPLETE_PARTIAL)
            val checkpoint = Json.decodeFromString<ExaminationCheckpoint>(done.checkpointJson)
            assertEquals(listOf("physical-1", "physical-2", "physical-3"), checkpoint.invocationIds)
            assertEquals(1, checkpoint.nextChunk)
            assertTrue(checkpoint.pendingAppraisalIds.isEmpty())
            assertEquals(body, archive.text(requireNotNull(database.research().version(requireNotNull(checkpoint.versionId))), "public"))
            coVerify(exactly = 1) { academic.read(any(), any(), any(), any(), any(), any()) }
        } finally {
            jobId?.let { coordinator?.cancel(it) }
            delay(50)
            database.close()
            chats.close()
            directory.deleteRecursively()
        }
    }
}
