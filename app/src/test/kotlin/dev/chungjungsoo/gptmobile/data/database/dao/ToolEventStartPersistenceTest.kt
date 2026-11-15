package dev.chungjungsoo.gptmobile.data.database.dao

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.ToolEventPersistenceFailure
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.repository.ToolEventRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ToolEventStartPersistenceTest {
    @Test fun recreatedRecorderAppendsAfterSavedEventsAndKeepsCompletedEvidence() = withDatabase { db ->
        val first = start(ToolEventRecorder(db.agentPersistenceDao(), db.agentRunDao()), "first")
        assertEquals(1, db.agentPersistenceDao().finishToolEvent(first.eventId, first.callId, "Saved exact evidence", "TEXT", ToolEventStatus.COMPLETED, false, 11, null))
        val saved = requireNotNull(db.agentPersistenceDao().rawGetToolEventById(first.eventId))
        val next = start(ToolEventRecorder(db.agentPersistenceDao(), db.agentRunDao()), "next")

        assertEquals(1, next.sequence)
        assertEquals(saved, db.agentPersistenceDao().rawGetToolEventById(first.eventId))
        assertEquals(listOf("first", "next"), db.agentPersistenceDao().rawGetToolEventsForRun("run").map { it.callId })
    }

    @Test fun concurrentStartsSerializeDistinctCallsAndReuseOneExactDuplicate() = withDatabase { db ->
        val persisted = coroutineScope {
            val ready = CompletableDeferred<Unit>()
            val calls = listOf("same", "same", "other", "third")
            val results = calls.map { call ->
                async(Dispatchers.Default) {
                    ready.await()
                    start(ToolEventRecorder(db.agentPersistenceDao(), db.agentRunDao()), call)
                }
            }
            ready.complete(Unit)
            results.awaitAll()
        }
        val rows = db.agentPersistenceDao().rawGetToolEventsForRun("run")

        assertEquals(persisted[0], persisted[1])
        assertEquals(3, rows.size)
        assertEquals(listOf(0, 1, 2), rows.map { it.sequence })
        assertEquals(setOf("same", "other", "third"), rows.map { it.callId }.toSet())
        assertTrue(rows.all { it.arguments == """{"path":"original"}""" })
    }

    @Test fun duplicateCallPreservesItsOriginalInputsAndIsScopedToTheRun() = withDatabase { db ->
        val dao = db.agentPersistenceDao()
        val first = dao.startToolEvent(event("first", "same").copy(sequence = 7))
        val repeated = dao.startToolEvent(event("replacement", "same").copy(sequence = 99, toolName = "other", arguments = "different"))
        seedRun(db, "second-run")
        val independent = dao.startToolEvent(event("independent", "same").copy(runId = "second-run"))

        assertEquals(first, repeated)
        assertEquals(7, first.sequence)
        assertEquals(0, independent.sequence)
        assertEquals(first, dao.rawGetToolEventsForRun("run").single())
        assertEquals(independent, dao.rawGetToolEventsForRun("second-run").single())
        assertNull(dao.rawGetToolEventById("replacement"))
    }

    @Test fun callerSequenceAboveTheDurableMaximumRemainsIntact() = withDatabase { db ->
        val dao = db.agentPersistenceDao()
        assertEquals(7, dao.startToolEvent(event("first", "first").copy(sequence = 7)).sequence)
        assertEquals(12, dao.startToolEvent(event("later", "later").copy(sequence = 12)).sequence)
        assertEquals(13, dao.startToolEvent(event("resumed", "resumed")).sequence)
    }

    @Test fun primaryAndForeignKeyFailuresRemainFailuresWithoutReplacingAnyReceipt() = withDatabase { db ->
        val dao = db.agentPersistenceDao()
        val original = dao.startToolEvent(event("stable-id", "first"))
        val primary = failure { dao.startToolEvent(event("stable-id", "other")) }
        assertEquals(ToolEventPersistenceFailure.Reason.EVENT_ID_CONFLICT, primary.reason)
        assertTrue(primary.cause is SQLiteConstraintException)
        assertEquals(JsonPrimitive(1), primary.evidence["tool_event_assigned_sequence"])
        val foreign = failure { dao.startToolEvent(event("foreign", "other").copy(runId = "missing-run")) }
        assertEquals(ToolEventPersistenceFailure.Reason.RUN_FOREIGN_KEY, foreign.reason)
        assertTrue(foreign.cause is SQLiteConstraintException)
        assertEquals(listOf(original), dao.rawGetToolEventsForRun("run"))
        assertTrue(dao.rawGetToolEventsForRun("missing-run").isEmpty())
    }

    @Test fun exhaustedSequenceFailsBeforeInsertButExistingCallRemainsReadable() = withDatabase { db ->
        val dao = db.agentPersistenceDao()
        val last = event("last", "last").copy(sequence = Int.MAX_VALUE)
        dao.insertToolEvent(last)
        val exhausted = failure { dao.startToolEvent(event("overflow", "new")) }

        assertEquals(ToolEventPersistenceFailure.Reason.SEQUENCE_EXHAUSTED, exhausted.reason)
        assertEquals(JsonPrimitive(false), exhausted.evidence["tool_event_write_attempted"])
        assertFalse("tool_event_assigned_sequence" in exhausted.evidence)
        assertEquals(JsonPrimitive(Int.MAX_VALUE), exhausted.evidence["tool_event_previous_max_sequence"])
        assertNull(exhausted.cause)
        assertEquals(last, dao.startToolEvent(event("duplicate", "last")))
        assertEquals(listOf(last), dao.rawGetToolEventsForRun("run"))
    }

    private fun withDatabase(block: suspend kotlinx.coroutines.CoroutineScope.(ChatDatabaseV2) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        try {
            db.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Tool persistence", enabledPlatform = listOf("p"), activePlatform = listOf("p")))
            db.messageDao().addMessages(MessageV2(id = 2, chatId = 1, content = "Prompt", platformType = null), MessageV2(id = 3, chatId = 1, content = "", platformType = "p", linkedMessageId = 2))
            seedRun(db, "run")
            block(db)
        } finally {
            db.close()
        }
    }

    private suspend fun seedRun(db: ChatDatabaseV2, runId: String) = db.agentRunDao().upsert(AgentRun(runId, 1, 2, 3, "p", "CUSTOM", "model", AgentRunStatus.RUNNING))

    private suspend fun start(recorder: ToolEventRecorder, call: String): ToolEvent = recorder.startTool("run", 0, call, "read", "read__connection", buildJsonObject { put("path", "original") }, startedAt = 10)

    private fun event(id: String, call: String) = ToolEvent(id, "run", 0, call, "connection", "Connection", "read", "read__connection", "{}", null, null, ToolEventStatus.RUNNING, startedAt = 10)

    private suspend fun failure(block: suspend () -> Unit): ToolEventPersistenceFailure = try {
        block()
        throw AssertionError("Expected a persistence failure")
    } catch (failure: ToolEventPersistenceFailure) {
        failure
    }
}
