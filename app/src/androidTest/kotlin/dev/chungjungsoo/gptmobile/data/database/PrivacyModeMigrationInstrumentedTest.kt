package dev.chungjungsoo.gptmobile.data.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyModeMigrationInstrumentedTest {
    @Test fun upgradePreservesConversationHistoryAndPrivacyUseSurvivesReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "privacy-mode-migration-${UUID.randomUUID()}"
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/52.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject.getValue("database").jsonObject }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(52) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schema.getValue("entities").jsonArray.forEach { entry ->
                        val entity = entry.jsonObject
                        val table = entity.getValue("tableName").jsonPrimitive.content
                        db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                        entity["indices"]?.jsonArray.orEmpty().forEach { index ->
                            db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                        }
                        entity["contentSyncTriggers"]?.jsonArray.orEmpty().forEach { db.execSQL(it.jsonPrimitive.content) }
                    }
                    schema["views"]?.jsonArray.orEmpty().forEach { db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content) }
                    schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                    db.execSQL("INSERT INTO chats_v2(chat_id,title,enabled_platform,created_at,updated_at,is_favorite,draft_text) VALUES(7,'Preserved conversation','profile',1,2,1,'Preserved draft')")
                    db.execSQL("INSERT INTO messages_v2(message_id,chat_id,thoughts,content,attachments,linked_message_id,created_at) VALUES(11,7,'','Preserved message','[]',0,1)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        )
        helper.writableDatabase
        helper.close()
        fun open() = Room.databaseBuilder(context, ChatDatabaseV2::class.java, name)
            .addMigrations(*ChatDatabaseV2Migrations.ALL_MIGRATIONS).build()
        var database = open()
        try {
            val original = database.chatRoomDao().getChatRooms().single()
            assertEquals(53, database.openHelper.readableDatabase.version)
            assertEquals("Preserved conversation", original.title)
            assertEquals("Preserved draft", original.draftText)
            assertTrue(original.isFavorite)
            assertFalse(original.privacyModeEnabled)
            assertEquals("Preserved message", database.messageDao().loadMessages(original.id).single().content)
            database.chatRoomDao().editChatRoom(original.copy(privacyModeEnabled = true))
            database.close()
            database = open()
            assertTrue(database.chatRoomDao().getChatRooms().single().privacyModeEnabled)
            assertEquals("Preserved message", database.messageDao().loadMessages(original.id).single().content)
            database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
