package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayConversationRecord
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayCachedConversations
import dev.chungjungsoo.gptmobile.data.github.GatewayChatAdoption
import dev.chungjungsoo.gptmobile.data.github.GatewayChatCreator
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationAttachment
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationPage
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationSnapshot
import dev.chungjungsoo.gptmobile.data.github.GatewayJobRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayKnownProfileStore
import dev.chungjungsoo.gptmobile.data.github.GatewayVerifiedScope
import dev.chungjungsoo.gptmobile.data.github.GatewayVerifiedScopeStore
import dev.chungjungsoo.gptmobile.data.github.gatewayIdentityKey
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import dev.chungjungsoo.gptmobile.data.queue.DurablePromptQueue
import dev.chungjungsoo.gptmobile.data.queue.GatewayQueuedSelection
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
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
    private fun viewModel(
        profiles: List<PlatformV2> = emptyList(),
        knownUids: Set<String> = emptySet(),
        rooms: List<ChatRoomV2> = emptyList(),
        creator: GatewayChatCreator = mockk(),
        attachedRecords: List<GatewayConversationRecord> = emptyList()
    ): GatewayConversationsViewModel {
        val settings = mockk<SettingRepository>()
        every { settings.observePlatformV2s() } returns flowOf(profiles)
        coEvery { settings.fetchPlatformV2s() } returns profiles
        every { settings.observeFeatureSettings() } returns flowOf(AppFeatureSettings())
        val scopes = mockk<GatewayVerifiedScopeStore>()
        every { scopes.changes } returns MutableStateFlow(0L)
        coEvery { scopes.verified(any()) } returns null
        coEvery { scopes.configurationRevision(any()) } returns "a".repeat(64)
        val known = mockk<GatewayKnownProfileStore>()
        every { known.changes } returns MutableStateFlow(0L)
        coEvery { known.remembered(any()) } answers { firstArg<PlatformV2>().uid in knownUids }
        val database = mockk<ChatDatabaseV2>()
        every { database.chatRoomDao().observeGatewayChats() } returns flowOf(rooms)
        every { database.gatewayJobDao().observeAttachedConversations() } returns flowOf(attachedRecords)
        every { database.agentRunDao().observeActive() } returns flowOf(emptyList())
        val queue = mockk<DurablePromptQueue>()
        every { queue.pending } returns flowOf(emptyList())
        every { queue.gatewayStates } returns MutableStateFlow(emptyMap())
        coEvery { creator.localAttachment(any(), any(), any(), any()) } returns null
        coEvery { creator.adopt(any(), any()) } coAnswers {
            GatewayChatAdoption(ChatRoomV2(id = firstArg(), title = "Retained gateway", conversationMode = ConversationMode.GATEWAY))
        }
        coEvery { creator.hasQueueBoundary(any()) } returns false
        val monitor = GatewayMenuMonitor(flowOf(emptyList()), CoroutineScope(Dispatchers.Main))
        val completions = mockk<dev.chungjungsoo.gptmobile.data.chat.GenerationCompletionStore>()
        every { completions.items } returns MutableStateFlow(emptyList())
        return GatewayConversationsViewModel(SavedStateHandle(), settings, jobs, conversations, scopes, monitor, known, creator, database, queue, completions)
    }

    @Test fun visibleObserverCoalescesManualRefreshAndStopsWithItsLifecycle() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val response = CompletableDeferred<GatewayConversationPage>()
        val requests = MutableStateFlow(0)
        try {
            coEvery { jobs.listConversations("gateway-profile", null) } coAnswers {
                requests.value++
                response.await()
            }
            val model = viewModel().also { store.put("browser", it) }
            model.select(connection())
            requests.first { it == 1 }
            val observer = backgroundScope.launch { model.observeVisible() }
            runCurrent()
            model.refresh()
            assertEquals(1, requests.value)
            response.complete(page.copy(items = listOf(row.copy(activeJobId = "job", jobState = "RUNNING"))))
            model.state.first { it.page != null && !it.loading }
            runCurrent()
            advanceTimeBy(10_001)
            requests.first { it == 2 }
            observer.cancel()
            model.pause()
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(2, requests.value)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
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
            assertEquals(attached.copy(conversationMode = ConversationMode.GATEWAY), opened.await())
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
            assertTrue(failed.cachedInventory)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun eachVisibleLocalConversationHidesOnlyItsMatchingServerRow() {
        val secondAccount = row.copy(principalId = "other-account")
        val visible = ChatRoomV2(id = 7, title = "Local gateway", conversationMode = ConversationMode.GATEWAY)
        val canonicalKey = gatewayIdentityKey(row.gatewayId, row.principalId, row.conversationId)
        val otherKey = gatewayIdentityKey(secondAccount.gatewayId, secondAccount.principalId, secondAccount.conversationId)
        val initial = GatewayConversationsState(page = page, localChats = listOf(visible), localConversationChats = mapOf(canonicalKey to 7, otherKey to 8))
        assertTrue(initial.unmirroredConversations.isEmpty())
        assertEquals(listOf(row), initial.copy(localChats = emptyList()).unmirroredConversations)
        assertEquals(listOf(secondAccount), initial.copy(page = page.copy(principalId = secondAccount.principalId, items = listOf(secondAccount))).unmirroredConversations)
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

    @Test fun backgroundRefreshRetainsExpandedHistoryAndUsesTheFreshCursor() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val older = row.copy(conversationId = "older", activeJobId = "job", jobState = "RUNNING")
            val completed = older.copy(jobState = "COMPLETED")
            val newest = row.copy(conversationId = "newest")
            coEvery { jobs.listConversations("gateway-profile", null) } returns page.copy(hasMore = true, nextCursor = "original")
            coEvery { jobs.listConversations("gateway-profile", "original") } returns page.copy(items = listOf(older))
            val model = viewModel().also { store.put("browser", it) }
            model.select(connection())
            model.state.first { it.page != null && !it.loading }
            model.refresh(loadMore = true)
            model.state.first { it.page?.items == listOf(row, older) && !it.loading }

            coEvery { jobs.listConversations("gateway-profile", null) } returns page.copy(items = listOf(newest, row), hasMore = true, nextCursor = "fresh")
            coEvery { jobs.listConversations("gateway-profile", "fresh") } returns page.copy(items = listOf(completed))
            val observer = backgroundScope.launch { model.observeVisible() }
            val refreshed = model.state.first { it.page?.items?.firstOrNull() == newest && !it.loading }
            assertEquals(listOf(newest, row, completed), refreshed.page?.items)
            assertFalse(requireNotNull(refreshed.page).hasMore)
            observer.cancel()
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

    @Test fun oneKnownOfflineProfileCreatesAndOpensImmediatelyEvenBeforeTheFirstUiEmission() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val profile = gatewayProfile("one")
            val room = ChatRoomV2(id = 42, title = "Gateway", enabledPlatform = listOf(profile.uid), conversationMode = ConversationMode.GATEWAY)
            val creator = mockk<GatewayChatCreator>()
            coEvery { creator.create(GatewayQueuedSelection.capture(profile, profile.model)) } returns room
            val model = viewModel(listOf(profile), setOf(profile.uid), creator = creator).also { store.put("browser", it) }
            val opened = CompletableDeferred<ChatRoomV2>()
            model.newChat { opened.complete(it) }
            assertTrue(model.state.value.creatingChat)
            assertEquals(room, opened.await())
            assertFalse(model.state.value.profilePickerVisible)
            coVerify(exactly = 1) { creator.create(any()) }
            coVerify(exactly = 0) { jobs.listConversations(any(), any()) }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun multipleKnownProfilesShowThePickerAndOnlyTheChosenProfileCreatesAChat() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val profiles = listOf(gatewayProfile("one"), gatewayProfile("two"))
            val room = ChatRoomV2(id = 43, title = "Gateway", enabledPlatform = listOf("two"), conversationMode = ConversationMode.GATEWAY)
            val creator = mockk<GatewayChatCreator>()
            coEvery { creator.create(GatewayQueuedSelection.capture(profiles[1], profiles[1].model)) } returns room
            val model = viewModel(profiles, profiles.map { it.uid }.toSet(), creator = creator).also { store.put("browser", it) }
            val opened = CompletableDeferred<ChatRoomV2>()
            model.newChat { opened.complete(it) }
            model.state.first { it.profilePickerVisible && !it.creatingChat }
            assertEquals(listOf("one", "two"), model.state.value.profileChoices.map { it.profileUid })
            assertFalse(opened.isCompleted)
            coVerify(exactly = 0) { creator.create(any()) }
            model.chooseProfile("two") { opened.complete(it) }
            assertEquals(room, opened.await())
            coVerify(exactly = 1) { creator.create(GatewayQueuedSelection.capture(profiles[1], profiles[1].model)) }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun candidateOnlyProfileExplainsMissingSupportWithoutCreatingAChat() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val creator = mockk<GatewayChatCreator>()
            val model = viewModel(listOf(gatewayProfile("ordinary-llama")), creator = creator).also { store.put("browser", it) }
            model.newChat { error("Unverified profile cannot create a Gateway chat") }
            model.state.first { it.creationError != null && !it.creatingChat }
            assertTrue(model.state.value.profileChoices.isEmpty())
            coVerify(exactly = 0) { creator.create(any()) }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun remoteConnectionRemovalPreservesLocalGatewayRoomsAndTheProfilePicker() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val profiles = listOf(gatewayProfile("one"), gatewayProfile("two"))
            val local = ChatRoomV2(id = 44, title = "Offline gateway", enabledPlatform = listOf("removed-profile"), conversationMode = ConversationMode.GATEWAY)
            coEvery { jobs.listConversations("gateway-profile", null) } returns page
            val model = viewModel(profiles, profiles.map { it.uid }.toSet(), listOf(local)).also { store.put("browser", it) }
            model.select(connection())
            model.state.first { it.page != null && it.localChats.isNotEmpty() && !it.loading }
            model.newChat { error("Two profiles should show a picker") }
            model.state.first { it.profilePickerVisible && !it.creatingChat }
            model.updateConnections(emptyList())
            assertNull(model.state.value.page)
            assertEquals(listOf(local), model.state.value.localChats)
            assertTrue(model.state.value.profilePickerVisible)
            assertEquals(2, model.state.value.profileChoices.size)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun repeatedNewChatPressesDoNotCreateTwoDrafts() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val profile = gatewayProfile("one")
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<ChatRoomV2>()
            val creator = mockk<GatewayChatCreator>()
            coEvery { creator.create(any()) } coAnswers {
                started.complete(Unit)
                release.await()
            }
            val model = viewModel(listOf(profile), setOf(profile.uid), creator = creator).also { store.put("browser", it) }
            val opened = CompletableDeferred<ChatRoomV2>()
            model.newChat { opened.complete(it) }
            model.newChat { error("Second click should be ignored") }
            started.await()
            model.newChat { error("Active creation should be ignored") }
            val room = ChatRoomV2(id = 45, title = "Gateway", enabledPlatform = listOf(profile.uid), conversationMode = ConversationMode.GATEWAY)
            release.complete(room)
            assertEquals(room, opened.await())
            coVerify(exactly = 1) { creator.create(any()) }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun locallyRetainedActiveServerRowOpensWithoutWaitingForATranscriptRead() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val active = row.copy(transcriptAvailable = false)
            val key = gatewayIdentityKey(row.gatewayId, row.principalId, row.conversationId)
            val retained = GatewayConversationRecord(key, row.gatewayId, row.principalId, row.conversationId, "gateway-profile", "http://192.168.1.4:8090", "inference", 42, Json.encodeToString(active))
            val creator = mockk<GatewayChatCreator>()
            val model = viewModel(creator = creator, attachedRecords = listOf(retained)).also { store.put("browser", it) }
            coEvery { jobs.listConversations("gateway-profile", null) } returns page.copy(items = listOf(active))
            val attachment = GatewayConversationAttachment(42, "inference", null, key, false)
            coEvery { creator.localAttachment("gateway-profile", row.gatewayId, row.principalId, row.conversationId) } returns attachment
            model.select(connection())
            model.state.first { !it.loading && it.page != null && key in it.localConversationKeys }
            val opened = CompletableDeferred<GatewayConversationAttachment>()
            model.open(active) { opened.complete(it) }
            assertEquals(attachment.copy(conversationMode = ConversationMode.GATEWAY), opened.await())
            coVerify(exactly = 0) { conversations.openConversation(any(), any(), any(), any()) }
            coVerify(exactly = 1) { creator.adopt(42, "inference") }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun localHistoryRemainsReadableWhenItsQueueBindingCannotBeRestored() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val local = ChatRoomV2(id = 99, title = "Retained history", enabledPlatform = listOf("missing-profile"), conversationMode = ConversationMode.GATEWAY)
            val creator = mockk<GatewayChatCreator>()
            val model = viewModel(rooms = listOf(local), creator = creator).also { store.put("browser", it) }
            coEvery { creator.adopt(local.id, null) } throws IllegalStateException("local binding unavailable")
            coEvery { creator.hasQueueBoundary(local.id) } returns true
            val opened = CompletableDeferred<ChatRoomV2>()
            model.openLocalChat(local) { opened.complete(it) }
            assertEquals(local, opened.await())
            assertTrue(model.state.value.creationError.orEmpty().contains("read this conversation"))
            coVerify(exactly = 0) { jobs.listConversations(any(), any()) }
            coVerify(exactly = 0) { conversations.openConversation(any(), any(), any(), any()) }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun failedLegacyMarkerPersistenceCannotOpenAnOrdinaryComposer() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val local = ChatRoomV2(id = 100, title = "Legacy retained history", enabledPlatform = listOf("original-profile"))
            val creator = mockk<GatewayChatCreator>()
            val model = viewModel(rooms = listOf(local), creator = creator).also { store.put("browser", it) }
            coEvery { creator.adopt(local.id, null) } throws IllegalStateException("marker write failed")
            coEvery { creator.hasQueueBoundary(local.id) } returns false
            model.openLocalChat(local) { error("An ordinary composer must not open after its Gateway marker failed") }
            val blocked = model.state.first { it.creationError != null && it.openingId == null }
            assertTrue(blocked.creationError.orEmpty().contains("queue boundary could not be saved"))
            coVerify(exactly = 0) { jobs.listConversations(any(), any()) }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private fun gatewayProfile(uid: String) = PlatformV2(uid = uid, name = uid, compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.4:8090", model = "model-$uid")
}
