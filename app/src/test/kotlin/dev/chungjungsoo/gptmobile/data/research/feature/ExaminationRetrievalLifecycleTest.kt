package dev.chungjungsoo.gptmobile.data.research.feature

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.diagnostics.JavaHeapWorkDeferred
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicHttpFailure
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicNativeClient
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicPaper
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicRedirectFailure
import dev.chungjungsoo.gptmobile.data.research.academic.academicFailureEvidence
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExaminationRetrievalLifecycleTest {
    @Test fun typedAccessFailuresHaveDurableWaitSemanticsWithoutReliabilityGrades() {
        val paper = AcademicPaper("doi:10.1234/example", "Study", "https://example.org/article")
        val cases = listOf(
            AcademicHttpFailure(403) to ExaminationStage.BLOCKED_SOURCE,
            AcademicHttpFailure(429, retryAtMs = 90000) to ExaminationStage.WAITING_RATE_LIMIT,
            AcademicHttpFailure(503) to ExaminationStage.WAITING_NETWORK,
            java.net.UnknownHostException("offline") to ExaminationStage.WAITING_NETWORK,
            AcademicRedirectFailure(listOf("https://example.org/a", "https://example.org/b", "https://example.org/a")) to ExaminationStage.BLOCKED_SOURCE,
            JavaHeapWorkDeferred() to ExaminationStage.WAITING_CAPACITY
        )
        for ((failure, stage) in cases) {
            val evidence = requireNotNull(academicFailureEvidence(paper, failure))
            val result = examinationFailure(ExaminationRetrievalException(evidence), 0, 1000)
            assertEquals(stage, result.stage)
            assertEquals("", evidence.text)
            assertFalse(result.code.contains("RELIABILITY"))
            assertEquals(result, Json.decodeFromString<ExaminationFailure>(Json.encodeToString(ExaminationFailure.serializer(), result)))
            if (stage == ExaminationStage.BLOCKED_SOURCE) assertFalse(result.retryable) else assertTrue(result.retryable)
            if (failure is AcademicHttpFailure && failure.status == 429) assertEquals(90000L, result.retryAtMs)
        }
        assertFalse(examinationFailure(JavaHeapWorkDeferred(), 5, 1000).retryable)
        assertFalse(examinationFailure(IllegalStateException("CombinedBudgetExceeded"), 0, 1000).retryable)
        assertEquals(ExaminationStage.WAITING_CAPACITY, examinationFailure(IllegalStateException("CombinedBudgetExceeded"), 0, 1000).stage)
    }

    @Test fun realCoordinatorPersistsTypedRetrievalFailuresAndPreservesManualCancellation() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = kotlin.io.path.createTempDirectory("examination-retrieval").toFile()
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
        }
        val database = Room.inMemoryDatabaseBuilder(app, ResearchDatabase::class.java).allowMainThreadQueries().build()
        val chats = Room.inMemoryDatabaseBuilder(app, ChatDatabaseV2::class.java).allowMainThreadQueries().build()
        var coordinator: ArticleExaminationCoordinator? = null
        val ids = mutableListOf<String>()
        try {
            val sessions = ResearchSessionStore(context, database)
            val archive = ResearchArchiveRepository(database, sessions)
            val profile = PlatformV2(uid = "examiner", name = "Examiner", model = "fixture", enabled = true)
            val features = AppFeatureSettings(delegation = ModelDelegationSettings(reviewMode = dev.chungjungsoo.gptmobile.data.delegation.DelegationReviewMode.OFF, deepResearch = DeepResearchSettings(academicSources = true)), researchFeature = ResearchFeatureSettings(crawlerProfileUid = profile.uid, backgroundExamination = true))
                .withToolPluginEnabled(ToolPluginId.DEEP_RESEARCH, true).withProfileToolPluginEnabled(profile.uid, ToolPluginId.DEEP_RESEARCH, true)
            val settings = mockk<SettingRepository>()
            coEvery { settings.getFeatureSettings() } returns features
            coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
            val academic = mockk<AcademicNativeClient>()
            coEvery { academic.getPaper(any(), any(), any(), any()) } answers { AcademicPaper(firstArg<String>(), "Study", "https://example.org/article") }
            val resolver = mockk<AgentToolResolver>()
            coEvery { resolver.resolve(any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
            val models = mockk<ChatRepository>()
            coordinator = ArticleExaminationCoordinator(archive, settings, chats, academic, models, Provider { resolver }, sessions)
            val cases = listOf(AcademicHttpFailure(429, System.currentTimeMillis() + 60000) to ExaminationStage.WAITING_RATE_LIMIT, java.net.UnknownHostException("offline") to ExaminationStage.WAITING_NETWORK, AcademicHttpFailure(403) to ExaminationStage.BLOCKED_SOURCE, JavaHeapWorkDeferred() to ExaminationStage.WAITING_CAPACITY)
            for ((index, case) in cases.withIndex()) {
                coEvery { academic.read(any(), any(), any(), any(), any(), any()) } throws case.first
                val version = archive.capture(ResearchSource("S$index", "https://example.org/article/$index", "Study $index", status = "Metadata only", retrievalQuality = "METADATA", doi = "10.1234/study-$index"), "Metadata record", "public", true, -1, "import", true, 0, ownerKind = "import", ownerId = "fixture-$index")
                val id = coordinator.userExamine(ExaminationRequest(version.workId, profile.uid, versionId = version.versionId))
                ids += id
                val stage = withTimeout(15000) { coordinator.awaitExecution(id) }
                assertEquals(case.second, stage)
                val before = requireNotNull(database.research().job(id))
                val saved = Json.decodeFromString<ExaminationCheckpoint>(before.checkpointJson)
                assertNotNull(saved.failure)
                assertEquals(1, saved.retryAttempt)
                assertTrue(database.research().studyReviews(version.versionId).isEmpty())
                coordinator.cancel(id)
                assertEquals(ExaminationStage.CANCELED, database.research().job(id)!!.stage)
                assertNull(coordinator.resumeWaiting(id))
                assertEquals(before.checkpointJson, database.research().job(id)!!.checkpointJson)
            }
            coVerify(exactly = 0) { models.openResearchSession(any(), any(), any(), any(), any(), any(), any()) }
        } finally {
            ids.forEach { coordinator?.cancel(it) }
            delay(50)
            database.close()
            chats.close()
            directory.deleteRecursively()
        }
    }

    @Test fun validLookingPartialModelArtifactCannotBecomeAcceptedAfterStreamFailure() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = kotlin.io.path.createTempDirectory("examination-partial").toFile()
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
        }
        val database = Room.inMemoryDatabaseBuilder(app, ResearchDatabase::class.java).allowMainThreadQueries().build()
        val chats = Room.inMemoryDatabaseBuilder(app, ChatDatabaseV2::class.java).allowMainThreadQueries().build()
        var coordinator: ArticleExaminationCoordinator? = null
        var id: String? = null
        try {
            val sessions = ResearchSessionStore(context, database)
            val archive = ResearchArchiveRepository(database, sessions)
            val profile = PlatformV2(uid = "examiner", name = "Examiner", model = "fixture", enabled = true)
            val features = AppFeatureSettings(delegation = ModelDelegationSettings(reviewMode = dev.chungjungsoo.gptmobile.data.delegation.DelegationReviewMode.OFF, deepResearch = DeepResearchSettings(academicSources = true)), researchFeature = ResearchFeatureSettings(crawlerProfileUid = profile.uid, backgroundExamination = true))
                .withToolPluginEnabled(ToolPluginId.DEEP_RESEARCH, true).withProfileToolPluginEnabled(profile.uid, ToolPluginId.DEEP_RESEARCH, true)
            val settings = mockk<SettingRepository>()
            coEvery { settings.getFeatureSettings() } returns features
            coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
            val body = "Methods: randomized 24 participants. Results: uncertain effect."
            val version = archive.capture(ResearchSource("S1", "https://example.org/article", "Trial", "Read", body, structuredFullText = true, sectionNames = setOf("Methods", "Results"), captureTruncated = false), body, "public", true, -1, "import", true, 0, ownerKind = "import", ownerId = "fixture")
            assertEquals(ArticleCoverage.FULL_TEXT_CAPTURED, version.coverage)
            val models = mockk<ChatRepository>()
            val reply = """{"schemaVersion":1,"design":"RANDOMIZED_TRIAL","passages":[{"id":"P1","quote":"Methods: randomized 24 participants.","locator":"Methods","start":0}],"fields":[],"judgments":[],"limitations":["Uncertain effect"]}"""
            coEvery { models.openResearchSession(any(), any(), any(), any(), any(), any(), any()) } answers {
                arg<(String) -> Unit>(6).invoke("physical-partial")
                object : AgentProviderSession {
                    override val handlesToolsInternally = false
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.TextDelta(reply), ProviderEvent.Failed("Connection reset"))
                }
            }
            coordinator = ArticleExaminationCoordinator(archive, settings, chats, mockk<AcademicNativeClient>(), models, Provider { error("Saved evidence must be reused") }, sessions)
            id = coordinator.userExamine(ExaminationRequest(version.workId, profile.uid, versionId = version.versionId))
            assertEquals(ExaminationStage.WAITING_NETWORK, withTimeout(15000) { coordinator.awaitExecution(id) })
            val saved = Json.decodeFromString<ExaminationCheckpoint>(database.research().job(id)!!.checkpointJson)
            assertEquals("TRANSPORT_FAILED", saved.failure!!.code)
            assertEquals(listOf("physical-partial"), saved.invocationIds)
            assertNotNull(saved.unfinishedModelCheckpoint)
            assertTrue(database.research().appraisals(version.versionId).isEmpty())
            assertTrue(database.research().studyReviews(version.versionId).isEmpty())
            assertEquals(researchHash(body), database.research().version(version.versionId)!!.contentHash)
        } finally {
            id?.let { coordinator?.cancel(it) }
            delay(50)
            database.close()
            chats.close()
            directory.deleteRecursively()
        }
    }
}
