# AI Memory

Persistent repository context for AI coding agents. Keep this file synchronized whenever repository files are added, modified, renamed, or deleted.

> Index status: comprehensive and actively maintained on `main`, `0.9.1`, and `release-0.9.1`. Core architecture, Android targets, UI screens, encrypted backup/security, agent runtime/tools, Room V2 database & migrations, DataStore, local runtime & acceleration, network transports & SSE parsing, model catalogs, OpenRouter advanced routing/reasoning, WorkManager workers, DI modules, DTOs, and test roots are fully indexed. All open issues and PRs are reconciled and resolved.
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

- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/NavigationGraph.kt` — Core Compose navigation destination graph (`SetupNavGraph`, `homeScreenNavigation`, `chatScreenNavigation`, `settingNavigation`, `setupNavigation`). Correctly aligned composable arguments for `SetupPlatformTypeScreen`, `SetupCompleteScreen`, `PlatformSettingScreen`, `ToolConnectionsScreen`, `ToolConnectionEditorScreen`, `McpToolsSelectionScreen`, and `McpMarketplaceScreen`.
