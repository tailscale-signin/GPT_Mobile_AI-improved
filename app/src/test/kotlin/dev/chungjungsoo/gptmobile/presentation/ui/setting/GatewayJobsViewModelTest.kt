package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import androidx.lifecycle.ViewModelStore
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayJobRecord
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationAttachment
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayJobCatalog
import dev.chungjungsoo.gptmobile.data.github.GatewayJobControl
import dev.chungjungsoo.gptmobile.data.github.GatewayJobRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayJobRoute
import dev.chungjungsoo.gptmobile.data.github.GatewayJobStopOutcome
import dev.chungjungsoo.gptmobile.data.github.GatewayJobStopResult
import dev.chungjungsoo.gptmobile.data.github.GatewayJobStopReview
import dev.chungjungsoo.gptmobile.data.github.GatewayJobSummary
import dev.chungjungsoo.gptmobile.data.github.gatewayIdentityKey
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
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
import kotlinx.coroutines.flow.first
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayJobsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val owner = ViewModelStore()
    private val repository = mockk<GatewayJobRepository>(relaxed = true)
    private val conversations = mockk<GatewayConversationRepository>(relaxed = true)
    private val settings = mockk<SettingRepository>(relaxed = true)
    private val control = mockk<GatewayJobControl>(relaxed = true)
    private val config = ProviderRequestConfig("https://gateway.invalid/v1", token = null, profileUid = "gateway-a")
    private val route = GatewayJobRoute("gateway-a", config.apiUrl, config)
    private val summary = GatewayJobSummary("job-id", "engineering", "conversation-id", title = "Saved work", profileId = "original-profile", model = "exact-model", repository = "owner/repo", branch = "original-branch", state = "running", phase = "CI", revision = 9, acceptedAt = 0.5, serverNow = 1.0, allowedActions = listOf("cancel"))
    private val record = GatewayJobRecord(gatewayIdentityKey("installation", "principal", "engineering", summary.jobId), "installation", "principal", "engineering", summary.jobId, gatewayIdentityKey("installation", "principal", "conversation-id"), "gateway-a", config.apiUrl, "original-profile", "snapshot", 1000)
    private val row = GatewayJobRow(record, summary)
    private val page = GatewayJobCatalog(gatewayId = "installation", principalId = "principal", serverNow = 1.0, items = listOf(summary), complete = true)
    private lateinit var model: GatewayJobsViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.configured(any()) } returns true
        coEvery { repository.route("profile-a", requireEnabled = false) } returns route
        coEvery { repository.route("profile-b", requireEnabled = false) } returns GatewayJobRoute("gateway-b", "https://other.invalid/v1", config.copy(apiUrl = "https://other.invalid/v1", profileUid = "gateway-b"))
        every { repository.observe(any()) } answers { flowOf(if (firstArg<String>() == "profile-a") listOf(record) else emptyList()) }
        every { repository.summary(record) } returns summary
        coEvery { repository.refresh("profile-a", any()) } returns page
        coEvery { repository.refresh("profile-b", any()) } returns page.copy(gatewayId = "other-installation", items = emptyList())
        coEvery { settings.fetchPlatformV2s() } returns listOf(PlatformV2(uid = "gateway-a", name = "Gateway A"), PlatformV2(uid = "gateway-b", name = "Gateway B"))
        model = GatewayJobsViewModel(repository, conversations, settings, control)
        owner.put("jobs", model)
    }

    @After fun tearDown() {
        owner.clear()
        Dispatchers.resetMain()
    }

    private suspend fun bindOriginal() {
        model.bind("profile-a")
        model.state.first { it.rows.size == 1 && it.configured }
    }

    @Test fun offlineRefreshKeepsTheCachedOriginalJobAndDoesNotSubmitWork() = runTest(dispatcher) {
        bindOriginal()
        coEvery { repository.refresh("profile-a", any()) } throws IOException("Offline fixture")
        model.refresh()
        assertEquals(listOf(record), model.state.value.rows.map { it.record })
        assertTrue(model.state.value.error.orEmpty().contains("Offline"))
        coVerify(exactly = 1) { repository.refresh("profile-a", null) }
        coVerify(exactly = 0) { conversations.queueInstruction(any(), any(), any()) }
        coVerify(exactly = 0) { control.stop(any()) }
    }

    @Test fun repeatedOpenUsesOneReadAndNavigatesToTheSavedConversationOnce() = runTest(dispatcher) {
        bindOriginal()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val opened = CompletableDeferred<Int>()
        coEvery { conversations.attach(record.key) } coAnswers {
            started.complete(Unit)
            release.await()
            GatewayConversationAttachment(7, "original-profile", null, requireNotNull(record.conversationKey))
        }
        repeat(2) { model.open(row) { opened.complete(it) } }
        started.await()
        coVerify(exactly = 1) { conversations.attach(record.key) }
        release.complete(Unit)
        assertEquals(7, opened.await())
        coVerify(exactly = 0) { conversations.queueInstruction(any(), any(), any()) }
        coVerify(exactly = 0) { control.stop(any()) }
    }

    @Test fun anOpenFinishingAfterSelectionChangesCannotNavigateTheNewScreen() = runTest(dispatcher) {
        bindOriginal()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        coEvery { conversations.attach(record.key) } coAnswers {
            started.complete(Unit)
            release.await()
            finished.complete(Unit)
            GatewayConversationAttachment(7, "original-profile", null, requireNotNull(record.conversationKey))
        }
        val destinations = mutableListOf<Int>()
        model.open(row, destinations::add)
        started.await()
        model.bind("profile-b")
        model.state.first { it.profileUid == "profile-b" && it.configured }
        release.complete(Unit)
        finished.await()
        runCurrent()
        assertTrue(destinations.isEmpty())
        assertEquals("profile-b", model.state.value.profileUid)
    }

    @Test fun staleRowsCannotStopOrSendInstructionsThroughAnotherSelectedGateway() = runTest(dispatcher) {
        bindOriginal()
        model.bind("profile-b")
        model.state.first { it.profileUid == "profile-b" && it.configured }
        model.showStop(row)
        model.showInstruction(row)
        assertNull(model.state.value.stop)
        assertNull(model.state.value.instruction)
        coVerify(exactly = 0) { control.inspect(any()) }
        coVerify(exactly = 0) { control.stop(any()) }
        coVerify(exactly = 0) { conversations.queueInstruction(any(), any(), any()) }
    }

    @Test fun explicitStopUsesTheOriginalScopedJobAndKeepsRequestedDistinct() = runTest(dispatcher) {
        bindOriginal()
        val review = GatewayJobStopReview(record, summary)
        coEvery { control.inspect(record) } returns review
        coEvery { control.stop(review) } returns GatewayJobStopResult(GatewayJobStopOutcome.REQUESTED)
        model.showStop(row)
        model.state.first { it.stop?.review != null && it.stop?.busy == false }
        coVerify(exactly = 0) { control.stop(any()) }
        model.confirmStop()
        model.state.first { it.stop?.result?.outcome == GatewayJobStopOutcome.REQUESTED }
        coVerify(exactly = 1) { control.stop(review) }
        assertEquals(9L, model.state.value.rows.single().summary.revision)
        assertEquals("running", model.state.value.rows.single().summary.state)
    }

    @Test fun leavingTheVisibleScreenStopsPollingWithoutStoppingTheJob() = runTest(dispatcher) {
        val observing = backgroundScope.launch(dispatcher) { model.visible("profile-a") }
        model.state.first { it.loaded && !it.loading }
        observing.cancelAndJoin()
        advanceTimeBy(30_000)
        runCurrent()
        coVerify(exactly = 1) { repository.refresh("profile-a", null) }
        coVerify(exactly = 0) { control.stop(any()) }
    }
}
