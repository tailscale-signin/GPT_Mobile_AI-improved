# AI Memory

Persistent repository context for AI coding agents. Keep this file synchronized whenever repository files are added, modified, renamed, or deleted.

> Index status: comprehensive and actively maintained on `main`, `0.9.2.3`, `release-0.9.2.3`, `0.9.1`, and `feature/v0.9.2-initiation`. Core architecture, Android targets, UI screens, encrypted backup/security, agent runtime/tools, Room V2 database & migrations, DataStore, local runtime & acceleration, network transports & SSE parsing, model catalogs, OpenRouter advanced routing/reasoning, Ollama advanced options & timeout resilience, WorkManager workers, DI modules, DTOs, and test roots are fully indexed.
>
> **Latest Official Release:** [v0.9.2.1](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/releases/tag/v0.9.2.1) (`prerelease: false`, `draft: false`, official release).
> **Current Version:** `0.9.2.3` (versionCode 38, pre-release on `0.9.2.3` / `release-0.9.2.3`). Key additions: bottom-right message timestamp layout, conversational continuation trigger detection with pulsing glowing chip, high-importance heads-up background completion alerts, live active chat loading spinner in HomeScreen, and visual platform badges in Tool Connections.

## 1. Repository Overview

GPT Mobile AI (Improved) is a Kotlin Android application for chatting with cloud, self-hosted, and on-device large language models. It supports OpenAI-compatible services, Anthropic, Google Gemini, Groq, OpenRouter, Ollama, and local LiteRT models. It also includes an autonomous agent runtime, Model Context Protocol (MCP) tools and marketplace, resilient streaming, background execution, chat search/history, multi-key API credential rotation with dynamic UI management and seamless streaming fallback, and encrypted credential storage.

### Architecture and stack

- **Architecture:** Clean MVVM with repository, domain-boundary abstractions, and data-source layers.
- **UI:** Jetpack Compose, Material 3, lifecycle-aware state collection (`collectAsStateWithLifecycle`), and Compose Navigation.
- **Motion & Transitions:** Theme motion primitives (`defaultSpatialSpec`, `fastSpatialSpec`, `fastEffectsSpec`) in `presentation.theme.Motion.kt` backing collapsible details animations, continuation pulsing chip, and responsive indicator state transitions.
- **Language/runtime:** Kotlin 2.x, Java 21 bytecode, coroutines, Flow/StateFlow, and kotlinx.serialization.
- **Dependency injection:** Hilt/Dagger with KSP.
- **Networking:** Ktor clients (OkHttp and CIO engines), Server-Sent Events (SSE) streaming support, resilient retry/exponential backoff, and `ApiCredentialRotator` for round-robin multi-key failover across `ProviderAdapters` (OpenAI, Anthropic, Gemini, Groq, OpenRouter, and OpenAI-compatible services).
- **Persistence:** Room (`ChatDatabaseV2`, Schema version 19) with full FTS search and DataStore preferences (`SettingDataSource`). Migration 18->19 adds `is_archived` to `chats_v2`, `labels` and `is_favorite` to `platform_v2`, and `timestamp` to `messages_v2`.
- **Security:** Android Keystore-backed AES-256-GCM credential encryption (`SecretVault`); passphrase-protected user exports using PBKDF2-HMAC-SHA256 and AES-256-GCM (`AppBackupCrypto`).
- **Local inference:** LiteRT-LM (`LocalRuntimeImpl`, conversation fingerprinting, dynamic accelerator selection for NPU/GPU/CPU, warm engine retention, speculative decoding); Ollama supported for self-hosted network inference with timeout resilience and configurable advanced options.
- **Background work:** Foreground service (`AgentRunForegroundService`) with partial wake locks for active agent runs, high-importance completion notifications (`CHANNEL_AGENT_COMPLETION`), and WorkManager (`LocalModelDownloadWorker`) for resilient background model downloads.
- **Android targets:** application ID `dev.melo.gptmobile.improved`, min SDK 31, compile/target SDK 36, arm64-v8a and x86_64 ABIs.
- **Build/release:** Gradle Kotlin DSL, R8/resource shrinking, ABI splits plus universal APK, and Room schema export (`app/schemas/`). Version `0.9.2.3` (versionCode 38). Release workflow automatically runs `apksigner` and outputs signed release packages (`app-universal-release.apk`, `app-arm64-v8a-release.apk`, `app-x86_64-release.apk`, `app-release.aab`).
- **Testing/style:** JUnit 4/5, kotlinx-coroutines-test, AndroidX instrumented/Compose tests, Room testing (`ChatDatabaseV2MigrationsTest`), and ktlint 1.3.1 using Android Studio style.

## 2. Repository Index

### Root

- `.editorconfig` — Editor and ktlint-compatible formatting rules.
- `.github/workflows/` — CI build, check, formatting, and release automation workflows (`release-build.yml`).
- `.gitignore` — Version-control exclusions; do not scan ignored files for secrets.
- `AGENTS.md` — Authoritative agent-facing build, style, architecture, and test guidance.
- `AI-Memory.md` — This persistent architecture and structural index file.
- `CHANGELOG.md`, `RELEASE_NOTES.md`, `PROGRESS.md` — Historical changes, release details, and progress records.
- `CLAUDE.md`, `CONTEXT.md` — Additional AI/project context files.
- `README.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `LICENSE` — Product and contributor documentation.
- `build.gradle.kts`, settings.gradle.kts, `gradle.properties`, `gradle/`, `gradlew*` — Gradle build orchestration and wrappers.
- `model_catalog.json` — Bundled offline model catalog consumed by local model discovery features.
- `docs/` — ADRs, operational guidance, release validation, and CI diagnostics.
- `images/`, `metadata/` — Documentation/store assets and distribution metadata.
- `scripts/` — Build, maintenance, validation, and release helper scripts.

### Core Application & UI Architecture

- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/NavigationGraph.kt` — Core Compose navigation destination graph (`SetupNavGraph`, `homeScreenNavigation`, `chatScreenNavigation`, `settingNavigation`, `setupNavigation`). Correctly aligned composable arguments for `SetupPlatformTypeScreen`, `SetupCompleteScreen`, `PlatformSettingScreen`, `ToolConnectionsScreen`, `ToolConnectionEditorScreen`, `McpToolsSelectionScreen`, `AiPlatformsScreen`, and `McpMarketplaceScreen`.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/PlatformSettingDialogs.kt` — Dialog composables for platform customization, parameter tuning, accelerator selection, and advanced options. Single clean implementation of dialogs without duplicates, correct parameter mapping, and safe null handling.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/PlatformSettingScreen.kt` — Primary platform settings view with full reactive StateFlow observation, advanced options routing, and MCP/search tools integration.
