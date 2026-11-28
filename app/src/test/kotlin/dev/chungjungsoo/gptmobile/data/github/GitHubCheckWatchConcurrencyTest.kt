package dev.chungjungsoo.gptmobile.data.github

import androidx.work.WorkManager
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringApi
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringApiFactory
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.queue.DurablePromptQueue
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceDao
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRecord
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCheckWatchConcurrencyTest {
    private val head = "a".repeat(40)
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun olderPassedInspectionCannotOverwriteRetainedRerunOrStopScheduling() = runBlocking {
        withTimeout(5000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val reads = AtomicInteger()
            val fixture = fixture(
                api {
                    if (reads.incrementAndGet() == 1) {
                        entered.complete(Unit)
                        release.await()
                        run("completed", "success")
                    } else {
                        run("in_progress")
                    }
                }
            )
            val initial = fixture.retained()
            val older = async { fixture.repository.poll(initial.id, 0) }
            entered.await()
            assertFalse(fixture.repository.poll(initial.id, 0))
            val newer = fixture.retained()
            assertEquals("RUNNING", newer.status)
            assertEquals(initial.observationGeneration + 1, newer.observationGeneration)
            assertEquals(initial.headSha, newer.headSha)
            assertEquals(initial.startedAt, newer.startedAt)
            release.complete(Unit)
            assertFalse(older.await())
            assertEquals(newer, fixture.retained())
            coVerify(exactly = 1) { fixture.dao.save(any()) }
        }
    }

    @Test fun cancelledOrReplacedSubscriptionWinsOverAnInflightPassedResponse() = runBlocking {
        withTimeout(5000) {
            for (replacementStatus in listOf("CANCELLED", "RUNNING")) {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val fixture = fixture(
                    api {
                        entered.complete(Unit)
                        release.await()
                        run("completed", "success")
                    }
                )
                val initial = fixture.retained()
                val older = async { fixture.repository.poll(initial.id, 0) }
                entered.await()
                // Replacement deliberately keeps the same head and timestamps; the durable version wins.
                val replacement = initial.copy(status = replacementStatus, observationGeneration = initial.observationGeneration + 1)
                fixture.record.set(record(replacement))
                release.complete(Unit)
                assertEquals(replacement.terminal, older.await())
                assertEquals(replacement, fixture.retained())
                coVerify(exactly = 0) { fixture.dao.save(any()) }
            }
        }
    }

    @Test fun inlineDeadlineIncludesInitialHeadReadAndDoesNotCreateASubscription() = runBlocking {
        val reads = AtomicInteger()
        val fixture = fixture(
            api {
                reads.incrementAndGet()
                delay(2000)
                run("completed", "success")
            },
            temporary = true
        )
        val started = System.nanoTime()
        val result = withTimeout(4000) { fixture.repository.subscribe("connection", "profile", 1, inlineArguments(1)) }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertEquals(1, reads.get())
        assertTrue("The initial head read must consume the requested deadline: $elapsed ms", elapsed < 1800)
        assertEquals(JsonPrimitive("PENDING_TIMEOUT"), result["wait_outcome"])
        assertEquals(JsonPrimitive(0), result["observations"])
        assertEquals(JsonPrimitive(false), result["head_binding_verified"])
        assertEquals(JsonPrimitive(false), result["background_subscription"])
        assertFalse("head_sha" in result)
        assertFalse("watch_id" in result)
        coVerify(exactly = 0) { fixture.dao.save(any()) }
    }

    @Test fun inlineCompletedObservationKeepsExistingBackgroundStateUntouched() = runBlocking {
        val fixture = fixture(api { run("completed", "success") }, temporary = true)
        val initial = fixture.retained()
        val result = fixture.repository.subscribe("connection", "profile", 1, inlineArguments(30))
        assertEquals(JsonPrimitive("PASSED"), result["status"])
        assertEquals(JsonPrimitive("TERMINAL_OBSERVED"), result["wait_outcome"])
        assertEquals(JsonPrimitive(head), result["head_sha"])
        assertEquals(JsonPrimitive(true), result["head_binding_verified"])
        assertEquals(JsonPrimitive(false), result["background_subscription"])
        assertEquals(initial, fixture.retained())
        coVerify(exactly = 0) { fixture.dao.save(any()) }
    }

    @Test fun commitOnlyWatchVerifiesTheRemoteObjectBeforeObservingAllChecks() = runBlocking {
        var returnedHead = head
        val paths = mutableListOf<String>()
        val repository = fixture(
            object : GitHubEngineeringApi({ "token" }) {
                override suspend fun request(path: String, method: String, body: JSONObject?): String {
                    paths += path
                    return when (path) {
                        "/repos/owner/repo/git/commits/$head" -> """{"sha":"$returnedHead"}"""
                        "/repos/owner/repo/commits/$head/check-runs?per_page=100&page=1" -> """{"total_count":1,"check_runs":[{"id":1,"status":"completed","conclusion":"success"}]}"""
                        "/repos/owner/repo/commits/$head/status?per_page=100" -> """{"total_count":0,"statuses":[]}"""
                        else -> error("Unexpected request $path")
                    }
                }
            },
            temporary = true
        ).repository
        val arguments = buildJsonObject {
            put("owner", "owner")
            put("repo", "repo")
            put("commit_sha", head)
            put("all_checks", true)
            put("wait_timeout_seconds", 2)
        }
        val result = repository.subscribe("connection", "profile", 1, arguments)
        assertEquals(JsonPrimitive("PASSED"), result["status"])
        assertEquals(JsonPrimitive(head), result["head_sha"])
        assertEquals(JsonPrimitive(false), result["background_subscription"])
        returnedHead = "b".repeat(40)
        paths.clear()
        assertTrue(runCatching { repository.subscribe("connection", "profile", 1, arguments) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(listOf("/repos/owner/repo/git/commits/$head"), paths)
    }

    @Test fun temporaryChatBackgroundWatchReturnsAnActionableReasonBeforeNetworkAccess() = runBlocking {
        val fixture = fixture(api { error("Background watch must not send a request") }, temporary = true)
        val arguments = JsonObject(inlineArguments(1) - "wait_timeout_seconds")
        val failure = runCatching { fixture.repository.subscribe("connection", "profile", 1, arguments) }.exceptionOrNull()
        assertEquals("USER_INPUT_REQUIRED", (failure as? GitHubReadValidation)?.code)
        assertTrue(failure?.message.orEmpty().contains("wait_timeout_seconds"))
    }

    @Test fun checkWatchUsesTheLiveGitHubChatPolicyForItsBoundRepository() = runBlocking {
        val scope = GitHubConversationContext("connection", "owner", "repo", "main", head, "profile")
        val features = AppFeatureSettings(
            toolPluginStates = mapOf(ToolPluginId.GITHUB to true),
            conversationGitHub = mapOf(1 to scope)
        )
        val profile = PlatformV2(uid = "profile", name = "Profile")
        val reads = AtomicInteger(0)
        val fixture = fixture(
            api {
                reads.incrementAndGet()
                run("completed", "success")
            },
            features = features,
            profile = profile
        )
        val result = fixture.repository.subscribe("connection", "profile", 1, inlineArguments(5))
        assertEquals(JsonPrimitive("PASSED"), result["status"])
        assertTrue(reads.get() > 0)

        val priorReads = reads.get()
        val unrelated = JsonObject(inlineArguments(5) + ("repo" to JsonPrimitive("another-repo")))
        val unrelatedFailure = runCatching { fixture.repository.subscribe("connection", "profile", 1, unrelated) }.exceptionOrNull()
        assertEquals("PERMISSION_DENIED", (unrelatedFailure as? GitHubReadValidation)?.code)

        coEvery { fixture.settings.getFeatureSettings() } returns features.copy(
            githubProfileOverrides = mapOf("profile" to GitHubChatProfileOverride(githubTools = false))
        )
        val disabledFailure = runCatching { fixture.repository.subscribe("connection", "profile", 1, inlineArguments(5)) }.exceptionOrNull()
        assertEquals("PERMISSION_DENIED", (disabledFailure as? GitHubReadValidation)?.code)
        coEvery { fixture.settings.getFeatureSettings() } returns features.copy(toolPluginStates = mapOf(ToolPluginId.GITHUB to false))
        val pluginFailure = runCatching { fixture.repository.subscribe("connection", "profile", 1, inlineArguments(5)) }.exceptionOrNull()
        assertEquals("PERMISSION_DENIED", (pluginFailure as? GitHubReadValidation)?.code)
        coEvery { fixture.settings.getFeatureSettings() } returns features
        coEvery { fixture.settings.fetchPlatformV2s() } returns listOf(profile.copy(disableRemoteTools = true))
        val remoteFailure = runCatching { fixture.repository.subscribe("connection", "profile", 1, inlineArguments(5)) }.exceptionOrNull()
        assertEquals("PERMISSION_DENIED", (remoteFailure as? GitHubReadValidation)?.code)
        assertEquals(priorReads, reads.get())
    }

    @Test fun serializedGenerationsSurviveRestartAndLegacyRecordsAdvanceSafely() {
        val legacy = watch()
        val restoredLegacy = json.decodeFromString<GitHubCheckWatch>(json.encodeToString(legacy))
        assertEquals(0L, restoredLegacy.observationGeneration)
        val next = restoredLegacy.copy(observationGeneration = GitHubWatchObservationFence.nextGeneration(restoredLegacy))
        val restoredNext = json.decodeFromString<GitHubCheckWatch>(json.encodeToString(next))
        assertEquals(1L, restoredNext.observationGeneration)
        assertFalse(GitHubWatchObservationFence.accepts(legacy, restoredNext))
        assertTrue(GitHubWatchObservationFence.accepts(next, restoredNext))
    }

    @Test fun legacyTimestampOmissionUsesDurableRowTimeAndSurvivesRepositoryRestart() = runBlocking {
        val response = api {
            delay(10)
            run("in_progress")
        }
        val fixture = fixture(response)
        val original = fixture.record.get()
        val retainedAt = System.currentTimeMillis() - 1000
        fixture.record.set(
            original.copy(
                payload = JsonObject(json.parseToJsonElement(original.payload).jsonObject - setOf("startedAt", "updatedAt")).toString(),
                updatedAt = retainedAt
            )
        )
        assertFalse(fixture.repository.poll(original.id, 0))
        val first = fixture.retained()
        assertEquals(1L, first.observationGeneration)
        assertEquals(retainedAt, first.startedAt)

        val restarted = fixture(response, initial = fixture.record.get().copy(payload = json.encodeToString(first.copy(retryAt = 0))))
        assertFalse(restarted.repository.poll(original.id, 0))
        assertEquals(2L, restarted.retained().observationGeneration)
        assertEquals(retainedAt, restarted.retained().startedAt)
    }

    private fun inlineArguments(seconds: Int): JsonObject = buildJsonObject {
        put("owner", "owner")
        put("repo", "repo")
        put("run_id", 42)
        put("wait_timeout_seconds", seconds)
    }

    private fun api(expectedPath: String = "/repos/owner/repo/actions/runs/42", response: suspend () -> String) = object : GitHubEngineeringApi({ "token" }) {
        override suspend fun request(path: String, method: String, body: JSONObject?): String {
            assertEquals(expectedPath, path)
            return response()
        }
    }

    @Test fun wholeCommitWatchWaitsForTheOtherWorkflowAfterAnchorSucceeds() = runBlocking {
        var secondStatus = "in_progress"
        var secondConclusion: String? = null
        val aggregateApi = object : GitHubEngineeringApi({ "token" }) {
            override suspend fun request(path: String, method: String, body: JSONObject?): String = when (path) {
                "/repos/owner/repo/commits/$head/check-runs?per_page=100&page=1" -> {
                    val second = buildJsonObject {
                        put("id", 2)
                        put("status", secondStatus)
                        secondConclusion?.let { put("conclusion", it) }
                    }
                    """{"total_count":2,"check_runs":[{"id":1,"status":"completed","conclusion":"success"},$second]}"""
                }
                "/repos/owner/repo/commits/$head/status?per_page=100" -> """{"total_count":0,"statuses":[]}"""
                else -> error("Unexpected watch request $path")
            }
        }
        val repository = fixture(aggregateApi).repository
        val watch = watch().copy(allChecks = true)
        val pending = repository.inspect(watch, aggregateApi)
        assertEquals("RUNNING", pending.status)
        assertFalse(pending.terminal)
        secondStatus = "completed"
        secondConclusion = "failure"
        val failed = repository.inspect(pending, aggregateApi)
        assertEquals("FAILED", failed.status)
        assertTrue(failed.terminal)
    }

    @Test fun terminalAggregateResumesOnlyItsBoundSourceOnceAndIgnoresAChangedHead() = runBlocking {
        val profile = PlatformV2(uid = "profile", name = "CI", model = "tester")
        val scope = GitHubConversationContext("connection", "owner", "repo", "main", head, "profile")
        val task = GitHubCiWatchTask("original", 10, "main", "tester", GitHubApprovalBinding.profileContract(profile.compatibleType.name, profile.apiUrl, profile.providerConnectionUid, profile.reasoning))
        val terminal = watch().copy(allChecks = true, waitingTasks = listOf(task), status = "PASSED")
        var live = head
        val fixture = fixture(api("/repos/owner/repo/commits/main") { """{"sha":"$live"}""" }, initial = record(terminal), profile = profile, features = AppFeatureSettings(conversationGitHub = mapOf(1 to scope)))
        val db = fixture.workspace.database
        var state = AgentRunStatus.RUNNING
        coEvery { db.agentRunDao().getById("original") } answers { AgentRun("original", 1, 10, 11, "profile", "OPENAI", "tester", state) }
        coEvery { db.agentPersistenceDao().rawRecoveryMessage(10) } returns MessageV2(id = 10, chatId = 1, platformType = null, content = "Diagnose CI failures")
        coEvery { db.agentPersistenceDao().getChatRoom(1) } returns ChatRoomV2(id = 1, title = "CI", activePlatform = listOf("profile"))
        val queue = mockk<DurablePromptQueue>(relaxed = true)
        val lazyQueue = dagger.Lazy { queue }
        assertFalse(fixture.repository.deliverTerminalTasks(terminal.id, lazyQueue))
        state = AgentRunStatus.COMPLETED
        assertTrue(fixture.repository.deliverTerminalTasks(terminal.id, lazyQueue))
        assertTrue(fixture.repository.deliverTerminalTasks(terminal.id, lazyQueue))
        live = "b".repeat(40)
        fixture.record.set(record(terminal))
        assertTrue(fixture.repository.deliverTerminalTasks(terminal.id, lazyQueue))
        coVerify(exactly = 1) {
            queue.enqueue(1, "Continue", match { it.recovery?.sourceMessageId == 10 && it.tools.github?.commitSha == head }, "github-ci-task:${terminal.id}:$head:10")
        }
    }

    @Test fun stopWinsWhileTerminalTaskHeadReadIsSuspended() = runBlocking {
        mockkObject(WorkManager.Companion, GitHubWatchNotifications)
        try {
            val manager = mockk<WorkManager>(relaxed = true)
            every { WorkManager.getInstance(any()) } returns manager
            every { GitHubWatchNotifications.publish(any(), any()) } returns Unit
            withTimeout(5000) {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val (fixture, terminal) = terminalTaskFixture(
                    api("/repos/owner/repo/commits/main") {
                        entered.complete(Unit)
                        release.await()
                        """{"sha":"$head"}"""
                    }
                )
                val queue = mockk<DurablePromptQueue>(relaxed = true)
                val delivery = async { fixture.repository.deliverTerminalTasks(terminal.id, dagger.Lazy { queue }) }
                entered.await()
                val stopping = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.cancel(terminal.id) }
                try {
                    assertTrue("Stop must not wait for the suspended GitHub response", stopping.isCompleted)
                    assertEquals("CANCELLED", fixture.retained().status)
                } finally {
                    release.complete(Unit)
                }
                stopping.await()
                assertTrue(delivery.await())
                assertEquals("CANCELLED", fixture.retained().status)
                coVerify(exactly = 0) { queue.enqueue(any(), any(), any(), any()) }
            }
        } finally {
            unmockkObject(WorkManager.Companion, GitHubWatchNotifications)
        }
    }

    @Test fun changedTerminalTasksAreRetriedWithoutConsumingAReplacementWatch() = runBlocking {
        withTimeout(5000) {
            for (replacementStatus in listOf("PASSED", "RUNNING")) {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val (fixture, terminal) = terminalTaskFixture(
                    api("/repos/owner/repo/commits/main") {
                        entered.complete(Unit)
                        release.await()
                        """{"sha":"$head"}"""
                    }
                )
                val queue = mockk<DurablePromptQueue>(relaxed = true)
                val delivery = async { fixture.repository.deliverTerminalTasks(terminal.id, dagger.Lazy { queue }) }
                entered.await()
                val replacement = terminal.copy(
                    status = replacementStatus,
                    observationGeneration = terminal.observationGeneration + 1,
                    waitingTasks = listOf(terminal.waitingTasks.single().copy(runId = "replacement", sourceMessageId = 20))
                )
                fixture.record.set(record(replacement))
                release.complete(Unit)
                // The same terminal watch needs a delivery retry; an active replacement owns its poll chain.
                assertEquals(replacementStatus != "PASSED", delivery.await())
                assertEquals(replacement, fixture.retained())
                coVerify(exactly = 0) { queue.enqueue(any(), any(), any(), any()) }
                coVerify(exactly = 0) { fixture.dao.save(any()) }
            }
        }
    }

    @Test fun terminalTaskRechecksRunRoomProfileAndPermissionAfterTheRemoteRead() = runBlocking {
        withTimeout(5000) {
            for (change in listOf("model", "permission", "run_status", "archived_room")) {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val (fixture, terminal) = terminalTaskFixture(
                    api("/repos/owner/repo/commits/main") {
                        entered.complete(Unit)
                        release.await()
                        """{"sha":"$head"}"""
                    }
                )
                val queue = mockk<DurablePromptQueue>(relaxed = true)
                val delivery = async { fixture.repository.deliverTerminalTasks(terminal.id, dagger.Lazy { queue }) }
                entered.await()
                when (change) {
                    "model" -> coEvery { fixture.settings.fetchPlatformV2s() } returns listOf(PlatformV2(uid = "profile", name = "CI", model = "changed"))
                    "permission" -> {
                        val scope = GitHubConversationContext("connection", "owner", "repo", "main", head, "profile")
                        coEvery { fixture.settings.getFeatureSettings() } returns AppFeatureSettings(
                            conversationGitHub = mapOf(1 to scope),
                            githubProfileOverrides = mapOf("profile" to GitHubChatProfileOverride(githubTools = false))
                        )
                    }
                    "run_status" -> coEvery { fixture.workspace.database.agentRunDao().getById("original") } returns AgentRun("original", 1, 10, 11, "profile", "OPENAI", "tester", AgentRunStatus.RUNNING)
                    "archived_room" -> coEvery { fixture.workspace.database.agentPersistenceDao().getChatRoom(1) } returns ChatRoomV2(id = 1, title = "CI", activePlatform = listOf("profile"), isArchived = true)
                }
                release.complete(Unit)
                assertEquals(change != "run_status", delivery.await())
                coVerify(exactly = 0) { queue.enqueue(any(), any(), any(), any()) }
            }
        }
    }

    private fun terminalTaskFixture(api: GitHubEngineeringApi): Pair<Fixture, GitHubCheckWatch> {
        val profile = PlatformV2(uid = "profile", name = "CI", model = "tester")
        val scope = GitHubConversationContext("connection", "owner", "repo", "main", head, "profile")
        val task = GitHubCiWatchTask("original", 10, "main", "tester", GitHubApprovalBinding.profileContract(profile.compatibleType.name, profile.apiUrl, profile.providerConnectionUid, profile.reasoning))
        val terminal = watch().copy(allChecks = true, waitingTasks = listOf(task), status = "PASSED")
        val fixture = fixture(api, initial = record(terminal), profile = profile, features = AppFeatureSettings(conversationGitHub = mapOf(1 to scope)))
        val db = fixture.workspace.database
        coEvery { db.agentRunDao().getById("original") } returns AgentRun("original", 1, 10, 11, "profile", "OPENAI", "tester", AgentRunStatus.COMPLETED)
        coEvery { db.agentPersistenceDao().rawRecoveryMessage(10) } returns MessageV2(id = 10, chatId = 1, platformType = null, content = "Diagnose CI failures")
        coEvery { db.agentPersistenceDao().getChatRoom(1) } returns ChatRoomV2(id = 1, title = "CI", activePlatform = listOf("profile"))
        return fixture to terminal
    }

    private fun run(status: String, conclusion: String? = null): String = buildJsonObject {
        put("head_sha", head)
        put("status", status)
        conclusion?.let { put("conclusion", it) }
    }.toString()

    private fun watch() = GitHubCheckWatch("watch", 1, "connection", "profile", "owner", "repo", runId = 42, headSha = head, connectionUpdatedAt = 1)
    private fun record(watch: GitHubCheckWatch) = WorkspaceRecord(watch.id, "github_checks", "watch", json.encodeToString(watch), 1)

    private fun fixture(
        api: GitHubEngineeringApi,
        temporary: Boolean = false,
        initial: WorkspaceRecord = record(watch()),
        features: AppFeatureSettings = AppFeatureSettings(),
        profile: PlatformV2 = PlatformV2(uid = "profile", name = "Profile")
    ): Fixture {
        val workspace = mockk<WorkspaceRepository>(relaxed = true)
        val stored = AtomicReference(initial)
        val dao = mockk<WorkspaceDao>()
        every { workspace.dao } returns dao
        every { workspace.json } returns json
        coEvery { dao.get(any()) } answers { stored.get().takeIf { it.id == firstArg<String>() } }
        coEvery { dao.save(any()) } answers { stored.set(firstArg()) }
        coEvery { workspace.database.chatRoomDao().getChatRoomsByIds(listOf(1)) } returns listOf(mockk<ChatRoomV2> { every { isTemporary } returns temporary })
        val connection = mockk<ToolConnection> {
            every { type } returns "GITHUB"
            every { updatedAt } returns 1
            every { authType } returns "BEARER"
            every { toolPolicy } returns "ASK_WRITES"
            every { approvedReadTools } returns ""
            every { endpointUrl } returns null
        }
        val connections = mockk<ToolConnectionRepository> { coEvery { getConnection("connection") } returns connection }
        val settings = mockk<SettingRepository> {
            coEvery { fetchPlatformV2s() } returns listOf(profile)
            coEvery { getFeatureSettings() } returns features
        }
        val factory = mockk<GitHubEngineeringApiFactory> { every { createForEndpoint(any(), any()) } returns api }
        val auth = mockk<GitHubPublicAuthCoordinator> {
            coEvery { accessToken(connection) } returns "fixture-token"
            coEvery { durableIdentity("connection") } returns "verified-test-account"
        }
        return Fixture(GitHubCheckWatchRepository(mockk(relaxed = true), workspace, connections, settings, auth, factory), workspace, dao, stored, settings)
    }

    private inner class Fixture(val repository: GitHubCheckWatchRepository, val workspace: WorkspaceRepository, val dao: WorkspaceDao, val record: AtomicReference<WorkspaceRecord>, val settings: SettingRepository) {
        fun retained(): GitHubCheckWatch = json.decodeFromString(record.get().payload)
    }
}
