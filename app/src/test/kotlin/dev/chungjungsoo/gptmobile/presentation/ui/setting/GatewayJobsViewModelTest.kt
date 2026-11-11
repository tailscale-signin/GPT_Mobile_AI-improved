package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import androidx.lifecycle.ViewModelStore
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayJobRecord
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationAttachment
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayHistoryUnsupported
import dev.chungjungsoo.gptmobile.data.github.GatewayJobCatalog
import dev.chungjungsoo.gptmobile.data.github.GatewayJobControl
import dev.chungjungsoo.gptmobile.data.github.GatewayJobRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayJobRoute
import dev.chungjungsoo.gptmobile.data.github.GatewayJobStopOutcome
import dev.chungjungsoo.gptmobile.data.github.GatewayJobStopResult
import dev.chungjungsoo.gptmobile.data.github.GatewayJobStopReview
import dev.chungjungsoo.gptmobile.data.github.GatewayJobSummary
import dev.chungjungsoo.gptmobile.data.github.GatewayVerifiedScope
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
    private val secondRoute = GatewayJobRoute("gateway-b", "https://other.invalid/v1", config.copy(apiUrl = "https://other.invalid/v1", profileUid = "gateway-b"))
    private val configuration = MutableStateFlow<GatewayJobRoute?>(route)
    private val summary = GatewayJobSummary("job-id", "engineering", "conversation-id", title = "Saved work", profileId = "original-profile", model = "exact-model", repository = "owner/repo", branch = "original-branch", state = "running", phase = "CI", revision = 9, acceptedAt = 0.5, serverNow = 1.0, allowedActions = listOf("cancel"))
    private val record = GatewayJobRecord(gatewayIdentityKey("installation", "principal", "engineering", summary.jobId), "installation", "principal", "engineering", summary.jobId, gatewayIdentityKey("installation", "principal", "conversation-id"), "gateway-a", config.apiUrl, "original-profile", "snapshot", 1000)
    private val row = GatewayJobRow(record, summary)
    private val page = GatewayJobCatalog(gatewayId = "installation", principalId = "principal", serverNow = 1.0, items = listOf(summary), complete = true)
    private lateinit var model: GatewayJobsViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.configured(any()) } returns true
        coEvery { repository.route("profile-a", requireEnabled = false) } returns route
        coEvery { repository.route("profile-b", requireEnabled = false) } returns secondRoute
        every { repository.observeConfiguration("profile-a") } returns configuration
        every { repository.observeConfiguration("profile-b") } returns flowOf(secondRoute)
        coEvery { repository.cachedScope(any()) } returns GatewayVerifiedScope("a".repeat(64), "installation", "principal", 1)
        every { repository.observe(any<GatewayJobRoute>()) } answers { flowOf(if (firstArg<GatewayJobRoute>().profileUid == "gateway-a") listOf(record) else emptyList()) }
        every { repository.summary(record) } returns summary
        coEvery { repository.refresh("profile-a", any()) } returns page
        coEvery { repository.refresh("profile-b", any()) } returns page.copy(gatewayId = "other-installation", items = emptyList())
        coEvery { settings.fetchPlatformV2s() } returns listOf(PlatformV2(uid = "gateway-a", name = "Gateway A"), PlatformV2(uid = "gateway-b", name = "Gateway B"))
        model = GatewayJobsViewModel(repository, conversations, settings, control, dispatcher)
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

    @Test fun leavingTheVisibleScreenStopsPollingWithoutStoppingTheJob() = runTest(StandardTestDispatcher(dispatcher.scheduler)) {
        // Binding, polling and cancellation all use this controlled scheduler.
        val observing = backgroundScope.launch { model.visible("profile-a") }
        model.state.first { it.loaded && !it.loading }
        observing.cancelAndJoin()
        advanceTimeBy(30_000)
        runCurrent()
        coVerify(exactly = 1) { repository.refresh("profile-a", null) }
        coVerify(exactly = 0) { control.stop(any()) }
    }

    @Test fun unsupportedCatalogSuspendsPollingUntilAnExplicitRefreshSucceeds() = runTest(StandardTestDispatcher(dispatcher.scheduler)) {
        coEvery { repository.refresh("profile-a", any()) } throws GatewayHistoryUnsupported()
        coEvery { repository.legacyJobs("profile-a") } returns emptyList()
        val observing = backgroundScope.launch { model.visible("profile-a") }
        model.state.first { !it.automaticRefreshAllowed && !it.loading }
        advanceTimeBy(60_000)
        runCurrent()
        coVerify(exactly = 1) { repository.refresh("profile-a", null) }
        assertEquals(listOf(record), model.state.value.rows.map { it.record })

        coEvery { repository.refresh("profile-a", any()) } returns page
        model.refresh()
        runCurrent()
        assertTrue(model.state.value.automaticRefreshAllowed)
        advanceTimeBy(5_000)
        runCurrent()
        coVerify(exactly = 3) { repository.refresh("profile-a", null) }
        observing.cancelAndJoin()
        coVerify(exactly = 0) { control.stop(any()) }
    }

    @Test fun reopeningAnUnsupportedRouteKeepsTheRepairStateWithoutAnotherRequest() = runTest(StandardTestDispatcher(dispatcher.scheduler)) {
        coEvery { repository.refresh("profile-a", any()) } throws GatewayHistoryUnsupported()
        coEvery { repository.legacyJobs("profile-a") } returns emptyList()
        val first = backgroundScope.launch { model.visible("profile-a") }
        model.state.first { !it.automaticRefreshAllowed && it.error != null }
        first.cancelAndJoin()
        val reopened = backgroundScope.launch { model.visible("profile-a") }
        model.state.first { it.configured && !it.automaticRefreshAllowed && it.error != null }
        advanceTimeBy(60_000)
        runCurrent()
        coVerify(exactly = 1) { repository.refresh("profile-a", null) }
        reopened.cancelAndJoin()
    }

    @Test fun transientCatalogFailureBacksOffAndRetainsPreviouslyVerifiedRows() = runTest(StandardTestDispatcher(dispatcher.scheduler)) {
        coEvery { repository.refresh("profile-a", any()) } throws IOException("Offline fixture")
        val observing = backgroundScope.launch { model.visible("profile-a") }
        model.state.first { it.error != null && !it.loading }
        assertTrue(model.state.value.automaticRefreshAllowed)
        assertEquals(10_000L, model.state.value.refreshDelayMillis)
        advanceTimeBy(9_999)
        runCurrent()
        coVerify(exactly = 1) { repository.refresh("profile-a", null) }
        advanceTimeBy(1)
        runCurrent()
        coVerify(exactly = 2) { repository.refresh("profile-a", null) }
        assertEquals(20_000L, model.state.value.refreshDelayMillis)
        assertEquals(listOf(record), model.state.value.rows.map { it.record })
        observing.cancelAndJoin()
        coVerify(exactly = 0) { control.stop(any()) }
    }

    @Test fun repeatedCatalogFailuresAreCappedAndSuccessfulRefreshResetsTheBackoff() = runTest(StandardTestDispatcher(dispatcher.scheduler)) {
        coEvery { repository.refresh("profile-a", any()) } throws IOException("Offline fixture")
        val observing = backgroundScope.launch { model.visible("profile-a") }
        runCurrent()
        var refreshes = 1
        for (delayMillis in listOf(10_000L, 20_000L, 40_000L, 60_000L)) {
            assertEquals(delayMillis, model.state.value.refreshDelayMillis)
            advanceTimeBy(delayMillis - 1)
            runCurrent()
            coVerify(exactly = refreshes) { repository.refresh("profile-a", null) }
            advanceTimeBy(1)
            runCurrent()
            refreshes++
            coVerify(exactly = refreshes) { repository.refresh("profile-a", null) }
            assertEquals(listOf(record), model.state.value.rows.map { it.record })
        }
        assertEquals(60_000L, model.state.value.refreshDelayMillis)

        coEvery { repository.refresh("profile-a", any()) } returns page
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(GatewayCatalogState.CURRENT, model.state.value.catalogState)
        assertNull(model.state.value.error)
        assertEquals(5_000L, model.state.value.refreshDelayMillis)

        coEvery { repository.refresh("profile-a", any()) } throws IOException("Offline again")
        advanceTimeBy(4_999)
        runCurrent()
        coVerify(exactly = refreshes + 1) { repository.refresh("profile-a", null) }
        advanceTimeBy(1)
        runCurrent()
        coVerify(exactly = refreshes + 2) { repository.refresh("profile-a", null) }
        assertEquals(10_000L, model.state.value.refreshDelayMillis)
        assertEquals(listOf(record), model.state.value.rows.map { it.record })
        observing.cancelAndJoin()
        coVerify(exactly = 0) { control.stop(any()) }
        coVerify(exactly = 0) { conversations.queueInstruction(any(), any(), any()) }
    }

    @Test fun anUnverifiedConfigurationCannotDisplayCachedAccountsOrUseTheirActions() = runTest(dispatcher) {
        coEvery { repository.cachedScope(route) } returns null
        model.bind("profile-a")
        val ready = model.state.first { it.configured }
        assertTrue(ready.rows.isEmpty())
        assertNull(ready.gatewayId)
        model.open(row) { error("An unverified cached account cannot navigate") }
        model.showStop(row)
        model.showInstruction(row)
        assertNull(model.state.value.stop)
        assertNull(model.state.value.instruction)
        coVerify(exactly = 0) { conversations.attach(any()) }
        coVerify(exactly = 0) { control.inspect(any()) }
    }

    @Test fun offlineRowsAreFilteredToThePreviouslyVerifiedGatewayAndPrincipal() = runTest(dispatcher) {
        val other = record.copy(key = "other-account-job", principalId = "other-account")
        every { repository.observe(route) } returns flowOf(listOf(record, other))
        bindOriginal()
        assertEquals(listOf(record), model.state.value.rows.map { it.record })
        assertEquals("principal", model.state.value.principalId)
        model.showStop(GatewayJobRow(other, summary))
        assertNull(model.state.value.stop)
        coVerify(exactly = 0) { control.inspect(any()) }
    }

    @Test fun credentialRevisionChangeClearsCachedRowsAndPendingActionsBeforeTheNextPoll() = runTest(dispatcher) {
        bindOriginal()
        val changed = route.copy(configurationRevision = "b".repeat(64))
        coEvery { repository.cachedScope(changed) } returns null
        val review = GatewayJobStopReview(record, summary)
        coEvery { control.inspect(record) } returns review
        model.showStop(row)
        model.state.first { it.stop?.review != null }
        configuration.value = changed
        model.state.first { it.configured && it.gatewayId == null }
        assertTrue(model.state.value.rows.isEmpty())
        assertNull(model.state.value.stop)
        model.confirmStop()
        model.showInstruction(row)
        assertNull(model.state.value.instruction)
        coVerify(exactly = 0) { control.stop(any()) }
        coVerify(exactly = 0) { repository.refresh(any(), any()) }
    }

    @Test fun aNewlyConfirmedPrincipalCannotKeepThePreviousAccountsRowsOrControls() = runTest(dispatcher) {
        bindOriginal()
        coEvery { repository.refresh("profile-a", null) } returns page.copy(principalId = "new-account", items = emptyList())
        model.refresh()
        assertEquals("new-account", model.state.value.principalId)
        assertTrue(model.state.value.rows.isEmpty())
        model.showStop(row)
        model.showInstruction(row)
        assertNull(model.state.value.stop)
        assertNull(model.state.value.instruction)
        coVerify(exactly = 0) { control.inspect(any()) }
    }

    @Test fun unknownLegacyEvidenceRequiresAnExplicitReadOnlyViewAndNeverBecomesCurrentAccountRows() = runTest(dispatcher) {
        coEvery { repository.cachedScope(route) } returns null
        val retained = record.copy(key = "legacy-key", principalId = "unknown")
        coEvery { repository.refresh("profile-a", null) } throws GatewayHistoryUnsupported()
        coEvery { repository.legacyJobs("profile-a") } returns listOf(retained)
        every { repository.summary(retained) } returns summary
        model.bind("profile-a")
        model.state.first { it.configured }
        model.refresh()
        assertTrue(model.state.value.rows.isEmpty())
        assertTrue(!model.state.value.legacy)
        assertEquals(listOf(retained), model.state.value.legacyRows.map { it.record })
        model.showLegacyEvidence()
        assertTrue(model.state.value.legacy)
        assertTrue(model.state.value.rows.isEmpty())
        val legacyRow = GatewayJobRow(retained, summary)
        val binding = mockk<dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding>()
        coEvery { repository.legacyBinding(retained.remoteJobId) } returns binding
        model.open(legacyRow) { error("Legacy evidence must not navigate through an unverified remote account") }
        model.state.first { it.legacyDetail === binding && it.opening == null }
        coVerify(exactly = 1) { repository.legacyBinding(retained.remoteJobId) }
        coVerify(exactly = 0) { conversations.attach(any()) }
        model.showStop(legacyRow)
        model.showInstruction(legacyRow)
        model.showTranscript(legacyRow)
        assertNull(model.state.value.stop)
        assertNull(model.state.value.instruction)
        assertNull(model.state.value.transcriptKey)
        coEvery { repository.refresh("profile-a", null) } returns page
        model.refresh()
        assertTrue(!model.state.value.legacy)
        assertTrue(model.state.value.automaticRefreshAllowed)
        assertEquals(GatewayCatalogState.CURRENT, model.state.value.catalogState)
        assertNull(model.state.value.legacyDetail)
        coVerify(exactly = 0) { control.inspect(any()) }
        coVerify(exactly = 0) { conversations.queueInstruction(any(), any(), any()) }
        coVerify(exactly = 0) { conversations.prepareTranscript(any(), any(), any(), any()) }
    }
}
