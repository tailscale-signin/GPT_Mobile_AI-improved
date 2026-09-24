package dev.chungjungsoo.gptmobile.data.backup

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentToolBinding
import dev.chungjungsoo.gptmobile.data.database.entity.ChatPlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.LocalModel
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.OpenRouterBatchCacheEntity
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ChatAttachment
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
class CompleteBackupManagerTest {
    private lateinit var context: Context
    private lateinit var database: ChatDatabaseV2
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vault = TestVault()
    private val settings = mockk<SettingRepository>(relaxed = true)
    private val legacy = mockk<AppBackupManager>(relaxed = true)
    private lateinit var preferences: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var manager: CompleteBackupManager

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        database = Room.databaseBuilder(context, ChatDatabaseV2::class.java, "test-${UUID.randomUUID()}.db").build()
        preferences = PreferenceDataStoreFactory.create(scope = scope) { File(context.cacheDir, "${UUID.randomUUID()}.preferences_pb") }
        every { legacy.getBackupStatus() } returns BackupStatus()
        manager = CompleteBackupManager(context, database, preferences, vault, settings, legacy)
    }

    @After
    fun cleanup() {
        database.close()
        scope.cancel()
    }

    @Test
    fun completeRoundTripRestoresEveryTablePreferencesCredentialsAndPortableFiles() = runBlocking {
        val attachment = File(context.cacheDir, "photo.txt").apply { writeText("attachment data") }
        seed(attachment)
        preferences.edit {
            it[booleanPreferencesKey("gateway_stable")] = false
            it[intPreferencesKey("soft_rounds")] = 0
            it[stringSetPreferencesKey("favorite_groups")] = setOf("work", "personal")
        }
        context.getSharedPreferences("llama_settings", 0).edit().putString("advanced_settings", "{\"stableToolSurface\":false}").commit()
        vault.put("provider", "provider-token".toByteArray())
        vault.put("tool", "tool-token".toByteArray())
        val archive = File(context.cacheDir, "complete.gptbackup")
        val saved = manager.backup(Uri.fromFile(archive))
        assertTrue(saved.message, saved.success)
        // Simulate changed data and a different attachment location/device.
        database.chatRoomDao().updateTitle(7, "changed", true)
        database.agentRunDao().updateStatus("run", "COMPLETED", null, null, null)
        preferences.edit {
            it.clear()
            it[intPreferencesKey("new_setting")] = 99
        }
        context.getSharedPreferences("llama_settings", 0).edit().clear().commit()
        vault.put("provider", "changed-token".toByteArray())
        vault.put("extra", "extra-token".toByteArray())
        attachment.delete()
        File(context.filesDir, "remove.txt").writeText("newer data")
        val restored = manager.restore(Uri.fromFile(archive))
        assertTrue(restored.message, restored.success)
        val chat = database.chatRoomDao().getChatRooms().single()
        assertEquals("saved", chat.title)
        assertTrue(chat.isFavorite)
        assertEquals("draft", chat.draftText)
        val message = database.messageDao().loadMessages(7).first { it.id == 11 }
        assertTrue(message.isFavorite)
        assertEquals("attachment data", File(message.attachments.single().localFilePath).readText())
        assertTrue(message.attachments.single().localFilePath.startsWith(context.filesDir.absolutePath))
        assertEquals("provider-token", vault.read("provider")!!.decodeToString())
        assertEquals("tool-token", vault.read("tool")!!.decodeToString())
        assertFalse("extra" in vault.references())
        assertEquals(false, preferences.data.first()[booleanPreferencesKey("gateway_stable")])
        assertEquals(0, preferences.data.first()[intPreferencesKey("soft_rounds")])
        assertEquals(setOf("work", "personal"), preferences.data.first()[stringSetPreferencesKey("favorite_groups")])
        assertEquals(null, preferences.data.first()[intPreferencesKey("new_setting")])
        assertEquals("{\"stableToolSurface\":false}", context.getSharedPreferences("llama_settings", 0).getString("advanced_settings", null))
        assertFalse(File(context.filesDir, "remove.txt").exists())
        assertEquals("model", database.chatPlatformModelDao().getByChatId(7).single().model)
        assertEquals("READY", database.localModelDao().getAll().single().status)
        assertEquals("model bytes", File(context.getExternalFilesDir(null), "models/local/revision/model.bin").readText())
        assertEquals("cached", database.openRouterBatchCacheDao().getByCacheKey("key")!!.responseContent)
        assertEquals("tool", database.toolConnectionDao().getAllConnections().single().secretRef)
        assertEquals("write_file", database.toolConnectionDao().listBindingsByProfile("profile").single().toolName)
        assertEquals("provider", database.platformDao().getPlatforms().single().secretRef)
        assertEquals("INTERRUPTED", database.agentRunDao().getById("run")!!.status)
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.query("SELECT status FROM tool_events").use {
                assertTrue(it.moveToFirst())
                assertEquals("CANCELED", it.getString(0))
            }
            database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
        verify { settings.invalidatePlatformCache() }
    }

    @Test
    fun corruptBackupAndCredentialWriteFailureLeaveExistingStateIntact() = runBlocking {
        seed(File(context.cacheDir, "file").apply { writeText("old attachment") })
        vault.put("provider", "saved-token".toByteArray())
        val archive = File(context.cacheDir, "complete.gptbackup")
        assertTrue(manager.backup(Uri.fromFile(archive)).success)
        database.agentRunDao().updateStatus("run", "COMPLETED", null, null, null)
        database.chatRoomDao().updateTitle(7, "current", true)
        preferences.edit { it[intPreferencesKey("current")] = 7 }
        vault.put("provider", "current-token".toByteArray())
        File(context.filesDir, "current.txt").writeText("current file")
        val encryptedBytes = archive.readBytes()
        archive.writeBytes(encryptedBytes.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        })
        assertFalse(manager.restore(Uri.fromFile(archive)).success)
        archive.writeBytes(encryptedBytes)
        vault.failNextPut = true
        assertFalse(manager.restore(Uri.fromFile(archive)).success)
        assertEquals("current", database.chatRoomDao().getChatRooms().single().title)
        assertEquals(7, preferences.data.first()[intPreferencesKey("current")])
        assertEquals("current-token", vault.read("provider")!!.decodeToString())
        assertEquals("current file", File(context.filesDir, "current.txt").readText())
    }

    private suspend fun seed(attachment: File) {
        database.chatRoomDao().addChatRoom(ChatRoomV2(id = 7, title = "saved", enabledPlatform = listOf("profile"), isFavorite = true, draftText = "draft"))
        database.messageDao().addMessages(
            MessageV2(
                id = 11,
                chatId = 7,
                content = "question",
                platformType = null,
                isFavorite = true,
                attachments = listOf(ChatAttachment(attachment.absolutePath, "", "photo.txt", "text/plain", attachment.length()))
            ),
            MessageV2(id = 12, chatId = 7, content = "answer", platformType = "profile")
        )
        database.platformDao().addPlatform(PlatformV2(id = 9, uid = "profile", name = "saved", secretRef = "provider"))
        database.chatPlatformModelDao().upsertChatPlatformModel(ChatPlatformModelV2(7, "profile", "model"))
        database.toolConnectionDao().upsertConnection(
            dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection(
                "connection",
                "GitHub",
                "github",
                "MCP",
                "https://example.com/mcp",
                "BEARER",
                "tool",
                null
            )
        )
        database.toolConnectionDao().insertBinding(AgentToolBinding("binding", "profile", "connection", "write_file"))
        database.agentRunDao().upsert(AgentRun("run", 7, 11, 12, "profile", "{}", "model", status = "RUNNING"))
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL("INSERT INTO tool_events(event_id, run_id, sequence, call_id, tool_name, model_tool_name, arguments, status, is_error) VALUES ('event', 'run', 1, 'call', 'write_file', 'write_file', '{}', 'RUNNING', 0)")
        }
        File(context.getExternalFilesDir(null), "models/local/revision/model.bin").apply {
            parentFile!!.mkdirs()
            writeText("model bytes")
        }
        database.localModelDao().upsert(LocalModel("local", "revision", "model.bin", "models/local/revision", 11, "READY"))
        database.openRouterBatchCacheDao().insertOrUpdate(OpenRouterBatchCacheEntity(cacheKey = "key", responseContent = "cached"))
    }

    private class TestVault : SecretVault {
        private val values = mutableMapOf<String, ByteArray>()
        var failNextPut = false
        override suspend fun put(secretRef: String, secret: ByteArray) {
            if (failNextPut) {
                failNextPut = false
                error("Injected credential write failure")
            }
            values[secretRef] = secret.copyOf()
        }
        override suspend fun read(secretRef: String) = values[secretRef]?.copyOf()
        override suspend fun delete(secretRef: String) {
            values.remove(secretRef)
        }
        override suspend fun references() = values.keys.toSet()
    }
}
