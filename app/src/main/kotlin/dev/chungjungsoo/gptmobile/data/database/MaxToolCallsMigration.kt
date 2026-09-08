package dev.chungjungsoo.gptmobile.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object MaxToolCallsMigration {
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `platform_v2` ADD COLUMN `max_tool_calls` INTEGER NOT NULL DEFAULT 2147483647"
            )
        }
    }
}
