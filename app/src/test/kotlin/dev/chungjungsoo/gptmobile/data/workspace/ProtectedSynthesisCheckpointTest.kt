package dev.chungjungsoo.gptmobile.data.workspace

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ProtectedSynthesisCheckpointTest {
    private lateinit var database: ChatDatabaseV2
    private val secrets = mutableMapOf<String, ByteArray>()
    private var beforePut: (suspend () -> Unit)? = null
    private val vault = object : SecretVault {
        override suspend fun put(secretRef: String, secret: ByteArray) {
            require(secret.size <= 65_536)
            beforePut?.let { action ->
                beforePut = null
                action()
            }
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

    @Test fun largeUnicodeReportSurvivesReopenWithoutPlaintextInMetadata() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Checkpoint")).toInt()
        val report = "Private 🌍 evidence ".repeat(5000)
        val payload = buildJsonObject { put("report", report) }
        WorkspaceRepository(database, vault).saveProtectedSynthesisCheckpoint("pass", chat, "run", payload)
        assertTrue(secrets.size > 1)
        assertTrue(secrets.values.all { it.size <= 65_536 })
        assertFalse(database.workspaceDao().get("pass")!!.payload.contains("Private"))
        assertEquals(payload, WorkspaceRepository(database, vault).protectedSynthesisCheckpoint("pass"))
    }

    @Test fun missingOrCorruptChunksAreNeverReturnedAsCompletedEvidence() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Checkpoint")).toInt()
        val repository = WorkspaceRepository(database, vault)
        repository.saveProtectedSynthesisCheckpoint("pass", chat, "run", buildJsonObject { put("report", "Private evidence") })
        val key = secrets.keys.single()
        secrets[key] = "altered".encodeToByteArray()
        assertNull(repository.protectedSynthesisCheckpoint("pass"))
        secrets.clear()
        assertNull(repository.protectedSynthesisCheckpoint("pass"))
    }

    @Test fun replacingCheckpointReleasesPriorChunksAndTemporaryChatsStoreNothing() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Checkpoint")).toInt()
        val temporary = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Temporary", isTemporary = true)).toInt()
        val repository = WorkspaceRepository(database, vault)
        repository.saveProtectedSynthesisCheckpoint("pass", chat, "run", buildJsonObject { put("report", "First") })
        val previous = secrets.keys.toSet()
        repository.saveProtectedSynthesisCheckpoint("pass", chat, "run", buildJsonObject { put("report", "Second") })
        assertTrue(secrets.keys.intersect(previous).isEmpty())
        assertEquals(JsonPrimitive("Second"), repository.protectedSynthesisCheckpoint("pass")?.get("report"))
        val count = secrets.size
        repository.saveProtectedSynthesisCheckpoint("private", temporary, "run", buildJsonObject { put("report", "Do not retain") })
        assertNull(database.workspaceDao().get("private"))
        assertEquals(count, secrets.size)
    }

    private suspend fun terminalOwner(chatId: Int): AgentRun {
        database.messageDao().addMessages(
            MessageV2(id = 1, chatId = chatId, content = "Task", platformType = null),
            MessageV2(id = 2, chatId = chatId, platformType = "p", linkedMessageId = 1, currentRunId = "run", ownershipFence = 7)
        )
        return AgentRun("run", chatId, 1, 2, "p", "CUSTOM", "model", "FAILED", startedAt = 5, ownershipFence = 7).also { database.agentRunDao().upsert(it) }
    }

    @Test fun terminalCheckpointCompareAndSetRequiresTheExactBytesAndCurrentOwner() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Checkpoint")).toInt()
        val run = terminalOwner(chat)
        val repository = WorkspaceRepository(database, vault)
        val saved = buildJsonObject {
            put("state", "WAITING_PROVIDER_LIMIT")
            put("resume_allowed", true)
            put("draft", "Retained")
        }
        val terminal = buildJsonObject {
            put("state", "FAILED")
            put("resume_allowed", false)
            put("draft", "Retained")
        }
        repository.saveProtectedSynthesisCheckpoint("pass", chat, run.runId, saved)
        val originalRefs = secrets.keys.toSet()
        val digest = WorkspaceRepository.digest(saved.toString())
        assertFalse(repository.finishProviderCheckpoint("pass", chat, run.runId, 8, digest, "FAILED", terminal))
        assertFalse(repository.finishProviderCheckpoint("pass", chat, run.runId, 7, "0".repeat(64), "FAILED", terminal))
        assertFalse(repository.finishProviderCheckpoint("pass", chat, run.runId, 7, digest, "CANCELED", terminal))
        assertEquals(originalRefs, secrets.keys)
        assertEquals(saved, repository.protectedSynthesisCheckpoint("pass"))
        assertTrue(repository.finishProviderCheckpoint("pass", chat, run.runId, 7, digest, "FAILED", terminal))
        assertEquals(terminal, repository.protectedSynthesisCheckpoint("pass"))
        assertTrue(secrets.keys.intersect(originalRefs).isEmpty())
    }

    @Test fun checkpointChangedWhileEncryptingIsRetainedAndUnpublishedChunksAreRemoved() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Checkpoint")).toInt()
        val run = terminalOwner(chat)
        val repository = WorkspaceRepository(database, vault)
        val saved = buildJsonObject {
            put("state", "WAITING_PROVIDER_LIMIT")
            put("resume_allowed", true)
        }
        val newer = buildJsonObject {
            put("state", "WAITING_PROVIDER_LIMIT")
            put("resume_allowed", true)
            put("draft", "New evidence")
        }
        repository.saveProtectedSynthesisCheckpoint("pass", chat, run.runId, saved)
        beforePut = { repository.saveProtectedSynthesisCheckpoint("pass", chat, run.runId, newer) }
        assertFalse(repository.finishProviderCheckpoint("pass", chat, run.runId, 7, WorkspaceRepository.digest(saved.toString()), "FAILED", buildJsonObject { put("state", "FAILED") }))
        assertEquals(newer, repository.protectedSynthesisCheckpoint("pass"))
        assertEquals(1, secrets.size)
    }

    @Test fun supersededOwnerCannotTerminalizeAStillReadableCheckpoint() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Checkpoint")).toInt()
        val run = terminalOwner(chat)
        val repository = WorkspaceRepository(database, vault)
        val saved = buildJsonObject {
            put("state", "WAITING_PROVIDER_LIMIT")
            put("resume_allowed", true)
        }
        repository.saveProtectedSynthesisCheckpoint("pass", chat, run.runId, saved)
        beforePut = {
            val message = requireNotNull(database.agentPersistenceDao().rawRecoveryMessage(2))
            database.agentPersistenceDao().rawUpdateMessage(message.copy(currentRunId = "new-run", ownershipFence = 8))
        }
        assertFalse(repository.finishProviderCheckpoint("pass", chat, run.runId, 7, WorkspaceRepository.digest(saved.toString()), "FAILED", buildJsonObject { put("state", "FAILED") }))
        assertEquals(saved, repository.protectedSynthesisCheckpoint("pass"))
        assertEquals(1, secrets.size)
    }
}
