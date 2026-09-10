# AI Memory

Persistent repository context for AI coding agents. Keep this file synchronized whenever repository files are added, modified, renamed, or deleted.

> Index status: comprehensive and actively maintained on `main` and branch `0.9.1`. Core architecture, Android targets, UI screens, encrypted backup/security, agent runtime/tools, Room V2 database & migrations, DataStore, local runtime & acceleration, network transports & SSE parsing, model catalogs, OpenRouter advanced routing/reasoning, WorkManager workers, DI modules, DTOs, and test roots are fully indexed. All open issues and PRs are reconciled and resolved.
>
> **Latest Official Release:** [v0.9.0](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/releases/tag/v0.9.0) (`prerelease: false`, `draft: false`, official latest release). Build pipeline configured with automated `apksigner` code signing and stripped `-unsigned` suffixes so all released APK artifacts are cleanly named signed release packages with accompanying `.idsig` v4 signature files: universal APK (`app-universal-release.apk`), ARM64 APK (`app-arm64-v8a-release.apk`), and x86_64 APK (`app-x86_64-release.apk`).
> **Current Version:** `0.9.1` (versionCode 33) — LiteRT-LM hardware acceleration, OpenRouter advanced routing, Room Schema 16 (`MIGRATION_15_16`) adding `disable_all_tools` to `platform_v2`, dedicated full-screen `McpToolsSelectionScreen`, synchronized platform toggles disabling models everywhere, Settings menu feature organization with "AI Platforms" situated cleanly above "Local Models", bounded layout isolation eliminating thinking/tool-call overlaps, details indicator positioned above user message bubbles, modernized Tool Connections screen with direct switch toggles, multi-key 3-second round-robin auto-retry on high demand spikes, and interactive sorting chips in platform selection dialogs.

## 1. Repository Overview

GPT Mobile AI (Improved) is a Kotlin Android application for chatting with cloud, self-hosted, and on-device large language models. It supports OpenAI-compatible services, Anthropic, Google Gemini, Groq, OpenRouter, Ollama, and local LiteRT models. It also includes an autonomous agent runtime, Model Context Protocol (MCP) tools and marketplace, resilient streaming, background execution, chat search/history, multi-key API credential rotation with dynamic UI management and seamless streaming fallback, and encrypted credential storage.

### Architecture and stack

- **Architecture:** Clean MVVM with repository, domain-boundary abstractions, and data-source layers.
- **UI:** Jetpack Compose, Material 3, lifecycle-aware state collection (`collectAsStateWithLifecycle`), and Compose Navigation.
- **Motion & Transitions:** Theme motion primitives (`defaultSpatialSpec`, `fastSpatialSpec`, `fastEffectsSpec`) in `presentation.theme.Motion.kt` backing collapsible details animations and responsive indicator state transitions.
- **Language/runtime:** Kotlin 2.x, Java 21 bytecode, coroutines, Flow/StateFlow, and kotlinx.serialization.
- **Dependency injection:** Hilt/Dagger with KSP.
- **Networking:** Ktor clients (OkHttp and CIO engines), Server-Sent Events (SSE) streaming support, resilient retry/exponential backoff, and `ApiCredentialRotator` for round-robin multi-key failover across `ProviderAdapters` (OpenAI, Anthropic, Gemini, Groq, OpenRouter, and OpenAI-compatible services). Includes 3-second delay and rotation on high-demand spikes ("This model is currently experiencing high demand").
- **Persistence:** Room (`ChatDatabaseV2`, Schema version 16) with full FTS search and DataStore preferences (`SettingDataSource`). Migration 15->16 adds `disable_all_tools` boolean column to `platform_v2`.
- **Security:** Android Keystore-backed AES-256-GCM credential encryption (`SecretVault`); passphrase-protected user exports using PBKDF2-HMAC-SHA256 and AES-256-GCM (`AppBackupCrypto`).
- **Local inference:** LiteRT-LM (`LocalRuntimeImpl`, conversation fingerprinting, dynamic accelerator selection for NPU/GPU/CPU, warm engine retention, speculative decoding); Ollama supported for self-hosted network inference.
- **Background work:** Foreground service (`AgentRunForegroundService`) with partial wake locks for active agent runs, and WorkManager (`LocalModelDownloadWorker`) for resilient background model downloads.
- **Android targets:** application ID `dev.melo.gptmobile.improved`, min SDK 31, compile/target SDK 36, arm64-v8a and x86_64 ABIs.
- **Build/release:** Gradle Kotlin DSL, R8/resource shrinking, ABI splits plus universal APK, and Room schema export (`app/schemas/`). Version `0.9.1` (versionCode 33). Release workflow automatically runs `apksigner` and outputs clean signed artifacts (`app-*-release.apk` with `.idsig` v4 signatures).
- **Testing/style:** JUnit 4/5, kotlinx-coroutines-test, AndroidX instrumented/Compose tests, Room testing (`ChatDatabaseV2MigrationsTest`), and ktlint 1.3.1 using Android Studio style.

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

### Core Application & UI Architecture

- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatScreen.kt` — Core conversation screen. Renders chat messages, tool execution progress cards, multi-turn tool calling logs, auto-scroll to favorited message on deep navigation, platform tab switching, and `rememberChatListState(messageCount, hasTargetMessage)` for instant bottom anchoring. Expandable details button and details section are positioned above the user chat bubble.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatBubble.kt` — User and assistant speech bubbles. Includes `OpponentResponseContainer` with animated cyan highlight bubble, padded borders around assistant responses, collapsible details toggle button (`DetailsButton`), and vertically isolated bounded containers for reasoning (`ThinkingBlock`) and tool traces (`ToolTraceBlock`) ensuring zero overlap.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt` — Core conversation ViewModel and decoupled pure domain/presentation helper functions.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/theme/Motion.kt` — Shared motion and spring transition primitives (`defaultSpatialSpec`, `fastSpatialSpec`, `fastEffectsSpec`).
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeScreen.kt` — Home dashboard, conversation list, interactive platform selection sorting chips (`PlatformSortOrder`), favorites modal dialog (`FavoriteDetailDialog`) with custom category groups, category assignments, LaTeX/Markdown/code rendering, un-favorite confirmation, and direct navigation to favorited message in chat.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeViewModel.kt` — ViewModel driving conversation listings, favorite grouping/filtering, and asynchronous `getChatRoom(chatId, onResult)` resolution for reliable navigation.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/SettingScreen.kt` & `SettingViewModelV2.kt` — Settings screen managing dynamic multi-key API credential configurations, encrypted storage, and model choices. Reorganized with "AI Platforms" in a dedicated category cleanly situated above "Local Models", and synchronized platform enabling/disabling states.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/PlatformSettingScreen.kt` & `PlatformSettingViewModel.kt` — Configuration screen for AI platforms, including OpenRouter Advanced Settings dialog, model pickers, sampling parameters, safety settings, master "Disable All Tools" toggle, and navigation to the dedicated full-screen `McpToolsSelectionScreen`.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/tools/McpToolsSelectionScreen.kt` — Dedicated full-page screen for browsing, searching, and configuring MCP tools with category icons, server groups, subcategorized tool functions, and master disable tool toggle.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/ToolConnectionsScreen.kt` — Redesigned tool connections screen featuring Material 3 elevated card containers, direct active status switches, and quick edits.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/navigation/Route.kt` & `NavigationGraph.kt` — Jetpack Compose navigation graph wiring destinations including `McpTools` route (`platform_setting/{platformId}/mcp_tools`).

### Database & Migrations

- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/ChatDatabaseV2.kt` — Room database definition (version 16).
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/ChatDatabaseV2Migrations.kt` — Incremental Room migrations, including `MIGRATION_14_15` (`open_router_routing`) and `MIGRATION_15_16` (`disable_all_tools` column added to `platform_v2`).
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/entity/PlatformV2.kt` — Room entity storing AI platform configs, tokens, endpoints, sampling parameters, `openRouterRouting`, and `disableAllTools`.
- `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/database/ChatDatabaseV2MigrationsTest.kt` — Migration unit tests verifying migration version ranges, default schema values, and entity conversion.

### Agent & Tooling

- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/domain/agent/tools/ReadFileSliceTool.kt` — Built-in line slicing tool for viewing bounded line ranges of local/remote files with 1-based indexing and line-number metadata.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/domain/agent/AgentRuntime.kt` — Autonomous agent orchestrator running multi-step reasoning, tool dispatching, MCP execution, and reflection loops.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt` — Chat repository integrating `disableAllTools` to suppress both offline and remote tools when disabled on a platform.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/provider/ProviderAdapters.kt` — Provider adapters for OpenAI, Anthropic, Gemini, Groq, and OpenRouter.

### Local Runtime & Hardware Acceleration

- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/localruntime/LocalRuntime.kt` — Core local runtime interfaces and event definitions (`LocalInferencePhase`, `LocalRuntimeEvent.PhaseChanged`).
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/localruntime/LocalRuntimeImpl.kt` — Implementation supporting cooperative thread yielding, phase-split scheduling, and warm engine retention.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/localruntime/DeviceHardwareGovernor.kt` — Dynamic hardware governor monitoring thermal and battery status for adaptive streaming intervals and token clamps.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/context/RollingContextWindowCompactor.kt` — Rolling context compaction preserving Turn 0 anchor prompts and system instructions while adhering to token ceilings.

### Networking & Credentials

- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/ApiCredentialRotator.kt` — Multi-key round-robin rotation with dynamic failover on rate limits (429), payment/quota issues (402), auth failures (401), and high demand spikes with a 3-second delay.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/ProviderRequestConfig.kt` — Provider request configuration; detects unsupported tool schemas to avoid retry loops.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/openrouter/OpenRouterAdvancedOptions.kt` — OpenRouter provider routing, reasoning, and plugin schemas.

## 3. Engineering Guidelines ("Do's")

- Always adhere to Kotlin 2.x and Material 3 design patterns.
- Prefer immutable data structures and explicit types where readability benefits.
- Preserve backward-compatibility for Room schemas; always update database version and migration definitions when modifying entities.
- Ensure all new tools are registered in baseline tools and exposed via appropriate interfaces.
- Keep pure presentation and domain functions testable in headless JVM environments without coupling to Compose UI classes or layout contexts.

## 4. Anti-Patterns & Traps ("Don'ts")

- Never hardcode API keys, secrets, or access tokens in source code or commits.
- Do not bypass `SecretVault` or `AppBackupCrypto` for storing or exporting sensitive user data.
- Avoid uncaught exceptions or blocking operations on the Compose UI thread.
