# Database Migrations — GPT Mobile AI (Improved)

This document tracks all database migrations for `ChatDatabaseV2` (current schema version: **22**).

## Current State
- **Current Database Version:** 22
- **Database Class:** `dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2`
- **Migration Registry:** `dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2Migrations.ALL_MIGRATIONS`
- **Migration Strategy:** Forward-compatible, strict schema tests, no data loss.

---

## Migration History

### v21 → v22: OpenRouter Batch Response Cache Table
- **Date:** 2025
- **PR:** [#393]
- **Changes:**
  - Created table `openrouter_batch_cache`:
    - `id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL`
    - `cache_key TEXT NOT NULL`
    - `response_content TEXT NOT NULL`
    - `timestamp INTEGER NOT NULL`
  - Added unique index: `index_openrouter_batch_cache_cache_key` on (`cache_key`).
  - Added timestamp index: `index_openrouter_batch_cache_timestamp` on (`timestamp`).
- **Risk Level:** LOW (new standalone cache table, existing data untouched).

### v20 → v21: Chat Custom Title Customization
- **Changes:**
  - Added column `is_title_customized INTEGER NOT NULL DEFAULT 0` to `chats_v2`.
- **Risk Level:** LOW.

### v19 → v20: Draft Persistence & OpenRouter Batch Mode
- **Changes:**
  - Added `draft_text TEXT DEFAULT NULL` and `draft_updated_at INTEGER DEFAULT NULL` to `chats_v2`.
  - Added `batch_mode INTEGER NOT NULL DEFAULT 0` and `batch_api_url TEXT DEFAULT NULL` to `platform_v2`.
- **Risk Level:** LOW.

### v18 → v19: Chat Archive Flag & Message Timestamps
- **Changes:**
  - Added `is_archived INTEGER NOT NULL DEFAULT 0` to `chats_v2`.
  - Added `labels TEXT DEFAULT NULL` and `is_favorite INTEGER NOT NULL DEFAULT 0` to `platform_v2`.
  - Added `timestamp INTEGER NOT NULL DEFAULT 0` to `messages_v2`.
- **Risk Level:** MEDIUM.

### v17 → v18: Ollama Extra Generation Options
- **Changes:**
  - Added `ollama_options TEXT DEFAULT NULL` to `platform_v2`.
- **Risk Level:** LOW.

### v16 → v17: Granular MCP Remote vs Local Tool Disabling
- **Changes:**
  - Added `disable_remote_tools INTEGER NOT NULL DEFAULT 0` to `platform_v2`.
  - Added `disable_local_tools INTEGER NOT NULL DEFAULT 0` to `platform_v2`.
- **Risk Level:** LOW.

### v15 → v16: Master Tool Disabling
- **Changes:**
  - Added `disable_all_tools INTEGER NOT NULL DEFAULT 0` to `platform_v2`.
- **Risk Level:** LOW.

### v14 → v15: OpenRouter Custom Provider Routing
- **Changes:**
  - Added `open_router_routing TEXT DEFAULT NULL` to `platform_v2`.
- **Risk Level:** LOW.

### v13 → v14: Per-Platform Tool Execution Limits
- **Changes:**
  - Added `max_tool_calls INTEGER NOT NULL DEFAULT 2147483647` to `platform_v2`.
- **Risk Level:** LOW.

### v12 → v13: Message Favorites
- **Changes:**
  - Added `is_favorite INTEGER NOT NULL DEFAULT 0` to `messages_v2`.
  - Created index `index_messages_v2_is_favorite` on (`is_favorite`).
- **Risk Level:** LOW.

### v11 → v12: Message Revision History
- **Changes:**
  - Added `revisions TEXT NOT NULL DEFAULT '[]'` to `messages_v2`.
  - Added `current_revision_index INTEGER NOT NULL DEFAULT 0` to `messages_v2`.
- **Risk Level:** LOW.

### v10 → v11: Room Favorites
- **Changes:**
  - Added `is_favorite INTEGER NOT NULL DEFAULT 0` to `chat_rooms_v2`.
  - Created index `index_chat_rooms_v2_is_favorite` on (`is_favorite`).
- **Risk Level:** LOW.

---

## Migration Checklist for New Schema Increments
1. Define the migration SQL in `ChatDatabaseV2Migrations.kt`.
2. Add the migration to `ALL_MIGRATIONS`.
3. Bump `version = N` in `ChatDatabaseV2.kt`.
4. Ensure KSP outputs new schema into `app/schemas/`.
5. Update `MigrationTest.kt` in `app/src/androidTest/` or unit test suite.
6. Document the version increment in this file.
