package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.agent.ActiveAgentRun
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.agent.AgentRunNotice
import dev.chungjungsoo.gptmobile.data.agent.AgentRunRequest
import dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.AgentToolBinding
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryResult
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnResult
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.LocalModelRepository
import dev.chungjungsoo.gptmobile.data.repository.ModelCatalogRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ChatPromptQueueTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val messages = MutableStateFlow<List<MessageV2>>(emptyList())
    private val runs = MutableStateFlow<List<AgentRun>>(emptyList())
    private val activeRuns = MutableStateFlow<Map<String, ActiveAgentRun>>(emptyMap())
    private val submissions = mutableListOf<PersistAgentTurnRequest>()
    private val starts = mutableListOf<List<AgentRunRequest>>()
    private val synthesisGate = CompletableDeferred<Unit>()
    private val synthesisMerged = CompletableDeferred<Unit>()
    private val combinedSynthesisStarted = CompletableDeferred<Unit>()
    private val queuedTurnStarted = CompletableDeferred<Unit>()
    private var synthesisCompletionGate: CompletableDeferred<Unit>? = null
    private var membershipGate: CompletableDeferred<Unit>? = null
    private var failMembershipUpdate = false
    private var nextId = 10

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun unavailableSavedFreeProfileKeepsTheDraftAndCreatesNoRunOrQueuedPrompt() = runTest(dispatcher) {
        val model = createViewModel(freeProvider = dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.OPENCODE)
        runCurrent()
        completePersistedRuns()
        activeRuns.value = emptyMap()
        runCurrent()
        send(model, "Keep this draft")
        assertTrue(submissions.isEmpty())
        assertTrue(starts.isEmpty())
        assertEquals(0, model.queuedPromptCount.value)
        assertEquals("Keep this draft", model.question.text.toString())
        assertTrue(model.freeProviderNotice.value!!.contains("restricted to OpenCode’s own apps"))
    }

    @Test
    fun `queue drains when coordinator releases completed run and keeps the current draft`() = runTest(dispatcher) {
        val model = createViewModel()
        runCurrent()
        send(model, "Second prompt")
        send(model, "Third prompt")
        model.question.setTextAndPlaceCursorAtEnd("Unsent draft")
        assertEquals(2, model.queuedPromptCount.value)

        completePersistedRuns()
        runCurrent()
        assertTrue(submissions.isEmpty())
        activeRuns.value = emptyMap()
        runCurrent()

        assertEquals(listOf("Second prompt"), submissions.map { it.userMessage.content })
        assertEquals("Unsent draft", model.question.text.toString())
        assertEquals(1, model.queuedPromptCount.value)
        completePersistedRuns()
        runCurrent()
        activeRuns.value = emptyMap()
        runCurrent()

        assertEquals(listOf("Second prompt", "Third prompt"), submissions.map { it.userMessage.content })
        assertEquals("Unsent draft", model.question.text.toString())
        assertEquals(0, model.queuedPromptCount.value)
    }

    @Test
    fun `queue waits for persisted completion when coordinator finishes first`() = runTest(dispatcher) {
        val model = createViewModel()
        runCurrent()
        send(model, "Follow up")
        activeRuns.value = emptyMap()
        runCurrent()
        assertTrue(submissions.isEmpty())

        completePersistedRuns()
        runCurrent()
        assertEquals(listOf("Follow up"), submissions.map { it.userMessage.content })
        assertEquals(0, model.queuedPromptCount.value)
    }

    @Test
    fun `queue waits until background message grouping catches up`() = runTest(dispatcher) {
        val grouping = PausableDispatcher(dispatcher)
        val model = createViewModel(groupingDispatcher = grouping)
        runCurrent()
        send(model, "Follow up")
        grouping.paused = true
        completePersistedRuns()
        runCurrent()
        activeRuns.value = emptyMap()
        runCurrent()
        assertTrue(submissions.isEmpty())
        assertEquals(1, model.queuedPromptCount.value)

        grouping.resume()
        runCurrent()
        assertEquals(listOf("Follow up"), submissions.map { it.userMessage.content })
        assertEquals(0, model.queuedPromptCount.value)
    }

    @Test
    fun `queued prompt waits through combined synthesis persistence and generation`() = runTest(dispatcher) {
        synthesisCompletionGate = CompletableDeferred()
        val model = createViewModel(combined = true)
        runCurrent()
        send(model, "Follow up")
        completePersistedRuns()
        runCurrent()
        activeRuns.value = emptyMap()
        runCurrent()
        assertTrue(submissions.isEmpty())
        assertTrue(starts.isEmpty())

        synthesisGate.complete(Unit)
        combinedSynthesisStarted.await()
        synthesisMerged.complete(Unit)
        runCurrent()
        assertTrue(submissions.isEmpty())
        assertTrue(starts.single().any { it.runId.startsWith("combined-") })

        synthesisCompletionGate!!.complete(Unit)
        val combinedRunId = starts.single().single().runId
        messages.value = messages.value.map { if (it.currentRunId == combinedRunId) it.copy(content = "Combined response") else it }
        runs.value = runs.value.map { if (it.runId == combinedRunId) it.copy(status = AgentRunStatus.COMPLETED) else it }
        activeRuns.value = emptyMap()
        runCurrent()
        queuedTurnStarted.await()
        runCurrent()
        assertEquals(2, starts.size)
        assertTrue(starts.first().any { it.runId.startsWith("combined-") })
        assertFalse(starts.last().any { it.runId.startsWith("combined-") })
        assertEquals(listOf("Follow up"), submissions.map { it.userMessage.content })
        assertTrue(runs.value.any { it.runId.startsWith("combined-") && it.status == AgentRunStatus.COMPLETED })
    }

    private fun send(model: ChatViewModel, text: String) {
        model.question.setTextAndPlaceCursorAtEnd(text)
        model.askQuestion()
    }

    @Test
    fun `queue waits while every profile is paused and resumes without losing the draft`() = runTest(dispatcher) {
        val model = createViewModel()
        runCurrent()
        model.togglePlatformDisabled("profile-1")
        send(model, "Queued while paused")
        model.question.setTextAndPlaceCursorAtEnd("Next draft")
        completePersistedRuns()
        activeRuns.value = emptyMap()
        runCurrent()
        assertTrue(submissions.isEmpty())
        assertEquals(1, model.queuedPromptCount.value)
        model.togglePlatformDisabled("profile-1")
        runCurrent()
        assertEquals(listOf("Queued while paused"), submissions.map { it.userMessage.content })
        assertEquals("Next draft", model.question.text.toString())
        assertEquals(0, model.queuedPromptCount.value)
    }

    @Test
    fun `sending during attachment preparation keeps the draft and does not enqueue incomplete content`() = runTest(dispatcher) {
        val model = createViewModel()
        runCurrent()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = java.io.File(context.cacheDir, "preparing.txt").apply { writeText("Document content") }
        try {
            model.addSelectedFile(file.absolutePath)
            model.question.setTextAndPlaceCursorAtEnd("Read this document")
            model.askQuestion()
            assertEquals("Read this document", model.question.text.toString())
            assertEquals(0, model.queuedPromptCount.value)
            assertTrue(submissions.isEmpty())
        } finally {
            store.clear()
            file.delete()
        }
    }

    @Test
    fun `adding a profile releases a paused queue only after membership is saved and refreshes its tools`() = runTest(dispatcher) {
        val model = createViewModel(availableProfileCount = 2)
        runCurrent()
        model.availableChatTools.first { it.isNotEmpty() }
        model.togglePlatformDisabled("profile-1")
        send(model, "Waiting for another profile")
        model.question.setTextAndPlaceCursorAtEnd("Unsent draft")
        completePersistedRuns()
        activeRuns.value = emptyMap()
        runCurrent()
        assertTrue(submissions.isEmpty())
        assertTrue(model.availableChatTools.value.none { it.id == "profile-2-tools:search" })

        membershipGate = CompletableDeferred()
        model.setPlatformMembership("profile-2", true)
        runCurrent()
        assertEquals(listOf("profile-1"), model.activePlatformUids.value)
        assertTrue(submissions.isEmpty())
        membershipGate!!.complete(Unit)
        runCurrent()

        assertEquals(listOf("Waiting for another profile"), submissions.map { it.userMessage.content })
        assertEquals(listOf("profile-2"), starts.single().map { it.platform.uid })
        assertEquals(listOf("profile-1", "profile-2"), model.enabledPlatformsInChat)
        assertEquals("Unsent draft", model.question.text.toString())
        // Catalog reads use Dispatchers.IO, which runCurrent does not drain.
        model.availableChatTools.first { tools -> tools.any { it.id == "profile-2-tools:search" } }
        assertEquals(0, model.queuedPromptCount.value)
    }

    @Test
    fun `rapid membership updates use the last selection before starting queued work`() = runTest(dispatcher) {
        val model = createViewModel(availableProfileCount = 3)
        runCurrent()
        model.togglePlatformDisabled("profile-1")
        completePersistedRuns()
        activeRuns.value = emptyMap()
        runCurrent()
        membershipGate = CompletableDeferred()

        model.setPlatformMembership("profile-2", true)
        runCurrent()
        model.setPlatformMembership("profile-3", true)
        model.setPlatformMembership("profile-2", false)
        send(model, "Use the final selection")
        runCurrent()
        assertTrue(submissions.isEmpty())
        membershipGate!!.complete(Unit)
        runCurrent()

        assertEquals(listOf("profile-1", "profile-3"), model.activePlatformUids.value)
        assertEquals(listOf("profile-3"), starts.single().map { it.platform.uid })
        assertTrue(model.availableChatTools.value.none { it.id == "profile-2-tools:search" })
    }

    @Test
    fun `failed membership changes keep the paused queue intact for a successful retry`() = runTest(dispatcher) {
        val model = createViewModel(availableProfileCount = 2)
        runCurrent()
        model.togglePlatformDisabled("profile-1")
        send(model, "Keep this prompt")
        completePersistedRuns()
        activeRuns.value = emptyMap()
        runCurrent()
        failMembershipUpdate = true
        model.setPlatformMembership("profile-2", true)
        runCurrent()

        assertEquals(listOf("profile-1"), model.activePlatformUids.value)
        assertEquals(setOf("profile-1"), model.disabledPlatformUids.value)
        assertEquals(1, model.queuedPromptCount.value)
        assertTrue(submissions.isEmpty())
        failMembershipUpdate = false
        model.setPlatformMembership("profile-2", true)
        runCurrent()

        assertEquals(listOf("Keep this prompt"), submissions.map { it.userMessage.content })
        assertEquals(0, model.queuedPromptCount.value)
    }

    @Test
    fun `pending optional palette persistence cannot delay profile selection or queued membership`() = runTest(dispatcher) {
        val paletteGate = CompletableDeferred<Unit>()
        try {
            val model = createViewModel(availableProfileCount = 2, paletteGate = paletteGate)
            runCurrent()
            model.setPlatformMembership("profile-2", true)
            runCurrent()
            model.selectNextMessageProfile("profile-2")
            assertEquals("profile-2", model.nextMessageProfileUid.value)
            assertEquals(listOf("profile-1", "profile-2"), model.activePlatformUids.value)
            assertFalse(paletteGate.isCompleted)
            send(model, "Selected while colors are pending")
            completePersistedRuns()
            activeRuns.value = emptyMap()
            runCurrent()
            assertEquals(listOf("profile-2"), starts.single().map { it.platform.uid })
        } finally {
            paletteGate.complete(Unit)
        }
    }

    @Test
    fun `queued one-message choice snapshots its profile and preserves membership`() = runTest(dispatcher) {
        val model = createViewModel(availableProfileCount = 2)
        runCurrent()
        model.setPlatformMembership("profile-2", true)
        runCurrent()
        model.selectNextMessageProfile("profile-2")
        send(model, "Use this profile once")
        assertEquals(null, model.nextMessageProfileUid.value)
        assertEquals(listOf("profile-1", "profile-2"), model.activePlatformUids.value)
        completePersistedRuns()
        activeRuns.value = emptyMap()
        runCurrent()
        assertEquals(listOf("profile-2"), starts.single().map { it.platform.uid })
        assertEquals(listOf("profile-1", "profile-2"), model.activePlatformUids.value)
    }

    @Test
    fun `removed one-message queue target waits without losing its accepted prompt`() = runTest(dispatcher) {
        val model = createViewModel(availableProfileCount = 2)
        runCurrent()
        model.setPlatformMembership("profile-2", true)
        runCurrent()
        model.selectNextMessageProfile("profile-2")
        send(model, "Wait for my selected profile")
        model.setPlatformMembership("profile-2", false)
        runCurrent()
        completePersistedRuns()
        activeRuns.value = emptyMap()
        runCurrent()
        assertTrue(submissions.isEmpty())
        assertEquals(1, model.queuedPromptCount.value)
        model.setPlatformMembership("profile-2", true)
        runCurrent()
        assertEquals(listOf("profile-2"), starts.single().map { it.platform.uid })
        assertEquals(0, model.queuedPromptCount.value)
    }

    @Test
    fun `one-message choice rejects profiles outside membership and combined mode`() = runTest(dispatcher) {
        val model = createViewModel(availableProfileCount = 2)
        runCurrent()
        model.selectNextMessageProfile("profile-2")
        assertEquals(null, model.nextMessageProfileUid.value)
        val combined = createViewModel(combined = true)
        runCurrent()
        combined.selectNextMessageProfile("profile-1")
        assertEquals(null, combined.nextMessageProfileUid.value)
    }

    private fun completePersistedRuns() {
        messages.value = messages.value.map { message ->
            if (message.platformType != null) message.copy(content = "Finished response") else message
        }
        runs.value = runs.value.map { it.copy(status = AgentRunStatus.COMPLETED) }
    }

    private class PausableDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var paused = false
        private val pending = ArrayDeque<Pair<CoroutineContext, Runnable>>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (paused) pending.addLast(context to block) else delegate.dispatch(context, block)
        }
        fun resume() {
            paused = false
            while (pending.isNotEmpty()) {
                val (context, block) = pending.removeFirst()
                delegate.dispatch(context, block)
            }
        }
    }

    private fun createViewModel(combined: Boolean = false, availableProfileCount: Int = if (combined) 2 else 1, groupingDispatcher: CoroutineDispatcher = dispatcher, paletteGate: CompletableDeferred<Unit>? = null, freeProvider: dev.chungjungsoo.gptmobile.data.model.FreeAiProvider? = null): ChatViewModel {
        val profiles = (1..availableProfileCount).map { index ->
            PlatformV2(
                uid = "profile-$index",
                name = "Profile $index",
                enabled = true,
                compatibleType = ClientType.OPENROUTER,
                apiUrl = "https://openrouter.ai/api/v1",
                model = "model-$index"
            ).let { freeProvider?.applyTo(it) ?: it }
        }
        val members = profiles.take(if (combined) 2 else 1)
        val room = ChatRoomV2(
            id = 7,
            title = "Queue test",
            enabledPlatform = members.map { it.uid },
            activePlatform = members.map { it.uid },
            conversationMode = if (combined) ConversationMode.COMBINED else ConversationMode.STANDARD
        )
        messages.value = listOf(MessageV2(id = 1, chatId = 7, content = "First prompt", platformType = null)) + members.mapIndexed { index, profile ->
            MessageV2(id = index + 2, chatId = 7, content = "", platformType = profile.uid, currentRunId = "first-$index")
        }
        runs.value = members.mapIndexed { index, profile ->
            AgentRun("first-$index", 7, 1, index + 2, profile.uid, profile.compatibleType.name, profile.model, AgentRunStatus.RUNNING)
        }
        activeRuns.value = runs.value.associate { it.runId to ActiveAgentRun(it.runId, 7, it.profileUid, it.assistantMessageId) }
        val repository = mockk<ChatRepository>(relaxed = true)
        every { repository.observeMessagesV2(7) } returns messages
        every { repository.observeMessageWindow(7, any()) } returns messages
        every { repository.observeTurnCount(7) } returns messages.map { rows -> rows.count { it.platformType == null } }
        every { repository.observeAgentRuns(7) } returns runs
        every { repository.observeToolEvents(7) } returns flowOf(emptyList())
        coEvery { repository.fetchChatListV2() } returns listOf(room)
        coEvery { repository.fetchMessagesV2(7) } answers { messages.value }
        coEvery { repository.fetchChatPlatformModels(7) } returns profiles.associate { it.uid to it.model }
        // This queue fixture uses a pass-through planner; relaxed context mocks cannot be serialized.
        coEvery { repository.prepareCombinedTurn(any(), any(), any(), any(), any()) } answers {
            firstArg<CombinedExecutionContext>()
        }
        coEvery { repository.updateChatPlatforms(any(), any()) } coAnswers {
            membershipGate?.await()
            check(!failMembershipUpdate) { "Database unavailable" }
            val current = firstArg<ChatRoomV2>()
            val active = secondArg<List<String>>()
            current.copy(enabledPlatform = (current.enabledPlatform + active).distinct(), activePlatform = active)
        }
        coEvery { repository.persistAgentTurn(any()) } answers {
            val request = firstArg<PersistAgentTurnRequest>()
            submissions += request
            val user = request.userMessage.copy(id = nextId++, chatId = 7)
            val assistants = request.runs.map { draft ->
                MessageV2(id = nextId++, chatId = 7, content = "", platformType = draft.profileUid, currentRunId = draft.runId)
            }
            val persistedRuns = request.runs.zip(assistants).map { (draft, message) ->
                AgentRun(draft.runId, 7, user.id, message.id, draft.profileUid, draft.providerSnapshot, draft.modelSnapshot)
            }
            messages.value = messages.value + user + assistants
            runs.value = runs.value + persistedRuns
            PersistAgentTurnResult(request.chatRoom, user, assistants, persistedRuns)
        }
        coEvery { repository.persistAgentRetry(any()) } coAnswers {
            val request = firstArg<PersistAgentRetryRequest>()
            synthesisGate.await()
            val assistant = request.assistantMessage.copy(currentRunId = request.run.runId, content = "")
            val run = AgentRun(request.run.runId, 7, request.userMessage.id, assistant.id, request.run.profileUid, request.run.providerSnapshot, request.run.modelSnapshot)
            messages.value = messages.value.map { if (it.id == assistant.id) assistant else it }
            runs.value = runs.value + run
            PersistAgentRetryResult(assistant, run)
        }
        coEvery { repository.updateAgentMessage(any()) } coAnswers {
            val updated = firstArg<MessageV2>()
            messages.value = messages.value.map { if (it.id == updated.id) updated else it }
            if (updated.currentRunId?.startsWith("combined-") == true) synthesisMerged.complete(Unit)
        }
        coEvery { repository.finishQueuedAgentRun(any(), any(), any(), any()) } coAnswers {
            val id = firstArg<String>()
            if (id.startsWith("combined-")) synthesisCompletionGate?.await()
            runs.value = runs.value.map { if (it.runId == id) it.copy(status = AgentRunStatus.COMPLETED) else it }
            true
        }
        val settings = mockk<SettingRepository>(relaxed = true)
        coEvery { settings.fetchPlatformV2s() } returns profiles
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings()
        if (paletteGate != null) {
            coEvery { settings.mutateFeatureSettings(any()) } coAnswers {
                paletteGate.await()
                firstArg<(AppFeatureSettings) -> AppFeatureSettings>()(AppFeatureSettings())
            }
        }
        every { settings.observeDebugMode() } returns flowOf(false)
        every { settings.observeFeatureSettings() } returns flowOf(AppFeatureSettings(automaticConversationTitles = false))
        val coordinator = mockk<AgentRunCoordinator>(relaxed = true)
        every { coordinator.activeRuns } returns activeRuns
        every { coordinator.streamMessages } returns MutableStateFlow(emptyMap())
        every { coordinator.notices } returns MutableSharedFlow<AgentRunNotice>()
        every { coordinator.start(any()) } answers {
            val requests = firstArg<List<AgentRunRequest>>()
            starts += requests
            activeRuns.value = activeRuns.value + requests.associate { it.runId to ActiveAgentRun(it.runId, 7, it.platform.uid) }
            if (requests.any { it.runId.startsWith("combined-") }) {
                combinedSynthesisStarted.complete(Unit)
            } else {
                queuedTurnStarted.complete(Unit)
            }
        }
        val localModels = mockk<LocalModelRepository>(relaxed = true)
        every { localModels.observeAll() } returns flowOf(emptyList())
        val catalog = mockk<ModelCatalogRepository>(relaxed = true)
        coEvery { catalog.getCachedVisibleEntries() } returns emptyList()
        val tools = mockk<ToolConnectionRepository>(relaxed = true)
        coEvery { tools.getAllConnections() } returns listOf(
            ToolConnection("profile-2-tools", "research", "Research", ToolConnectionType.MCP, "https://example.com/mcp", ToolConnectionAuthType.NONE, null, null)
        )
        coEvery { tools.listBindingsByProfile("profile-2") } returns listOf(
            AgentToolBinding("binding", "profile-2", "profile-2-tools", "search")
        )
        val completions = mockk<dev.chungjungsoo.gptmobile.data.chat.GenerationCompletionStore>(relaxed = true)
        every { completions.items } returns MutableStateFlow(emptyList())
        return ChatViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf("chatRoomId" to 7, "enabledPlatforms" to members.joinToString(",") { it.uid })
            ),
            context = ApplicationProvider.getApplicationContext(),
            chatRepository = repository,
            settingRepository = settings,
            delegationRecoveryInteractions = mockk(relaxed = true),
            attachmentUploadCoordinator = mockk(relaxed = true),
            agentRunCoordinator = coordinator,
            toolConnectionRepository = tools,
            localModelRepository = localModels,
            modelCatalogRepository = catalog,
            freeModelToolConsentStore = dev.chungjungsoo.gptmobile.data.permissions.FreeModelToolConsentStore(
                ApplicationProvider.getApplicationContext()
            ),
            conversationReadStateStore = dev.chungjungsoo.gptmobile.data.chat.ConversationReadStateStore(completions),
            generationCompletionStore = completions,
            conversationExitWork = mockk(relaxed = true),
            messageGroupingDispatcher = groupingDispatcher
        ).also { store.put("chat", it) }
    }
}
