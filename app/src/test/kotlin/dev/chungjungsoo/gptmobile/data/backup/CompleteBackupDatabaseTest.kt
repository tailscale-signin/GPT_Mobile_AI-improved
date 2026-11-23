package dev.chungjungsoo.gptmobile.data.backup

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CompleteBackupDatabaseTest {
    @Test
    fun reorderedColumnsAndAdditiveDefaultsRestoreByNameWithoutLosingTypedValues() {
        helper("CREATE TABLE records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, body TEXT NOT NULL, payload BLOB, retired TEXT)").use { source ->
            helper("CREATE TABLE records (body TEXT NOT NULL, id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, payload BLOB, optional TEXT)").use { target ->
                source.writableDatabase.execSQL("INSERT INTO records (id, body, payload, retired) VALUES (?, ?, ?, ?)", arrayOf(41, "Saved message", byteArrayOf(1, 2, 3), "old setting"))
                CompleteBackupDatabase.restore(source.writableDatabase, target.writableDatabase)
                target.writableDatabase.query("SELECT id, body, enabled, payload, optional FROM records").use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(41, rows.getInt(0))
                    assertEquals("Saved message", rows.getString(1))
                    assertEquals(1, rows.getInt(2))
                    assertArrayEquals(byteArrayOf(1, 2, 3), rows.getBlob(3))
                    assertTrue(rows.isNull(4))
                    assertFalse(rows.moveToNext())
                }
                target.writableDatabase.execSQL("INSERT INTO records(body) VALUES ('Next')")
                target.writableDatabase.query("SELECT MAX(id) FROM records").use { rows ->
                    rows.moveToFirst()
                    assertEquals(42, rows.getInt(0))
                }
            }
        }
    }

    @Test
    fun missingRequiredValuesFailBeforeDeletingCurrentData() {
        helper("CREATE TABLE records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)").use { source ->
            helper("CREATE TABLE records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, body TEXT NOT NULL)").use { target ->
                source.writableDatabase.execSQL("INSERT INTO records(id) VALUES (1)")
                target.writableDatabase.execSQL("INSERT INTO records(id, body) VALUES (2, 'Keep me')")
                val result = runCatching { CompleteBackupDatabase.restore(source.writableDatabase, target.writableDatabase) }
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull()!!.message!!.contains("required data"))
                target.writableDatabase.query("SELECT body FROM records").use { rows ->
                    rows.moveToFirst()
                    assertEquals("Keep me", rows.getString(0))
                }
            }
        }
    }

    @Test
    fun completeRestorePreservesPermanentPrivacyWithoutInventingMaskingHistory() {
        helper(chatSchema()).use { source ->
            helper(chatSchema()).use { target ->
                source.writableDatabase.execSQL("INSERT INTO chats_v2(chat_id,title,privacy_mode_locked,privacy_mode_enabled) VALUES (7,'Older ordinary snapshot',0,0),(8,'Ordinary',0,0),(9,'Saved private',1,1)")
                target.writableDatabase.execSQL("INSERT INTO chats_v2(chat_id,title,privacy_mode_locked,privacy_mode_enabled) VALUES (7,'Currently private',1,1),(9,'Currently ordinary',0,0),(10,'Absent from backup',1,1)")

                CompleteBackupDatabase.restore(source.writableDatabase, target.writableDatabase)

                assertPrivacy(target.writableDatabase, 7, locked = true, maskingHistory = false)
                assertPrivacy(target.writableDatabase, 8, locked = false, maskingHistory = false)
                assertPrivacy(target.writableDatabase, 9, locked = true, maskingHistory = true)
                target.writableDatabase.query("SELECT 1 FROM chats_v2 WHERE chat_id = 10").use { assertFalse(it.moveToFirst()) }
            }
        }
    }

    @Test
    fun conversationSectionRestorePreservesPermanentPrivacyAcrossAnOlderSnapshot() {
        helper(chatSchema(), agentSchema, agentPolicyRefSchema).use { source ->
            helper(chatSchema(), agentSchema, agentPolicyRefSchema).use { target ->
                source.writableDatabase.execSQL("INSERT INTO chats_v2(chat_id,title) VALUES (7,'Older ordinary snapshot'),(8,'Ordinary')")
                target.writableDatabase.execSQL("INSERT INTO chats_v2(chat_id,title,privacy_mode_locked,privacy_mode_enabled) VALUES (7,'Currently private',1,1)")

                CompleteBackupDatabase.restoreSections(
                    source.writableDatabase,
                    target.writableDatabase,
                    CompleteBackupSelection(setOf(CompleteBackupSection.CONVERSATIONS))
                )

                assertPrivacy(target.writableDatabase, 7, locked = true, maskingHistory = false)
                assertPrivacy(target.writableDatabase, 8, locked = false, maskingHistory = false)
            }
        }
    }

    @Test
    fun backupWithoutThePrivacyColumnCannotUnlockAnExistingPrivateConversation() {
        helper(chatSchema(includePrivacyLock = false)).use { source ->
            helper(chatSchema()).use { target ->
                source.writableDatabase.execSQL("INSERT INTO chats_v2(chat_id,title) VALUES (7,'Legacy snapshot'),(8,'Ordinary')")
                target.writableDatabase.execSQL("INSERT INTO chats_v2(chat_id,title,privacy_mode_locked) VALUES (7,'Currently private',1)")

                CompleteBackupDatabase.restore(source.writableDatabase, target.writableDatabase)

                assertPrivacy(target.writableDatabase, 7, locked = true, maskingHistory = false)
                assertPrivacy(target.writableDatabase, 8, locked = false, maskingHistory = false)
            }
        }
    }

    @Test
    fun legacyDestinationWithoutThePrivacyColumnStillRestores() {
        helper(chatSchema(includePrivacyLock = false)).use { source ->
            helper(chatSchema(includePrivacyLock = false)).use { target ->
                source.writableDatabase.execSQL("INSERT INTO chats_v2(chat_id,title) VALUES (7,'Legacy snapshot')")
                target.writableDatabase.execSQL("INSERT INTO chats_v2(chat_id,title) VALUES (8,'Previous content')")

                CompleteBackupDatabase.restore(source.writableDatabase, target.writableDatabase)

                target.writableDatabase.query("SELECT chat_id,title FROM chats_v2").use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(7, rows.getInt(0))
                    assertEquals("Legacy snapshot", rows.getString(1))
                    assertFalse(rows.moveToNext())
                }
            }
        }
    }

    @Test
    fun settingsOnlyRestoreLeavesCurrentConversationPrivacyAndHistoryUntouched() {
        val settings = "CREATE TABLE openrouter_batch_cache (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, value TEXT NOT NULL)"
        helper(chatSchema(), settings).use { source ->
            helper(chatSchema(), settings).use { target ->
                source.writableDatabase.execSQL("INSERT INTO chats_v2(chat_id,title) VALUES (7,'Older ordinary snapshot')")
                source.writableDatabase.execSQL("INSERT INTO openrouter_batch_cache(id,value) VALUES (1,'Restored setting')")
                target.writableDatabase.execSQL("INSERT INTO chats_v2(chat_id,title,privacy_mode_locked,privacy_mode_enabled) VALUES (7,'Currently private',1,1)")
                target.writableDatabase.execSQL("INSERT INTO openrouter_batch_cache(id,value) VALUES (1,'Previous setting')")

                CompleteBackupDatabase.restoreSections(
                    source.writableDatabase,
                    target.writableDatabase,
                    CompleteBackupSelection(setOf(CompleteBackupSection.SETTINGS))
                )

                assertPrivacy(target.writableDatabase, 7, locked = true, maskingHistory = true)
                target.writableDatabase.query("SELECT value FROM openrouter_batch_cache WHERE id = 1").use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals("Restored setting", rows.getString(0))
                }
            }
        }
    }

    private fun assertPrivacy(database: SupportSQLiteDatabase, chatId: Int, locked: Boolean, maskingHistory: Boolean) {
        database.query("SELECT privacy_mode_locked,privacy_mode_enabled FROM chats_v2 WHERE chat_id = ?", arrayOf<Any>(chatId)).use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("Permanent privacy for chat $chatId", locked, rows.getInt(0) != 0)
            assertEquals("Observed masking history for chat $chatId", maskingHistory, rows.getInt(1) != 0)
        }
    }

    private fun chatSchema(includePrivacyLock: Boolean = true): String =
        "CREATE TABLE chats_v2 (chat_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, is_temporary INTEGER NOT NULL DEFAULT 0, privacy_mode_enabled INTEGER NOT NULL DEFAULT 0" +
            (if (includePrivacyLock) ", privacy_mode_locked INTEGER NOT NULL DEFAULT 0" else "") + ")"

    private val agentSchema = "CREATE TABLE agent_runs (run_id TEXT PRIMARY KEY NOT NULL, chat_id INTEGER NOT NULL, user_message_id INTEGER NOT NULL, assistant_message_id INTEGER NOT NULL, profile_uid TEXT NOT NULL, provider_snapshot TEXT NOT NULL, model_snapshot TEXT NOT NULL, synthesis_key TEXT, synthesis_snapshot TEXT, status TEXT NOT NULL, terminal_error TEXT)"
    private val agentPolicyRefSchema = "CREATE TABLE agent_run_policy_refs (runId TEXT NOT NULL, chatId INTEGER NOT NULL, digest TEXT NOT NULL)"

    private fun helper(vararg schemas: String): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
            .callback(object : SupportSQLiteOpenHelper.Callback(30) {
                override fun onCreate(db: SupportSQLiteDatabase) = schemas.forEach(db::execSQL)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
    )
}
