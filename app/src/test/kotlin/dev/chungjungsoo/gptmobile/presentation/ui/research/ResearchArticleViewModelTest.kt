package dev.chungjungsoo.gptmobile.presentation.ui.research

import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.feature.ArticleCoverage
import dev.chungjungsoo.gptmobile.data.research.feature.ArticleExaminationCoordinator
import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationAdmissionResult
import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationRequest
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchArchiveRepository
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchDao
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchWork
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchWorkVersion
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ResearchArticleViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val dao = mockk<ResearchDao>(relaxed = true)
    private val archive = mockk<ResearchArchiveRepository>()
    private val coordinator = mockk<ArticleExaminationCoordinator>(relaxed = true)
    private val settings = mockk<SettingRepository>()
    private val work = ResearchWork("work", "Study", "https://example.org/study")
    private val version = ResearchWorkVersion("v1", work.workId, "hash", sourceUrl = work.canonicalUrl, retrievedAt = 1, coverage = ArticleCoverage.PARTIAL_TEXT, authority = "owner", publicSource = false)
    private lateinit var model: ResearchArticleViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { archive.dao } returns dao
        every { coordinator.jobs } returns flowOf(emptyList())
        every { settings.observePlatformV2s() } returns flowOf(emptyList())
        every { settings.observeFeatureSettings() } returns flowOf(AppFeatureSettings())
        coEvery { dao.work(work.workId) } returns work
        coEvery { dao.version("v1") } returns version
        coEvery { dao.version("v2") } returns version.copy(versionId = "v2")
        coEvery { archive.text(any(), any()) } returns "Saved text"
        model = ResearchArticleViewModel(archive, coordinator, settings, dispatcher)
    }

    @After fun cleanup() {
        model.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun refreshKeepsDetailsButChangingVersionClearsTheOldAssessmentImmediately() = runTest(dispatcher) {
        model.open(work.workId, "v1", "owner")
        runCurrent()
        model.open(work.workId, "v1", "owner")
        assertEquals("v1", model.state.value.version?.versionId)
        assertTrue(model.state.value.loading)
        runCurrent()
        model.open(work.workId, "v2", "owner")
        assertNull(model.state.value.version)
        runCurrent()
        assertEquals("v2", model.state.value.version?.versionId)
    }

    @Test fun changingAuthorityDoesNotRetainPrivateArticleDetails() = runTest(dispatcher) {
        model.open(work.workId, "v1", "owner")
        runCurrent()
        assertEquals(version, model.state.value.version)
        model.open(work.workId, "v1", "other")
        assertNull(model.state.value.work)
        assertNull(model.state.value.version)
        runCurrent()
        assertEquals("Article access is no longer authorized", model.state.value.error)
        assertNull(model.state.value.version)
    }

    @Test fun delayedAdmissionCannotAttachItsJobToAnotherVersionAndDoubleTapsAreIgnored() = runTest(dispatcher) {
        val result = CompletableDeferred<ExaminationAdmissionResult>()
        coEvery { coordinator.submitUserAction(any(), any()) } coAnswers { result.await() }
        model.open(work.workId, "v1", "owner")
        runCurrent()
        val request = ExaminationRequest(work.workId, versionId = "v1", authority = "owner")
        model.examine(request) { ChatMcpToolConfig() }
        model.examine(request) { ChatMcpToolConfig() }
        runCurrent()
        assertTrue(model.admissionInProgress.value)
        model.open(work.workId, "v2", "owner")
        runCurrent()
        result.complete(ExaminationAdmissionResult(jobId = "old-version-job"))
        runCurrent()
        assertEquals("v2", model.state.value.version?.versionId)
        assertNull(model.state.value.acceptedJobId)
        assertFalse(model.admissionInProgress.value)
        coVerify(exactly = 1) { coordinator.submitUserAction(any(), any()) }
    }

    private val examiner = dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(uid = "examiner", name = "Selected Examiner", model = "known")
    private fun installConsentState(): () -> AppFeatureSettings {
        var saved = AppFeatureSettings().withToolPluginEnabled(dev.chungjungsoo.gptmobile.data.model.ToolPluginId.DEEP_RESEARCH, true).withProfileToolPluginEnabled(examiner.uid, dev.chungjungsoo.gptmobile.data.model.ToolPluginId.DEEP_RESEARCH, true)
        saved = saved.copy(profileBehavior = saved.profileBehavior + (examiner.uid to saved.profileBehavior.getValue(examiner.uid).copy(academicSourcesAllowed = false)))
        coEvery { settings.fetchPlatformV2s() } returns listOf(examiner)
        coEvery { settings.getFeatureSettings() } coAnswers { saved }
        coEvery { settings.mutateFeatureSettings(any()) } coAnswers { firstArg<(AppFeatureSettings) -> AppFeatureSettings>().invoke(saved).also { saved = it } }
        return { saved }
    }

    @Test fun missingPapersConsentOffersPromptAndDeclineNeverStartsOrSavesPermission() = runTest(dispatcher) {
        val saved = installConsentState()
        model.open(work.workId, "v1", "owner")
        runCurrent()
        model.requestExamination(ExaminationRequest(work.workId, examiner.uid, versionId = "v1", authority = "owner")) { ChatMcpToolConfig() }
        runCurrent()
        assertEquals(examiner.uid, model.examinationConsent.value?.profileUid)
        model.declineExaminationConsent()
        runCurrent()
        assertNull(model.examinationConsent.value)
        assertEquals(false, saved().profileBehavior[examiner.uid]?.academicSourcesAllowed)
        assertEquals(listOf("OFFERED", "DISMISSED"), saved().researchFeature.consentReceipts.map { it.decision })
        assertEquals(setOf(examiner.uid), saved().researchFeature.consentReceipts.map { it.profileUid }.toSet())
        coVerify(exactly = 2) { settings.mutateFeatureSettings(any()) }
        coVerify(exactly = 0) { coordinator.submitUserAction(any(), any()) }
    }

    @Test fun acceptanceSavesOnlySelectedProfileAndResumesTheExactActionOnce() = runTest(dispatcher) {
        val saved = installConsentState()
        coEvery { coordinator.submitUserAction(any(), any()) } returns ExaminationAdmissionResult(jobId = "accepted")
        model.open(work.workId, "v1", "owner")
        runCurrent()
        val request = ExaminationRequest(work.workId, examiner.uid, 17, "v1", "owner", reexamine = true, resumeJobId = "prior")
        model.requestExamination(request) { ChatMcpToolConfig() }
        runCurrent()
        val id = requireNotNull(model.examinationConsent.value).id
        model.acceptExaminationConsent(id)
        model.acceptExaminationConsent(id)
        runCurrent()
        assertEquals(true, saved().profileBehavior[examiner.uid]?.academicSourcesAllowed)
        assertEquals(true, saved().profileBehavior[examiner.uid]?.researchEnabledOverride)
        assertFalse(saved().delegation.deepResearch.academicSources)
        assertEquals("accepted", model.state.value.acceptedJobId)
        coVerify(exactly = 1) { coordinator.submitUserAction(request, any()) }
    }

    @Test fun changingArticleOrProfileRejectsAStaleConsentAction() = runTest(dispatcher) {
        val saved = installConsentState()
        model.open(work.workId, "v1", "owner")
        runCurrent()
        model.requestExamination(ExaminationRequest(work.workId, examiner.uid, versionId = "v1", authority = "owner")) { ChatMcpToolConfig() }
        runCurrent()
        val id = requireNotNull(model.examinationConsent.value).id
        coEvery { settings.fetchPlatformV2s() } returns listOf(examiner.copy(model = "changed"))
        model.acceptExaminationConsent(id)
        runCurrent()
        assertTrue(model.state.value.error.orEmpty().contains("configuration changed"))
        assertEquals(false, saved().profileBehavior[examiner.uid]?.academicSourcesAllowed)
        assertTrue(saved().researchFeature.consentReceipts.none { it.decision == "ACCEPTED" })
        coVerify(exactly = 1) { settings.mutateFeatureSettings(any()) }
        coVerify(exactly = 0) { coordinator.submitUserAction(any(), any()) }

        coEvery { settings.fetchPlatformV2s() } returns listOf(examiner)
        model.requestExamination(ExaminationRequest(work.workId, examiner.uid, versionId = "v1", authority = "owner")) { ChatMcpToolConfig() }
        runCurrent()
        val stale = requireNotNull(model.examinationConsent.value).id
        model.open(work.workId, "v2", "owner")
        model.acceptExaminationConsent(stale)
        runCurrent()
        assertNull(model.examinationConsent.value)
        assertEquals(false, saved().profileBehavior[examiner.uid]?.academicSourcesAllowed)
        assertTrue(saved().researchFeature.consentReceipts.none { it.decision == "ACCEPTED" })
        coVerify(exactly = 0) { coordinator.submitUserAction(any(), any()) }
    }

    @Test fun configuredExaminerIsResolvedBeforeConsentAndFrozenForTheAcceptedAction() = runTest(dispatcher) {
        val saved = installConsentState()
        coEvery { settings.getFeatureSettings() } coAnswers { saved().copy(researchFeature = saved().researchFeature.copy(crawlerProfileUid = examiner.uid)) }
        coEvery { coordinator.submitUserAction(any(), any()) } returns ExaminationAdmissionResult(jobId = "configured")
        model.open(work.workId, "v1", "owner")
        runCurrent()
        val request = ExaminationRequest(work.workId, versionId = "v1", authority = "owner")
        model.requestExamination(request) { ChatMcpToolConfig() }
        runCurrent()
        assertEquals(examiner.uid, model.examinationConsent.value?.profileUid)
        model.acceptExaminationConsent(requireNotNull(model.examinationConsent.value).id)
        runCurrent()
        coVerify(exactly = 1) { coordinator.submitUserAction(request.copy(profileUid = examiner.uid), any()) }
        assertEquals(true, saved().profileBehavior[examiner.uid]?.academicSourcesAllowed)
    }

    @Test fun revokingChatToolsWhilePromptIsOpenPreventsPermissionSaveAndAdmission() = runTest(dispatcher) {
        val saved = installConsentState()
        var tools = ChatMcpToolConfig()
        model.open(work.workId, "v1", "owner")
        runCurrent()
        model.requestExamination(ExaminationRequest(work.workId, examiner.uid, versionId = "v1", authority = "owner")) { tools }
        runCurrent()
        val id = requireNotNull(model.examinationConsent.value).id
        tools = tools.copy(disabledToolIds = setOf("deep_research_read_paper"))
        model.acceptExaminationConsent(id)
        runCurrent()
        assertEquals(false, saved().profileBehavior[examiner.uid]?.academicSourcesAllowed)
        assertTrue(model.state.value.error.orEmpty().contains("excluded"))
        coVerify(exactly = 0) { coordinator.submitUserAction(any(), any()) }
    }

    @Test fun dismissalWhileConsentLookupIsSuspendedCannotShowALatePrompt() = runTest(dispatcher) {
        installConsentState()
        val profiles = CompletableDeferred<List<dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2>>()
        coEvery { settings.fetchPlatformV2s() } coAnswers { profiles.await() }
        model.open(work.workId, "v1", "owner")
        runCurrent()
        model.requestExamination(ExaminationRequest(work.workId, examiner.uid, versionId = "v1", authority = "owner")) { ChatMcpToolConfig() }
        runCurrent()
        model.declineExaminationConsent()
        profiles.complete(listOf(examiner))
        runCurrent()
        assertNull(model.examinationConsent.value)
        coVerify(exactly = 0) { settings.mutateFeatureSettings(any()) }
        coVerify(exactly = 0) { coordinator.submitUserAction(any(), any()) }
    }
}
