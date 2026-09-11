# AI Memory

Persistent repository context for AI coding agents. Keep this file synchronized whenever repository files are added, modified, renamed, or deleted.

> Index status: comprehensive and actively maintained on `main`, `0.9.1`, and `release-0.9.1`. Core architecture, Android targets, UI screens, encrypted backup/security, agent runtime/tools, Room V2 database & migrations, DataStore, local runtime & acceleration, network transports & SSE parsing, model catalogs, OpenRouter advanced routing/reasoning, Ollama advanced options & timeout resilience, WorkManager workers, DI modules, DTOs, and test roots are fully indexed. All open issues, PRs, and build test suites are reconciled and passing. PR #172 (`merge-ollama-to-main`) has been merged into `main` and superseded PR #171 was closed.
>
> **Latest Official Release:** [v0.9.0](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/releases/tag/v0.9.0) (`prerelease: false`, `draft: false`, official latest release). Build pipeline configured with automated `apksigner` code signing and stripped `-unsigned` suffixes so all released APK artifacts are cleanly named signed release packages with accompanying `.idsig` v4 signature files: universal APK (`app-universal-release.apk`), ARM64 APK (`app-arm64-v8a-release.apk`), and x86_64 APK (`app-x86_64-release.apk`).
> **Current Version:** `0.9.1` (versionCode 33) — LiteRT-LM hardware acceleration, OpenRouter advanced routing, Ollama 5-minute timeout resilience loop, emulator alias fallback (`10.0.2.2`), Room Schema 18 (`MIGRATION_17_18`) adding `ollama_options` to `platform_v2`, Room Schema 17 (`MIGRATION_16_17`) adding `disable_remote_tools` and `disable_local_tools` to `platform_v2`, dedicated full-screen `McpToolsSelectionScreen` and `AiPlatformsScreen`, reactive flow synchronization between platform cards and platform settings detail toggles, Settings menu feature organization with "AI Platforms" situated cleanly above "Local Models", collapsible Tool Connections with direct inline settings and status badges, bounded layout isolation eliminating thinking/tool-call overlaps, details indicator positioned above user message bubbles, multi-key 3-second round-robin auto-retry on high demand spikes, fancy interactive sorting chips in platform selection dialogs with fixed HomeScreen compilation bindings, vector asset `ic_extended_thinking` supplied in drawables, clean single-definition `PlatformSettingDialogs.kt` removing duplicated legacy blocks, corrected `PlatformSettingScreen.kt` implementation consistent with `PlatformSettingViewModel`, SearchBackendDialog and LegacyMcpToolsDialog implementations with string resources `none` and `search_backend`, de-duplicated resource keys across `missing_build_resources.xml` and `strings.xml` satisfying `scripts/check_android_resources.py` preflight, ktlint standard compliance (function-expression-body, no unused imports), aligned `SettingDataSource` package imports and preferences snapshot mocks in unit tests, and comprehensive test suite updates for reactive flows, Ollama resilience, and Room Schemas 17 and 18.

## 1. Repository Overview

GPT Mobile AI (Improved) is a Kotlin Android application for chatting with cloud, self-hosted, and on-device large language models. It supports OpenAI-compatible services, Anthropic, Google Gemini, Groq, OpenRouter, Ollama, and local LiteRT models. It also includes an autonomous agent runtime, Model Context Protocol (MCP) tools and marketplace, resilient streaming, background execution, chat search/history, multi-key API credential rotation with dynamic UI management and seamless streaming fallback, and encrypted credential storage.

### Architecture and stack

- **Architecture:** Clean MVVM with repository, domain-boundary abstractions, and data-source layers.
- **UI:** Jetpack Compose, Material 3, lifecycle-aware state collection (`collectAsStateWithLifecycle`), and Compose Navigation.
- **Motion & Transitions:** Theme motion primitives (`defaultSpatialSpec`, `fastSpatialSpec`, `fastEffectsSpec`) in `presentation.theme.Motion.kt` backing collapsible details animations and responsive indicator state transitions.
- **Language/runtime:** Kotlin 2.x, Java 21 bytecode, coroutines, Flow/StateFlow, and kotlinx.serialization.
- **Dependency injection:** Hilt/Dagger with KSP.
- **Networking:** Ktor clients (OkHttp and CIO engines), Server-Sent Events (SSE) streaming support, resilient retry/exponential backoff, and `ApiCredentialRotator` for round-robin multi-key failover across `ProviderAdapters` (OpenAI, Anthropic, Gemini, Groq, OpenRouter, and OpenAI-compatible services). Includes 3-second delay and rotation on high-demand spikes ("This model is currently experiencing high demand").
- **Persistence:** Room (`ChatDatabaseV2`, Schema version 18) with full FTS search and DataStore preferences (`SettingDataSource`). Migration 16->17 adds `disable_remote_tools` and `disable_local_tools` integer columns to `platform_v2`. Migration 17->18 adds `ollama_options` column to `platform_v2`.
- **Security:** Android Keystore-backed AES-256-GCM credential encryption (`SecretVault`); passphrase-protected user exports using PBKDF2-HMAC-SHA256 and AES-256-GCM (`AppBackupCrypto`).
- **Local inference:** LiteRT-LM (`LocalRuntimeImpl`, conversation fingerprinting, dynamic accelerator selection for NPU/GPU/CPU, warm engine retention, speculative decoding); Ollama supported for self-hosted network inference with 5-minute timeout resilience loop, emulator alias fallback (`10.0.2.2`), and configurable advanced options.
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

- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/NavigationGraph.kt` — Core Compose navigation destination graph (`SetupNavGraph`, `homeScreenNavigation`, `chatScreenNavigation`, `settingNavigation`, `setupNavigation`). Correctly aligned composable arguments for `SetupPlatformTypeScreen`, `SetupCompleteScreen`, `PlatformSettingScreen`, `ToolConnectionsScreen`, `ToolConnectionEditorScreen`, `McpToolsSelectionScreen`, `AiPlatformsScreen`, and `McpMarketplaceScreen`.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/PlatformSettingScreen.kt` — Screen rendering platform details, switches, and setting items. Connects `PlatformSettingViewModel` and `PlatformSettingDialogs`.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/PlatformSettingDialogs.kt` — Modal configuration dialogs for platform settings (Ollama advanced options, OpenRouter routing, API tokens, temperature, topP, topK, accelerator, maxTokens, system prompt, Gemini safety).
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/PlatformSettingViewModel.kt` — ViewModel managing platform settings, tool binding state, catalog models, and dialog states.
- `app/src/main/res/drawable/ic_extended_thinking.xml` — Vector drawable for the extended thinking toggle.
- `app/src/main/res/values/strings.xml` — Canonical localization and string resources.
