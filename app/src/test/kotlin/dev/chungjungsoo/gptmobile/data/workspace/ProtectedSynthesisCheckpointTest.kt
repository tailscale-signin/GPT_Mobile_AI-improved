package dev.chungjungsoo.gptmobile.data.workspace

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
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
    private val vault = object : SecretVault {
        override suspend fun put(secretRef: String, secret: ByteArray) {
            require(secret.size <= 65_536)
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
}
