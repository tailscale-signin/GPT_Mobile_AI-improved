package dev.chungjungsoo.gptmobile.data.database

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayMigrationRecord
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationSnapshot
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import dev.chungjungsoo.gptmobile.data.github.gatewayIdentityKey
import io.mockk.mockk
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
class GatewayJobsMigrationTest {
    @Test fun version49DatabaseUpgradesInPlaceAndRoomValidatesTheEntireSchema() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "gateway-migration-${UUID.randomUUID()}"
        val schemaFile = listOf(File("schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/49.json"), File("app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/49.json")).first(File::exists)
        val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject.getValue("database").jsonObject
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(49) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    for (element in schema.getValue("entities").jsonArray) {
                        val entity = element.jsonObject
                        val table = entity.getValue("tableName").jsonPrimitive.content
                        db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                        for (index in entity["indices"]?.jsonArray.orEmpty()) db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                    }
                    schema["views"]?.jsonArray?.forEach { db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content) }
                    schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                    db.execSQL("INSERT INTO chats_v2(chat_id,title,enabled_platform,created_at,updated_at) VALUES(7,'Keep original room','profile',1,2)")
                    db.execSQL("INSERT INTO messages_v2(message_id,chat_id,thoughts,content,attachments,linked_message_id,created_at) VALUES(11,7,'','Keep original turn','[]',0,1)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        )
        helper.writableDatabase
        helper.close()
        val database = Room.databaseBuilder(context, ChatDatabaseV2::class.java, name).addMigrations(*ChatDatabaseV2Migrations.ALL_MIGRATIONS).build()
        try {
            assertEquals("Keep original room", database.gatewayJobDao().localChat(7)?.title)
            assertEquals("Keep original turn", database.gatewayJobDao().localMessage(11)?.content)
            assertEquals(51, database.openHelper.readableDatabase.version)
            database.gatewayJobDao().saveMigration(GatewayMigrationRecord("verified", "checksum", 1))
            assertNotNull(database.gatewayJobDao().migration("verified"))
            database.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'gateway_%'").use { rows -> assertTrue(rows.count >= 7) }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun canonical50LargeMessageMigratesWithoutLoadingAnOversizedCursorRow() = runBlocking {
        val content = "α🌍 original saved text\n".repeat(100_000)
        val key = gatewayIdentityKey("gateway", "principal", "conversation")
        val snapshot = Json.encodeToString(GatewayConversationSnapshot(gatewayId = "gateway", principalId = "principal", conversationId = "conversation", profileId = "profile", model = "model"))
        val name = seed50(canonical = true) { db ->
            db.execSQL("INSERT INTO gateway_conversations VALUES(?,?,?,?,?,?,?,?,?,3,'retained-cursor',1,0)", arrayOf<Any?>(key, "gateway", "principal", "conversation", "route", "http://127.0.0.1:8081", "profile", 7, snapshot))
            db.execSQL("INSERT INTO gateway_messages VALUES(?,?,?,?,?,'completed',3,'original:assistant',1,NULL)", arrayOf<Any?>(key, "original", "command", "assistant", content))
        }
        val db = openMigrated(name)
        try {
            assertEquals(51, db.openHelper.readableDatabase.version)
            assertEquals("retained-cursor", db.gatewayJobDao().conversation(key)?.messageCursor)
            assertTrue(requireNotNull(db.gatewayJobDao().message(key, "original")).content.length < 1000)
            val parts = mutableListOf<dev.chungjungsoo.gptmobile.data.database.entity.GatewayMessagePartRecord>()
            do {
                val page = db.gatewayJobDao().messageParts(key, "original", 3, parts.size)
                parts += page
            } while (page.size == 8)
            assertTrue(parts.size > 8)
            assertTrue(parts.all { it.content.toByteArray(Charsets.UTF_8).size <= 256 * 1024 && it.contentVerified })
            assertEquals(content, parts.joinToString("") { it.content })
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            assertEquals(listOf(digest), parts.map { it.contentSha256 }.distinct())
            assertEquals("Keep original turn", db.gatewayJobDao().localMessage(11)?.content)
        } finally {
            db.close()
            RuntimeEnvironment.getApplication().deleteDatabase(name)
        }
    }

    @Test fun canonical50LinkedOversizedMirrorRetainsDifferentLocalTextTimelineAndOriginalFence() = runBlocking {
        val gatewayText = "Gateway canonical 🌍\n".repeat(120_000)
        val localText = "Original local partial α🌍\n".repeat(110_000)
        val timeline = "[{\"type\":\"TEXT\",\"content\":" + org.json.JSONObject.quote(localText) + "}]"
        val key = gatewayIdentityKey("gateway", "principal", "conversation")
        val snapshot = Json.encodeToString(GatewayConversationSnapshot(gatewayId = "gateway", principalId = "principal", conversationId = "conversation", profileId = "profile", model = "model"))
        val name = seed50(canonical = true) { db ->
            db.execSQL("INSERT INTO messages_v2(message_id,chat_id,thoughts,content,attachments,linked_message_id,created_at,platform_type,timeline,current_run_id,ownership_fence) VALUES(12,7,'',?,'[]',11,1,'profile',?,'original-run',9)", arrayOf<Any?>(localText, timeline))
            db.execSQL("INSERT INTO gateway_conversations VALUES(?,?,?,?,?,?,?,?,?,3,'retained-cursor',1,0)", arrayOf<Any?>(key, "gateway", "principal", "conversation", "route", "http://127.0.0.1:8081", "profile", 7, snapshot))
            db.execSQL("INSERT INTO gateway_messages VALUES(?,?,?,?,?,'completed',3,'original:assistant',1,12)", arrayOf<Any?>(key, "original", "command", "assistant", gatewayText))
        }
        val db = openMigrated(name)
        try {
            val message = requireNotNull(db.gatewayJobDao().localMessage(12))
            assertTrue(message.content.contains("Saved transcript"))
            assertTrue(message.timeline.single().content.contains("Saved transcript"))
            assertEquals("original-run", message.currentRunId)
            assertEquals(9L, message.ownershipFence)
            assertEquals(11, message.linkedMessageId)
            for ((logicalId, revision, original) in listOf(Triple("original", 3L, gatewayText), Triple("retained-local:12:content", 9L, localText), Triple("retained-local:12:timeline", 9L, timeline))) {
                val parts = mutableListOf<dev.chungjungsoo.gptmobile.data.database.entity.GatewayMessagePartRecord>()
                do {
                    val page = db.gatewayJobDao().messageParts(key, logicalId, revision, parts.size)
                    parts += page
                } while (page.size == 8)
                assertTrue(parts.size > 8)
                assertTrue(parts.all { it.content.toByteArray(Charsets.UTF_8).size <= 256 * 1024 && it.contentVerified })
                assertEquals(original, parts.joinToString("") { it.content })
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(original.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                assertEquals(listOf(hash), parts.map { it.contentSha256 }.distinct())
            }
            assertEquals("Keep original turn", db.gatewayJobDao().localMessage(11)?.content)
        } finally {
            db.close()
            RuntimeEnvironment.getApplication().deleteDatabase(name)
        }
    }

    @Test fun alternate50RetainsExactCommandBindingsAndReadableEvidenceWithoutReplayingIncompatiblePayload() = runBlocking {
        val retained = "Retained Unicode evidence αβ\n".repeat(12_000)
        val request = """{"profileUid":"route","endpoint":"http://127.0.0.1:8081","principalId":"principal","gatewayId":"gateway","userMessageId":11,"assistantMessageId":12,"localRunId":"original-run","request":{"repository":"owner/repo","branch":"work","base":"main","objective":"Read it","model":"model","chat_id":7,"request_id":"original-command","profile_id":"profile","scope":"read","source_branch":"feature","source_sha":"${"a".repeat(40)}"}}"""
        val name = seed50 { db ->
            db.execSQL("INSERT INTO gateway_scopes VALUES('scope','route','http://127.0.0.1:8081','gateway','principal','account','ONLINE',1000,'old-catalog-cursor',0,NULL)")
            db.execSQL("INSERT INTO gateway_conversations VALUES('old-key','scope','conversation',7,'profile','Original','model','owner/repo','feature','Retained title',4,NULL,NULL,1,NULL,4000,?,0,0,'CONTINUITY')", arrayOf<Any?>("a".repeat(40)))
            db.execSQL("INSERT INTO gateway_messages VALUES('old-key',1,'message','job','assistant','final',?,1000,'fingerprint',NULL,'logical',0,1,NULL,0,0)", arrayOf<Any?>(retained))
            db.execSQL("INSERT INTO gateway_replay_cursors VALUES('old-key',1,'incompatible-opaque',0,1,NULL,NULL)")
            db.execSQL("INSERT INTO gateway_engineering_bindings VALUES('original-command','scope',7,NULL,?,-1,-1,-1)", arrayOf<Any?>(request))
            db.execSQL("INSERT INTO gateway_engineering_updates VALUES('original-command',1,'QUEUED','Saved before response',1)")
            db.execSQL("INSERT INTO gateway_commands VALUES('original-command','scope','old-key',7,11,12,'original-run','create','original-digest','exact-original-payload','UNKNOWN',NULL,1000,2000,'lost response')")
        }
        val db = openMigrated(name)
        try {
            val key = gatewayIdentityKey("gateway", "principal", "conversation")
            val conversation = requireNotNull(db.gatewayJobDao().conversation(key))
            assertEquals(7, conversation.localChatId)
            assertEquals("profile", conversation.executionProfileUid)
            assertNull(conversation.messageCursor)
            assertFalse(conversation.historyComplete)
            val command = requireNotNull(db.gatewayJobDao().submission("original-command"))
            assertEquals("MIGRATED_REVIEW", command.state)
            assertEquals("original-run", command.runId)
            assertEquals(11, command.userMessageId)
            assertEquals(12, command.assistantMessageId)
            assertEquals("exact-original-payload", command.payload)
            val binding = Json { ignoreUnknownKeys = true }.decodeFromString<GatewayEngineeringBinding>(requireNotNull(db.gatewayJobDao().binding("original-command")).binding)
            assertEquals("read", binding.request.taskScope)
            assertEquals("feature", binding.request.sourceBranch)
            assertEquals("a".repeat(40), binding.request.sourceSha)
            assertEquals(11, binding.localUserMessageId)
            assertEquals(12, binding.localAssistantMessageId)
            assertEquals("original-run", binding.localRunId)
            val repository = GatewayConversationRepository(db, mockk(), mockk(), mockk(), mockk())
            var cursor: String? = null
            val recovered = StringBuilder()
            do {
                val page = repository.transcriptPage(key, cursor)
                assertFalse(page.historyComplete)
                assertTrue(page.items.all { !it.contentVerified && it.content.toByteArray(Charsets.UTF_8).size <= 256 * 1024 })
                page.items.forEach { recovered.append(it.content) }
                cursor = page.nextCursor
            } while (cursor != null)
            assertEquals(retained, recovered.toString())
            db.openHelper.readableDatabase.query("SELECT binding FROM retained_v50_gateway_engineering_bindings").use {
                assertTrue(it.moveToFirst())
                assertEquals(request, it.getString(0))
            }
            assertEquals("Keep original turn", db.gatewayJobDao().localMessage(11)?.content)
        } finally {
            db.close()
            RuntimeEnvironment.getApplication().deleteDatabase(name)
        }
    }

    private fun seed50(canonical: Boolean = false, seed: (SupportSQLiteDatabase) -> Unit): String {
        val context = RuntimeEnvironment.getApplication()
        val name = "gateway-fifty-${UUID.randomUUID()}"
        val text = if (canonical) {
            requireNotNull(javaClass.classLoader?.getResource("gateway-schema50-canonical.json")).readText()
        } else {
            listOf(File("schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/50.json"), File("app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/50.json")).first(File::exists).readText()
        }
        val schema = Json.parseToJsonElement(text).jsonObject.getValue("database").jsonObject
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(50) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    for (element in schema.getValue("entities").jsonArray) {
                        val entity = element.jsonObject
                        val table = entity.getValue("tableName").jsonPrimitive.content
                        db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                        for (index in entity["indices"]?.jsonArray.orEmpty()) db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                    }
                    schema["views"]?.jsonArray?.forEach { db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content) }
                    schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                    db.execSQL("INSERT INTO chats_v2(chat_id,title,enabled_platform,created_at,updated_at) VALUES(7,'Keep original room','profile',1,2)")
                    db.execSQL("INSERT INTO messages_v2(message_id,chat_id,thoughts,content,attachments,linked_message_id,created_at) VALUES(11,7,'','Keep original turn','[]',0,1)")
                    seed(db)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        )
        helper.writableDatabase
        helper.close()
        return name
    }

    private fun openMigrated(name: String): ChatDatabaseV2 = Room.databaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java, name).addMigrations(*ChatDatabaseV2Migrations.ALL_MIGRATIONS).build()
}
