package dev.chungjungsoo.gptmobile.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object ChatDatabaseV2Migrations {
    val CHAT_FAVORITE_COLUMN_MIGRATIONS = listOf(
        "ALTER TABLE `chats_v2` ADD COLUMN `is_favorite` INTEGER NOT NULL DEFAULT 0"
    )

    val MIGRATION_10_11: Migration = object : Migration(10, 11) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_messages_v2_chat_id_created_at_message_id` " +
                    "ON `messages_v2` (`chat_id`, `created_at`, `message_id`)"
            )
        }
    }

    val MIGRATION_11_12: Migration = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            CHAT_FAVORITE_COLUMN_MIGRATIONS.forEach { statement ->
                db.execSQL(statement)
            }
        }
    }
}
