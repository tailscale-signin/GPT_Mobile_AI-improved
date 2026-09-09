# AI Memory

Persistent repository context for AI coding agents. Keep this file synchronized whenever repository files are added, modified, renamed, or deleted.

> Index status: comprehensive and actively maintained on `main`. Core architecture, Android targets, UI screens, encrypted backup/security, agent runtime/tools, Room V2 database & migrations, DataStore, local runtime & acceleration, network transports & SSE parsing, model catalogs, OpenRouter advanced routing/reasoning, WorkManager workers, DI modules, DTOs, and test roots are fully indexed. All open issues and PRs are reconciled and resolved. Latest Official Release: `v0.8.9` (branch `v0.8.9`, release branch `release-v0.8.9`, release promotion workflow `.github/workflows/promote-latest-release.yml` configured to publish official latest releases with `RELEASE_NOTES.md`).

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
- **Build/release:** Gradle Kotlin DSL, R8/resource shrinking, ABI splits plus universal APK, and Room schema export (`app/schemas/`). Latest Official Release: `v0.8.9` (branches `v0.8.9` and `release-v0.8.9`).
- **Testing/style:** JUnit 4/5, kotlinx-coroutines-test, AndroidX instrumented/Compose tests, Room testing, and ktlint 1.3.1 using Android Studio style.

### Source layout

- Main Kotlin tree: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/`
- Additional Kotlin source in the Java source set: `app/src/main/java/dev/chungjungsoo/gptmobile/`
- JVM tests: `app/src/test/kotlin/` and `app/src/test/java/`
- Instrumented tests: `app/src/androidTest/kotlin/`

## 2. Repository Index

### Root

- `.editorconfig` — Editor and ktlint-compatible formatting rules.
- `.github/workflows/` — CI build, check, formatting, and release automation workflows (includes PR validation with automated failure diagnostic extraction for unit test errors and Android Lint violations, as well as `promote-latest-release.yml`).
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

- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatScreen.kt` — Core conversation screen. Renders chat messages, tool execution progress cards, multi-turn tool calling logs, auto-scroll to favorited message on deep navigation, and platform tab switching.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatBubble.kt` — User and assistant speech bubbles. Includes `OpponentResponseContainer` with animated cyan highlight bubble (`Color.Cyan.copy(alpha = 0.2f)`), 32.dp rounded corners, and padded borders around the entire assistant response block.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeScreen.kt` — Home dashboard, conversation list, favorites modal dialog (`FavoriteDetailDialog`) with custom category groups ("All", user groups, "+ Add Group"), category assignments, LaTeX/Markdown/code rendering, un-favorite confirmation, and direct navigation to favorited message in chat.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeViewModel.kt` — ViewModel driving conversation listings, favorite grouping/filtering, and asynchronous `getChatRoom(chatId, onResult)` resolution for reliable navigation.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/SettingScreen.kt` & `SettingViewModel.kt` — Settings screen managing dynamic multi-key API credential configurations, encrypted storage, and model choices.

### Agent & Tooling

- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/domain/agent/tools/ReadFileSliceTool.kt` — Built-in line slicing tool for viewing bounded line ranges of local/remote files with 1-based indexing and line-number metadata.
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/domain/agent/AgentRuntime.kt` — Autonomous agent orchestrator running multi-step reasoning, tool dispatching, MCP execution, and reflection loops.

### Networking & Credentials

- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/ApiCredentialRotator.kt` — Multi-key round-robin rotation with dynamic failover on rate limits (429), payment/quota issues (402), and auth failures (401).

## 3. Engineering Guidelines ("Do's")

- Always adhere to Kotlin 2.x and Material 3 design patterns.
- Prefer immutable data structures and explicit types where readability benefits.
- Preserve backward-compatibility for Room schemas; always update database version and migration definitions when modifying entities.
- Ensure all new tools are registered in baseline tools and exposed via appropriate interfaces.

## 4. Anti-Patterns & Traps ("Don'ts")

- Never hardcode API keys, secrets, or access tokens in source code or commits.
- Do not bypass `SecretVault` or `AppBackupCrypto` for storing or exporting sensitive user data.
- Avoid uncaught exceptions or blocking operations on the Compose UI thread.
