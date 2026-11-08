package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.chungjungsoo.gptmobile.data.github.GatewayCachedConversations
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationAttachment
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationPage
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationSnapshot
import dev.chungjungsoo.gptmobile.data.github.GatewayJobRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayVerifiedScope
import dev.chungjungsoo.gptmobile.data.github.GatewayVerifiedScopeStore
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayConversationsViewModelTest {
    private val jobs = mockk<GatewayJobRepository>()
    private val conversations = mockk<GatewayConversationRepository>()
    private val row = GatewayConversationSnapshot(gatewayId = "gateway-id", principalId = "network:shared", conversationId = "server-only", title = "Saved on the server")
    private val page = GatewayConversationPage(gatewayId = row.gatewayId, principalId = row.principalId, items = listOf(row))
    init {
        coEvery { jobs.cachedConversations(any()) } returns null
    }
    private fun connection(uid: String = "gateway-profile", identity: String = "route-identity") = GatewayMenuConnection(uid, "Gateway", "10.0.0.2:8090", identity, GatewayAvailability())
    private fun viewModel(): GatewayConversationsViewModel {
        val settings = mockk<SettingRepository>()
        every { settings.observePlatformV2s() } returns flowOf(emptyList())
        every { settings.observeFeatureSettings() } returns flowOf(AppFeatureSettings())
        val scopes = mockk<GatewayVerifiedScopeStore>()
        every { scopes.changes } returns MutableStateFlow(0L)
        return GatewayConversationsViewModel(SavedStateHandle(), settings, mockk(), jobs, conversations, scopes)
    }

    @Test fun serverInventoryAndCanonicalIdentityAreUsedToOpenConversation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            coEvery { jobs.listConversations("gateway-profile", null) } returns page
            val attached = GatewayConversationAttachment(42, "inference", null, "canonical-key")
            coEvery { conversations.openConversation("gateway-profile", "gateway-id", "network:shared", "server-only") } returns attached
            val model = viewModel().also { store.put("browser", it) }
            model.select(connection())
            assertEquals(page, model.state.first { it.page != null && !it.loading }.page)
            val opened = CompletableDeferred<GatewayConversationAttachment>()
            model.open(row) { opened.complete(it) }
            assertEquals(attached, opened.await())
            coVerify(exactly = 1) { conversations.openConversation("gateway-profile", "gateway-id", "network:shared", "server-only") }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun disconnectedRefreshPreservesFetchedRowsAndLastConfirmedTimestamp() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            coEvery { jobs.listConversations("gateway-profile", null) } returns page
            val model = viewModel().also { store.put("browser", it) }
            model.select(connection())
            val synced = model.state.first { it.page != null && !it.loading }
            coEvery { jobs.listConversations("gateway-profile", null) } throws java.net.ConnectException()
            model.refresh()
            val failed = model.state.first { it.error == GatewayBrowserError.CONNECTION }
            assertEquals(page, failed.page)
            assertNotNull(failed.lastSyncedAt)
            assertEquals(synced.lastSyncedAt, failed.lastSyncedAt)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun paginationCannotCombineDifferentGatewayPrincipals() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            coEvery { jobs.listConversations("gateway-profile", null) } returns page.copy(hasMore = true, nextCursor = "next")
            coEvery { jobs.listConversations("gateway-profile", "next") } returns page.copy(principalId = "other-principal")
            val model = viewModel().also { store.put("browser", it) }
            model.select(connection())
            model.state.first { it.page != null && !it.loading }
            model.refresh(loadMore = true)
            val failed = model.state.first { it.error == GatewayBrowserError.IDENTITY_CHANGED }
            assertNull(failed.page)
            assertNull(failed.lastSyncedAt)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun missingExecutionProfileCanReadItsSavedTranscriptWithoutCreatingAnotherChat() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            coEvery { jobs.listConversations("gateway-profile", null) } returns page
            coEvery { conversations.prepareTranscript("gateway-profile", "gateway-id", "network:shared", "server-only") } returns "canonical-transcript"
            val model = viewModel().also { store.put("browser", it) }
            model.select(connection())
            model.state.first { it.page != null && !it.loading }
            model.showTranscript(row)
            assertEquals("canonical-transcript", model.state.first { it.transcriptKey != null && it.openingId == null }.transcriptKey)
            coVerify(exactly = 0) { conversations.openConversation(any(), any(), any(), any()) }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun restartedOfflineBrowserRestoresOnlyItsVerifiedLocalInventoryAndOriginalTimestamp() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            coEvery { jobs.cachedConversations("gateway-profile") } returns GatewayCachedConversations(page, 1234)
            coEvery { jobs.listConversations("gateway-profile", null) } throws java.net.ConnectException()
            val model = viewModel().also { store.put("browser", it) }
            model.select(connection())
            val offline = model.state.first { it.error == GatewayBrowserError.CONNECTION }
            assertEquals(page, offline.page)
            assertEquals(1234L, offline.lastSyncedAt)
            assertTrue(offline.cachedInventory)
            coVerify(exactly = 0) { conversations.openConversation(any(), any(), any(), any()) }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun changedCredentialOrVerifiedPrincipalClearsThePreviousAccountBeforeLoading() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            coEvery { jobs.listConversations("gateway-profile", null) } returns page
            val model = viewModel().also { store.put("browser", it) }
            val first = connection().copy(verifiedScope = GatewayVerifiedScope("a".repeat(64), row.gatewayId, row.principalId, 1))
            model.select(first)
            model.state.first { it.page != null && !it.loading }
            coEvery { jobs.listConversations("gateway-profile", null) } throws java.net.ConnectException()
            model.select(first.copy(verifiedScope = first.verifiedScope!!.copy(principalId = "new-account")))
            assertNull(model.state.value.page)
            val changed = model.state.first { it.error == GatewayBrowserError.CONNECTION }
            assertNull(changed.page)
            assertNull(changed.lastSyncedAt)
            model.open(row) { error("Old account cannot navigate") }
            coVerify(exactly = 0) { conversations.openConversation(any(), any(), any(), any()) }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun removingTheSavedConnectionClearsItsRowsAndTranscriptSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            coEvery { jobs.listConversations("gateway-profile", null) } returns page
            val model = viewModel().also { store.put("browser", it) }
            model.select(connection())
            model.state.first { it.page != null && !it.loading }
            model.updateConnections(emptyList())
            assertNull(model.state.value.page)
            assertNull(model.state.value.routeIdentity)
            assertNull(model.state.value.transcriptKey)
            model.showTranscript(row)
            coVerify(exactly = 0) { conversations.prepareTranscript(any(), any(), any(), any()) }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun pausingTheBrowserCancelsPendingOpenWithoutNavigatingAfterTheScreenLeaves() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            coEvery { jobs.listConversations("gateway-profile", null) } returns page
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { conversations.openConversation(any(), any(), any(), any()) } coAnswers {
                started.complete(Unit)
                release.await()
                GatewayConversationAttachment(42, "inference", null, "canonical-key")
            }
            val model = viewModel().also { store.put("browser", it) }
            model.select(connection())
            model.state.first { it.page != null && !it.loading }
            val destinations = mutableListOf<Int>()
            model.open(row) { destinations += it.chatId }
            started.await()
            model.pause()
            release.complete(Unit)
            assertNull(model.state.value.openingId)
            assertTrue(destinations.isEmpty())
            assertEquals(page, model.state.value.page)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
