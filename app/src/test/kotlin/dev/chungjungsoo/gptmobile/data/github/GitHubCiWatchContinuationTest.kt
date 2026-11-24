package dev.chungjungsoo.gptmobile.data.github

import android.app.Application
import android.content.Context
import androidx.room.Room
import dagger.Lazy
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.agent.AgentRunRequest
import dev.chungjungsoo.gptmobile.data.agent.resolveTaskRecovery
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringApi
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringApiFactory
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.GatewaySubmissionRecord
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnResult
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityRepository
import dev.chungjungsoo.gptmobile.data.queue.DurablePromptQueue
import dev.chungjungsoo.gptmobile.data.queue.GatewayQueueStore
import dev.chungjungsoo.gptmobile.data.queue.PendingPrompt
import dev.chungjungsoo.gptmobile.data.queue.PendingPromptDao
import dev.chungjungsoo.gptmobile.data.queue.PendingPromptPayload
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRecord
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GitHubCiWatchContinuationTest {
    @Test fun terminalWatchCrossesTheRealQueueAndRoomBoundaryOnlyOnceWithoutRenewingItsTask() = runBlocking {
        val fixture = Fixture(this)
        try {
            fixture.initialize()
            fixture.enqueue()
            val queued = fixture.pending()
            assertFalse(GitHubNativeContinuation.matches(queued.text))
            assertNotNull(queued.details().githubCiWatch)
            fixture.release.complete(Unit)
            val request = withTimeout(5_000) { fixture.started.await() }.single()
            val consumed = requireNotNull(fixture.database.pendingPromptDao().get(queued.id))
            val owner = fixture.database.agentPersistenceDao().getMessages(1).single { it.id == request.assistantMessage.id }
            val notice = owner.timeline.single { it.recovery != null }
            val descriptor = requireNotNull(notice.recovery)
            val resumed = requireNotNull(resolveTaskRecovery(request.userMessages, descriptor))

            assertEquals(request.runId, descriptor.generationId)
            assertEquals("1:${fixture.original.userMessage.id}", descriptor.rootWorkId)
            assertEquals(fixture.original.userMessage.id, resumed.sourceMessageId)
            assertEquals("Diagnose CI failures", resumed.originalRequest)
            assertFalse(resumed.renewsToolBudget)
            assertFalse(resumed.retryUndispatchedBudgetFailures)
            assertFalse(descriptor.allowNewToolBudget)
            assertFalse(descriptor.allowUndispatchedBudgetRetry)
            assertEquals("Continuing the saved GitHub diagnosis after its watched checks finished.", notice.content)
            val tools = requireNotNull(request.chatToolConfig)
            assertEquals(fixture.head, tools.github?.commitSha)
            assertEquals(queued.details().tools, tools)
            assertNotNull(consumed.userMessageId)
            assertFalse(consumed.paused)
            assertTrue(fixture.database.pendingPromptDao().observePending().first().isEmpty())
            assertEquals(6, fixture.database.agentPersistenceDao().getMessages(1).size)
            assertEquals(1, fixture.persistenceCalls.get())
            assertEquals(1, fixture.headReads.get())

            assertTrue(fixture.watches.deliverTerminalTasks(fixture.watch.id, Lazy { fixture.queue }))
            fixture.queue.enqueue(1, queued.text, queued.details(), fixture.key())
            assertEquals(consumed, fixture.database.pendingPromptDao().get(queued.id))
            assertEquals(1, fixture.database.pendingPromptDao().forChat(1).size)
            assertEquals(1, fixture.starts.get())
            assertEquals(1, fixture.persistenceCalls.get())
        } finally {
            fixture.close()
        }
    }

    @Test fun delayedQueueRechecksAccountPermissionProfileRepositoryAndWatchIdentity() = runBlocking {
        for (change in listOf("account", "permission", "profile", "repository", "watch")) {
            val fixture = Fixture(this)
            try {
                fixture.initialize()
                fixture.enqueue()
                when (change) {
                    "account" -> fixture.account = "different-account"
                    "permission" -> fixture.connection = fixture.connection.copy(approvedReadTools = "different-permission")
                    "profile" -> fixture.profiles.value = listOf(fixture.profiles.value.single().copy(apiUrl = "https://different.example/v1"))
                    "repository" -> fixture.features.value = fixture.features.value.copy(conversationGitHub = mapOf(1 to fixture.scope.copy(repo = "other-repository")))
                    "watch" -> fixture.saveWatch(fixture.watch.copy(headSha = "b".repeat(40)))
                }
                fixture.release.complete(Unit)
                fixture.assertPausedBeforeRun()
                assertEquals(change, 0, fixture.persistenceCalls.get())
                assertEquals(change, 1, fixture.headReads.get())
            } finally {
                fixture.close()
            }
        }
    }

    @Test fun transactionRejectsChangedTypedRecoveryAndKeepsTheNativeOnlyContinueFence() = runBlocking {
        for (change in listOf("untyped", "new-budget", "retry-budget", "source", "watch", "model")) {
            val fixture = Fixture(this)
            try {
                fixture.initialize()
                fixture.enqueue()
                fixture.beforePersistence = { request ->
                    val queued = fixture.pending()
                    val payload = queued.details()
                    val descriptor = requireNotNull(payload.recovery)
                    val changed = when (change) {
                        "untyped" -> payload.copy(githubCiWatch = null)
                        "new-budget" -> payload.copy(recovery = descriptor.copy(allowNewToolBudget = true))
                        "retry-budget" -> payload.copy(recovery = descriptor.copy(allowUndispatchedBudgetRetry = true))
                        "source" -> payload.copy(recovery = descriptor.copy(sourceMessageId = fixture.unrelated.userMessage.id, rootWorkId = "1:${fixture.unrelated.userMessage.id}"))
                        else -> payload
                    }
                    if (change == "watch") fixture.saveWatch(fixture.watch.copy(accountScopeHash = "f".repeat(64)))
                    if (changed != payload) fixture.replacePayload(queued, changed)
                    if (change == "model") request.copy(runs = request.runs.map { it.copy(modelSnapshot = "other-model") }) else request
                }
                fixture.release.complete(Unit)
                fixture.assertPausedBeforeRun()
                assertEquals(change, 1, fixture.persistenceCalls.get())
                assertFalse(GitHubNativeContinuation.matches("Continue"))
            } finally {
                fixture.close()
            }
        }
    }

    @Test fun bufferedContinuationCannotRetryAfterRoomPausesIt() = runBlocking {
        val observed = MutableStateFlow(emptyList<PendingPrompt>())
        val fixture = Fixture(this)
        try {
            fixture.initialize(observed)
            val dao = fixture.database.pendingPromptDao()
            val accepted = fixture.watch.waitingTasks.single().currentDelivery(fixture.watch, fixture.database, fixture.settings) as GitHubCiWatchTask.Delivery.Ready
            val id = UUID.nameUUIDFromBytes("1:${fixture.key()}".toByteArray(Charsets.UTF_8)).toString()
            dao.enqueue(PendingPrompt(id, 1, "Continue", Json.encodeToString(accepted.payload.copy(githubCiWatch = null)), 0))
            val earlier = fixture.pending()
            dao.pauseUnconsumed(earlier.id, true)

            val other = ChatRoomV2(id = 2, title = "Other queued chat", enabledPlatform = listOf("profile"))
            fixture.database.chatRoomDao().addChatRoom(other)
            val next = PendingPrompt("other-prompt", other.id, "Start the other chat", Json.encodeToString(PendingPromptPayload(profileUids = listOf("profile"))), 0)
            dao.enqueue(next)
            // The dispatcher receives the pre-pause row and a valid new row in that order.
            observed.value = listOf(earlier, requireNotNull(dao.get(next.id)))
            fixture.queue.start()
            fixture.release.complete(Unit)
            val run = withTimeout(5_000) { fixture.started.await() }.single()
            assertEquals(other.id, run.chatId)
            assertEquals(1, fixture.persistenceCalls.get())
            assertNotNull(dao.get(next.id)?.userMessageId)
            val paused = requireNotNull(dao.get(earlier.id))
            assertTrue(paused.paused)
            assertNull(paused.userMessageId)
        } finally {
            fixture.close()
        }
    }

    private class Fixture(private val owner: CoroutineScope) {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        val head = "a".repeat(40)
        val scope = GitHubConversationContext("connection", "owner", "repo", "main", head, "profile")
        val profiles = MutableStateFlow(listOf(PlatformV2(uid = "profile", name = "CI", model = "tester")))
        val features = MutableStateFlow(AppFeatureSettings(conversationGitHub = mapOf(1 to scope)))
        var account = "verified-account"
        var connection = ToolConnection("connection", "GitHub", "github", "GITHUB", null, "BEARER", null, null, updatedAt = 1)
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<List<AgentRunRequest>>()
        val starts = AtomicInteger()
        val persistenceCalls = AtomicInteger()
        val headReads = AtomicInteger()
        lateinit var original: PersistAgentTurnResult
        lateinit var unrelated: PersistAgentTurnResult
        lateinit var watch: GitHubCheckWatch
        lateinit var watches: GitHubCheckWatchRepository
        lateinit var settings: SettingRepository
        lateinit var queue: DurablePromptQueue
        var beforePersistence: suspend (PersistAgentTurnRequest) -> PersistAgentTurnRequest = { it }

        suspend fun initialize(observed: MutableStateFlow<List<PendingPrompt>>? = null) {
            val context = RuntimeEnvironment.getApplication()
            context.getSharedPreferences("prompt_queue", Context.MODE_PRIVATE).edit().clear().commit()
            StartupRecoveryGate.start(owner) { release.await() }
            val room = ChatRoomV2(id = 1, title = "CI", enabledPlatform = listOf("profile"))
            database.chatRoomDao().addChatRoom(room)
            suspend fun turn(text: String, id: String): PersistAgentTurnResult {
                val profile = profiles.value.single()
                val result = database.agentPersistenceDao().persistAgentTurn(PersistAgentTurnRequest(room, MessageV2(content = text, platformType = null), listOf(AgentRunDraft(id, profile.uid, profile.compatibleType.name, profile.model)), mapOf(profile.uid to profile.model)))
                database.agentRunDao().updateStatus(id, "COMPLETED", 1, 2, null)
                return result
            }
            original = turn("Diagnose CI failures", "original")
            unrelated = turn("Inspect a different pull request", "unrelated")
            settings = mockk<SettingRepository> {
                coEvery { fetchPlatformV2s() } answers { profiles.value }
                coEvery { getFeatureSettings() } answers { features.value }
                every { observePlatformV2s() } returns profiles
                every { observeFeatureSettings() } returns features
            }
            val task = requireNotNull(GitHubCiWatchTask.capture("original", 1, "profile", "owner", "repo", database, settings))
            watch = GitHubCheckWatch(
                "watch", 1, "connection", "profile", "owner", "repo", runId = 42, headSha = head, connectionUpdatedAt = 1,
                startedAt = 1, status = "PASSED", allChecks = true, waitingTasks = listOf(task),
                permissionRevision = GitHubAuthorizationIdentity.permission(connection.authType, connection.toolPolicy, connection.approvedReadTools, connection.endpointUrl),
                accountScopeHash = GitHubRepositoryStateStore.digest(account)
            )
            saveWatch(watch)
            val api = object : GitHubEngineeringApi({ "fixture-token" }) {
                override suspend fun request(path: String, method: String, body: JSONObject?): String {
                    assertEquals("/repos/owner/repo/commits/main", path)
                    assertEquals("GET", method)
                    assertNull(body)
                    headReads.incrementAndGet()
                    return """{"sha":"$head"}"""
                }
            }
            val connections = mockk<ToolConnectionRepository> { coEvery { getConnection("connection") } answers { connection } }
            val auth = mockk<GitHubPublicAuthCoordinator> {
                coEvery { durableIdentity("connection") } answers { account }
                coEvery { accessToken(any()) } returns "fixture-token"
            }
            val factory = mockk<GitHubEngineeringApiFactory> { every { createForEndpoint(any(), any()) } returns api }
            watches = GitHubCheckWatchRepository(context, WorkspaceRepository(database, mockk(relaxed = true)), connections, settings, auth, factory)
            val chats = mockk<ChatRepository> {
                coEvery { fetchMessagesV2(any()) } coAnswers { database.agentPersistenceDao().getMessages(firstArg()) }
                coEvery { fetchChatPlatformModels(any()) } returns mapOf("profile" to "tester")
                coEvery { persistAgentTurn(any()) } coAnswers {
                    persistenceCalls.incrementAndGet()
                    database.agentPersistenceDao().persistAgentTurn(beforePersistence(firstArg()))
                }
            }
            val coordinator = mockk<AgentRunCoordinator> {
                every { activeRuns } returns MutableStateFlow(emptyMap())
                every { start(any()) } answers {
                    starts.incrementAndGet()
                    started.complete(firstArg())
                    Unit
                }
            }
            val store = mockk<GatewayQueueStore>(relaxed = true) { every { active } returns MutableStateFlow(emptyList<GatewaySubmissionRecord>()) }
            val availability = mockk<GatewayAvailabilityRepository> { every { observe(any()) } returns flowOf(emptyMap()) }
            val queueDatabase = if (observed == null) database else mockk<ChatDatabaseV2> {
                every { pendingPromptDao() } returns object : PendingPromptDao by database.pendingPromptDao() {
                    override fun observePending() = observed
                }
                every { agentPersistenceDao() } returns database.agentPersistenceDao()
                every { gatewayJobDao() } returns database.gatewayJobDao()
                every { agentRunDao() } returns database.agentRunDao()
            }
            queue = DurablePromptQueue(queueDatabase, chats, settings, coordinator, context, store, availability, mockk(relaxed = true), mockk(relaxed = true), Lazy { watches })
        }

        fun key() = "github-ci-task:${watch.id}:$head:${original.userMessage.id}"

        suspend fun enqueue() {
            assertTrue(watches.deliverTerminalTasks(watch.id, Lazy { queue }))
            assertTrue(Json.decodeFromString<GitHubCheckWatch>(requireNotNull(database.workspaceDao().get(watch.id)).payload).waitingTasks.isEmpty())
        }

        suspend fun saveWatch(value: GitHubCheckWatch) = database.workspaceDao().save(WorkspaceRecord(value.id, "github_checks", "CI", Json.encodeToString(value), 1))

        suspend fun pending(): PendingPrompt = database.pendingPromptDao().forChat(1).single()

        suspend fun replacePayload(prompt: PendingPrompt, payload: PendingPromptPayload) {
            assertEquals(1, database.pendingPromptDao().replaceUnconsumed(prompt.id, prompt.text, prompt.payload, prompt.text, Json.encodeToString(payload)))
        }

        suspend fun assertPausedBeforeRun() {
            val saved = withTimeout(5_000) { database.pendingPromptDao().observePending().first { it.singleOrNull()?.paused == true }.single() }
            assertNull(saved.userMessageId)
            assertEquals(0, starts.get())
            assertFalse(started.isCompleted)
            assertEquals(4, database.agentPersistenceDao().getMessages(1).size)
        }

        suspend fun close() {
            release.complete(Unit)
            if (::queue.isInitialized) {
                // The real singleton dispatcher owns its IO job; join it before closing this fixture's Room database.
                val field = DurablePromptQueue::class.java.getDeclaredField("scope").apply { isAccessible = true }
                val queueScope = field.get(queue) as CoroutineScope
                requireNotNull(queueScope.coroutineContext[Job]).cancelAndJoin()
            }
            database.close()
        }
    }
}
