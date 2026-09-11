# AI Memory

Persistent repository context for AI coding agents. Keep this file synchronized whenever repository files are added, modified, renamed, or deleted.

> Index status: comprehensive and actively maintained on `feature/v0.9.2-initiation`. Core architecture, Android targets, UI screens, encrypted backup/security, agent runtime/tools, Room V2 database & migrations, DataStore, local runtime & acceleration, network transports & SSE parsing, model catalogs, OpenRouter advanced routing/reasoning, Ollama advanced options & timeout resilience, WorkManager workers, DI modules, DTOs, and test roots are fully indexed.
>
> **Current Version:** `0.9.2-dev` — Implemented Room Schema 19 (`MIGRATION_18_19`) adding `is_archived` to `chats_v2`, `labels` and `is_favorite` to `platform_v2`, and `timestamp` to `messages_v2`. Added `getArchivedChatRooms` and `updateArchived` to `ChatRoomV2Dao`, `updateFavorite` and `updateLabels` to `PlatformV2Dao`, and archive management methods (`fetchArchivedChatListV2`, `setChatArchived`) to `ChatRepository` and `ChatRepositoryImpl`. Completed Phase 2 Domain & Service layer with `ArchiveConversationUseCase`, `ManagePlatformsUseCase`, `SortType`, `ValidatePlatformConnectionUseCase`, and `ModelProviderService`.

## 1. Repository Overview

GPT Mobile AI (Improved) is a Kotlin Android application for chatting with cloud, self-hosted, and on-device large language models. It supports OpenAI-compatible services, Anthropic, Google Gemini, Groq, OpenRouter, Ollama, and local LiteRT models. It also includes an autonomous agent runtime, Model Context Protocol (MCP) tools and marketplace, resilient streaming, background execution, chat search/history, multi-key API credential rotation with dynamic UI management and seamless streaming fallback, and encrypted credential storage.

### Architecture and stack

- **Architecture:** Clean MVVM with repository, domain-boundary abstractions, and data-source layers.
- **UI:** Jetpack Compose, Material 3, lifecycle-aware state collection (`collectAsStateWithLifecycle`), and Compose Navigation.
- **Motion & Transitions:** Theme motion primitives (`defaultSpatialSpec`, `fastSpatialSpec`, `fastEffectsSpec`) in `presentation.theme.Motion.kt` backing collapsible details animations and responsive indicator state transitions.
- **Language/runtime:** Kotlin 2.x, Java 21 bytecode, coroutines, Flow/StateFlow, and kotlinx.serialization.
- **Dependency injection:** Hilt/Dagger with KSP.
- **Networking:** Ktor clients (OkHttp and CIO engines), Server-Sent Events (SSE) streaming support, resilient retry/exponential backoff, and `ApiCredentialRotator` for round-robin multi-key failover across `ProviderAdapters`.
- **Persistence:** Room (`ChatDatabaseV2`, Schema version 19) with full FTS search and DataStore preferences (`SettingDataSource`).
  - Migration 16->17: added `disable_remote_tools` and `disable_local_tools` integer columns to `platform_v2`.
  - Migration 17->18: added `ollama_options` column to `platform_v2`.
  - Migration 18->19: added `is_archived` integer column to `chats_v2`, `labels` (text) and `is_favorite` (integer) to `platform_v2`, and `timestamp` (integer) to `messages_v2`.
- **Domain Layer**:
  - `domain.model.SortType`: Sort ordering for platforms (`ENABLED`, `FAVORITES`, `NAME`).
  - `domain.usecase.ArchiveConversationUseCase`: Chat archiving, unarchiving, and list retrieval.
  - `domain.usecase.ManagePlatformsUseCase`: Platform favorite toggles, label serialization/deserialization, and platform sorting.
  - `domain.service.ValidatePlatformConnectionUseCase`: Lightweight test probes across LLM providers with detailed error states.
  - `domain.service.ModelProviderService`: Model discovery, caching, and querying across providers (e.g., OpenRouter).
- **Security:** Android Keystore-backed AES-256-GCM credential encryption (`SecretVault`); passphrase-protected user exports using PBKDF2-HMAC-SHA256 and AES-256-GCM (`AppBackupCrypto`).
- **Local inference:** LiteRT-LM (`LocalRuntimeImpl`, conversation fingerprinting, dynamic accelerator selection for NPU/GPU/CPU, warm engine retention, speculative decoding); Ollama supported for self-hosted network inference with 5-minute timeout resilience loop, emulator alias fallback (`10.0.2.2`), and configurable advanced options.
- **Background work:** Foreground service (`AgentRunForegroundService`) with partial wake locks for active agent runs, and WorkManager (`LocalModelDownloadWorker`) for resilient background model downloads.
- **Android targets:** application ID `dev.melo.gptmobile.improved`, min SDK 31, compile/target SDK 36, arm64-v8a and x86_64 ABIs.

## 2. Repository Index

### Database & DAOs
- `ChatDatabaseV2.kt` (version 19): Primary Room database holding chat rooms, messages, platforms, tool connections, and local models.
- `ChatDatabaseV2Migrations.kt`: Contains all schema migrations including `MIGRATION_18_19`.
- `ChatRoomV2Dao.kt`: Queries and updates for `chats_v2`, with filtering for non-archived chats and querying archived chats.
- `PlatformV2Dao.kt`: Platform configuration queries, reactive flows, and updates for favorite status and labels.
- `MessageV2Dao.kt`: Message CRUD and timeline/revision persistence.
