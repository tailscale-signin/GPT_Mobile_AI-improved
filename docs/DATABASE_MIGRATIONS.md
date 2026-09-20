# Database Migrations - GPT Mobile AI

## Current State
- **Schema Version:** 22 (ChatDatabaseV2) / 2 (ChatDatabase legacy)
- **Database Engine:** Room Database with SQLite & encrypted vault credentials
- **Migration Strategy:** Explicit non-destructive schema migrations with exhaustive unit test verification (`ChatDatabaseV2MigrationsTest.kt`). Never wipe databases with `fallbackToDestructiveMigration` in production.

---

## Migration Log (ChatDatabaseV2)

### v21 → v22 (OpenRouter Batch Cache)
- **Changes:**
  - Added table `openrouter_batch_cache` (`id`, `cache_key`, `response_content`, `timestamp`).
  - Added unique index on `cache_key` for O(1) hash cache lookups.
  - Added index on `timestamp` for TTL and LRU expiration.
- **Risk Level:** LOW
- **Rollback Plan:** Forward-compatible addition. Cache table can be dropped without losing chat history.

### v20 → v21 (Customized Titles)
- **Changes:**
  - Added `is_title_customized` (`INTEGER NOT NULL DEFAULT 0`) to `chats_v2`.
- **Risk Level:** LOW
- **Rollback Plan:** Defaults to 0 (auto-generated titles continue normally).

### v19 → v20 (Batch API & Chat Drafts)
- **Changes:**
  - Added `draft_text` (`TEXT DEFAULT NULL`) and `draft_updated_at` (`INTEGER DEFAULT NULL`) to `chats_v2`.
  - Added `batch_mode` (`INTEGER NOT NULL DEFAULT 0`) and `batch_api_url` (`TEXT DEFAULT NULL`) to `platform_v2`.
- **Risk Level:** LOW
- **Rollback Plan:** Nullable columns preserve existing chat state and platform configs.

### v18 → v19 (Archiving, Favorites & Timestamps)
- **Changes:**
  - Added `is_archived` to `chats_v2`.
  - Added `labels` and `is_favorite` to `platform_v2`.
  - Added `timestamp` to `messages_v2`.
- **Risk Level:** MEDIUM
- **Rollback Plan:** Defaults provided (`0` or `NULL`); existing messages remain intact.

### v17 → v18 (Ollama Advanced Options)
- **Changes:**
  - Added `ollama_options` (`TEXT DEFAULT NULL`) to `platform_v2`.
- **Risk Level:** LOW

### v16 → v17 (Granular Tool Isolation)
- **Changes:**
  - Added `disable_remote_tools` (`INTEGER NOT NULL DEFAULT 0`) to `platform_v2`.
  - Added `disable_local_tools` (`INTEGER NOT NULL DEFAULT 0`) to `platform_v2`.
- **Risk Level:** LOW

### v15 → v16 (Global Tool Kill-Switch)
- **Changes:**
  - Added `disable_all_tools` (`INTEGER NOT NULL DEFAULT 0`) to `platform_v2`.
- **Risk Level:** LOW

### v14 → v15 (OpenRouter Custom Routing)
- **Changes:**
  - Added `open_router_routing` (`TEXT DEFAULT NULL`) to `platform_v2`.
- **Risk Level:** LOW

### v13 → v14 (Agent Tool Execution Budgeting)
- **Changes:**
  - Added `max_tool_calls` (`INTEGER NOT NULL DEFAULT 2147483647`) to `platform_v2` for loop watchdog prevention.
- **Risk Level:** LOW

### v12 → v13 (Favorite Message Pinning)
- **Changes:**
  - Added `is_favorite` to `messages_v2` with index `index_messages_v2_is_favorite`.
- **Risk Level:** LOW

### v11 → v12 (Multi-Turn Assistant Message Revisions)
- **Changes:**
  - Added `revisions` (`TEXT NOT NULL DEFAULT '[]'`) and `current_revision_index` (`INTEGER NOT NULL DEFAULT 0`) to `messages_v2`.
- **Risk Level:** LOW

### v10 → v11 (Room Level Favorites)
- **Changes:**
  - Added `is_favorite` to `chat_rooms_v2` with index `index_chat_rooms_v2_is_favorite`.
- **Risk Level:** LOW

---

## Testing Checklist for New Migrations

1. **Schema JSON Generation**: Verify Room schema exported files exist under `app/schemas/`.
2. **Unit Test Migration Step**: Add migration unit test in `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/database/ChatDatabaseV2MigrationsTest.kt`.
3. **Validate All Migrations Array**: Ensure the new migration is appended to `ChatDatabaseV2Migrations.ALL_MIGRATIONS`.
4. **Data Integrity Test**: Assert columns, non-null defaults, indices, and foreign keys retain pre-migration rows.
5. **Rollback & Safety Plan**: Document column/table reversibility in this log.
