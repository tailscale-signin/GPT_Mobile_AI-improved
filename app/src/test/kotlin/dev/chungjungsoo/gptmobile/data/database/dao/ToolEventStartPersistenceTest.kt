package dev.chungjungsoo.gptmobile.data.database.dao

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultCheckpoint
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.recoveryResult
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.ToolEventPersistenceFailure
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventResultType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.github.GitHubSessionToolFailure
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
    @Test fun legacyDecisionWaitsStayNeutralInSessionAuditWithoutHidingActualFailures() = withDatabase { db ->
        val dao = db.agentPersistenceDao()
        val waiting = legacyChoice("waiting", 0)
        val archived = legacyChoice("archived", 1).let { it.copy(result = dev.chungjungsoo.gptmobile.data.chat.ArchivedTextCodec.encode(requireNotNull(it.result))) }
        assertTrue(ToolResultCheckpoint.waitingForUserChoice(waiting))
        assertTrue(requireNotNull(archived.result).startsWith(dev.chungjungsoo.gptmobile.data.chat.ArchivedTextCodec.PREFIX))
        val actual = listOf(
            legacyChoice("wrong-call", 2).copy(callId = "foreign-call"),
            legacyChoice("wrong-event", 3).copy(eventId = "foreign-event"),
            legacyChoice("wrong-model", 4).copy(modelToolName = "github_decide__untrusted"),
            legacyChoice("actual-failure", 5).copy(result = "The decision could not be saved", resultType = ToolEventResultType.TEXT),
            legacyChoice("dispatched", 6, dispatched = true)
        )
        assertTrue(actual.none(ToolResultCheckpoint::waitingForUserChoice))
        val readFailure = event("read-failure", "read-failure").copy(sequence = 7, status = ToolEventStatus.FAILED, isError = true)
        val cancelled = event("cancelled", "cancelled").copy(sequence = 8, status = ToolEventStatus.CANCELED)
        for (row in listOf(waiting, archived, readFailure, cancelled) + actual) dao.insertToolEvent(row)
        seedRun(db, "other-run")
        dao.insertToolEvent(legacyChoice("foreign-scope", 0, runId = "other-run"))

        assertEquals(
            listOf(
                GitHubSessionToolFailure("github_decide", ToolEventStatus.FAILED, 5),
                GitHubSessionToolFailure("read", ToolEventStatus.CANCELED, 1),
                GitHubSessionToolFailure("read", ToolEventStatus.FAILED, 1)
            ).toSet(),
            dao.sessionAuditToolFailures(listOf("run")).toSet()
        )
        assertEquals(waiting, dao.rawGetToolEventById(waiting.eventId))
        assertEquals(archived, dao.rawGetToolEventById(archived.eventId))
    }

    @Test fun legacyWaitPagingPreservesAllThirtyTwoRealFailureGroups() = withDatabase { db ->
        val dao = db.agentPersistenceDao()
        repeat(65) { index -> dao.insertToolEvent(legacyChoice("wait-${index.toString().padStart(3, '0')}", index)) }
        val expected = (0 until 32).map { index ->
            val name = "tool_${index.toString().padStart(2, '0')}"
            dao.insertToolEvent(event(name, name).copy(sequence = 65 + index, toolName = name, modelToolName = name, status = ToolEventStatus.FAILED, isError = true))
            GitHubSessionToolFailure(name, ToolEventStatus.FAILED, 1)
        }
        assertEquals(expected, dao.sessionAuditToolFailures(listOf("run")))
    }

    @Test fun savedDecisionWaitUsesBoundCompareAndSetAndNeverOverwritesACompletedAnswer() = withDatabase { db ->
        val dao = db.agentPersistenceDao()
        val recorder = ToolEventRecorder(dao, db.agentRunDao())
        val started = recorder.startTool("run", 0, "choice", "github_decide", "github_decide", buildJsonObject { put("question", "Choose a strategy") }, startedAt = 10)
        val pending = AgentToolResult(
            started.callId,
            ToolResultContent.Json(
                buildJsonObject {
                    put("status", "GITHUB_CHOICE_PENDING")
                    put("choice_prompt_id", "a".repeat(64))
                }
            ),
            isError = true,
            dispatched = false,
            sharedResult = true,
            errorCode = "GITHUB_CHOICE_PENDING"
        )
        val waiting = requireNotNull(recorder.finishTool(started.eventId, pending, 11))
        assertEquals(ToolEventStatus.WAITING_FOR_USER, waiting.status)
        assertTrue(ToolResultCheckpoint.confirmsNoEffect(waiting))
        assertTrue(dao.sessionAuditToolFailures(listOf("run")).isEmpty())
        assertEquals(0, dao.finishToolEvent(waiting.eventId, waiting.callId, "unbound", "TEXT", ToolEventStatus.COMPLETED, false, 12, null))
        assertEquals(0, dao.finishToolEvent(waiting.eventId, waiting.callId, "stale", "TEXT", ToolEventStatus.COMPLETED, false, 12, null, "different saved receipt"))
        assertEquals(0, dao.finishToolEvent(waiting.eventId, "other-call", "foreign", "TEXT", ToolEventStatus.COMPLETED, false, 12, null, waiting.result))
        assertEquals(waiting, dao.rawGetToolEventById(waiting.eventId))

        val restored = ToolEventRecorder(dao, db.agentRunDao())
        assertEquals(waiting, restored.startTool("run", 99, "choice", "github_decide", "github_decide", buildJsonObject {}, startedAt = 12))
        val completed = requireNotNull(restored.finishTool(waiting.eventId, AgentToolResult(waiting.callId, ToolResultContent.Text("Saved answer"), false, dispatched = false, sharedResult = true), 13))
        assertEquals(ToolEventStatus.COMPLETED, completed.status)
        assertEquals("Saved answer", completed.recoveryResult())
        assertNull(restored.finishTool(waiting.eventId, pending, 14))
        assertEquals(listOf(completed), dao.rawGetToolEventsForRun("run"))
    }

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

    private fun legacyChoice(id: String, sequence: Int, dispatched: Boolean = false, runId: String = "run"): ToolEvent {
        val payload = buildJsonObject {
            put("status", "GITHUB_CHOICE_PENDING")
            put("choice_prompt_id", "a".repeat(64))
            put("message", "The choice is saved. This task is paused until its original question is answered.")
        }.toString()
        val receipt = buildJsonObject {
            put("schema", "gptmobile.tool-execution.v1")
            put("call_id", id)
            put("run_id", runId)
            put("event_id", id)
            put("tool", "github_decide")
            put("error_code", "GITHUB_CHOICE_PENDING")
            put("dispatched", dispatched)
            put("execution_state", if (dispatched) "FAILED" else "PRECHECK_REJECTED")
            put("requires_reconciliation", false)
        }
        return event(id, id).copy(
            runId = runId,
            sequence = sequence,
            toolName = "github_decide",
            modelToolName = "github_decide",
            status = ToolEventStatus.FAILED,
            isError = true,
            resultType = ToolEventResultType.CHECKPOINT,
            result = ToolResultCheckpoint.encode(payload, ToolEventResultType.JSON, payload, executionReceipt = receipt)
        )
    }

    private suspend fun failure(block: suspend () -> Unit): ToolEventPersistenceFailure = try {
        block()
        throw AssertionError("Expected a persistence failure")
    } catch (failure: ToolEventPersistenceFailure) {
        failure
    }
}
