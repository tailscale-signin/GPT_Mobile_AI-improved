# AI Memory

Persistent repository context for AI coding agents. Keep this file synchronized whenever repository files are added, modified, renamed, or deleted.

> Index status: incremental and in progress on `main`. Architecture, core configuration, UI feature paths, encrypted backup/security code, selected catalog/context/parser files, and major test roots are indexed. Expand file-level entries as additional implementations are inspected.

## 1. Repository Overview

GPT Mobile AI (Improved) is a Kotlin Android application for chatting with cloud, self-hosted, and on-device large language models. It supports OpenAI-compatible services, Anthropic, Google, Groq, OpenRouter, Ollama, and local LiteRT models. It also includes an autonomous agent runtime, Model Context Protocol (MCP) tools and marketplace, resilient streaming, background execution, chat search/history, and encrypted credential storage.

### Architecture and stack

- **Architecture:** MVVM with repository and data-source layers.
- **UI:** Jetpack Compose, Material 3, lifecycle-aware state collection, and Compose Navigation.
- **Language/runtime:** Kotlin 2.x, Java 21 bytecode, coroutines, Flow/StateFlow, and kotlinx.serialization.
- **Dependency injection:** Hilt/Dagger with KSP.
- **Networking:** Ktor clients (CIO and OkHttp), SSE/streaming support.
- **Persistence:** Room (`ChatDatabaseV2`) and DataStore preferences.
- **Security:** Android Keystore-backed AES-GCM credential encryption; passphrase-protected exports use PBKDF2-HMAC-SHA256 and AES-256-GCM.
- **Local inference:** LiteRT-LM; Ollama is supported for self-hosted inference.
- **Background work:** Android foreground service, WorkManager, and partial wake locks for active agent runs.
- **Android targets:** application ID `dev.melo.gptmobile.improved`, min SDK 31, compile/target SDK 36, arm64-v8a and x86_64 ABIs.
- **Build/release:** Gradle Kotlin DSL, R8/resource shrinking, ABI splits plus universal APK, and Room schema export.
- **Testing/style:** JUnit, kotlinx-coroutines-test, AndroidX instrumented/Compose tests, Room testing, and ktlint 1.3.1 using Android Studio style.

### Source layout

- Main Kotlin tree: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/`
- Additional Kotlin source in the Java source set: `app/src/main/java/dev/chungjungsoo/gptmobile/`
- JVM tests: `app/src/test/kotlin/` and `app/src/test/java/`
- Instrumented tests: `app/src/androidTest/kotlin/`

## 2. Repository Index

### Root

- `.editorconfig` — Editor and ktlint-compatible formatting rules.
- `.github/workflows/` — CI build, check, formatting, and release automation.
- `.gitignore` — Version-control exclusions; do not scan ignored files for secrets.
- `AGENTS.md` — Authoritative agent-facing build, style, architecture, and test guidance.
- `AI-Memory.md` — This persistent architecture/index file.
- `CHANGELOG.md`, `RELEASE_NOTES.md`, `PROGRESS.md` — Historical changes, release details, and implementation progress.
- `CLAUDE.md`, `CONTEXT.md` — Additional AI/project context.
- `README.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `LICENSE` — Product and contributor documentation.
- `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/`, `gradlew*` — Gradle configuration and wrappers.
- `model_catalog.json` — Bundled model catalog consumed by model-discovery/catalog features.
- `docs/` — ADRs, operational guidance, release validation, and CI diagnostics.
- `images/`, `metadata/` — Documentation/store assets and distribution metadata.
- `scripts/` — Build, maintenance, validation, and release helpers.

### `app/`

- `app/build.gradle.kts` — Android app and dependency configuration: Compose, Hilt/KSP, Room schemas, SDK levels, ABI splits, R8, packaging, OAuth placeholders, LiteRT-LM, Ktor, WorkManager, and tests.
- `app/proguard-rules.pro` — Application-specific R8/ProGuard rules.
- `app/schemas/` — Exported Room schemas used to validate database evolution.
- `app/src/main/AndroidManifest.xml` — Application, activities, voice services, quick-settings tile, agent foreground service, permissions, and service types.
- `app/src/main/res/` — Strings, themes, icons, XML configuration, and packaged Android resources.

### Main Kotlin package

#### `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/`

Data and integration layer:

- `agents/` — Autonomous agent orchestration, tool-call loops, limits, logs, and result synthesis.
- `backup/` — Backup/restore data behavior outside the encrypted manager in the Java source set.
- `catalog/` — Model and MCP catalog metadata and parsing.
  - `McpPresetCatalog.kt` — Defines MCP transport/category/pricing enums and preset models; exposes built-in server presets, compatibility aliases, category filtering, ID/alias lookup, and search.
  - `ModelCatalog.kt` — Serializable model-catalog schema, including capabilities, default generation configuration, and SoC-specific model variants.
  - `ModelCatalogParser.kt` — Parses lenient JSON while ignoring unknown fields, validates schema compatibility and minimum app versions, formats model download sizes, and compares dotted app versions.
- `context/` — Context-window budgeting and compaction.
  - `ContextBuilder.kt` — Builds provider-aware history: selects provider-specific assistant responses, strips error notes, excludes failed historical turns, applies recent-turn and character budgets, and removes attachments from older turns.
  - `ConversationTurn.kt` — Models paired user/assistant messages and identifies the current turn.
  - `ProviderContextPolicy.kt` — Defines provider-specific history, attachment, and character limits.
- `database/` — Room V2 entities, DAOs, converters, database construction, search, and migrations.
- `datastore/` — Preference-backed settings and app state.
- `dto/` — Serialized transport models.
- `huggingface/` — Hugging Face authentication, catalog, and downloads.
- `local/` — Local model metadata, lifecycle, downloading, and LiteRT runtime.
- `mcp/` — MCP client/runtime, tools, marketplace, execution, and fallbacks.
- `network/` — Shared clients, streaming/SSE, retries, and provider transport.
- `openrouter/` — OpenRouter catalog/API behavior.
- `parser/` — Provider and streaming payload parsing.
  - `ThinkingParser.kt` — Extracts case-insensitive `<think>...</think>` blocks and returns reasoning plus cleaned response content. A second parser exists in `ui/thinking/ThinkingParser.kt`; inspect call sites and semantics before consolidating.
- `repository/` — Repository interfaces and `*Impl` implementations coordinating data sources.
- `security/` — Keystore-backed encryption and credential handling.
  - `SecretVault.kt` — Defines `SecretVault`, `SecretVaultException`, and `AndroidSecretVault`. Stores bounded byte-array credentials as versioned AES-GCM records in `noBackupFilesDir`, using an Android Keystore key, randomized IVs, record-reference AAD, strict reference/record validation, `AtomicFile`, serialized access via `Mutex`, and `Dispatchers.IO`. Missing or permanently invalidated keys cause irrecoverable records to be deleted and read as absent.
- `worker/` — WorkManager jobs.

Dependencies should flow from repositories to database/DataStore/network/local/MCP sources. Presentation code consumes repositories instead of constructing transports or persistence objects.

#### `app/src/main/kotlin/dev/chungjungsoo/gptmobile/di/`

Hilt modules providing singleton clients, databases/DAOs, repositories, settings sources, local runtimes, agent/MCP components, and workers. Centralize construction here and scope expensive dependencies appropriately.

#### `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/`

Application/presentation layer, including initialization, foreground-state tracking, chat presentation, shared components/icons, service notification/wake-lock behavior, themes, feature screens, and ViewModels.

Key inspected UI feature directories:

- `presentation/ui/chat/` — Chat screen/ViewModel, Markdown and MathJax rendering, attachments, tool traces, thinking blocks, and dialogs.
- `presentation/ui/home/` — Home/history UI.
- `presentation/ui/localmodel/` — Local-model download UI.
- `presentation/ui/main/` — Main activity/navigation/ViewModel responsibilities.
- `presentation/ui/mcpmarketplace/` — MCP marketplace UI.
- `presentation/ui/migrate/MigrateScreen.kt` — Migration flow UI.
- `presentation/ui/migrate/MigrateViewModel.kt` — Migration state and actions.
- `presentation/ui/setting/` — Settings and provider/tool administration:
  - `SettingScreen.kt`, `SettingViewModel.kt`, `SettingViewModelV2.kt` — Main settings UI/state.
  - `AddPlatformScreen.kt`, `AddPlatformViewModel.kt` — Add-provider flow.
  - `PlatformSettingScreen.kt`, `PlatformSettingViewModel.kt`, `PlatformSettingViewModelExtensions.kt`, `PlatformSettingDialogs.kt` — Provider configuration and supporting dialogs/actions.
  - `LocalModelsScreen.kt`, `LocalModelsViewModel.kt`, `LocalModelCatalogUi.kt` — Installed/local model management and catalog UI.
  - `ToolConnectionsScreen.kt`, `ToolConnectionsViewModel.kt`, `ToolConnectionSetupFlow.kt`, `McpServerPickerDialog.kt` — MCP/tool connection setup and management.
  - `MaxToolCallsSetting.kt`, `PlatformMaxToolCallsSettingHost.kt` — Global/platform agent tool-call limits.
  - `OpenRouterModelPickerDialog.kt` — OpenRouter model selection.
  - `AboutScreen.kt`, `LicenseScreen.kt` — App information and licenses.
- `presentation/ui/setup/` — Initial setup flow:
  - `SetupPlatformListScreen.kt`, `SetupPlatformTypeScreen.kt`, `SetupPlatformWizardScreen.kt` — Provider selection and configuration wizard.
  - `LocalModelPicker.kt` — Setup-time local model selection.
  - `SetupAppBar.kt`, `SetupCompleteScreen.kt` — Shared setup chrome and completion UI.
  - `SetupViewModelV2.kt` — Setup state and orchestration.
- `presentation/ui/startscreen/StartScreen.kt` — Start/entry screen.
- `presentation/ui/thinking/ThinkingAccordion.kt` — Presentation-layer reasoning accordion.

Presentation dependencies should point to repository abstractions and expose immutable UI state through StateFlow.

#### `app/src/main/kotlin/dev/chungjungsoo/gptmobile/ui/`

Additional UI package. It contains `ui/thinking/ThinkingAccordion.kt` and `ui/thinking/ThinkingParser.kt`, while another accordion exists under `presentation/ui/thinking/` and a parser exists under `data/parser/`. Inspect all implementations and call sites before changing reasoning behavior; avoid introducing another implementation and preserve package-specific semantics unless deliberately consolidating them with tests.

#### `app/src/main/kotlin/dev/chungjungsoo/gptmobile/util/`

Cross-cutting helpers for API state, attachments/files, networking/platform behavior, scrolling, strings, and themes. Keep utilities narrow; do not turn this package into a service locator.

### Kotlin in `app/src/main/java/`

- `app/src/main/java/dev/chungjungsoo/gptmobile/data/backup/EncryptedBackupManager.kt` — Kotlin despite its source-root location. Defines `EncryptedBackupManager`, `UserConfigurationBackup`, and `ThemeConfiguration`. Creates/restores passphrase-encrypted configuration and SQLite backups using PBKDF2-HMAC-SHA256 plus AES-256-GCM. It validates custom backup magic and SQLite headers; database restore uses a temporary replacement and removes stale WAL/SHM sidecars. Preserve authenticated encryption, passphrase handling, format checks, and atomic restore behavior.

### Tests

- `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/` — JVM data tests. Current groups include `agent/`, `catalog/`, `context/`, `database/`, `dto/`, `huggingface/`, `localmodel/`, `localruntime/`, `mcp/`, `model/`, `network/`, `parser/`, and `repository/`, plus `ModelConstantsTest.kt`.
- `app/src/test/kotlin/dev/chungjungsoo/gptmobile/presentation/`, `ui/`, `util/` — JVM tests for UI-independent presentation state/logic, UI helpers, and utilities.
- `app/src/test/java/dev/chungjungsoo/gptmobile/data/backup/EncryptedBackupManagerTest.kt` — JVM tests for configuration and SQLite encrypted backup/restore round trips, wrong-passphrase failure, and non-SQLite rejection. Uses a reduced PBKDF2 iteration count only for test speed.
- `app/src/androidTest/kotlin/dev/chungjungsoo/gptmobile/ExampleInstrumentedTest.kt` — Basic Android instrumented test.
- `app/src/androidTest/kotlin/dev/chungjungsoo/gptmobile/data/backup/` — Device backup integration tests.
- `app/src/androidTest/kotlin/dev/chungjungsoo/gptmobile/data/database/` — Room/database and migration integration tests.
- `app/src/androidTest/kotlin/dev/chungjungsoo/gptmobile/data/security/SecretVaultInstrumentedTest.kt` — Verifies vault round-trip, overwrite/delete, `noBackupFilesDir` placement, unsafe-reference and oversized-secret rejection, malformed and reference-moved record rejection, and deletion of records whose Keystore key is missing.
- `app/src/androidTest/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/` — Instrumented/Compose presentation tests.

## 3. Engineering Guidelines (Do's)

### Before changing code

- Read the target, callers, interfaces, DI binding, persistence/network models, and relevant tests first.
- Follow package boundaries: Compose/ViewModel → repository abstraction → data sources/integrations.
- Keep diffs minimal and atomic; preserve local naming and formatting.
- Update this file whenever files or responsibilities change.

### Kotlin and formatting

- Use 4 spaces, LF endings, a final newline, and no tabs.
- Follow ktlint 1.3.1 with `android_studio` style; do not add trailing commas.
- Keep one primary class per file and match its filename.
- Use PascalCase for classes/Composables/ViewModels, camelCase for functions/variables, and `SCREAMING_SNAKE_CASE` for constants.
- Suffix implementations with `Impl`, ViewModels with `ViewModel`, and versioned V2 models with `V2`.
- Prefix mutable StateFlow backing fields with `_` and expose them via `asStateFlow()`.
- Preserve the repository's grouped/alphabetized import style.

### State, coroutines, and Compose

- Model UI state with immutable data/sealed classes and StateFlow.
- Collect in Compose with lifecycle-aware APIs such as `collectAsStateWithLifecycle()`.
- Use `viewModelScope` and structured concurrency; preserve cancellation.
- Emit explicit loading/error/completion states.
- Use lifecycle-aware side effects and reuse shared Compose/Scaffold/navigation patterns.
- Batch/throttle streaming UI updates instead of recomposing for every token.

### Validation, persistence, and security

- Validate indices and provider/user input with early returns and null-safe access.
- Preserve provider-specific errors without leaking credentials or sensitive payloads.
- Keep SSE parsing chunk-safe and retries bounded with backoff.
- Enforce configurable agent/MCP tool-call ceilings.
- Add Room migrations, exported schemas, and migration tests for schema changes.
- Store credentials only through the established `SecretVault`/Keystore AES-GCM path.
- Preserve vault record versioning, secret-reference AAD binding, strict size/path validation, atomic writes, no-backup storage, and zeroing of temporary sensitive arrays.
- Treat backups, logs, notifications, and errors as possible exfiltration surfaces.
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
