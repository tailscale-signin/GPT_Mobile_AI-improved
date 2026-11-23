package dev.chungjungsoo.gptmobile.data.workspace

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation
import dev.chungjungsoo.gptmobile.data.agent.ProviderWait
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.github.GitHubChatDecision
import dev.chungjungsoo.gptmobile.data.github.GitHubDecisionOption
import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GitHubWorkTimingProjectionTest {
    private lateinit var database: ChatDatabaseV2
    private val secrets = mutableMapOf<String, ByteArray>()
    private val vault = object : SecretVault {
        override suspend fun put(secretRef: String, secret: ByteArray) {
            secrets[secretRef] = secret.copyOf()
        }
        override suspend fun read(secretRef: String) = secrets[secretRef]?.copyOf()
        override suspend fun delete(secretRef: String) {
            secrets.remove(secretRef)
        }
    }

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
    }

    @After fun close() {
        database.close()
    }

    @Test fun cancellationAndRejectionKeepWorkAfterAnOlderVerificationSnapshot() = runBlocking {
        for (state in listOf("CANCELED", "REJECTED")) {
            val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = state)).toInt()
            val dao = database.agentPersistenceDao()
            val user = dao.insertMessage(MessageV2(chatId = chat, content = "Accepted task", platformType = null)).toInt()
            val answer = dao.insertMessage(MessageV2(chatId = chat, content = "", platformType = "p", currentRunId = state)).toInt()
            database.agentRunDao().upsert(AgentRun(state, chat, user, answer, "p", "provider", "model", status = if (state == "CANCELED") state else "BLOCKED", createdAt = 1, startedAt = 1, completedAt = 10))
            val store = WorkspaceRepository(database, vault)
            val binding = (if (state == "CANCELED") "c" else "e").repeat(64)
            val prefix = "github-evidence:$binding:"
            store.saveProtectedSynthesisCheckpoint(
                prefix + "task_contract",
                chat,
                state,
                buildJsonObject {
                    put("binding", binding)
                    put("accepted_at_ms", 1000)
                },
                appendOnly = true
            )
            val snapshot = prefix + "verification_current_" + GitHubEvidenceBook.digest(binding).take(24)
            store.saveProtectedSynthesisCheckpoint(
                snapshot,
                chat,
                state,
                buildJsonObject {
                    put("binding", binding)
                    put("phase", "IN_PROGRESS")
                    if (state == "REJECTED") put("task_state", state)
                }
            )
            store.dao.save(requireNotNull(store.dao.get(snapshot)).copy(updatedAt = 2000))
            database.invocationDao().save(ModelInvocation(state, state, "$chat:$user", "provider", "model", "chat", 0, 0, status = "COMPLETED", startedAt = 3000, durationMs = 5000))
            dao.saveProviderWait(ProviderWait(state, 0, "scope", 10000, """{"accumulatedProviderWaitMillis":2000,"closedWaitIntervals":[{"startMillis":8000,"endMillis":10000}],"closedWaitIntervalsComplete":true}"""))
            for (observedAt in listOf(12000L, 15000L)) {
                val task = WorkspaceRepository(database, vault).conversationWork(chat, observedAtMillis = observedAt).tasks.single()
                assertEquals(state, task.state)
                val timing = requireNotNull(task.timing?.task)
                assertEquals(9000L, timing.elapsedMillis)
                assertEquals(5000L, timing.observedActiveMillis)
                assertEquals(2000L, timing.providerWaitMillis)
                assertEquals(2000L, timing.unobservedMillis)
            }
        }
    }

    @Test fun reopenedTaskCombinesOnlyItsRetainedRunsAndCountsEveryWaitOnce() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Timing")).toInt()
        val dao = database.agentPersistenceDao()
        val user = dao.insertMessage(MessageV2(chatId = chat, content = "Complete the accepted task", platformType = null)).toInt()
        val answer = dao.insertMessage(MessageV2(chatId = chat, content = "", platformType = "p", currentRunId = "b")).toInt()
        suspend fun run(id: String, start: Long, completed: Long? = null) {
            database.agentRunDao().upsert(AgentRun(id, chat, user, answer, "p", "provider", "model", status = if (completed == null) "RUNNING" else "COMPLETED", createdAt = start, startedAt = start, completedAt = completed))
        }
        run("a", 1, 9)
        run("b", 3)
        run("unrelated", 1)
        val store = WorkspaceRepository(database, vault)
        val binding = "b".repeat(64)
        val prefix = "github-evidence:$binding:"
        store.saveProtectedSynthesisCheckpoint(
            prefix + "task_contract",
            chat,
            "a",
            buildJsonObject {
                put("binding", binding)
                put("accepted_at_ms", 1000)
            },
            appendOnly = true
        )
        store.saveProtectedSynthesisCheckpoint(
            prefix + "verification_current_" + GitHubEvidenceBook.digest(binding).take(24),
            chat,
            "b",
            buildJsonObject {
                put("binding", binding)
                put("phase", "IN_PROGRESS")
            }
        )
        suspend fun invocation(id: String, parent: String, start: Long, duration: Long) {
            database.invocationDao().save(ModelInvocation(id, parent, "$chat:$user", "provider", "model", "chat", 0, 0, status = "COMPLETED", startedAt = start, durationMs = duration))
        }
        invocation("one", "a", 1000, 7000)
        invocation("two", "b", 3000, 5000)
        invocation("other", "unrelated", 1000, 11000)
        dao.insertToolEvent(ToolEvent("decision", "a", 0, "c1", null, null, "github_decide", "github_decide", "{}", null, null, "COMPLETED", startedAt = 1, completedAt = 12))
        dao.insertToolEvent(ToolEvent("read", "b", 0, "c2", null, null, "github_read", "github_read", "{}", null, null, "COMPLETED", startedAt = 9, completedAt = 10))
        dao.saveProviderWait(ProviderWait("a", 0, "scope", 4000, """{"accumulatedProviderWaitMillis":2000,"closedWaitIntervals":[{"startMillis":2000,"endMillis":4000}],"closedWaitIntervalsComplete":true}"""))
        dao.saveProviderWait(ProviderWait("b", 0, "scope", 9000, """{"accumulatedProviderWaitMillis":2000,"closedWaitIntervals":[{"startMillis":7000,"endMillis":9000}],"closedWaitIntervalsComplete":true}"""))
        val decision = GitHubChatDecision("choice", chat, user, "p", "workspace", "Choose?", "", listOf(GitHubDecisionOption("One"), GitHubDecisionOption("Two")), answer = "One", offeredAtMillis = 5000, answeredAtMillis = 6000, runId = "b")
        val reopened = WorkspaceRepository(database, vault).conversationWork(chat, listOf(decision, decision.copy(id = "automatic", automatic = true, offeredAtMillis = 1000, answeredAtMillis = 12000), decision.copy(id = "other-chat", chatId = chat + 1)), 12000)
        val timing = requireNotNull(reopened.tasks.single().timing)
        val task = requireNotNull(timing.task)
        assertEquals(setOf("a", "b"), timing.runs.map { it.runId }.toSet())
        assertEquals(11000L, task.elapsedMillis)
        assertEquals(4000L, task.observedActiveMillis)
        assertEquals(4000L, task.providerWaitMillis)
        assertEquals(1000L, task.userWaitMillis)
        assertEquals(2000L, task.unobservedMillis)
        assertFalse(task.incompleteEvidence)
        assertEquals(8000L, timing.runs.single { it.runId == "a" }.timing?.elapsedMillis)
        assertTrue(timing.runs.sumOf { requireNotNull(it.timing).observedActiveMillis } > task.observedActiveMillis)

        // Old checkpoints retain a total without interval locations. That total is
        // visible evidence but cannot become an exact, potentially overlapping slice.
        dao.saveProviderWait(ProviderWait("b", 0, "scope", 9000, """{"accumulatedProviderWaitMillis":5000}"""))
        val legacy = requireNotNull(WorkspaceRepository(database, vault).conversationWork(chat, listOf(decision), 12000).tasks.single().timing?.task)
        assertEquals(5000L, legacy.unlocatedProviderWaitMillis)
        assertEquals(2000L, legacy.providerWaitMillis)
        assertTrue(legacy.incompleteEvidence)
        assertEquals(legacy.elapsedMillis, legacy.observedActiveMillis + legacy.providerWaitMillis + legacy.userWaitMillis + legacy.unobservedMillis)

        database.agentRunDao().updateStatus("a", "COMPLETED", 1, null, null)
        val missingEnd = requireNotNull(WorkspaceRepository(database, vault).conversationWork(chat, listOf(decision), 12000).tasks.single().timing)
        assertTrue(missingEnd.runs.single { it.runId == "a" }.timing == null)
        assertTrue(requireNotNull(missingEnd.task).incompleteEvidence)
    }
}
