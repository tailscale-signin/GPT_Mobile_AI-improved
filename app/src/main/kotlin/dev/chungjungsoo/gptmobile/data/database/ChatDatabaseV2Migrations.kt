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

    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `platform_v2` ADD COLUMN `max_tool_calls` INTEGER NOT NULL DEFAULT 2147483647")
        }
    }

    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `platform_v2` ADD COLUMN `open_router_routing` TEXT DEFAULT NULL")
        }
    }

    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `platform_v2` ADD COLUMN `disable_all_tools` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `platform_v2` ADD COLUMN `disable_remote_tools` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `platform_v2` ADD COLUMN `disable_local_tools` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `platform_v2` ADD COLUMN `ollama_options` TEXT DEFAULT NULL")
        }
    }

    val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `chats_v2` ADD COLUMN `is_archived` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `platform_v2` ADD COLUMN `labels` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `platform_v2` ADD COLUMN `is_favorite` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `messages_v2` ADD COLUMN `timestamp` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `chats_v2` ADD COLUMN `draft_text` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `chats_v2` ADD COLUMN `draft_updated_at` INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE `platform_v2` ADD COLUMN `batch_mode` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `platform_v2` ADD COLUMN `batch_api_url` TEXT DEFAULT NULL")
        }
    }

    val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `chats_v2` ADD COLUMN `is_title_customized` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `openrouter_batch_cache` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `cache_key` TEXT NOT NULL,
                    `response_content` TEXT NOT NULL,
                    `timestamp` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_openrouter_batch_cache_cache_key` ON `openrouter_batch_cache` (`cache_key`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_openrouter_batch_cache_timestamp` ON `openrouter_batch_cache` (`timestamp`)")
        }
    }

    val ALL_MIGRATIONS: Array<Migration> = arrayOf(
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
        MIGRATION_16_17,
        MIGRATION_17_18,
        MIGRATION_18_19,
        MIGRATION_19_20,
        MIGRATION_20_21,
        MIGRATION_21_22
    )
}
