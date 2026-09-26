package dev.chungjungsoo.gptmobile.data.database

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AuditMigrationTest {
    @Test
    fun upgradeFromPublishedSchemaPreservesMessagesAndBuildsSearchIndex() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val sql = javaClass.classLoader!!.getResource("schema-v27.sql")!!.readText()
            sql.lineSequence().filterNot { it.startsWith("--") }.joinToString("\n").split(';').filter { it.isNotBlank() }.forEach(old::execSQL)
            old.execSQL("INSERT INTO chats_v2(chat_id,title,enabled_platform,created_at,updated_at) VALUES(1,'preserved','[]',1,1)")
            old.execSQL("INSERT INTO messages_v2(message_id,chat_id,thoughts,content,attachments,linked_message_id,created_at) VALUES(1,1,'','migration evidence','[]',0,1)")
            old.version = 27
        }
        val migrated = Room.databaseBuilder(context, ChatDatabaseV2::class.java, name)
            .addMigrations(*ChatDatabaseV2Migrations.ALL_MIGRATIONS).build()
        try {
            assertEquals("migration evidence", migrated.messageDao().loadMessages(1).single().content)
            assertEquals(listOf(1), migrated.messageDao().searchMessagesByContent("evidence"))
            assertTrue(migrated.pendingPromptDao().observePending().first().isEmpty())
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
