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

    val MIGRATION_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `agent_runs` ADD COLUMN `gateway_job_id` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `agent_runs` ADD COLUMN `gateway_base_url` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `agent_runs` ADD COLUMN `gateway_last_sequence` INTEGER NOT NULL DEFAULT -1")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_runs_gateway_job_id` ON `agent_runs` (`gateway_job_id`)")
        }
    }

    val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `chats_v2` ADD COLUMN `conversation_mode` TEXT NOT NULL DEFAULT 'STANDARD'")
            db.execSQL("ALTER TABLE `messages_v2` ADD COLUMN `combined_sources` TEXT NOT NULL DEFAULT '[]'")
        }
    }

    val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `provider_connections` (
                    `connection_uid` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `compatible_type` TEXT NOT NULL,
                    `api_url` TEXT NOT NULL,
                    `secret_ref` TEXT DEFAULT NULL,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`connection_uid`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_provider_connections_compatible_type` " +
                    "ON `provider_connections` (`compatible_type`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_provider_connections_compatible_type_api_url` " +
                    "ON `provider_connections` (`compatible_type`, `api_url`)"
            )
            db.execSQL(
                "ALTER TABLE `platform_v2` ADD COLUMN `provider_connection_uid` TEXT DEFAULT NULL"
            )

            val now = System.currentTimeMillis() / 1000
            db.execSQL(
                """
                INSERT INTO `provider_connections`
                    (`connection_uid`, `name`, `compatible_type`, `api_url`, `secret_ref`, `created_at`, `updated_at`)
                SELECT
                    'legacy-provider-' || `platform_id`,
                    CASE
                        WHEN TRIM(`name`) = '' THEN `compatible_type` || ' connection'
                        ELSE `name` || ' connection'
                    END,
                    `compatible_type`,
                    COALESCE(`api_url`, ''),
                    `secret_ref`,
                    $now,
                    $now
                FROM `platform_v2`
                WHERE `compatible_type` != 'LITERT_LM'
                """.trimIndent()
            )
            db.execSQL(
                """
                UPDATE `platform_v2`
                SET `provider_connection_uid` = 'legacy-provider-' || `platform_id`,
                    `secret_ref` = NULL,
                    `token` = NULL
                WHERE `compatible_type` != 'LITERT_LM'
                """.trimIndent()
            )
        }
    }

    val MIGRATION_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `agent_runs` ADD COLUMN `input_tokens` INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE `agent_runs` ADD COLUMN `output_tokens` INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE `agent_runs` ADD COLUMN `total_tokens` INTEGER DEFAULT NULL")
        }
    }

    val MIGRATION_26_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `chats_v2` ADD COLUMN `active_platform` TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE `chats_v2` SET `active_platform` = `enabled_platform`")
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
        MIGRATION_21_22,
        MIGRATION_22_23,
        MIGRATION_23_24,
        MIGRATION_24_25,
        MIGRATION_25_26,
        MIGRATION_26_27
    )
}
