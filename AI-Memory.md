# AI Memory

Persistent repository context for AI coding agents. Keep this file synchronized whenever repository files are added, modified, renamed, or deleted.

> Index status: comprehensive and actively maintained on `main`. Core architecture, Android targets, UI screens, encrypted backup/security, agent runtime/tools, Room V2 database & migrations, DataStore, local runtime & acceleration, network transports & SSE parsing, model catalogs, OpenRouter advanced routing/reasoning, WorkManager workers, DI modules, DTOs, and test roots are fully indexed. All open issues and PRs are reconciled and resolved.

## 1. Repository Overview

GPT Mobile AI (Improved) is a Kotlin Android application for chatting with cloud, self-hosted, and on-device large language models. It supports OpenAI-compatible services, Anthropic, Google Gemini, Groq, OpenRouter, Ollama, and local LiteRT models. It also includes an autonomous agent runtime, Model Context Protocol (MCP) tools and marketplace, resilient streaming, background execution, chat search/history, multi-key API credential rotation with dynamic UI management and seamless streaming fallback, and encrypted credential storage.

### Architecture and stack

- **Architecture:** Clean MVVM with repository, domain-boundary abstractions, and data-source layers.
- **UI:** Jetpack Compose, Material 3, lifecycle-aware state collection (`collectAsStateWithLifecycle`), and Compose Navigation.
- **Language/runtime:** Kotlin 2.x, Java 21 bytecode, coroutines, Flow/StateFlow, and kotlinx.serialization.
- **Dependency injection:** Hilt/Dagger with KSP.
- **Networking:** Ktor clients (OkHttp and CIO engines), Server-Sent Events (SSE) streaming support, resilient retry/exponential backoff, and `ApiCredentialRotator` for round-robin multi-key failover across `ProviderAdapters` (OpenAI, Anthropic, Gemini, Groq, OpenRouter, and OpenAI-compatible services).
- **Persistence:** Room (`ChatDatabaseV2`, Schema version 14) with full FTS search and DataStore preferences (`SettingDataSource`).
- **Security:** Android Keystore-backed AES-256-GCM credential encryption (`SecretVault`); passphrase-protected user exports using PBKDF2-HMAC-SHA256 and AES-256-GCM (`AppBackupCrypto`).
- **Local inference:** LiteRT-LM (`LocalRuntimeImpl`, conversation fingerprinting, dynamic accelerator selection for NPU/GPU/CPU); Ollama supported for self-hosted network inference.
- **Background work:** Foreground service (`AgentRunForegroundService`) with partial wake locks for active agent runs, and WorkManager (`LocalModelDownloadWorker`) for resilient background model downloads.
- **Android targets:** application ID `dev.melo.gptmobile.improved`, min SDK 31, compile/target SDK 36, arm64-v8a and x86_64 ABIs.
- **Build/release:** Gradle Kotlin DSL, R8/resource shrinking, ABI splits plus universal APK, and Room schema export (`app/schemas/`).
- **Testing/style:** JUnit 4/5, kotlinx-coroutines-test, AndroidX instrumented/Compose tests, Room testing, and ktlint 1.3.1 using Android Studio style.

### Source layout

- Main Kotlin tree: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/`
- Additional Kotlin source in the Java source set: `app/src/main/java/dev/chungjungsoo/gptmobile/`
- JVM tests: `app/src/test/kotlin/` and `app/src/test/java/`
- Instrumented tests: `app/src/androidTest/kotlin/`

## 2. Repository Index

### Root

- `.editorconfig` — Editor and ktlint-compatible formatting rules.
- `.github/workflows/` — CI build, check, formatting, and release automation workflows.
- `.gitignore` — Version-control exclusions; do not scan ignored files for secrets.
- `AGENTS.md` — Authoritative agent-facing build, style, architecture, and test guidance.
- `AI-Memory.md` — This persistent architecture and structural index file.
- `CHANGELOG.md`, `RELEASE_NOTES.md`, `PROGRESS.md` — Historical changes, release details, and progress records.
- `CLAUDE.md`, `CONTEXT.md` — Additional AI/project context files.
- `README.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `LICENSE` — Product and contributor documentation.
- `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/`, `gradlew*` — Gradle build orchestration and wrappers.
- `model_catalog.json` — Bundled offline model catalog consumed by local model discovery features.
- `docs/` — ADRs, operational guidance, release validation, and CI diagnostics.
- `images/`, `metadata/` — Documentation/store assets and distribution metadata.
- `scripts/` — Build, maintenance, validation, and release helper scripts.

### `app/`

- `app/build.gradle.kts` — Android application configuration: Jetpack Compose, Hilt/KSP, Room schemas, SDK targets (min 31, target 36), ABI splits, R8 rules, OAuth placeholders, LiteRT-LM, Ktor, WorkManager, and test dependencies.
- `app/proguard-rules.pro` — Application-specific R8/ProGuard obfuscation and preservation rules.
- `app/schemas/` — Exported Room schemas (v1 through v14) used to validate database migration evolution.
- `app/src/main/AndroidManifest.xml` — Application declarations, activities, voice services, quick-settings tile, agent foreground service, permissions, and service types.
- `app/src/main/res/` — Strings, themes, icons, XML configurations, and packaged Android resources. Note: String definitions are kept unique across `strings.xml` and `missing_build_resources.xml` (e.g. `unfavorite` is retained only in `missing_build_resources.xml`).

### Main Kotlin package (`dev.chungjungsoo.gptmobile`)

#### `data/` — Data & Integration Layer

- `ModelConstants.kt` — Core constants for supported models, platform IDs, fallback model designations, and provider tags.
- `agent/` — Autonomous agent orchestration, tool-call loops, limits, background execution, provider adapters, and built-in/MCP tools:
  - `AgentContracts.kt` — Core agent interfaces and data types: `ProviderEvent` (deltas, tool calls/results, failures, notices), `AgentToolDefinition`, `AgentToolResult`, `ToolResultContent` (Text, Json, ResourceLinks), `AgentTool` interface, `AgentToolExchange`, `AgentProviderSession`, `AgentRunEvent`, and `ToolDefinitionsRejectedException`.
  - `AgentRunCoordinator.kt` — Singleton coordinator for background/foreground agent execution. Manages concurrent run jobs by `runId`, ensures per-chat serialization via `Mutex` gates, throttles database/UI updates (adaptive 33ms on >=10GB RAM devices, 250ms otherwise), starts `AgentRunForegroundService`, handles cancellation/interruption states, and writes terminal run records to `ChatRepository`.
  - `AgentRunner.kt` — Core bounded tool execution loop. Enforces `AgentRunLimits` (timeouts, max rounds, tool-call ceilings, concurrency semaphores, output byte limits). Injects final-response instructions when approaching tool limits and falls back cleanly when models reject tool definitions.
  - `PlatformAgentRunner.kt` — Factory function `agentRunnerForPlatform` providing isolated runner instances per platform and run override to prevent budget leakage.
  - `provider/` — Model provider streaming adapters:
    - `LiteRtLmAdapter.kt` — Adapter for local on-device LiteRT-LM inference and tool-calling execution.
    - `ProviderAdapters.kt` — Protocol-specific adapters mapping OpenAI, Anthropic, Google Gemini, Groq, and OpenRouter to `AgentProviderSession` with multi-key round-robin rotation (`ApiCredentialRotator`), attribution headers (`HTTP-Referer`, `X-Title`), reasoning configuration, and rotatable error detection.
    - `ProviderAttachmentEncoder.kt` — Formats and base64-encodes media and file attachments for various provider payload formats.
    - `ProviderEventAssemblers.kt` — Reconstructs and normalizes raw streaming SSE deltas into coherent `ProviderEvent` streams.
  - `tool/` — Agent tool execution and resolution:
    - `AgentToolResolver.kt` — Discovers, resolves, and binds available tools (built-in and MCP) for active profiles and chats.
    - `CalculatorTool.kt` — Built-in mathematical expression evaluation engine.
    - `CurrentDateTool.kt` — Supplies localized current date, time, and timezone information.
    - `DeviceLocationProvider.kt` — Android location services integration with permission verification.
    - `DeviceLocationTool.kt` — Built-in tool wrapping device location coordinates.
    - `McpClientManager.kt` — Manages active MCP client connections, transports, and tool life cycles.
    - `McpOAuthClient.kt` — Handles OAuth2 flows, PKCE, token refresh, and auth endpoints for protected MCP tools.
    - `McpOAuthCoordinator.kt` — Coordinates user authorization UX and callback dispatch for MCP OAuth services.
    - `McpToolMapper.kt` — Translates MCP tool definitions and execution schemas into native `AgentTool` interfaces.
    - `ReadUrlTool.kt` — Securely fetches and boundedly extracts readable text content from public HTTP/HTTPS URLs.
    - `WebSearchTool.kt` — Built-in web search tool supporting search providers and result parsing.
- `backup/` — Backup and restore data management:
  - `AppBackupCrypto.kt` — Typed PBKDF2-HMAC-SHA256 and AES-256-GCM encryption engine for backup archives. Uses random salts/IVs, payload-type AAD, magic headers, version checks, and a 128 MiB size limit.
  - `AppBackupManager.kt` — Exports and imports themes, platforms, credentials (via `SecretVault`), chats, messages, and model associations. Operates on `Dispatchers.IO` and wipes temporary secret buffers.
  - `AppBackupModels.kt` — Serializable data contracts for backup envelopes and records.
  - `SanitizedChatBackup.kt` — Full-backup integration. Creates sanitized SQLite snapshots via `VACUUM INTO`, strips sensitive tokens (`platform_v2.token`), restores via `AtomicFile`, and purges stale WAL/SHM sidecars.
  - `UserBackupManager.kt` — High-level user backup coordinator supporting selective export/import of platforms, credentials, chats, models, and tools.
- `catalog/` — Model and MCP catalog metadata:
  - `McpPresetCatalog.kt` — Defines MCP transport/category/pricing enums and preset models; exposes built-in server presets including preinstalled `droid-mcp-web` Online Search (`web_search`, `fetch_webpage`), compatibility aliases, category filtering, ID/alias lookup, and search.
  - `ModelCatalog.kt` — Serializable model-catalog schema, including capabilities, default generation configuration, and SoC-specific model variants.
  - `ModelCatalogParser.kt` — Parses lenient JSON while ignoring unknown fields, validates schema compatibility and minimum app versions, formats model download sizes, and compares dotted app versions.
- `context/` — Context-window budgeting and compaction:
  - `ContextBuilder.kt` — Builds provider-aware history: selects provider-specific assistant responses, strips error notes, excludes failed historical turns, applies recent-turn and character budgets, and removes attachments from older turns.
  - `ConversationTurn.kt` — Models paired user/assistant messages and identifies the current turn.
  - `ProviderContextPolicy.kt` — Defines provider-specific history, attachment, and character limits.
- `database/` — Room persistence layer (Database v14):
  - `ChatDatabase.kt` / `ChatDatabaseV2.kt` — Primary Room database holder with schema versioning and type converter declarations.
  - `ChatDatabaseV2Migrations.kt` — Production schema migrations covering versions 10 through 14 (adding tool connections, assistant timeline items, agent runs, agent tool bindings, and local models; v14 adds `max_tool_calls` column to `platform_v2`).
  - DAOs: `AgentPersistenceDao.kt`, `AgentRunDao.kt`, `ChatPlatformModelV2Dao.kt`, `ChatRoomDao.kt`, `ChatRoomV2Dao.kt`, `LocalModelDao.kt`, `MessageDao.kt`, `MessageV2Dao.kt`, `PlatformV2Dao.kt`, `ToolConnectionDao.kt`.
  - Entities & Converters: `AgentRun.kt`, `AgentToolBinding.kt`, `AssistantTimelineItem.kt`, `ChatPlatformModelV2.kt`, `ChatRoom.kt`, `ChatRoomV2.kt`, `Converters.kt`, `LocalModel.kt`, `Message.kt`, `MessageV2.kt`, `Platform.kt`, `PlatformV2.kt`, `ToolConnection.kt`, `ToolEvent.kt`, `ToolExecutionConverters.kt`.
- `datastore/` — Preferences & key-value configuration:
  - `SettingDataSource.kt` / `SettingDataSourceImpl.kt` — DataStore implementation persisting UI preferences, active platform selections, streaming toggles, theme configurations, and tool-call ceilings.
- `dto/` — Serializable data transport objects:
  - `APIModel.kt`, `ApiState.kt`, `ConfigBackupDto.kt`, `Platform.kt`, `ThemeSetting.kt`.
  - Subpackages: `anthropic/` (messages, content blocks), `google/` (generate content requests/responses), `groq/` (chat completions), `openai/` (chat completions, function callings, tool definitions, OpenRouter extensions).
- `huggingface/` — Hugging Face Hub integration:
  - `HuggingFaceOAuthConfig.kt`, `HuggingFaceOAuthRequests.kt`, `HuggingFaceTokenStore.kt`, `HuggingFaceUrls.kt` — OAuth2 authentication flow, secure token persistence, and API endpoint routing for model downloads.
- `localmodel/` — Metadata and models for downloaded on-device models.
- `localruntime/` — Local inference runtime via LiteRT:
  - `ConversationFingerprint.kt` — Generates unique state hashes to avoid unnecessary prompt re-evaluations.
  - `LocalAccelerators.kt` — Detection and configuration of NPU, GPU, and CPU hardware accelerators.
  - `LocalEngineHolder.kt` — Thread-safe lifecycle holder for the native LiteRT model instance.
  - `LocalEngineMaxTokens.kt` — Max token calculations and context boundary limits.
  - `LocalModelValidator.kt` — File integrity and schema validation for downloaded `.tflite` / `.bin` model files.
  - `LocalRuntime.kt` / `LocalRuntimeImpl.kt` — Native on-device execution engine coordinating prompt evaluations, sampling parameters, and streaming token responses.
  - `LocalSamplingDefaults.kt` — Default temperature, top-p, and top-k hyperparameters for local models.
- `mcp/` — Model Context Protocol search and integration:
  - `McpIntegratedSearchManager.kt` — Federated search coordination across active MCP tool providers (defaults to preinstalled `droid-mcp-web` Online Search tools: `web_search` and `fetch_webpage`).
  - `McpPresetCatalog.kt` — Catalog definitions and presets for MCP servers including `droid-mcp-web` Online Search preinstalled.
  - `McpSearchToolSet.kt` — Dynamic toolset wrappers for search operations based on `droid-mcp-web` (`web_search` and `fetch_webpage`).
- `model/` — Domain models:
  - `ApiType.kt`, `ChatAttachment.kt`, `ChatMcpToolConfig.kt`, `ClientType.kt`, `DynamicTheme.kt`, `GeminiSafetySettings.kt`, `ThemeMode.kt`.
- `network/` — Networking layer & provider APIs:
  - `ApiCredentialRotator.kt` — Multi-key parsing, serialization, error detection (HTTP 429 rate limit, 402 payment required, 401/403 auth failures, quota limits), and round-robin fallback execution.
  - `NetworkClient.kt` — Configured Ktor client factory supporting HTTP/SSE engines and timeouts.
  - `NetworkRetryUtils.kt` — Bounded retry loop with exponential backoff and jitter for transient network failures.
  - `ProviderRequestConfig.kt` — Provider timeout, auth header, custom `extraHeaders`, and proxy specifications.
  - `SseUtils.kt` — Robust Server-Sent Events parser handling multiline chunks and field extraction.
  - `UploadedProviderFile.kt` — Remote provider file upload metadata.
  - Provider interfaces & implementations: `AnthropicAPI.kt`, `AnthropicAPIImpl.kt`, `GoogleAPI.kt`, `GoogleAPIImpl.kt`, `GroqAPI.kt`, `GroqAPIImpl.kt`, `OpenAIAPI.kt`, `OpenAIAPIImpl.kt`.
- `openrouter/` — OpenRouter platform integration:
  - `OpenRouterModel.kt` — Data structures representing OpenRouter model specifications, pricing, and context limits.
  - `OpenRouterAdvancedOptions.kt` — Serializable DTOs for provider routing configuration (`OpenRouterProviderRouting`), reasoning configuration (`OpenRouterReasoning`), and plugins (`OpenRouterPlugin`).
- `parser/` — Payload and token parsing:
  - `ThinkingParser.kt` — Extracts reasoning blocks (`<think>...</think>`) and separates internal thoughts from final output.
- `repository/` — Repository implementations coordinating persistence and network:
  - `AttachmentUploadCoordinator.kt` — Uploads and prepares media attachments for provider consumption.
  - `ChatRepository.kt` / `ChatRepositoryImpl.kt` — Central repository for chat rooms, message history, streaming response aggregation, and run state management.
  - `GroqReasoningParser.kt` — Specialized parser extracting reasoning thoughts from Groq completion payloads.
  - `LocalModelRepository.kt` / `LocalModelRepositoryImpl.kt` — Tracks downloaded local models, validation states, and file deletions.
  - `ModelCatalogRepository.kt` / `ModelCatalogRepositoryImpl.kt` — Fetches, caches, and parses remote/bundled model catalogs.
  - `OpenRouterModelRepository.kt` — Retrieves dynamic model listings from the OpenRouter catalog API.
  - `SettingRepository.kt` / `SettingRepositoryImpl.kt` — Domain facade over DataStore settings.
  - `ToolConnectionRepository.kt` — Manages database records for active MCP tool connections.
  - `ToolEventRecorder.kt` — Logs and persists tool execution traces and results into Room timeline entities.
- `security/` — Security & Keystore management:
  - `SecretVault.kt` — Keystore-backed AES-256-GCM encrypted vault storing sensitive API keys in `noBackupFilesDir`.
- `worker/` — WorkManager background tasks:
  - `LocalModelDownloadWorker.kt` — Resilient background worker handling large model file downloads with progress notifications, integrity hashing, and resumption.

#### `di/` — Dependency Injection (Hilt Modules)

- `ChatRepositoryModule.kt` — Binds `ChatRepository` to `ChatRepositoryImpl`.
- `DataStoreModule.kt` — Provides singleton `DataStore<Preferences>` instance.
- `DatabaseModule.kt` — Provides `ChatDatabaseV2` and all Room DAOs.
- `DeviceSocModel.kt` — Injects detected hardware SoC configuration for model matching.
- `LocalModelModule.kt` — Binds `LocalModelRepository`.
- `LocalRuntimeModule.kt` — Binds `LocalRuntime` to `LocalRuntimeImpl`.
- `ModelCatalogModule.kt` — Provides `ModelCatalogRepository`.
- `NetworkModule.kt` — Provides singleton Ktor HTTP engines, JSON serializers, and provider API clients.
- `SettingDataSourceModule.kt` — Binds `SettingDataSource`.
- `SettingRepositoryModule.kt` — Binds `SettingRepository`.

#### `presentation/` — Presentation Layer (Compose & ViewModels)

- UI features: `chat/`, `home/`, `localmodel/`, `main/`, `mcpmarketplace/`, `migrate/`, `setting/`, `setup/`, `startscreen/`, and `thinking/`.
- `HomeScreen.kt` / `HomeViewModel.kt`:
  - `HomeTab.CHATS`: Displays chat list with search, duplicate, delete actions, and model selection dialog.
  - `HomeTab.FAVORITES`: Redesigned favorites management with search bar removed. Features custom group filter chips ("All", user-created categories, "+ Add Group"), assignment of favorites to custom groups, and a full-screen favorite detail view/dialog (`DialogProperties(usePlatformDefaultWidth = false)`). The detail view renders rich content via `ChatMarkdown` (Markdown, LaTeX math, code highlighting, typography, assistant `GPTMobileIcon`), vertical scrolling, a persistent "View" button navigating into the chat, and a persistent Cyan-highlighted favorite star button that confirms removal via `AlertDialog`.
- `ToolTraceBlock.kt` — Displays active and completed tool execution traces with dark card backgrounds, official app foreground icons, full-width styling, clean tool names, and collapsible output payloads.
- `PlatformSettingDialogs.kt` — Dynamic multi-key API dialog with `+API` button, per-key removal, preserved rows on dismissal/update, and combination into `ApiCredentialRotator` format.
- `PlatformSettingScreen.kt` — Configures existing platforms and passes current tokens (`platformData.token`) to `APIKeyDialog`.
- `SetupPlatformWizardScreen.kt` — Step-by-step setup wizard with dynamic multi-key API credentials (`+API`, delete row), keeping keys visible and formatted via `ApiCredentialRotator`.
- `SetupViewModelV2.kt` — Wizard ViewModel retaining and prefilling existing API keys when adding similar platform types (e.g. OpenRouter).
- `ToolConnectionsScreen.kt` — External tool connection and MCP setup screen featuring multi-key dynamic credential input with `+API` button and per-key removal.
- `McpMarketplaceDialog.kt` — MCP marketplace and integration dialog featuring brand icons (`online_search`, `github`, `brave`, `terminal`, etc.), search/pricing filters, and preinstalled status presentation.
- Maintains unidirectional data flow: ViewModels expose immutable `StateFlow` consumed via `collectAsStateWithLifecycle()`.

### Kotlin in `app/src/main/java/`

- `app/src/main/java/dev/chungjungsoo/gptmobile/data/backup/EncryptedBackupManager.kt` — Legacy encrypted backup coordinator using PBKDF2-HMAC-SHA256 and AES-256-GCM.

### Tests

- `app/src/test/kotlin/dev/chungjungsoo/gptmobile/` — JVM unit test suites covering agents, catalogs, context compaction, database DAOs, DTO serialization (`OpenRouterAdvancedOptionsTest`, `ProviderAttachmentSerializationTest`), Hugging Face auth, local runtime, MCP search/tools (`McpIntegratedSearchManagerTest`, `McpPresetCatalogTest`), network retry/parsing, `ApiCredentialRotatorTest` (multi-key parsing, serialization, and round-robin fallback), repositories, and ViewModels.
- `app/src/test/java/dev/chungjungsoo/gptmobile/data/backup/EncryptedBackupManagerTest.kt` — Unit tests for legacy encrypted backup/restore roundtrips.
- `app/src/androidTest/kotlin/dev/chungjungsoo/gptmobile/` — Android instrumented integration tests covering database migrations, Room schemas, `SecretVaultInstrumentedTest`, and Compose UI interactions.

## 3. Engineering Guidelines (Do's)

### Before changing code

- Read the target, callers, interfaces, DI bindings, persistence/network models, and relevant tests first.
- Follow package boundaries: Compose/ViewModel → repository abstraction → data sources/integrations.
- Keep diffs minimal and atomic; preserve local naming and formatting.
- Update `AI-Memory.md` whenever files or responsibilities change.

### Kotlin and formatting

- Use 4 spaces, LF endings, a final newline, and no tabs.
- Follow ktlint 1.3.1 with `android_studio` style; do not add trailing commas.
- Keep one primary class per file and match its filename.
- Use PascalCase for classes/Composables/ViewModels, camelCase for functions/variables, and `SCREAMING_SNAKE_CASE` for constants.
- Suffix implementations with `Impl`, ViewModels with `ViewModel`, and versioned V2 models with `V2`.
- Prefix mutable StateFlow backing fields with `_` and expose them via `asStateFlow()`, or avoid leading underscores if there is no identical public property name without an underscore (comply with ktlint `standard:backing-property-naming`).
- Preserve the repository's grouped/alphabetized import style.

### State, coroutines, and Compose

- Model UI state with immutable data/sealed classes and StateFlow.
- Collect in Compose with lifecycle-aware APIs such as `collectAsStateWithLifecycle()`.
- Use `viewModelScope` and structured concurrency; preserve cancellation.
- Emit explicit loading/error/completion states.
- Batch/throttle streaming UI updates instead of recomposing for every token.

### Validation, persistence, and security

- Validate indices and provider/user input with early returns and null-safe access.
- Preserve provider-specific errors without leaking credentials or sensitive payloads.
- Keep SSE parsing chunk-safe and retries bounded with backoff.
- Enforce configurable agent/MCP tool-call ceilings.
- Add Room migrations, exported schemas, and migration tests for schema changes.
- Store credentials only through the established `SecretVault`/Keystore AES-GCM path.
- Preserve vault record versioning, secret-reference AAD binding, strict size/path validation, atomic writes, no-backup storage, and zeroing of temporary sensitive arrays.
- For encrypted exports, keep production KDF iterations strong; tests may inject a reduced count. Never reuse a salt/IV or replace authenticated encryption with unauthenticated encryption.

### Build and tests

Run focused checks first, then broader checks when feasible:

```bash
./gradlew testDebugUnitTest
./gradlew test
./gradlew assembleDebug
./gradlew connectedAndroidTest   # requires a device/emulator
```

Focused JVM test:

```bash
./gradlew test --tests "fully.qualified.TestClass"
```

Add tests for behavior changes, especially parsers, compaction, migrations, repositories, MCP limits, streaming, retries, backup formats, vault record validation, and restore failure paths. Verify release-sensitive changes against ABI splits, R8, shrinking, and Java 21 compatibility.

## 4. Anti-Patterns & Traps (Don'ts)

- Do not commit API keys, OAuth secrets, signing keys, passwords, tokens, private endpoints, or real credentials.
- Do not bypass `SecretVault`/Keystore encryption or persist credentials in plain Room/DataStore, logs, backups, or UI snapshots.
- Do not weaken vault reference validation, remove AAD binding, move vault records out of `noBackupFilesDir`, reuse IVs, or retain irrecoverable ciphertext after key loss.
- Do not log prompts, responses, authorization headers, credentials, or tool payloads in release paths.
- Do not perform network, database, file, crypto, or inference work on the main thread.
- Do not expose mutable flows, collect flows in Compose without lifecycle awareness, block cancellation, or swallow `CancellationException`.
- Do not instantiate repositories, databases, clients, MCP clients, or expensive runtimes directly in screens/ViewModels; use Hilt and abstractions.
- Do not assume stream chunks align with JSON objects or character boundaries.
- Do not remove bounded retry/backoff, context protection, tool-call limits, foreground-service compliance, or wake-lock release paths.
- Do not change Room entities without migrations, schema export, and tests; never use destructive migration as a shortcut.
- Do not lower production backup KDF work factors, skip format/header authentication checks, overwrite a live database before successful decryption/validation, or leave stale WAL/SHM files after restore.
- Do not hardcode provider/model capabilities when metadata already supplies them.
- Do not broaden permissions, exported components, service types, or URI/file access without a requirement and security review.
- Do not add unsupported 32-bit ABIs, weaken R8/privacy rules merely to silence failures, or manually edit generated Room schemas.
- Do not use wildcard imports unless explicitly permitted, and do not place comments inside/after annotation value parameters.
- Do not duplicate thinking parsers/screens/components across `data/`, `presentation/`, and `ui/`; inspect implementations, call sites, and tests first.
