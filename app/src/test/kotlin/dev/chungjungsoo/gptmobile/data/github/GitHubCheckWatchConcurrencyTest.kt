package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringApi
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringApiFactory
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceDao
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRecord
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
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

    @Test fun checkWatchUsesTheLiveGitHubChatPolicyForItsBoundRepository() = runBlocking {
        val scope = GitHubConversationContext("connection", "owner", "repo", "main", head, "profile")
        val features = AppFeatureSettings(
            toolPluginStates = mapOf(ToolPluginId.GITHUB to false),
            conversationGitHub = mapOf(1 to scope)
        )
        val profile = PlatformV2(uid = "profile", name = "Profile", disableRemoteTools = true)
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

    private fun api(response: suspend () -> String) = object : GitHubEngineeringApi({ "token" }) {
        override suspend fun request(path: String, method: String, body: JSONObject?): String {
            assertEquals("/repos/owner/repo/actions/runs/42", path)
            return response()
        }
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
        return Fixture(GitHubCheckWatchRepository(mockk(relaxed = true), workspace, connections, settings, mockk(), factory), workspace, dao, stored, settings)
    }

    private inner class Fixture(val repository: GitHubCheckWatchRepository, val workspace: WorkspaceRepository, val dao: WorkspaceDao, val record: AtomicReference<WorkspaceRecord>, val settings: SettingRepository) {
        fun retained(): GitHubCheckWatch = json.decodeFromString(record.get().payload)
    }
}
