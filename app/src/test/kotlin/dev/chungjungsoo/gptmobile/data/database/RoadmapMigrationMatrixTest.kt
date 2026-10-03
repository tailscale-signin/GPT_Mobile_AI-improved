package dev.chungjungsoo.gptmobile.data.database

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RoadmapMigrationMatrixTest {
    @Test fun publishedAndIntermediateUpgradePathsPreserveConversationEvidence() = runBlocking {
        for (version in listOf(10, 27, 28, 29, 30, 31, 32)) {
            val context = RuntimeEnvironment.getApplication()
            val name = "roadmap-migration-$version-${UUID.randomUUID()}.db"
            val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                if (version in 27..29) {
                    val sql = javaClass.classLoader!!.getResource("schema-v27.sql")!!.readText()
                    sql.lineSequence().filterNot { it.startsWith("--") }.joinToString("\n").split(';').filter(String::isNotBlank).forEach(old::execSQL)
                    val connection = mockk<SupportSQLiteDatabase>()
                    every { connection.execSQL(any()) } answers { old.execSQL(firstArg<String>()) }
                    ChatDatabaseV2Migrations.ALL_MIGRATIONS.filter { it.startVersion >= 27 && it.endVersion <= version }.forEach { it.migrate(connection) }
                } else {
                    val schema = File(System.getProperty("room.schemas"), "dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/$version.json").readText()
                    val data = Json.parseToJsonElement(schema).jsonObject.getValue("database").jsonObject
                    data.getValue("entities").jsonArray.forEach { entry ->
                        val entity = entry.jsonObject
                        val table = entity.getValue("tableName").jsonPrimitive.content
                        old.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                        entity["indices"]?.jsonArray.orEmpty().forEach { index -> old.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
                        entity["contentSyncTriggers"]?.jsonArray.orEmpty().forEach { old.execSQL(it.jsonPrimitive.content) }
                    }
                }
                old.execSQL("INSERT INTO chats_v2(chat_id,title,enabled_platform,created_at,updated_at) VALUES(1,'preserved','profile',1,1)")
                old.execSQL("INSERT INTO platform_v2(platform_id,uid,name,compatible_type,enabled,api_url,token,secret_ref,model,stream,reasoning,timeout) VALUES(1,'profile','Preserved','OPENAI',0,'https://example.com','migration-fixture-token','migration-fixture-ref','model',1,0,60)")
                if (version >= 27) old.execSQL("UPDATE chats_v2 SET active_platform = 'profile'")
                val columns = old.rawQuery("PRAGMA table_info(messages_v2)", null).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(1)) } }
                val extras = if (version == 10) ",revisions,active_revision_index,timeline" else ""
                val values = if (version == 10) ",'[]',-1,'[]'" else ""
                check("content" in columns)
                old.execSQL("INSERT INTO messages_v2(message_id,chat_id,thoughts,content,attachments,linked_message_id,created_at$extras) VALUES(1,1,'','migration evidence','[]',0,1$values)")
                old.execSQL("INSERT INTO messages_v2(message_id,chat_id,thoughts,content,attachments,linked_message_id,platform_type,created_at$extras) VALUES(2,1,'','saved response','[]',1,'profile',2$values)")
                val gatewayColumn = if (version >= 27) ",gateway_last_sequence" else ""
                val gatewayValue = if (version >= 27) ",0" else ""
                old.execSQL("INSERT INTO agent_runs(run_id,chat_id,user_message_id,assistant_message_id,profile_uid,provider_snapshot,model_snapshot,status,created_at$gatewayColumn) VALUES('saved-run',1,1,2,'profile','test','model','COMPLETED',1$gatewayValue)")
                old.execSQL("INSERT INTO tool_events(event_id,run_id,sequence,call_id,tool_name,model_tool_name,arguments,status,is_error,result) VALUES('saved-event','saved-run',1,'call','read','read','{}','COMPLETED',0,'evidence')")
                old.execSQL("UPDATE sqlite_sequence SET seq = 100 WHERE name = 'messages_v2'")
                old.version = version
            }
            val migrated = Room.databaseBuilder(context, ChatDatabaseV2::class.java, name).addMigrations(*ChatDatabaseV2Migrations.ALL_MIGRATIONS).build()
            try {
                assertEquals("from v$version", "migration evidence", migrated.messageDao().loadMessages(1).first().content)
                assertEquals("from v$version", listOf("profile"), migrated.chatRoomDao().getChatRooms().single().activePlatform)
                val profile = requireNotNull(migrated.platformDao().getPlatformByUid("profile"))
                assertFalse(profile.enabled)
                assertEquals("migration-fixture-token", profile.token)
                val credentialRef = if (version == 10) migrated.providerConnectionDao().getConnection(requireNotNull(profile.providerConnectionUid))?.secretRef else profile.secretRef
                assertEquals("migration-fixture-ref", credentialRef)
                assertEquals("from v$version", listOf(1), migrated.messageDao().searchMessagesByContent("evidence"))
                assertEquals("COMPLETED", migrated.agentRunDao().getById("saved-run")?.status)
                assertEquals("saved response", migrated.messageDao().loadMessages(1).last().content)
                org.junit.Assert.assertTrue(migrated.agentPersistenceDao().insertMessage(dev.chungjungsoo.gptmobile.data.database.entity.MessageV2(chatId = 1, content = "next", platformType = null)) > 100)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { migrated.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) } }
            } finally {
                migrated.close()
                context.deleteDatabase(name)
            }
        }
    }
}
