package dev.chungjungsoo.gptmobile.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object ChatDatabaseV2Migrations {
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `chats_v2` ADD COLUMN `is_favorite` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_rooms_v2_is_favorite` ON `chats_v2` (`is_favorite`)")
        }
    }

    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            addColumnIfMissing(db, "messages_v2", "revisions", "TEXT NOT NULL DEFAULT '[]'")
            if (columns(db, "messages_v2").contains("current_revision_index") && !columns(db, "messages_v2").contains("active_revision_index")) db.execSQL("ALTER TABLE messages_v2 RENAME COLUMN current_revision_index TO active_revision_index")
            addColumnIfMissing(db, "messages_v2", "active_revision_index", "INTEGER NOT NULL DEFAULT -1")
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
                    `secret_ref` = NULL
                WHERE `compatible_type` != 'LITERT_LM'
                """.trimIndent()
            )
            // Existing plaintext is cleared by migrateSecrets only after a verified vault write.
            // Clearing it here would lose credentials when upgrading across several releases.
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

    val MIGRATION_27_28 = object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS pending_prompts (id TEXT NOT NULL PRIMARY KEY, chatId INTEGER NOT NULL, text TEXT NOT NULL, payload TEXT NOT NULL, position INTEGER NOT NULL, paused INTEGER NOT NULL, userMessageId INTEGER, FOREIGN KEY(chatId) REFERENCES chats_v2(chat_id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_prompts_chatId ON pending_prompts(chatId)")
        }
    }

    val MIGRATION_28_29 = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS knowledge_projects (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, instructions TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS knowledge_project_chats (chatId INTEGER NOT NULL PRIMARY KEY, projectId TEXT NOT NULL, FOREIGN KEY(projectId) REFERENCES knowledge_projects(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(chatId) REFERENCES chats_v2(chat_id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX index_knowledge_project_chats_projectId ON knowledge_project_chats(projectId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS knowledge_documents (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, hash TEXT NOT NULL, projectId TEXT, chatId INTEGER, updatedAt INTEGER NOT NULL, FOREIGN KEY(projectId) REFERENCES knowledge_projects(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(chatId) REFERENCES chats_v2(chat_id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX index_knowledge_documents_projectId ON knowledge_documents(projectId)")
            db.execSQL("CREATE INDEX index_knowledge_documents_chatId ON knowledge_documents(chatId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS knowledge_chunks (id TEXT NOT NULL PRIMARY KEY, documentId TEXT NOT NULL, chunkIndex INTEGER NOT NULL, startOffset INTEGER NOT NULL, endOffset INTEGER NOT NULL, text TEXT NOT NULL, FOREIGN KEY(documentId) REFERENCES knowledge_documents(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX index_knowledge_chunks_documentId ON knowledge_chunks(documentId)")
        }
    }

    val MIGRATION_29_30 = object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `messages_search` USING FTS4(`content` TEXT NOT NULL, `revisions` TEXT NOT NULL, content=`messages_v2`)")
            db.execSQL("INSERT INTO messages_search(messages_search) VALUES('rebuild')")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_search_BEFORE_UPDATE BEFORE UPDATE ON messages_v2 BEGIN DELETE FROM messages_search WHERE docid=OLD.rowid; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_search_BEFORE_DELETE BEFORE DELETE ON messages_v2 BEGIN DELETE FROM messages_search WHERE docid=OLD.rowid; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_search_AFTER_UPDATE AFTER UPDATE ON messages_v2 BEGIN INSERT INTO messages_search(docid, content, revisions) VALUES(NEW.rowid, NEW.content, NEW.revisions); END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_search_AFTER_INSERT AFTER INSERT ON messages_v2 BEGIN INSERT INTO messages_search(docid, content, revisions) VALUES(NEW.rowid, NEW.content, NEW.revisions); END")
            db.execSQL("CREATE TABLE IF NOT EXISTS model_invocations (id TEXT NOT NULL PRIMARY KEY, parentRunId TEXT NOT NULL, turnKey TEXT NOT NULL, provider TEXT NOT NULL, model TEXT NOT NULL, kind TEXT NOT NULL, inputTokens INTEGER NOT NULL, outputTokens INTEGER NOT NULL, estimated INTEGER NOT NULL, status TEXT NOT NULL, startedAt INTEGER NOT NULL, durationMs INTEGER NOT NULL, firstTokenMs INTEGER)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_model_invocations_turnKey ON model_invocations(turnKey)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_model_invocations_startedAt ON model_invocations(startedAt)")
            db.execSQL("ALTER TABLE tool_connections ADD COLUMN tool_policy TEXT NOT NULL DEFAULT 'ASK_WRITES'")
            db.execSQL("ALTER TABLE tool_connections ADD COLUMN approved_read_tools TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE knowledge_documents ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE TABLE IF NOT EXISTS tool_approvals (id TEXT NOT NULL PRIMARY KEY, runId TEXT NOT NULL, connection TEXT NOT NULL, tool TEXT NOT NULL, argumentHash TEXT NOT NULL, argumentPreview TEXT NOT NULL, state TEXT NOT NULL, createdAt INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_approvals_runId ON tool_approvals(runId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_approvals_state ON tool_approvals(state)")
        }
    }

    val MIGRATION_30_31 = object : Migration(30, 31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE model_invocations ADD COLUMN profileUid TEXT")
            // Only primary/synthesis requests can be attributed safely from old run records.
            // Historical delegated calls remain unassigned instead of guessing their target profile.
            db.execSQL("UPDATE model_invocations SET profileUid = (SELECT profile_uid FROM agent_runs WHERE run_id = parentRunId) WHERE kind != 'delegate'")
        }
    }

    val MIGRATION_31_32 = object : Migration(31, 32) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS memory_graph_entities (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    normalizedName TEXT NOT NULL,
                    entityType TEXT NOT NULL,
                    scope TEXT NOT NULL,
                    standalone INTEGER NOT NULL,
                    sourceChatId INTEGER NOT NULL,
                    sourceMessageId INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_memory_graph_entities_scope_normalizedName ON memory_graph_entities(scope, normalizedName)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_graph_entities_entityType ON memory_graph_entities(entityType)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_graph_entities_sourceChatId ON memory_graph_entities(sourceChatId)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS memory_graph_observations (
                    id TEXT NOT NULL PRIMARY KEY,
                    entityId TEXT NOT NULL,
                    observation TEXT NOT NULL,
                    normalizedObservation TEXT NOT NULL,
                    scope TEXT NOT NULL,
                    sourceKind TEXT NOT NULL,
                    sourceChatId INTEGER NOT NULL,
                    sourceMessageId INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    FOREIGN KEY(entityId) REFERENCES memory_graph_entities(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_graph_observations_entityId ON memory_graph_observations(entityId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_graph_observations_sourceChatId ON memory_graph_observations(sourceChatId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_graph_observations_sourceKind ON memory_graph_observations(sourceKind)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS memory_graph_relations (
                    id TEXT NOT NULL PRIMARY KEY,
                    fromEntityId TEXT NOT NULL,
                    toEntityId TEXT NOT NULL,
                    relationType TEXT NOT NULL,
                    scope TEXT NOT NULL,
                    sourceKind TEXT NOT NULL,
                    sourceChatId INTEGER NOT NULL,
                    sourceMessageId INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    FOREIGN KEY(fromEntityId) REFERENCES memory_graph_entities(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(toEntityId) REFERENCES memory_graph_entities(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_graph_relations_fromEntityId ON memory_graph_relations(fromEntityId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_graph_relations_toEntityId ON memory_graph_relations(toEntityId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_graph_relations_sourceChatId ON memory_graph_relations(sourceChatId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_graph_relations_sourceKind ON memory_graph_relations(sourceKind)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_memory_graph_relations_fromEntityId_toEntityId_relationType_sourceKind ON memory_graph_relations(fromEntityId, toEntityId, relationType, sourceKind)")
        }
    }

    val MIGRATION_32_33 = object : Migration(32, 33) {
        override fun migrate(db: SupportSQLiteDatabase) {
            normalizeLegacyMessageDefaults(db)
            normalizeLegacyProfileDefaults(db)
            // Published pre-v27 schemas lacked several derived indices and gateway fields.
            db.execSQL("DROP INDEX IF EXISTS index_chat_rooms_v2_is_favorite")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_v2_archived_favorite_updated ON chats_v2(is_archived, is_favorite, updated_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_v2_archived_updated ON chats_v2(is_archived, updated_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_v2_chat_id_created_at_message_id ON messages_v2(chat_id, created_at, message_id)")
            addColumnIfMissing(db, "agent_runs", "gateway_job_id", "TEXT")
            addColumnIfMissing(db, "agent_runs", "gateway_base_url", "TEXT")
            addColumnIfMissing(db, "agent_runs", "gateway_last_sequence", "INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_runs_gateway_job_id ON agent_runs(gateway_job_id)")
            db.execSQL("ALTER TABLE knowledge_projects ADD COLUMN includePersonalMemory INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE knowledge_projects ADD COLUMN defaultProfileUid TEXT")
            db.execSQL("ALTER TABLE chats_v2 ADD COLUMN is_temporary INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE chats_v2 ADD COLUMN parent_chat_id INTEGER")
            db.execSQL("ALTER TABLE chats_v2 ADD COLUMN branch_message_id INTEGER")
            db.execSQL("ALTER TABLE chats_v2 ADD COLUMN draft_attachments TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE chats_v2 ADD COLUMN last_share_token TEXT")
            db.execSQL("ALTER TABLE model_invocations ADD COLUMN costMicros INTEGER")
            db.execSQL("ALTER TABLE model_invocations ADD COLUMN currency TEXT")
            db.execSQL("ALTER TABLE model_invocations ADD COLUMN priceSource TEXT")
            db.execSQL("CREATE TABLE IF NOT EXISTS workspace_records (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, title TEXT NOT NULL, payload TEXT NOT NULL, chatId INTEGER, runId TEXT, updatedAt INTEGER NOT NULL, version INTEGER NOT NULL, FOREIGN KEY(chatId) REFERENCES chats_v2(chat_id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_workspace_records_chatId ON workspace_records(chatId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_workspace_records_kind ON workspace_records(kind)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_workspace_records_runId ON workspace_records(runId)")
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
        MIGRATION_26_27,
        MIGRATION_27_28,
        MIGRATION_28_29,
        MIGRATION_29_30,
        MIGRATION_30_31,
        MIGRATION_31_32,
        MIGRATION_32_33
    )
    private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> = db.query("PRAGMA table_info(`$table`)").use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) } }
    private fun addColumnIfMissing(db: SupportSQLiteDatabase, table: String, column: String, definition: String) {
        if (column !in columns(db, table)) db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
    }
    private fun normalizeLegacyMessageDefaults(db: SupportSQLiteDatabase) {
        val defaults = db.query("PRAGMA table_info(messages_v2)").use { rows -> buildMap { while (rows.moveToNext()) put(rows.getString(1), rows.getString(4)) } }
        if (defaults["revisions"] == "'[]'" && defaults["active_revision_index"] == "-1") return
        // Keep dependent history even if this SQLite connection enforces ON DELETE CASCADE.
        val sequence = db.query("SELECT seq FROM sqlite_sequence WHERE name = 'messages_v2'").use { if (it.moveToFirst()) it.getLong(0) else 0L }
        db.execSQL("CREATE TEMP TABLE migration33_runs AS SELECT * FROM agent_runs")
        db.execSQL("CREATE TEMP TABLE migration33_events AS SELECT * FROM tool_events")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `migration33_messages` (`message_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chat_id` INTEGER NOT NULL, `thoughts` TEXT NOT NULL, `content` TEXT NOT NULL, `attachments` TEXT NOT NULL, `revisions` TEXT NOT NULL DEFAULT '[]', `active_revision_index` INTEGER NOT NULL DEFAULT -1, `linked_message_id` INTEGER NOT NULL, `platform_type` TEXT, `current_run_id` TEXT, `created_at` INTEGER NOT NULL, `timeline` TEXT NOT NULL DEFAULT '[]', `is_favorite` INTEGER NOT NULL DEFAULT 0, `timestamp` INTEGER NOT NULL DEFAULT 0, `combined_sources` TEXT NOT NULL DEFAULT '[]', FOREIGN KEY(`chat_id`) REFERENCES `chats_v2`(`chat_id`) ON UPDATE NO ACTION ON DELETE CASCADE )""")
        val names = columns(db, "migration33_messages").joinToString { "`$it`" }
        db.execSQL("INSERT INTO migration33_messages ($names) SELECT $names FROM messages_v2")
        db.execSQL("DROP TABLE messages_v2")
        db.execSQL("ALTER TABLE migration33_messages RENAME TO messages_v2")
        db.execSQL("UPDATE sqlite_sequence SET seq = MAX(seq, ?) WHERE name = 'messages_v2'", arrayOf(sequence))
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_v2_chat_id ON messages_v2(chat_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_v2_is_favorite ON messages_v2(is_favorite)")
        db.execSQL("INSERT OR IGNORE INTO agent_runs SELECT * FROM migration33_runs")
        db.execSQL("INSERT OR IGNORE INTO tool_events SELECT * FROM migration33_events")
        db.execSQL("DROP TABLE migration33_events")
        db.execSQL("DROP TABLE migration33_runs")
        db.execSQL("INSERT INTO messages_search(messages_search) VALUES('rebuild')")
    }
    private fun normalizeLegacyProfileDefaults(db: SupportSQLiteDatabase) {
        val enabledDefault = db.query("PRAGMA table_info(platform_v2)").use { rows ->
            var value: String? = null
            while (rows.moveToNext()) if (rows.getString(1) == "enabled") value = rows.getString(4)
            value
        }
        if (enabledDefault == "1") return
        val sequence = db.query("SELECT seq FROM sqlite_sequence WHERE name = 'platform_v2'").use { if (it.moveToFirst()) it.getLong(0) else 0L }
        db.execSQL("""CREATE TABLE IF NOT EXISTS `migration33_profiles` (`platform_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uid` TEXT NOT NULL, `name` TEXT NOT NULL, `compatible_type` TEXT NOT NULL, `enabled` INTEGER NOT NULL DEFAULT 1, `api_url` TEXT NOT NULL, `token` TEXT, `secret_ref` TEXT, `model` TEXT NOT NULL, `temperature` REAL, `top_p` REAL, `top_k` INTEGER, `max_tokens` INTEGER, `accelerator` TEXT, `system_prompt` TEXT, `stream` INTEGER NOT NULL, `reasoning` INTEGER NOT NULL, `timeout` INTEGER NOT NULL, `max_tool_calls` INTEGER NOT NULL DEFAULT 2147483647, `harassment_safety_threshold` TEXT NOT NULL DEFAULT 'BLOCK_NONE', `hate_speech_safety_threshold` TEXT NOT NULL DEFAULT 'BLOCK_NONE', `sexually_explicit_safety_threshold` TEXT NOT NULL DEFAULT 'BLOCK_NONE', `dangerous_content_safety_threshold` TEXT NOT NULL DEFAULT 'BLOCK_NONE', `open_router_routing` TEXT, `disable_all_tools` INTEGER NOT NULL DEFAULT 0, `disable_remote_tools` INTEGER NOT NULL DEFAULT 0, `disable_local_tools` INTEGER NOT NULL DEFAULT 0, `ollama_options` TEXT, `labels` TEXT, `is_favorite` INTEGER NOT NULL DEFAULT 0, `batch_mode` INTEGER NOT NULL DEFAULT 0, `batch_api_url` TEXT, `provider_connection_uid` TEXT)""")
        val names = columns(db, "migration33_profiles").joinToString { "`$it`" }
        // Existing enabled/disabled values and credential references are copied verbatim.
        db.execSQL("INSERT INTO migration33_profiles ($names) SELECT $names FROM platform_v2")
        db.execSQL("DROP TABLE platform_v2")
        db.execSQL("ALTER TABLE migration33_profiles RENAME TO platform_v2")
        db.execSQL("UPDATE sqlite_sequence SET seq = MAX(seq, ?) WHERE name = 'platform_v2'", arrayOf(sequence))
    }
}
