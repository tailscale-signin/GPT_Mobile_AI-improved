package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModelStore
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayCatalogJob
import dev.chungjungsoo.gptmobile.data.github.GatewayCommandRecord
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationRecord
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayJobRecord
import dev.chungjungsoo.gptmobile.data.github.GatewaySavedJob
import dev.chungjungsoo.gptmobile.data.github.GatewayScopeRecord
import dev.chungjungsoo.gptmobile.data.github.GatewayStopOutcome
import dev.chungjungsoo.gptmobile.data.github.GatewayWorkDao
import dev.chungjungsoo.gptmobile.data.github.GatewayWorkStore
import dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayJobsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val owner = ViewModelStore()
    private val repository = mockk<GatewayConversationRepository>(relaxed = true)
    private val store = mockk<GatewayWorkStore>(relaxed = true)
    private val dao = mockk<GatewayWorkDao>(relaxed = true)
    private val settings = mockk<SettingRepository>(relaxed = true)
    private val scope = GatewayScopeRecord("scope-a", "gateway-a", "https://gateway.invalid/v1", "installation", "account", "principal", "ONLINE", 1000)
    private val conversation = GatewayConversationRecord("conversation-key", scope.id, "conversation-id", 7, "original-profile", "Original profile", "exact-model", "owner/repo", "original-branch", "Saved work", 9, null, null, true, null, 1000)
    private val job = GatewayCatalogJob("job-id", "engineering", "conversation-id", 9, "CI", "waiting", title = "Saved work", updatedAtMs = 1000, serverTimeMs = 1000, permittedActions = listOf("cancel"))
    private val saved = GatewaySavedJob(GatewayJobRecord("row-id", scope.id, job.jobId, conversation.id, job.kind, 9, "waiting", "CI", 500, null, 1000, 100, "clock", 1000, "{}"), job, conversation, scope)
    private val command = GatewayCommandRecord("original-command", scope.id, null, 7, 8, 9, "original-run", "create", "digest", "{}", "UNKNOWN", null, 1000, 1000)
    private lateinit var model: GatewayJobsViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { store.dao } returns dao
        every { dao.observeScopes() } returns flowOf(listOf(scope))
        every { dao.observeCommandsForGateway(any()) } answers { flowOf(if (firstArg<String>() == scope.connectionUid) listOf(command) else emptyList()) }
        every { repository.jobs(any(), any()) } answers { flowOf(if (firstArg<String>() == scope.connectionUid) listOf(saved) else emptyList()) }
        coEvery { settings.fetchPlatformV2s() } returns listOf(PlatformV2(uid = "gateway-a", name = "Gateway A"), PlatformV2(uid = "gateway-b", name = "Gateway B"))
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings(githubProfileOverrides = mapOf("profile-a" to GitHubChatProfileOverride(engineeringGatewayProfileUid = "gateway-a"), "profile-b" to GitHubChatProfileOverride(engineeringGatewayProfileUid = "gateway-b")))
        model = GatewayJobsViewModel(repository, store, settings)
        owner.put("jobs", model)
    }

    @After fun tearDown() {
        owner.clear()
        Dispatchers.resetMain()
    }

    @Test fun offlineRefreshKeepsTheCachedOriginalJobAndDoesNotSubmitWork() = runTest(dispatcher) {
        coEvery { repository.refreshCatalog("gateway-a") } throws IOException("Offline fixture")
        backgroundScope.launch(dispatcher) { model.state.collect {} }
        backgroundScope.launch(dispatcher) { model.visible("profile-a") }
        assertEquals(listOf(saved), model.state.value.jobs)
        assertTrue(model.state.value.notice.orEmpty().contains("Offline"))
        coVerify(exactly = 1) { repository.refreshCatalog("gateway-a") }
        coVerify(exactly = 0) { repository.retrySavedSubmission(any()) }
        coVerify(exactly = 0) { repository.stopJob(any()) }
    }

    @Test fun repeatedOpenUsesOneReadAndNavigatesToTheSavedConversationOnce() = runTest(dispatcher) {
        backgroundScope.launch(dispatcher) { model.state.collect {} }
        backgroundScope.launch(dispatcher) { model.visible("profile-a") }
        val opened = CompletableDeferred<Int>()
        coEvery { repository.openConversation(conversation.id, "github-connection") } coAnswers { opened.await() }
        val destinations = mutableListOf<Int>()
        repeat(2) { model.open(saved, "github-connection", destinations::add) }
        coVerify(exactly = 1) { repository.openConversation(conversation.id, "github-connection") }
        opened.complete(7)
        assertEquals(listOf(7), destinations)
        coVerify(exactly = 0) { repository.retrySavedSubmission(any()) }
        coVerify(exactly = 0) { repository.stopJob(any()) }
    }

    @Test fun anOpenFinishingAfterSelectionChangesCannotNavigateTheNewScreen() = runTest(dispatcher) {
        backgroundScope.launch(dispatcher) { model.state.collect {} }
        val first = backgroundScope.launch(dispatcher) { model.visible("profile-a") }
        val opened = CompletableDeferred<Int>()
        coEvery { repository.openConversation(any(), any()) } coAnswers { opened.await() }
        val destinations = mutableListOf<Int>()
        model.open(saved, "github-connection", destinations::add)
        first.cancelAndJoin()
        backgroundScope.launch(dispatcher) { model.visible("profile-b") }
        opened.complete(7)
        assertTrue(destinations.isEmpty())
        assertEquals("gateway-b", model.state.value.selection.gatewayUid)
    }

    @Test fun staleRowsCannotStopOrRetryThroughAnotherSelectedGateway() = runTest(dispatcher) {
        backgroundScope.launch(dispatcher) { model.state.collect {} }
        val first = backgroundScope.launch(dispatcher) { model.visible("profile-a") }
        first.cancelAndJoin()
        backgroundScope.launch(dispatcher) { model.visible("profile-b") }
        model.stop(saved)
        model.retry(command)
        coVerify(exactly = 0) { repository.stopJob(any()) }
        coVerify(exactly = 0) { repository.retrySavedSubmission(any()) }
    }

    @Test fun explicitStopUsesTheOriginalScopedJobAndRevisionAndKeepsRequestedDistinct() = runTest(dispatcher) {
        backgroundScope.launch(dispatcher) { model.state.collect {} }
        backgroundScope.launch(dispatcher) { model.visible("profile-a") }
        coEvery { repository.stopJob(saved) } returns GatewayStopOutcome("REQUESTED", "Cancellation requested; not yet confirmed.")
        model.stop(saved)
        coVerify(exactly = 1) { repository.stopJob(saved) }
        assertEquals("Cancellation requested; not yet confirmed.", model.state.value.notice)
        assertEquals(9L, model.state.value.jobs.single().job.revision)
        assertEquals("waiting", model.state.value.jobs.single().job.schedulingState)
    }

    @Test fun leavingTheVisibleScreenStopsPollingWithoutStoppingTheJob() = runTest(dispatcher) {
        backgroundScope.launch(dispatcher) { model.state.collect {} }
        val observing = backgroundScope.launch(dispatcher) { model.visible("profile-a") }
        observing.cancelAndJoin()
        advanceTimeBy(30_000)
        runCurrent()
        coVerify(exactly = 1) { repository.refreshCatalog("gateway-a") }
        coVerify(exactly = 0) { repository.stopJob(any()) }
    }
}
