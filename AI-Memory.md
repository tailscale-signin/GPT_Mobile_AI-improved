# AI Memory

Persistent repository context for AI coding agents. Keep this file synchronized whenever repository files are added, modified, renamed, or deleted.

> Active Branch: `Ollama` — Ollama & OpenRouter platform features and timeout resilience: never fails on timeout, continuously retries for up to 5 minutes, supports Android emulator loopback alias fallback (`10.0.2.2`), handles HTTP 502/503/504 gateway & proxy loading codes, pre-populates default `ollama_options` on platform creation, and safely wraps up incomplete response if timeout persists. Cleanly rebased and conflict-free against `main`. Strongly typed `ChatMessage` objects in unit tests.
>
> Index status: comprehensive and actively maintained on `main`, `0.9.1`, `release-0.9.1`, and `Ollama`. Core architecture, Android targets, UI screens, encrypted backup/security, agent runtime/tools, Room V2 database & migrations, DataStore, local runtime & acceleration, network transports & SSE parsing, model catalogs, OpenRouter advanced routing/reasoning/sampling, Ollama advanced options & timeout resilience, WorkManager workers, DI modules, DTOs, and test roots are fully indexed. All open issues, PRs, and build test suites are reconciled and passing.
>
> **Latest Official Release:** [v0.9.0](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/releases/tag/v0.9.0) (`prerelease: false`, `draft: false`, official latest release). Build pipeline configured with automated `apksigner` code signing and stripped `-unsigned` suffixes so all released APK artifacts are cleanly named signed release packages with accompanying `.idsig` v4 signature files: universal APK (`app-universal-release.apk`), ARM64 APK (`app-arm64-v8a-release.apk`), and x86_64 APK (`app-x86_64-release.apk`).
> **Current Version:** `0.9.1` (versionCode 33) — LiteRT-LM hardware acceleration, OpenRouter advanced configurable parameters (`stream: true`, `max_tokens: 4096`, `temperature: 0.2`, `top_p: 0.15`, `top_k: 30`, `frequency_penalty: 0.0`, `presence_penalty: 0.0`, `repetition_penalty: 1.03`, `seed: 42`, `provider: { sort: "price-asc", allow_fallbacks: true, skip: ["Mancer"] }`), Ollama Advanced Options (`num_gpu: 999`, `num_ctx: 8192`, `num_batch: 512`, `num_thread: 10`, `temperature: 0.2`, `top_p: 0.15`, `top_k: 30`, `repeat_penalty: 1.1`, `seed: 42`, `stop: ["```end", "delimiter", "You"]`), Ollama 5-minute timeout resilience loop with live notice emissions, HTTP 502/503/504 and gateway timeout intercept, emulator alias fallback (`10.0.2.2`), default `ollama_options` pre-population in `AddPlatformScreen`, and incomplete wrap-up completion, Room Schema 18 (`MIGRATION_17_18`) adding `ollama_options` to `platform_v2`, dedicated full-screen `McpToolsSelectionScreen` and `AiPlatformsScreen`, reactive flow synchronization between platform cards and platform settings detail toggles, Settings menu feature organization with "AI Platforms" situated cleanly above "Local Models", collapsible Tool Connections with direct inline settings and status badges, bounded layout isolation eliminating thinking/tool-call overlaps, details indicator positioned above user message bubbles, multi-key 3-second round-robin auto-retry on high demand spikes, fancy interactive sorting chips in platform selection dialogs, CI `build-apk.yml` enabled on branch `Ollama`, and comprehensive test suite updates for reactive flows, OpenRouter options, Ollama timeout resilience, and Room Schema 17/18.

## 1. Repository Overview

GPT Mobile AI (Improved) is a Kotlin Android application for chatting with cloud, self-hosted, and on-device large language models. It supports OpenAI-compatible services, Anthropic, Google Gemini, Groq, OpenRouter, Ollama, and local LiteRT models. It also includes an autonomous agent runtime, Model Context Protocol (MCP) tools and marketplace, resilient streaming, background execution, chat search/history, multi-key API credential rotation with dynamic UI management and seamless streaming fallback, and encrypted credential storage.

### Architecture and stack

- **Architecture:** Clean MVVM with repository, domain-boundary abstractions, and data-source layers.
- **UI:** Jetpack Compose, Material 3, lifecycle-aware state collection (`collectAsStateWithLifecycle`), and Compose Navigation.
- **Motion & Transitions:** Theme motion primitives (`defaultSpatialSpec`, `fastSpatialSpec`, `fastEffectsSpec`) in `presentation.theme.Motion.kt` backing collapsible details animations and responsive indicator state transitions.
- **Language/runtime:** Kotlin 2.x, Java 21 bytecode, coroutines, Flow/StateFlow, and kotlinx.serialization.
- **Dependency injection:** Hilt/Dagger with KSP.
- **Networking:** Ktor clients (OkHttp and CIO engines), Server-Sent Events (SSE) streaming support, resilient retry/exponential backoff, and `ApiCredentialRotator` for round-robin multi-key failover across `ProviderAdapters` (OpenAI, Anthropic, Gemini, Groq, OpenRouter, and OpenAI-compatible services). Includes 3-second delay and rotation on high-demand spikes ("This model is currently experiencing high demand").
- **Persistence:** Room (`ChatDatabaseV2`, Schema version 18) with full FTS search and DataStore preferences (`SettingDataSource`). Migration 16->17 adds `disable_remote_tools` and `disable_local_tools`. Migration 17->18 adds `ollama_options` text column to `platform_v2`.
- **Security:** Android Keystore-backed AES-256-GCM credential encryption (`SecretVault`); passphrase-protected user exports using PBKDF2-HMAC-SHA256 and AES-256-GCM (`AppBackupCrypto`).
- **Local inference:** LiteRT-LM (`LocalRuntimeImpl`, conversation fingerprinting, dynamic accelerator selection for NPU/GPU/CPU, warm engine retention, speculative decoding); Ollama supported for self-hosted network inference with configurable advanced parameters, emulator fallback (`10.0.2.2`), and hardwired 5-minute timeout resilience loop with HTTP 502/503/504 retry intercept.
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

- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/provider/ProviderAdapters.kt`:
  - Contains `OpenAIResponsesAdapter`, `OpenAICompatibleAdapter`, `AnthropicMessagesAdapter`, and `GeminiAdapter`.
  - `OpenAICompatibleAdapter` handles `ClientType.OLLAMA`, `ClientType.OPENROUTER`, and `ClientType.GROQ`.
  - Ollama features hardwired 5-minute timeout resilience loop, emulator loopback alias fallback (`10.0.2.2`), extended per-chunk timeout (180s minimum for prompt evaluation/warmup), and comprehensive exception/HTTP code matching (`HTTP 408`, `502`, `503`, `504`, `ConnectException`, `SocketTimeoutException`, `HttpRequestTimeoutException`, etc.).
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/AddPlatformScreen.kt`:
  - Pre-populates default `ollama_options` JSON payload (`OllamaOptions.createDefault()`) when creating a new Ollama platform.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/SettingRepositoryImpl.kt`:
  - Repository implementation for user preferences and settings using DataStore. Pre-populates default Ollama options during migrations, exports and imports `ollamaOptions`.
- `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/ollama/OllamaTimeoutResilienceTest.kt`:
  - Unit tests verifying Ollama timeout resilience and HTTP 502 Bad Gateway recovery on retry. Uses strongly-typed `ChatMessage(role = Role.USER, content = listOf(TextContent(...)))`.
- `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/ollama/OllamaOptionsTest.kt` & `OpenRouterOptionsTest.kt`:
  - Unit tests verifying serialization of Ollama options and OpenRouter options with strongly typed `ChatMessage(role = Role.USER, content = listOf(TextContent("Hello")))`.
