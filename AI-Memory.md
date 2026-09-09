# AI Memory

Persistent repository context for AI coding agents. Keep this file synchronized whenever repository files are added, modified, renamed, or deleted.

> Index status: initialized from the repository at `main`. The directory-level architecture and core configuration are recorded below; expand individual feature and test entries as they are inspected.

## 1. Repository Overview

GPT Mobile AI (Improved) is a Kotlin Android application for chatting with cloud, self-hosted, and on-device large language models. It supports OpenAI-compatible services, Anthropic, Google, Groq, OpenRouter, Ollama, and local LiteRT models. It also includes an autonomous agent runtime, Model Context Protocol (MCP) tools and marketplace, resilient streaming, background execution, chat search/history, and encrypted credential storage.

### Architecture and stack

- **Architecture:** MVVM with repository and data-source layers.
- **UI:** Jetpack Compose and Material 3, lifecycle-aware state collection, Compose Navigation.
- **Language/runtime:** Kotlin 2.x, Java 21 bytecode, coroutines, Flow/StateFlow, kotlinx.serialization.
- **Dependency injection:** Hilt/Dagger with KSP.
- **Networking:** Ktor clients (CIO and OkHttp), SSE/streaming support.
- **Persistence:** Room (`ChatDatabaseV2`) and DataStore preferences.
- **Security:** Android Keystore-backed AES-GCM credential encryption.
- **Local inference:** LiteRT-LM; Ollama is supported for self-hosted inference.
- **Background work:** Android foreground service, WorkManager, and partial wake locks for active agent runs.
- **Android targets:** application ID `dev.melo.gptmobile.improved`, min SDK 31, compile/target SDK 36, arm64-v8a and x86_64 ABIs.
- **Build/release:** Gradle Kotlin DSL, R8/resource shrinking for release, ABI splits plus universal APK, Room schema export.
- **Testing/style:** JUnit, kotlinx-coroutines-test, AndroidX instrumented/Compose tests, Room testing, and ktlint 1.3.1 using Android Studio style.

### Source layout

- Kotlin production package: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/`
- Java production package: `app/src/main/java/dev/chungjungsoo/gptmobile/`
- JVM tests: `app/src/test/kotlin/` and `app/src/test/java/`
- Instrumented tests: `app/src/androidTest/kotlin/`

## 2. Repository Index

### Root

- `.editorconfig` — Editor and ktlint-compatible formatting rules.
- `.github/` — GitHub Actions and repository automation.
- `.gitignore` — Files excluded from version control; never scan ignored files for secrets.
- `AGENTS.md` — Authoritative agent-facing build, style, architecture, and testing guidance. Depends on the current Gradle and source layout.
- `AI-Memory.md` — This persistent architecture/index file; update with every repository change.
- `CHANGELOG.md` — User-facing historical change log.
- `CLAUDE.md` — Additional AI-agent instructions/context.
- `CODE_OF_CONDUCT.md` — Community conduct policy.
- `CONTEXT.md` — Project context and implementation notes.
- `CONTRIBUTING.md` — Contributor workflow and requirements.
- `LICENSE` — Apache License 2.0 terms.
- `PROGRESS.md` — Project progress and implementation tracking.
- `README.md` — Product overview, supported providers, architecture summary, prerequisites, and build commands.
- `RELEASE_NOTES.md` — Current release-focused notes.
- `build.gradle.kts` — Root Gradle plugin/build configuration.
- `settings.gradle.kts` — Gradle project and repository configuration.
- `gradle.properties` — Shared Gradle/Android build properties.
- `gradle/` — Gradle wrapper and version catalog configuration.
- `gradlew`, `gradlew.bat` — Unix and Windows Gradle wrappers.
- `model_catalog.json` — Bundled model catalog consumed by model discovery/catalog features.
- `docs/` — ADRs, agent guidance, release validation, and CI diagnostics.
- `images/` — Repository documentation/media assets.
- `metadata/` — Application/store metadata.
- `scripts/` — Build, maintenance, validation, and release helper scripts.

### `.github/`

- `.github/workflows/` — CI workflows for builds, checks, formatting, and release automation. Preserve constrained-memory build settings and existing release safety checks when editing workflows.

### `app/`

- `app/build.gradle.kts` — Android application configuration and dependencies. Configures Compose, Hilt/KSP, Room schemas, SDK levels, ABI splits, R8, packaging, OAuth placeholders, LiteRT-LM, Ktor, WorkManager, and test dependencies.
- `app/proguard-rules.pro` — Application-specific R8/ProGuard retention and optimization rules.
- `app/schemas/` — Exported Room schemas used to validate database evolution and migrations.
- `app/src/main/AndroidManifest.xml` — Declares the application, activities, voice-related services, quick-settings tile, agent foreground service, permissions, and service types.
- `app/src/main/res/` — Android resources: strings, themes, icons, XML configuration, and other packaged resources.

### Production Kotlin package

#### `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/`

Data and integration layer. Feature groups include:

- `agents/` — Autonomous agent orchestration, tool-call loops, limits, logs, and result synthesis.
- `backup/` — Chat/settings backup and restore behavior.
- `catalog/` — Model catalog loading and model metadata.
- `context/` — Context-window budgeting/compaction while retaining required instructions.
- `database/` — Room V2 entities, DAOs, converters, database construction, search, and migrations.
- `datastore/` — Preference-backed settings and application state.
- `dto/` — Serialized request/response and transport models.
- `huggingface/` — Hugging Face authentication/catalog/download integration.
- `local/` — Local model metadata, lifecycle, downloading, and LiteRT inference runtime.
- `mcp/` — MCP client/runtime, tool definitions, marketplace data, execution, and fallbacks.
- `network/` — Shared network clients, streaming/SSE handling, retries, and provider transport.
- `openrouter/` — OpenRouter-specific catalog and API behavior.
- `parser/` — Provider and streaming payload parsing.
- `repository/` — Repository interfaces and `*Impl` implementations coordinating persistence and remote/local sources.
- `security/` — Keystore-backed encryption and credential handling.
- `worker/` — WorkManager jobs for durable/background tasks.

Dependencies primarily flow from repositories to database/DataStore/network/local/MCP data sources. Presentation code should consume repositories rather than directly constructing transport or persistence objects.

#### `app/src/main/kotlin/dev/chungjungsoo/gptmobile/di/`

Hilt modules that provide singleton network clients, databases/DAOs, repositories, settings/data sources, local runtimes, agent/MCP components, and workers. Keep construction centralized here and scope expensive shared dependencies appropriately.

#### `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/`

Application/presentation layer, including app initialization and foreground-state tracking.

- `chat/` and shared chat presentation code — Conversation state and reusable chat behavior.
- `components/` or common component packages — Shared Compose controls and dialogs.
- `icons/` — Custom Compose icon definitions.
- `service/` — Agent-run foreground service and notification/wake-lock integration.
- `theme/` — Application Compose theme, colors, and typography.
- `ui/` — Feature screens and ViewModels for chat, home, local-model management, main navigation, MCP marketplace, migration, settings, setup, start screen, and thinking/reasoning UI.

Presentation dependencies should point to repository abstractions and immutable UI state exposed through StateFlow.

#### `app/src/main/kotlin/dev/chungjungsoo/gptmobile/ui/`

Additional UI-level package content. Inspect alongside `presentation/` before moving or adding screens to avoid duplicating established components or navigation responsibilities.

#### `app/src/main/kotlin/dev/chungjungsoo/gptmobile/util/`

Cross-cutting helpers for API state flows, attachments/files, networking/platform behavior, scrolling, strings, and themes. Utilities should remain narrowly scoped and must not become hidden service locators.

### Production Java package

- `app/src/main/java/dev/chungjungsoo/gptmobile/` — Java production sources and interoperability code. Inspect callers and Kotlin interfaces before changing signatures.

### Tests

- `app/src/test/kotlin/` — Kotlin JVM unit tests for repositories, parsers, context management, agents/MCP, utilities, and ViewModel-independent behavior.
- `app/src/test/java/` — Java/JVM unit tests and interoperability tests.
- `app/src/androidTest/kotlin/` — Device/emulator tests, including Android integration, Room migrations/schema validation, and Compose UI behavior.

### Documentation and support directories

- `docs/` — Architectural decisions, operational notes, release validation procedures, CI diagnostics, and AI-agent guidance. Update relevant documentation when behavior or release procedures change.
- `scripts/` — Automation entry points. Read workflow callers and expected environment variables before modifying.
- `metadata/` — Store/distribution metadata; keep release version text synchronized when preparing releases.
- `images/` — Documentation assets referenced by Markdown or store metadata.

## 3. Engineering Guidelines (Do's)

### Before changing code

- Read the target file, its callers, interfaces, DI binding, persistence/network models, and relevant tests before editing.
- Follow existing package boundaries: Compose/ViewModel → repository abstraction → data sources/integrations.
- Keep diffs minimal and atomic; preserve local naming, formatting, and patterns.
- Update this file whenever files or architectural responsibilities change.

### Kotlin and formatting

- Use 4 spaces, LF endings, and a final newline; do not use tabs.
- Follow ktlint 1.3.1 with `android_studio` style; run formatting/checks before committing.
- Do not add trailing commas. There is no enforced maximum line length.
- Keep one primary class per file and match the file name to it.
- Use PascalCase for classes, Composables, and ViewModels; camelCase for functions/variables; `SCREAMING_SNAKE_CASE` for constants.
- Suffix concrete repository implementations with `Impl`, ViewModels with `ViewModel`, and versioned V2 entities/models with `V2`.
- Prefix private mutable StateFlow backing fields with `_`; expose read-only flows using `asStateFlow()`.
- Keep imports alphabetized in the established Android, AndroidX, third-party, Hilt, project, Java, javax, Kotlin/kotlinx groups.

### State, coroutines, and Compose

- Model UI state with immutable data/sealed classes and StateFlow.
- Collect flows in Compose with lifecycle-aware APIs such as `collectAsStateWithLifecycle()`.
- Use `viewModelScope` for ViewModel work and structured coroutine scopes elsewhere.
- Handle Flow failures with explicit error states and emit loading/completion states consistently.
- Use `LaunchedEffect` and `rememberCoroutineScope` only for appropriate lifecycle-aware side effects.
- Preserve smooth streaming behavior by batching/throttling UI updates rather than recomposing for every token.
- Reuse shared Compose components and the established Scaffold/navigation patterns.

### Validation and error handling

- Validate indices and user/provider input with early returns.
- Use null-safe access (`?.`, `?:`, `getOrNull`, `takeIf`) and `checkNotNull` only for truly required state.
- Preserve provider-specific errors where useful while avoiding credential or sensitive payload leakage.
- Keep SSE parsing chunk-safe and network retry behavior bounded with backoff.
- Enforce configurable agent/MCP tool-call ceilings and surface execution failures cleanly.

### Persistence and security

- Add Room migrations and update exported schemas whenever the schema changes; test migration paths.
- Preserve V2 database/search semantics and transactional boundaries.
- Store credentials only through the established Keystore/AES-GCM path.
- Treat backups, logs, notifications, and error messages as possible data-exfiltration surfaces.

### Build and tests

Run the smallest relevant checks, then broader checks when feasible:

```bash
./gradlew testDebugUnitTest
./gradlew test
./gradlew assembleDebug
./gradlew connectedAndroidTest   # requires a device/emulator
```

For a focused JVM test:

```bash
./gradlew test --tests "fully.qualified.TestClass"
```

- Add or update tests for behavior changes, especially parsers, context compaction, migrations, repositories, MCP limits, streaming, and retries.
- Keep Room schemas available to Android tests.
- Verify release-sensitive changes against ABI splits, R8 rules, resource shrinking, and Java 21 compatibility.

## 4. Anti-Patterns & Traps (Don'ts)

- Do not commit API keys, OAuth client secrets, signing keys, passwords, tokens, private endpoints, or real credentials. Keep the Hugging Face OAuth values as configured placeholders unless a secure documented build-injection mechanism is introduced.
- Do not bypass Android Keystore encryption or persist credentials in plain Room tables, DataStore values, logs, backups, or UI state snapshots.
- Do not log prompts, responses, authorization headers, credentials, or tool payloads in release paths.
- Do not perform network, database, file, or model-inference work on the main thread.
- Do not collect flows in Compose without lifecycle awareness or expose mutable flows publicly.
- Do not instantiate repositories, databases, Ktor clients, MCP clients, or expensive local runtimes directly in screens/ViewModels; use Hilt and existing abstractions.
- Do not re-render the UI for every streamed token; preserve adaptive buffered updates and scroll performance.
- Do not assume SSE/network chunks align with JSON objects or character boundaries.
- Do not remove bounded retry/backoff, context-window protection, agent tool-call limits, foreground-service compliance, or wake-lock release paths.
- Do not change Room entities without a migration, schema export, and migration tests; never use destructive migration as a shortcut for user data.
- Do not block cancellation or swallow `CancellationException` in coroutine error handling.
- Do not hardcode model/provider capabilities when catalog or provider metadata already supplies them.
- Do not broaden Android permissions, exported components, foreground-service types, or URI/file access without a concrete requirement and security review.
- Do not add unsupported 32-bit ABIs or accidentally package duplicate/uncompressed native artifacts without checking APK impact.
- Do not weaken R8/privacy rules merely to silence a release-only failure; add narrow keep rules based on verified reflection/JNI requirements.
- Do not edit generated Room schema files manually.
- Do not use wildcard imports except where the repository's ktlint configuration explicitly permits them.
- Do not place comments inside or after annotation value parameters; ktlint requires such comments on a separate preceding line.
- Do not duplicate feature screens/components across `presentation/` and `ui/`; inspect both package trees first.
