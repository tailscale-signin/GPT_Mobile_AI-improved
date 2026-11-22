package dev.chungjungsoo.gptmobile.data.database

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupDatabase
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupSection
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupSelection
import dev.chungjungsoo.gptmobile.data.chat.ArchivedTextCodec
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.AgentToolBinding
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventError
import dev.chungjungsoo.gptmobile.data.model.ChatAttachment
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyProtectedRange
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyProtectionReceipt
import dev.chungjungsoo.gptmobile.data.privacy.effectivePrivacyProtectedValues
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentPersistenceDaoInstrumentedTest {
    private lateinit var database: ChatDatabaseV2

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ChatDatabaseV2::class.java
        ).addCallback(AgentToolBindingDatabase.callback).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun unmaskedProfilePermanentlyRemovesTheSharedUserHighlightInEitherOrder() = runBlocking {
        val dao = database.agentPersistenceDao()
        val room = newChat().let { it.copy(id = dao.insertChatRoom(it).toInt()) }
        val text = "Hello Alice"
        val protected = PrivacyProtectionReceipt.create(text, listOf(PrivacyProtectedRange(6, 11)))
        val unprotected = PrivacyProtectionReceipt.create(text, emptyList())
        for (receipts in listOf(listOf(protected, unprotected), listOf(unprotected, protected))) {
            val pending = MessageV2(
                chatId = room.id,
                content = text,
                platformType = null,
                timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, privacyProtection = PrivacyProtectionReceipt.create(text, emptyList(), confirmed = false)))
            ).let { it.copy(id = dao.insertMessage(it).toInt()) }
            receipts.forEach { dao.recordPrivacyProtection(pending, it) }
            dao.recordPrivacyProtection(pending, protected)
            val saved = requireNotNull(dao.recoveryMessage(pending.id))
            assertEquals(text, saved.content)
            assertTrue(saved.effectivePrivacyProtectedValues().isEmpty())
            assertTrue(requireNotNull(saved.timeline.last().privacyProtection).confirmed)
        }
    }

    @Test
    fun staleUserSnapshotCannotRestoreHighlightsAfterAnUnmaskedSend() = runBlocking {
        val dao = database.agentPersistenceDao()
        val room = newChat().let { it.copy(id = dao.insertChatRoom(it).toInt()) }
        val text = "Hello Alice"
        val stale = MessageV2(
            chatId = room.id,
            content = text,
            platformType = null,
            timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, privacyProtection = PrivacyProtectionReceipt.create(text, emptyList(), confirmed = false)))
        ).let { it.copy(id = dao.insertMessage(it).toInt()) }
        val protected = PrivacyProtectionReceipt.create(text, listOf(PrivacyProtectedRange(6, 11)))
        dao.recordPrivacyProtection(stale, protected)
        assertEquals(listOf("Alice"), requireNotNull(dao.recoveryMessage(stale.id)).effectivePrivacyProtectedValues())
        dao.recordPrivacyProtection(stale, PrivacyProtectionReceipt.create(text, emptyList()))

        dao.persistAgentTurn(
            PersistAgentTurnRequest(
                room,
                stale,
                listOf(AgentRunDraft("privacy-stale-retry", "profile-1", "OPENAI", "gpt-5")),
                mapOf("profile-1" to "gpt-5")
            )
        )
        dao.recordPrivacyProtection(stale, protected)

        assertTrue(requireNotNull(dao.recoveryMessage(stale.id)).effectivePrivacyProtectedValues().isEmpty())
    }

    @Test
    fun protectionReceiptCannotAuthorizeLegacyOrEditedMessageText() = runBlocking {
        val dao = database.agentPersistenceDao()
        val room = newChat().let { it.copy(id = dao.insertChatRoom(it).toInt()) }
        val original = MessageV2(chatId = room.id, content = "Hello Alice", platformType = null)
            .let { it.copy(id = dao.insertMessage(it).toInt()) }
        val protected = PrivacyProtectionReceipt.create(original.content, listOf(PrivacyProtectedRange(6, 11)))
        dao.recordPrivacyProtection(original, protected)
        assertTrue(requireNotNull(dao.recoveryMessage(original.id)).effectivePrivacyProtectedValues().isEmpty())

        val pending = original.copy(timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, privacyProtection = PrivacyProtectionReceipt.create(original.content, emptyList(), confirmed = false))))
        dao.updateMessage(pending)
        val edited = pending.copy(content = "Hello Bob")
        dao.updateMessage(edited)
        dao.recordPrivacyProtection(pending, protected)
        val saved = requireNotNull(dao.recoveryMessage(original.id))
        assertEquals(edited.content, saved.content)
        assertTrue(saved.effectivePrivacyProtectedValues().isEmpty())
    }

    @Test
    fun recordingProtectionPreservesCompressedArchivedBodies() = runBlocking {
        val dao = database.agentPersistenceDao()
        val room = newChat().let { it.copy(id = dao.insertChatRoom(it).toInt()) }
        val text = "Alice " + "ordinary text ".repeat(200)
        val thoughts = "Retained reasoning ".repeat(200)
        val storedContent = ArchivedTextCodec.encode(text)
        val storedThoughts = ArchivedTextCodec.encode(thoughts)
        assertTrue(storedContent.startsWith(ArchivedTextCodec.PREFIX))
        assertTrue(storedThoughts.startsWith(ArchivedTextCodec.PREFIX))
        val message = MessageV2(
            chatId = room.id,
            content = storedContent,
            thoughts = storedThoughts,
            platformType = "profile-1",
            timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, privacyProtection = PrivacyProtectionReceipt.create(text, emptyList(), confirmed = false)))
        ).let { it.copy(id = dao.insertMessage(it).toInt()) }

        dao.recordPrivacyProtection(message.copy(content = text, thoughts = thoughts), PrivacyProtectionReceipt.create(text, listOf(PrivacyProtectedRange(0, 5))))

        val raw = requireNotNull(dao.rawRecoveryMessage(message.id))
        assertEquals(storedContent, raw.content)
        assertEquals(storedThoughts, raw.thoughts)
        assertEquals(listOf("Alice"), requireNotNull(dao.recoveryMessage(message.id)).effectivePrivacyProtectedValues())
    }

    @Test
    fun staleSnapshotCannotClearPrivacyMarkerOrChangeAnotherConversation() = runBlocking {
        val dao = database.agentPersistenceDao()
        val stale = newChat().let { it.copy(id = dao.insertChatRoom(it).toInt()) }
        val other = newChat().let { it.copy(id = dao.insertChatRoom(it).toInt()) }
        dao.markPrivacyModeUsed(stale.id)

        dao.saveChatSnapshot(stale, emptyList(), emptyMap())

        assertTrue(requireNotNull(dao.getChatRoom(stale.id)).privacyModeEnabled)
        assertFalse(requireNotNull(dao.getChatRoom(other.id)).privacyModeEnabled)
    }

    @Test
    fun staleSnapshotsCannotRestoreOrWidenProtectionForCurrentBodyAndRevisions() = runBlocking {
        val dao = database.agentPersistenceDao()
        val text = "Alice and Bob"
        val both = PrivacyProtectionReceipt.create(text, listOf(PrivacyProtectedRange(0, 5), PrivacyProtectedRange(10, 13)))
        fun timeline(receipt: PrivacyProtectionReceipt) = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, privacyProtection = receipt))
        for (ranges in listOf(emptyList(), listOf(PrivacyProtectedRange(0, 5)))) {
            val room = newChat().let { it.copy(id = dao.insertChatRoom(it).toInt()) }
            val stale = MessageV2(
                chatId = room.id,
                content = text,
                platformType = "profile-1",
                timeline = timeline(both),
                revisions = listOf(AssistantRevision(text, createdAt = 42L, runId = "older", timeline = timeline(both)))
            ).let { it.copy(id = dao.insertMessage(it).toInt()) }
            val narrowed = PrivacyProtectionReceipt.create(text, ranges)
            dao.recordPrivacyProtection(stale, narrowed)
            val pending = timeline(PrivacyProtectionReceipt.create(text, emptyList(), confirmed = false))
            for (snapshot in listOf(stale, stale.copy(timeline = pending, revisions = stale.revisions.map { it.copy(timeline = pending) }))) {
                dao.saveChatSnapshot(room, listOf(snapshot), emptyMap())
                dao.recordPrivacyProtection(stale, both)

                val saved = requireNotNull(dao.recoveryMessage(stale.id))
                assertEquals(text, saved.content)
                assertEquals(narrowed, saved.timeline.last().privacyProtection)
                assertEquals(narrowed, saved.revisions.single().timeline.last().privacyProtection)
                val expected = if (ranges.isEmpty()) emptyList() else listOf("Alice")
                assertEquals(expected, saved.effectivePrivacyProtectedValues())
                assertEquals(expected, saved.copy(activeRevisionIndex = 0).effectivePrivacyProtectedValues())
            }
        }
    }

    @Test
    fun editedSnapshotBodiesAndUnownedRevisionsCannotBorrowProtection() = runBlocking {
        val dao = database.agentPersistenceDao()
        val room = newChat().let { it.copy(id = dao.insertChatRoom(it).toInt()) }
        fun timeline(text: String, name: String) = listOf(
            AssistantTimelineItem(AssistantTimelineItemType.NOTICE, privacyProtection = PrivacyProtectionReceipt.create(text, listOf(PrivacyProtectedRange(text.indexOf(name), text.indexOf(name) + name.length))))
        )
        val original = MessageV2(
            chatId = room.id,
            content = "Hello Alice",
            platformType = null,
            timeline = timeline("Hello Alice", "Alice"),
            revisions = listOf(AssistantRevision("Earlier Alice", createdAt = 42L, runId = "older", timeline = timeline("Earlier Alice", "Alice")))
        ).let { it.copy(id = dao.insertMessage(it).toInt()) }
        val edited = original.copy(
            content = "Hello Bob",
            timeline = timeline("Hello Bob", "Bob"),
            revisions = listOf(
                original.revisions.single().copy(content = "Earlier Bob", timeline = timeline("Earlier Bob", "Bob")),
                AssistantRevision(original.content, createdAt = 99L, runId = "unowned", timeline = original.timeline)
            )
        )

        dao.saveChatSnapshot(room, listOf(edited), emptyMap())

        val saved = requireNotNull(dao.recoveryMessage(original.id))
        assertEquals(edited.content, saved.content)
        assertTrue(saved.effectivePrivacyProtectedValues().isEmpty())
        assertTrue(requireNotNull(saved.timeline.last().privacyProtection).validFor(saved.content))
        for (index in saved.revisions.indices) {
            assertTrue(saved.copy(activeRevisionIndex = index).effectivePrivacyProtectedValues().isEmpty())
            val revision = saved.revisions[index]
            assertTrue(requireNotNull(revision.timeline.last().privacyProtection).validFor(revision.content))
        }
    }

    @Test
    fun snapshotRejectsMessagesOwnedByAnotherConversation() = runBlocking {
        val dao = database.agentPersistenceDao()
        val source = newChat().copy(privacyModeEnabled = true).let { it.copy(id = dao.insertChatRoom(it).toInt()) }
        val target = newChat().let { it.copy(id = dao.insertChatRoom(it).toInt()) }
        val foreign = MessageV2(chatId = source.id, content = "Alice", platformType = null).let { it.copy(id = dao.insertMessage(it).toInt()) }
        val retained = MessageV2(chatId = target.id, content = "Keep this history", platformType = null).let { it.copy(id = dao.insertMessage(it).toInt()) }

        val failure = runCatching {
            dao.saveChatSnapshot(target.copy(privacyModeEnabled = true), listOf(foreign.copy(chatId = target.id)), emptyMap())
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(source.id, requireNotNull(dao.recoveryMessage(foreign.id)).chatId)
        assertEquals(retained, dao.recoveryMessage(retained.id))
        assertTrue(requireNotNull(dao.getChatRoom(source.id)).privacyModeEnabled)
        assertFalse(requireNotNull(dao.getChatRoom(target.id)).privacyModeEnabled)
    }

    @Test
    fun removedQueuedProfileCannotDispatchOrOverwriteCurrentMembership() = runBlocking {
        val stale = ChatRoomV2(title = "Queued", enabledPlatform = listOf("profile-1", "profile-2"))
        val id = database.agentPersistenceDao().insertChatRoom(stale).toInt()
        val current = stale.copy(id = id, activePlatform = listOf("profile-1"))
        database.agentPersistenceDao().updateChatRoom(current)
        val payload = dev.chungjungsoo.gptmobile.data.queue.PendingPromptPayload(profileUids = listOf("profile-2"))
        database.pendingPromptDao().enqueue(dev.chungjungsoo.gptmobile.data.queue.PendingPrompt("queued-1", id, "Keep my prompt", kotlinx.serialization.json.Json.encodeToString(payload), 0))
        val failure = runCatching {
            database.agentPersistenceDao().persistAgentTurn(PersistAgentTurnRequest(stale.copy(id = id), MessageV2(content = "Keep my prompt", platformType = null), listOf(AgentRunDraft("removed-run", "profile-2", "OPENAI", "model")), mapOf("profile-2" to "model"), queuedPromptId = "queued-1"))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(listOf("profile-1"), database.agentPersistenceDao().getChatRoom(id)?.activePlatform)
        assertEquals("Keep my prompt", database.pendingPromptDao().get("queued-1")?.text)
        assertTrue(database.messageDao().loadMessages(id).isEmpty())
    }

    @Test
    fun persistAgentTurn_assignsIdsAndQueuesEveryProfileInOneTransaction() = runBlocking {
        val result = database.agentPersistenceDao().persistAgentTurn(
            PersistAgentTurnRequest(
                chatRoom = ChatRoomV2(
                    title = "Untitled Chat",
                    enabledPlatform = listOf("profile-1", "profile-2"),
                    createdAt = 100L,
                    updatedAt = 100L
                ),
                userMessage = MessageV2(
                    content = "Question",
                    platformType = null,
                    createdAt = 101L
                ),
                runs = listOf(
                    AgentRunDraft("run-1", "profile-1", "OPENAI", "gpt-5"),
                    AgentRunDraft("run-2", "profile-2", "ANTHROPIC", "claude")
                ),
                chatPlatformModels = mapOf(
                    "profile-1" to "gpt-5",
                    "profile-2" to "claude"
                )
            )
        )

        assertTrue(result.chatRoom.id > 0)
        assertTrue(result.userMessage.id > 0)
        assertEquals(listOf("profile-1", "profile-2"), result.assistantMessages.map { it.platformType })
        assertTrue(result.assistantMessages.all { it.id > 0 })
        assertEquals(listOf("run-1", "run-2"), result.assistantMessages.map { it.currentRunId })
        assertEquals(listOf(AgentRunStatus.QUEUED, AgentRunStatus.QUEUED), result.runs.map { it.status })
        assertEquals(result.userMessage.id, result.runs[0].userMessageId)
        assertEquals(result.assistantMessages[0].id, result.runs[0].assistantMessageId)

        assertEquals(3, database.messageDao().loadMessages(result.chatRoom.id).size)
        assertEquals(2, database.agentRunDao().getByChatId(result.chatRoom.id).size)
        assertEquals(2, database.chatPlatformModelDao().getByChatId(result.chatRoom.id).size)
    }

    @Test
    fun interruptActiveRuns_marksQueuedAndRunningWithoutTouchingTerminalRuns() = runBlocking {
        val persisted = database.agentPersistenceDao().persistAgentTurn(
            PersistAgentTurnRequest(
                chatRoom = ChatRoomV2(title = "Chat", enabledPlatform = listOf("profile-1")),
                userMessage = MessageV2(content = "Question", platformType = null),
                runs = listOf(AgentRunDraft("run-1", "profile-1", "OPENAI", "gpt-5")),
                chatPlatformModels = mapOf("profile-1" to "gpt-5")
            )
        )
        assertEquals(1, database.agentRunDao().markRunning("run-1", 200L))
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO tool_events (
                event_id, run_id, sequence, call_id, connection_uid_snapshot,
                connection_name_snapshot, tool_name, model_tool_name, arguments,
                result, result_type, status, is_error, started_at, completed_at, error
            ) VALUES (
                'event-active', 'run-1', 0, 'call-active', NULL, NULL,
                'read_url', 'read_url', '{}', NULL, NULL, 'RUNNING', 0, 201, NULL, NULL
            )
            """.trimIndent()
        )

        val interrupted = database.agentRunDao().interruptActiveRuns(completedAt = 300L)
        database.agentPersistenceDao().cancelInterruptedToolEvents(completedAt = 300L)
        val lateCompletion = database.agentRunDao().finishRunning(
            runId = "run-1",
            status = AgentRunStatus.COMPLETED,
            completedAt = 301L,
            terminalError = null
        )

        assertEquals(1, interrupted)
        assertEquals(0, lateCompletion)
        assertEquals(AgentRunStatus.INTERRUPTED, database.agentRunDao().getById("run-1")?.status)
        assertEquals(300L, database.agentRunDao().getById("run-1")?.completedAt)
        assertEquals(persisted.chatRoom.id, database.agentRunDao().getById("run-1")?.chatId)
        database.openHelper.writableDatabase.query(
            "SELECT status, completed_at, error FROM tool_events WHERE event_id = 'event-active'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("CANCELED", cursor.getString(0))
            assertEquals(300L, cursor.getLong(1))
            assertEquals(ToolEventError.INTERRUPTED_APP_STOPPED, cursor.getString(2))
        }
    }

    @Test
    fun finishActiveRun_terminalizesRunningRunBeforeLateCompletion() = runBlocking {
        database.agentPersistenceDao().persistAgentTurn(
            PersistAgentTurnRequest(
                chatRoom = ChatRoomV2(title = "Chat", enabledPlatform = listOf("profile-1")),
                userMessage = MessageV2(content = "Question", platformType = null),
                runs = listOf(AgentRunDraft("run-1", "profile-1", "OPENAI", "gpt-5")),
                chatPlatformModels = mapOf("profile-1" to "gpt-5")
            )
        )
        assertEquals(1, database.agentRunDao().markRunning("run-1", 200L))

        val canceled = database.agentRunDao().finishActive(
            runId = "run-1",
            status = AgentRunStatus.CANCELED,
            completedAt = 300L,
            terminalError = null
        )
        val lateCompletion = database.agentRunDao().finishRunning(
            runId = "run-1",
            status = AgentRunStatus.COMPLETED,
            completedAt = 301L,
            terminalError = null
        )

        assertEquals(1, canceled)
        assertEquals(0, lateCompletion)
        assertEquals(AgentRunStatus.CANCELED, database.agentRunDao().getById("run-1")?.status)
        assertEquals(300L, database.agentRunDao().getById("run-1")?.completedAt)
    }

    @Test
    fun persistAgentRetry_preservesPriorRunRevisionAndQueuesNewRun() = runBlocking {
        val persisted = database.agentPersistenceDao().persistAgentTurn(
            PersistAgentTurnRequest(
                chatRoom = ChatRoomV2(title = "Chat", enabledPlatform = listOf("profile-1")),
                userMessage = MessageV2(content = "Question", platformType = null),
                runs = listOf(AgentRunDraft("run-1", "profile-1", "OPENAI", "gpt-5")),
                chatPlatformModels = mapOf("profile-1" to "gpt-5")
            )
        )
        val firstAnswer = persisted.assistantMessages.single().copy(
            content = "First answer",
            thoughts = "First thoughts",
            attachments = listOf(ChatAttachment("/tmp/stale.txt", "/tmp/stale.txt", "stale.txt", "text/plain", 1)),
            revisions = listOf(AssistantRevision(content = "Older answer", createdAt = 90L)),
            activeRevisionIndex = 0
        )
        database.messageDao().editMessages(firstAnswer)
        database.agentRunDao().updateStatus("run-1", AgentRunStatus.COMPLETED, 200L, 250L, null)

        val retried = database.agentPersistenceDao().persistAgentRetry(
            PersistAgentRetryRequest(
                userMessage = persisted.userMessage,
                assistantMessage = firstAnswer,
                run = AgentRunDraft("run-2", "profile-1", "OPENAI", "gpt-5", createdAt = 300L)
            )
        )

        assertEquals(firstAnswer.id, retried.assistantMessage.id)
        assertEquals("", retried.assistantMessage.content)
        assertEquals(emptyList<ChatAttachment>(), retried.assistantMessage.attachments)
        assertEquals(-1, retried.assistantMessage.activeRevisionIndex)
        assertEquals("run-2", retried.assistantMessage.currentRunId)
        assertEquals("First answer", retried.assistantMessage.revisions.first().content)
        assertEquals("run-1", retried.assistantMessage.revisions.first().runId)
        assertEquals(AgentRunStatus.COMPLETED, database.agentRunDao().getById("run-1")?.status)
        assertEquals(AgentRunStatus.QUEUED, database.agentRunDao().getById("run-2")?.status)
        assertEquals(firstAnswer.id, database.agentRunDao().getById("run-2")?.assistantMessageId)
    }

    @Test
    fun finalAnswerCommit_rollsBackAnswerWhenTerminalStatusWriteFails() = runBlocking {
        val persisted = persistTurn(newChat(), "Question", "run-1", 100L)
        val dao = database.agentPersistenceDao()
        database.agentRunDao().updateStatus("run-1", AgentRunStatus.RUNNING, 101L, null, null)
        val message = persisted.assistantMessages.single().copy(content = "Saved first segment")
        assertTrue(dao.checkpointRunningMessage(message))
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_completion BEFORE UPDATE OF status ON agent_runs WHEN NEW.status = 'COMPLETED' BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        assertTrue(
            runCatching {
                dao.finishRunningWithMessage(message.copy(content = "First segment and full continuation"), "run-1", AgentRunStatus.COMPLETED, 102L, null)
            }.isFailure
        )
        assertEquals("Saved first segment", dao.recoveryMessage(message.id)?.content)
        assertEquals(AgentRunStatus.RUNNING, dao.recoveryRun("run-1")?.status)
    }

    @Test
    fun finalAnswerCommit_rejectsCanceledAndSupersededRuns() = runBlocking {
        val persisted = persistTurn(newChat(), "Question", "run-1", 100L)
        val dao = database.agentPersistenceDao()
        database.agentRunDao().updateStatus("run-1", AgentRunStatus.RUNNING, 101L, null, null)
        val answer = persisted.assistantMessages.single().copy(content = "First segment and continuation")
        assertTrue(dao.checkpointRunningMessage(answer))
        assertTrue(dao.finishRunningWithMessage(answer, "run-1", AgentRunStatus.COMPLETED, 102L, null))
        assertFalse(dao.checkpointRunningMessage(answer.copy(content = "Late tail")))
        assertFalse(dao.finishRunningWithMessage(answer.copy(content = "Late tail"), "run-1", AgentRunStatus.COMPLETED, 103L, null))
        assertEquals(answer.content, dao.recoveryMessage(answer.id)?.content)

        val retried = dao.persistAgentRetry(
            PersistAgentRetryRequest(
                userMessage = persisted.userMessage,
                assistantMessage = answer,
                run = AgentRunDraft("run-2", "profile-1", "OPENAI", "gpt-5", createdAt = 104L)
            )
        )
        database.agentRunDao().updateStatus("run-2", AgentRunStatus.CANCELED, 105L, 106L, null)
        assertFalse(dao.finishRunningWithMessage(retried.assistantMessage.copy(content = "Canceled tail"), "run-2", AgentRunStatus.COMPLETED, 107L, null))
        assertFalse(dao.checkpointRunningMessage(answer))
        assertEquals("run-2", dao.recoveryMessage(answer.id)?.currentRunId)
    }

    @Test
    fun finishAgentRun_persistsAssistantAndTerminalStatusTogether() = runBlocking {
        val persisted = persistTurn(newChat(), "Question", "run-1", 100L)
        database.agentRunDao().updateStatus("run-1", AgentRunStatus.RUNNING, 101L, null, null)
        val answer = persisted.assistantMessages.single().copy(content = "Final answer", thoughts = "Reasoning")

        database.agentPersistenceDao().finishAgentRun(
            assistantMessage = answer,
            runId = "run-1",
            status = AgentRunStatus.COMPLETED,
            startedAt = 101L,
            completedAt = 102L,
            terminalError = null
        )

        assertEquals("Final answer", database.messageDao().loadMessages(persisted.chatRoom.id).first { it.id == answer.id }.content)
        assertEquals(AgentRunStatus.COMPLETED, database.agentRunDao().getById("run-1")?.status)
    }

    @Test
    fun editedTurn_truncatesFutureHistoryBeforeQueuingReplacementRun() = runBlocking {
        val first = persistTurn(chatRoom = newChat(), question = "First", runId = "run-1", createdAt = 100L)
        val second = persistTurn(
            chatRoom = first.chatRoom,
            question = "Second",
            runId = "run-2",
            createdAt = 200L
        )
        persistTurn(
            chatRoom = first.chatRoom,
            question = "Future",
            runId = "run-3",
            createdAt = 300L
        )
        val editedUser = second.userMessage.copy(content = "Edited second", createdAt = 400L)

        database.agentPersistenceDao().saveChatSnapshot(
            chatRoom = first.chatRoom.copy(updatedAt = 400L),
            messages = listOf(first.userMessage, first.assistantMessages.single(), editedUser),
            chatPlatformModels = mapOf("profile-1" to "gpt-5")
        )
        val replacement = database.agentPersistenceDao().persistAgentTurn(
            PersistAgentTurnRequest(
                chatRoom = first.chatRoom.copy(updatedAt = 400L),
                userMessage = editedUser,
                runs = listOf(AgentRunDraft("run-4", "profile-1", "OPENAI", "gpt-5", createdAt = 401L)),
                chatPlatformModels = mapOf("profile-1" to "gpt-5")
            )
        )

        val messages = database.messageDao().loadMessages(first.chatRoom.id).sortedWith(compareBy(MessageV2::createdAt, MessageV2::id))
        assertEquals(listOf("First", "", "Edited second", ""), messages.map { it.content })
        assertEquals(second.userMessage.id, replacement.userMessage.id)
        assertEquals("run-4", replacement.assistantMessages.single().currentRunId)
        assertEquals(listOf("run-1", "run-4"), database.agentRunDao().getByChatId(first.chatRoom.id).map { it.runId })
    }

    @Test
    fun duplicateChatWithHistory_regeneratesMessageRunAndEventIds() = runBlocking {
        val persisted = database.agentPersistenceDao().persistAgentTurn(
            PersistAgentTurnRequest(
                chatRoom = ChatRoomV2(title = "Chat", enabledPlatform = listOf("profile-1")),
                userMessage = MessageV2(content = "Question", platformType = null, createdAt = 100L),
                runs = listOf(AgentRunDraft("run-1", "profile-1", "OPENAI", "gpt-5", createdAt = 101L)),
                chatPlatformModels = mapOf("profile-1" to "gpt-5")
            )
        )
        database.messageDao().editMessages(persisted.assistantMessages.single().copy(content = "Answer"))
        database.agentRunDao().updateStatus("run-1", AgentRunStatus.COMPLETED, 102L, 103L, null)
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO tool_events (
                event_id, run_id, sequence, call_id, connection_uid_snapshot,
                connection_name_snapshot, tool_name, model_tool_name, arguments,
                result, result_type, status, is_error, started_at, completed_at, error
            ) VALUES (
                'event-1', 'run-1', 0, 'call-1', 'connection-1', 'Fixture',
                'lookup', 'fixture__lookup', '{}', 'ok', 'TEXT', 'COMPLETED', 0, 102, 103, NULL
            )
            """.trimIndent()
        )

        val duplicate = database.agentPersistenceDao().duplicateChatWithHistory(
            sourceChatId = persisted.chatRoom.id,
            title = "Chat (copy)",
            timestamp = 500L
        )
        val duplicateMessages = database.messageDao().loadMessages(duplicate.id)
        val duplicateRuns = database.agentRunDao().getByChatId(duplicate.id)

        assertTrue(duplicate.id != persisted.chatRoom.id)
        assertEquals(listOf("Question", "Answer"), duplicateMessages.sortedBy { it.createdAt }.map { it.content })
        assertTrue(duplicateMessages.none { it.id in listOf(persisted.userMessage.id, persisted.assistantMessages.single().id) })
        assertEquals(1, duplicateRuns.size)
        assertTrue(duplicateRuns.single().runId != "run-1")
        assertEquals(AgentRunStatus.COMPLETED, duplicateRuns.single().status)
        assertEquals(duplicateRuns.single().runId, duplicateMessages.first { it.platformType != null }.currentRunId)
        database.openHelper.writableDatabase.query(
            "SELECT event_id, run_id, call_id FROM tool_events WHERE run_id = ?",
            arrayOf(duplicateRuns.single().runId)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.getString(0) != "event-1")
            assertEquals(duplicateRuns.single().runId, cursor.getString(1))
            assertEquals("call-1", cursor.getString(2))
        }
        assertEquals(mapOf("profile-1" to "gpt-5"), database.chatPlatformModelDao().getByChatId(duplicate.id).associate { it.platformUid to it.model })
    }

    @Test
    fun deletePlatform_removesItsToolBindings() = runBlocking {
        val platform = PlatformV2(
            uid = "profile-1",
            name = "OpenAI",
            compatibleType = ClientType.OPENAI,
            apiUrl = "https://api.openai.com/v1/",
            model = "gpt-5"
        )
        val platformId = database.platformDao().addPlatform(platform)
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO agent_tool_bindings (binding_uid, profile_uid, connection_uid, tool_name, created_at) " +
                "VALUES ('binding-1', 'profile-1', NULL, 'read_url', 1)"
        )

        database.platformDao().deletePlatform(platform.copy(id = platformId.toInt()))

        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM agent_tool_bindings").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        assertEquals(emptyList<PlatformV2>(), database.platformDao().getPlatforms())
    }

    @Test
    fun builtInToolBinding_rejectsDuplicateProfileAndTool() = runBlocking {
        val sql = database.openHelper.writableDatabase
        val dao = database.toolConnectionDao()
        dao.upsertConnection(toolConnection("remote-1"))
        dao.upsertConnection(toolConnection("remote-2"))
        insertRawBinding(sql, "binding-1")
        insertRawBinding(sql, "other-profile", profile = "profile-2")
        insertRawBinding(sql, "other-tool", tool = "current_date")
        insertRawBinding(sql, "remote-binding-1", connection = "remote-1")
        insertRawBinding(sql, "remote-binding-2", connection = "remote-2")

        assertSqlConstraint { insertRawBinding(sql, "binding-2") }
        assertSqlConstraint { sql.execSQL("UPDATE agent_tool_bindings SET profile_uid = 'profile-1' WHERE binding_uid = 'other-profile'") }
        assertSqlConstraint { sql.execSQL("UPDATE agent_tool_bindings SET tool_name = 'read_url' WHERE binding_uid = 'other-tool'") }
        assertSqlConstraint { sql.execSQL("UPDATE agent_tool_bindings SET connection_uid = NULL WHERE binding_uid = 'remote-binding-1'") }
        assertSqlConstraint { insertRawBinding(sql, "remote-duplicate", connection = "remote-1") }
        assertSqlConstraint { insertRawBinding(sql, "unknown-connection", connection = "missing") }

        // Updating a row's identity and replacing that same identity are legitimate.
        sql.execSQL("UPDATE agent_tool_bindings SET binding_uid = 'renamed', profile_uid = 'profile-1' WHERE binding_uid = 'binding-1'")
        sql.execSQL("INSERT OR REPLACE INTO agent_tool_bindings(binding_uid,profile_uid,connection_uid,tool_name,created_at) VALUES('renamed','profile-1',NULL,'read_url',3)")
        assertEquals("renamed", dao.listBindingsByProfile("profile-1").single { it.connectionUid == null && it.toolName == "read_url" }.bindingUid)

        // Normal configuration and backup imports keep their atomic replacement contract.
        dao.insertBinding(AgentToolBinding("replacement", "profile-1", null, "read_url", 4))
        dao.insertBinding(AgentToolBinding("remote-replacement", "profile-1", "remote-1", "read_url", 4))
        val bindings = dao.listBindingsByProfile("profile-1")
        assertEquals(setOf("replacement", "other-tool", "remote-replacement", "remote-binding-2"), bindings.map { it.bindingUid }.toSet())
        assertEquals(listOf("other-profile"), dao.listBindingsByProfile("profile-2").map { it.bindingUid })
        sql.execSQL("CREATE TRIGGER test_reject_builtin_replacement BEFORE INSERT ON agent_tool_bindings WHEN NEW.binding_uid = 'failed-replacement' BEGIN SELECT RAISE(ABORT, 'test replacement failure'); END")
        try {
            val failedReplacement = runCatching { dao.insertBinding(AgentToolBinding("failed-replacement", "profile-1", null, "read_url", 5)) }.exceptionOrNull()
            assertTrue(failedReplacement is SQLiteConstraintException)
            assertEquals(bindings, dao.listBindingsByProfile("profile-1"))
        } finally {
            sql.execSQL("DROP TRIGGER test_reject_builtin_replacement")
        }
        dao.deleteConnectionByUid("remote-1")
        assertEquals(setOf("replacement", "other-tool", "remote-binding-2"), dao.listBindingsByProfile("profile-1").map { it.bindingUid }.toSet())

        assertLegacyBuiltInBindingsAreNormalizedOnOpen()
    }

    @Test
    fun builtInToolBinding_replacesAtomicallyDuringConcurrentEnables() = runBlocking {
        val dao = database.toolConnectionDao()
        val original = AgentToolBinding("original", "profile-1", null, "current_date", 1)
        dao.insertBinding(original)
        val replacements = (1..8).map { index -> original.copy(bindingUid = "replacement-$index", createdAt = index.toLong()) }
        val releaseEnables = CompletableDeferred<Unit>()
        val enables = replacements.map { binding ->
            async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                releaseEnables.await()
                dao.insertBinding(binding)
            }
        }

        releaseEnables.complete(Unit)
        enables.awaitAll()

        val retained = dao.listBindingsByProfile("profile-1")
        assertEquals(1, retained.size)
        assertTrue(retained.single() in replacements)
    }

    private suspend fun assertLegacyBuiltInBindingsAreNormalizedOnOpen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "legacy-tool-bindings-${UUID.randomUUID()}.db"
        try {
            val legacy = Room.databaseBuilder(context, ChatDatabaseV2::class.java, name).build()
            try {
                val sql = legacy.openHelper.writableDatabase
                legacy.toolConnectionDao().upsertConnection(toolConnection("remote-legacy"))
                insertRawBinding(sql, "old", createdAt = 1)
                insertRawBinding(sql, "newer-a", createdAt = 2)
                insertRawBinding(sql, "newer-z", createdAt = 2)
                insertRawBinding(sql, "other-profile", profile = "profile-2")
                insertRawBinding(sql, "other-tool", tool = "current_date")
                insertRawBinding(sql, "remote", connection = "remote-legacy")
                assertEquals(3, legacy.toolConnectionDao().listBindingsByProfile("profile-1").count { it.connectionUid == null && it.toolName == "read_url" })
            } finally {
                legacy.close()
            }
            repeat(2) {
                val reopened = Room.databaseBuilder(context, ChatDatabaseV2::class.java, name).addCallback(AgentToolBindingDatabase.callback).build()
                try {
                    assertEquals(setOf("newer-z", "other-tool", "remote"), reopened.toolConnectionDao().listBindingsByProfile("profile-1").map { it.bindingUid }.toSet())
                    assertEquals(listOf("other-profile"), reopened.toolConnectionDao().listBindingsByProfile("profile-2").map { it.bindingUid })
                    assertSqlConstraint { insertRawBinding(reopened.openHelper.writableDatabase, "duplicate-after-reopen") }
                } finally {
                    reopened.close()
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun insertRawBinding(sql: SupportSQLiteDatabase, uid: String, profile: String = "profile-1", connection: String? = null, tool: String = "read_url", createdAt: Long = 1) {
        sql.execSQL("INSERT INTO agent_tool_bindings(binding_uid,profile_uid,connection_uid,tool_name,created_at) VALUES(?,?,?,?,?)", arrayOf<Any?>(uid, profile, connection, tool, createdAt))
    }

    private fun assertSqlConstraint(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("Expected a SQLite constraint failure; received ${error?.javaClass?.name ?: "no error"}", error is SQLiteConstraintException)
    }

    private fun toolConnection(uid: String) = ToolConnection(uid, uid, uid, "MCP", "https://example.com", "NONE", null, null)

    @Test
    fun builtInToolBinding_replacesImportedBindingsWithinItsExactScope() = runBlocking {
        val dao = database.toolConnectionDao()
        dao.upsertConnection(bindingConnection("mcp"))
        val original = AgentToolBinding("legacy-1", "profile-1", null, BuiltInAgentTool.READ_URL, 1)
        val otherProfile = original.copy(bindingUid = "other-profile", profileUid = "profile-2")
        val otherTool = original.copy(bindingUid = "other-tool", toolName = BuiltInAgentTool.CURRENT_DATE)
        val remote = original.copy(bindingUid = "remote-read-url", connectionUid = "mcp")
        // Restored IDs replace the same built-in scope through the supported boundary while SQL guards stay active.
        listOf(original, original.copy(bindingUid = "legacy-2"), otherProfile, otherTool, remote).forEach { dao.insertBinding(it) }

        val replacement = original.copy(bindingUid = "replacement", createdAt = 3)
        dao.replaceReadUrlBinding(replacement)

        assertEquals(setOf(replacement, otherTool, remote), dao.listBindingsByProfile("profile-1").toSet())
        assertEquals(listOf(otherProfile), dao.listBindingsByProfile("profile-2"))
        dao.removeReadUrlBinding("profile-1")
        assertEquals(setOf(otherTool, remote), dao.listBindingsByProfile("profile-1").toSet())

        // Generic configuration/legacy imports retain previously representable tool names.
        val legacy = original.copy(bindingUid = "legacy-name-1", toolName = "retired_builtin")
        dao.insertBinding(legacy)
        dao.insertBinding(legacy.copy(bindingUid = "legacy-name-2"))
        assertEquals(listOf("legacy-name-2"), dao.listBindingsByProfile("profile-1").filter { it.toolName == legacy.toolName }.map { it.bindingUid })
    }

    @Test
    fun builtInToolBinding_rejectsForeignOwnershipAndInvalidRequests() = runBlocking {
        val dao = database.toolConnectionDao()
        dao.upsertConnection(bindingConnection("mcp"))
        val retained = AgentToolBinding("retained", "profile-1", null, BuiltInAgentTool.READ_URL, 1)
        val foreign = retained.copy(bindingUid = "foreign", profileUid = "profile-2")
        dao.insertBinding(retained)
        dao.insertBinding(foreign)
        val invalid = listOf(
            retained.copy(bindingUid = "wrong-profile", profileUid = "profile-2"),
            retained.copy(bindingUid = "wrong-tool", toolName = BuiltInAgentTool.CURRENT_DATE),
            retained.copy(bindingUid = "wrong-connection", connectionUid = "mcp"),
            retained.copy(bindingUid = ""),
            retained.copy(bindingUid = foreign.bindingUid)
        )
        for (binding in invalid) {
            val failure = runCatching { dao.setBuiltInBinding(retained.profileUid, retained.toolName, binding) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(listOf(retained), dao.listBindingsByProfile("profile-1"))
            assertEquals(listOf(foreign), dao.listBindingsByProfile("profile-2"))
        }
        assertTrue(runCatching { dao.setBuiltInBinding("profile-1", "unrecognized", null) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { dao.insertBinding(foreign.copy(profileUid = "profile-1")) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { dao.insertBinding(retained.copy(connectionUid = "mcp")) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { dao.insertBinding(retained.copy(toolName = BuiltInAgentTool.CURRENT_DATE)) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(listOf(retained), dao.listBindingsByProfile("profile-1"))
        assertEquals(listOf(foreign), dao.listBindingsByProfile("profile-2"))
    }

    @Test
    fun builtInToolBinding_failedReplacementRollsBackThePreviousGrant() = runBlocking {
        val dao = database.toolConnectionDao()
        val retained = AgentToolBinding("retained", "profile-1", null, BuiltInAgentTool.READ_URL, 1)
        dao.insertBinding(retained)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_builtin_replacement BEFORE INSERT ON agent_tool_bindings " +
                "WHEN NEW.profile_uid = 'profile-1' AND NEW.connection_uid IS NULL AND NEW.tool_name = 'read_url' " +
                "BEGIN SELECT RAISE(ABORT, 'Injected binding persistence failure'); END"
        )
        try {
            // Exercise the production setter directly: an outer test transaction would hide a missing DAO boundary.
            val failure = runCatching { bindingRepository().setReadUrlBinding("profile-1", true) }.exceptionOrNull()
            assertTrue(failure is SQLiteConstraintException)
            assertEquals(listOf(retained), dao.listBindingsByProfile("profile-1"))
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_builtin_replacement")
        }
    }

    @Test
    fun builtInToolBinding_concurrentUiAndImportWritesKeepOneGrant() = runBlocking {
        val dao = database.toolConnectionDao()
        val repository = bindingRepository()
        val other = AgentToolBinding("other-profile", "profile-2", null, BuiltInAgentTool.READ_URL, 1)
        dao.insertBinding(other)
        val start = CompletableDeferred<Unit>()
        coroutineScope {
            val writes = (0 until 32).map { index ->
                async(Dispatchers.Default) {
                    start.await()
                    if (index % 2 == 0) {
                        repository.setReadUrlBinding("profile-1", true)
                    } else {
                        dao.insertBinding(AgentToolBinding("import-$index", "profile-1", null, BuiltInAgentTool.READ_URL, index.toLong()))
                    }
                }
            }
            start.complete(Unit)
            writes.awaitAll()
        }
        assertEquals(1, dao.listBindingsByProfile("profile-1").size)
        assertEquals(listOf(other), dao.listBindingsByProfile("profile-2"))
        repository.setReadUrlBinding("profile-1", false)
        assertTrue(dao.listBindingsByProfile("profile-1").isEmpty())
        assertEquals(listOf(other), dao.listBindingsByProfile("profile-2"))
    }

    @Test
    fun builtInToolBinding_stagedRestorePreservesUnselectedTools() = runBlocking {
        val source = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ChatDatabaseV2::class.java
        ).build()
        try {
            val dao = source.toolConnectionDao()
            dao.upsertConnection(bindingConnection("mcp-a"))
            dao.upsertConnection(bindingConnection("mcp-b"))
            val original = AgentToolBinding("old", "profile-1", null, BuiltInAgentTool.READ_URL, 1)
            val newest = original.copy(bindingUid = "new-z", createdAt = 2)
            val otherProfile = original.copy(bindingUid = "other-profile", profileUid = "profile-2")
            val otherTool = original.copy(bindingUid = "other-tool", toolName = "retired_builtin")
            val remoteA = original.copy(bindingUid = "remote-a", connectionUid = "mcp-a")
            val remoteB = original.copy(bindingUid = "remote-b", connectionUid = "mcp-b")
            listOf(newest, original, newest.copy(bindingUid = "new-a"), otherProfile, otherTool, remoteA, remoteB).forEach { dao.insertBindingRow(it) }
            assertEquals(3, dao.listBindingsByProfile("profile-1").count { it.connectionUid == null && it.toolName == BuiltInAgentTool.READ_URL })
            // Match prepareSnapshot: normalize the isolated legacy source before copying into a guarded destination.
            source.withTransaction { AgentToolBindingDatabase.enforceBuiltInUniqueness(source.openHelper.writableDatabase) }
            val expected = setOf(newest, otherProfile, otherTool, remoteA, remoteB)
            for (selection in listOf(null, CompleteBackupSelection(setOf(CompleteBackupSection.TOOLS)), CompleteBackupSelection(setOf(CompleteBackupSection.CONVERSATIONS)))) {
                database.clearAllTables()
                val kept = original.copy(bindingUid = "keep-1")
                database.toolConnectionDao().insertBinding(kept)
                database.withTransaction {
                    if (selection == null) {
                        CompleteBackupDatabase.restore(source.openHelper.writableDatabase, database.openHelper.writableDatabase)
                    } else {
                        CompleteBackupDatabase.restoreSections(source.openHelper.writableDatabase, database.openHelper.writableDatabase, selection)
                    }
                }
                val actual = (database.toolConnectionDao().listBindingsByProfile("profile-1") + database.toolConnectionDao().listBindingsByProfile("profile-2")).toSet()
                assertEquals(if (selection?.includes(CompleteBackupSection.CONVERSATIONS) == true) setOf(kept) else expected, actual)
                database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            }
        } finally {
            source.close()
        }
    }

    private fun bindingConnection(uid: String) = ToolConnection(uid, uid, uid, ToolConnectionType.MCP, null, ToolConnectionAuthType.NONE, null, null)

    private fun bindingRepository() = ToolConnectionRepository(
        database.toolConnectionDao(),
        object : SecretVault {
            override suspend fun put(secretRef: String, secret: ByteArray): Unit = error("Binding updates must not write credentials")
            override suspend fun read(secretRef: String): ByteArray? = error("Binding updates must not read credentials")
            override suspend fun delete(secretRef: String): Unit = error("Binding updates must not delete credentials")
        }
    )

    private suspend fun persistTurn(
        chatRoom: ChatRoomV2,
        question: String,
        runId: String,
        createdAt: Long
    ) = database.agentPersistenceDao().persistAgentTurn(
        PersistAgentTurnRequest(
            chatRoom = chatRoom,
            userMessage = MessageV2(
                chatId = chatRoom.id,
                content = question,
                platformType = null,
                createdAt = createdAt
            ),
            runs = listOf(
                AgentRunDraft(
                    runId = runId,
                    profileUid = "profile-1",
                    providerSnapshot = "OPENAI",
                    modelSnapshot = "gpt-5",
                    createdAt = createdAt + 1
                )
            ),
            chatPlatformModels = mapOf("profile-1" to "gpt-5")
        )
    )

    private fun newChat() = ChatRoomV2(
        title = "Chat",
        enabledPlatform = listOf("profile-1"),
        createdAt = 99L,
        updatedAt = 99L
    )
}
