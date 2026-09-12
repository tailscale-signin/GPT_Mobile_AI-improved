# AI-Memory.md

## Repository Overview
**GPT_Mobile_AI-improved** is an Android application built with Kotlin and Jetpack Compose, designed to interact with multiple AI platforms (OpenRouter, Ollama, etc.) via a unified interface. It features advanced capabilities like MCP tool integration, real-time SSE streaming, platform management, favorites/archiving, and background execution resilience.

## Repository Index

### 📂 `data/`
- **`database/ChatDatabase.kt`**: Room database definition. Currently at **Schema Version 19**. Includes migrations for `isArchived`, `labels`, and `timestamp` fields.
- **`dao/ConversationDao.kt`**: DAO layer handling conversation, platform, and message CRUD operations. Supports archived queries and favorite grouping.
- **`model/Conversation.kt`**: Domain data class for conversations. Maps to `ConversationEntity`.
- **`model/AIPlatform.kt`**: Domain data class for AI platforms. Includes `isFavorite`, `labels`, and `sortType` support.
- **`backup/BackupService.kt`**: Handles export/import of favorites (`favoriteGroups`) and messages (`messageGroups`).

### 🧠 `domain/`
- **`usecase/ArchiveConversationUseCase.kt`**: Logic for archiving/unarchiving conversations.
- **`usecase/ManagePlatformsUseCase.kt`**: Handles platform sorting (`SortType: ENABLED, FAVORITES, NAME`), favoriting, and label management.
- **`usecase/ValidatePlatformConnectionUseCase.kt`**: Lightweight probe to validate API keys/platform connectivity.
- **`service/ModelProviderService.kt`**: Fetches and caches models from OpenRouter API. Supports "Popular" and "Free" category filtering.

### 🎨 `ui/`
- **`screen/main/HomeScreen.kt`**: Main entry point. Features swipe-to-archive/delete gestures, archived conversations bottom bar, and anchored favorites view.
- **`screen/chat/ChatScreen.kt` / `ChatInputBar.kt`**: Chat interface with 2s/1s fade animation during generation, session platform disable toggle, transparent timestamps, and continuation prompt detection.
- **`component/chat/ChatBubble.kt`**: Pure black bubbles with 2x transparency. Details button positioned top-right.
- **`component/tool/ToolTraceBlock.kt`**: Handles tool call tracing with grouping logic (>3 identical calls show expandable count card).
- **`component/platform/PlatformCheckBoxItem.kt`**: Platform selection item with beveled color-coded label badges, long-press favorite toggle, and `isFavorite` state.
- **`dialog/OpenRouterModelPickerDialog.kt`**: Model picker with Popular/Free tabs and ApiKeyValidator integration.

### 🔧 `service/`
- **Foreground Service**: Handles background agent execution. Triggers haptic vibration on completion when app is backgrounded.

## Engineering Guidelines (Do's)
1. **Architecture**: Strictly follow Clean Architecture (Data → Domain → UI). Use UseCases for business logic and Services for external interactions.
2. **State Management**: Use `ViewModel` with `StateFlow`/`SharedFlow` for reactive UI updates. Avoid mutable state in composables.
3. **Database**: Use Room with explicit migrations. Always update schema version and add migration tests when modifying entities.
4. **UI Components**: Prefer small, reusable Compose components. Use semantic modifiers for layout and accessibility.
5. **Error Handling**: Provide detailed error messages via `ValidatePlatformConnectionUseCase` and UI feedback loops.

## Anti-Patterns & Traps (Don'ts)
1. **No Hardcoded Secrets**: API keys and endpoints must be injected or stored securely in preferences/keystore.
2. **Avoid Full DB Scans**: Use indexed DAO queries for favorites/archived lists. Do not load all conversations into memory.
3. **No Direct UI-State Mutation**: Always route state changes through ViewModel/UseCase flows.
4. **Migration Safety**: Never drop tables in migrations. Always use `addCallback` or explicit column additions to preserve user data.
5. **Blocking on Main Thread**: All network calls (OpenRouter, MCP) must be offloaded to `IO` dispatchers.

## v0.9.2 Implementation Status
**Branch:** `feature/v0.9.2-initiation`
**Status:** ✅ **ALL REQUIREMENTS IMPLEMENTED**
- **Data Layer:** Schema 19 migration (`isArchived`, `labels`, `timestamp`), Backup service updated for groups.
- **Domain:** `ArchiveConversationUseCase`, `ModelProviderService`, `ValidatePlatformConnectionUseCase`, `ManagePlatformsUseCase` all implemented.
- **UI:** Platform sorting/long-press favorites, beveled color labels, Chat input fade/disable toggle, transparent timestamps, Continue button detection, Tool call grouping, Archived bottom bar, Swipe actions, Favorites anchor fix.
- **Extras:** `ApiKeyValidator` probe, OpenRouter Popular/Free tabs, Unit tests for domain layer, Haptic vibration on background completion.
