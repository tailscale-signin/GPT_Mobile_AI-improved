package dev.chungjungsoo.gptmobile.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object ChatDatabaseV2Migrations {
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `chat_rooms_v2` ADD COLUMN `is_favorite` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_rooms_v2_is_favorite` ON `chat_rooms_v2` (`is_favorite`)")
        }
    }

    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `messages_v2` ADD COLUMN `revisions` TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE `messages_v2` ADD COLUMN `current_revision_index` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `messages_v2` ADD COLUMN `is_favorite` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_v2_is_favorite` ON `messages_v2` (`is_favorite`)")
        }
    }
}
