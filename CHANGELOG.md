# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.9.0] - 2026-09-10

### Added & Improved
- **LiteRT-LM Hardware Acceleration & Dynamic Inference Engine**:
  - Phase-split scheduling (`LocalInferencePhase.PREFILL` / `GENERATING`) with foreground notification updates displaying "Processing prompt…" and "Generating response…".
  - Cooperative thread yielding (`yield()`) during conversation creation and message evaluation to eliminate UI thread hitches.
  - DeviceHardwareGovernor: dynamic thermal and battery throttling adjusting context token clamps, stream intervals, and top-k sampling.
  - High-refresh 120Hz/144Hz token dispatching with 8ms frame budgets on capable hardware.
  - Idle memory auto-unloading (`unloadIfIdle`) in `LocalEngineHolder` to protect device memory after periods of inactivity.
  - Rolling Context Window Compactor (`RollingContextWindowCompactor`): Turn 0 anchor prefix preservation and rolling turn truncation preventing context ceiling exhaustion.
  - Real-time generation telemetry (`LocalInferenceMetrics`) exposing tok/s, TTFT, token counts, and thermal badges in the chat action row (`TelemetryBadge`).
  - Tier-aware context scaling up to 8,192 tokens on >=12GB/16GB devices, with SoC variant clamping for NPU targets.
- **OpenRouter Advanced Provider Routing & Reasoning**:
  - Configure provider ordering, fallback providers, sorting strategy (`price`, `throughput`, `latency`), data collection policies (`allow`, `deny`), and quantizations (`fp16`, `int8`, `int4`, `bf16`).
  - Added custom max reasoning tokens configuration.
  - Interactive UI with `OpenRouterAdvancedSettingsDialog` inside Platform Settings.
  - Added Room Schema 15 and `MIGRATION_14_15` (`open_router_routing` column on `platform_v2`).
- **Chat Presentation & Responsiveness**:
  - Instant bottom anchoring via `rememberChatListState` keyed on message counts with target message protection for favorites and deep links.
  - Collapsible details toggle button (`DetailsButton`) with accessible spring animations (`fastEffectsSpec`).
  - Continuous streaming heartbeat pulse (`●`) during response generation and tool execution.
- **Google Gemini Tool Calling Reliability**:
  - Recursive tool parameter sanitization (`geminiToolParameters`) stripping transport metadata (`x-mcp-header`, `x-mcp-param`) and unsupported schema keywords (`$schema`, `propertyNames`, `additionalProperties`).
  - Immediate detection of tool schema rejection errors (`throwIfToolDefinitionsRejected`) to prevent multi-API retry loops and timeouts.

## [0.8.9.1] - 2026-09-09

### Added & Improved
- **OpenRouter Advanced Provider Routing & Reasoning**:
  - Configure provider ordering, fallback providers, sorting strategy (`price`, `throughput`, `latency`), data collection policies (`allow`, `deny`), and quantizations (`fp16`, `int8`, `int4`, `bf16`).
  - Added custom max reasoning tokens configuration.
  - Interactive UI with `OpenRouterAdvancedSettingsDialog` inside Platform Settings.
  - Added Room Schema 15 and `MIGRATION_14_15` (`open_router_routing` column on `platform_v2`).
- **Chat Presentation & Responsiveness**:
  - Instant bottom anchoring via `rememberChatListState` keyed on message counts.
  - Collapsible details toggle button with accessible spring animations (`Motion.kt`).
  - Continuous streaming heartbeat pulse (`●`) during response generation and tool execution.

## [0.8.9] - 2026-09-09

### Added & Improved
- **Favorites Management & Deep Navigation**:
  - Rich Favorite Detail View with custom category groups ("All", user groups, "+ Add Group"), assignment dropdowns, Markdown/LaTeX/code rendering, and confirmation dialog for unfavoriting.
  - Reliable In-Chat Navigation: Tapping "View" in the favorite detail dialog seamlessly resolves chat rooms and navigates directly to the target favorited message.
  - Platform Tab Auto-Switching: Switching automatically to the favorited message's provider tab when navigating into multi-platform chat rooms.
  - Taller Highlight Container: `OpponentResponseContainer` with an animated cyan highlight surrounding the entire assistant response block (avatar, loading indicators, platform selection pills, and chat bubble).
  - Haptic feedback when favoriting messages.
- **Autonomous Agent Tooling & Line Slicing**:
  - Built-in `read_file_slice` tool for extracting bounded text line slices with 1-based indexing, range validation, and line count metadata.
  - MCP line slicing (`start_line`, `end_line`) for remote file-reading tools (e.g. GitHub `get_file_contents`) to prevent context window overflow and minimize token overhead.
  - Prebundled `droid-mcp-web` Online Search (`web_search`, `fetch_webpage`).
  - Restyled `ToolTraceBlock` with dark card backgrounds, brand icons, and collapsible tool outputs.
- **Multi-Key API Credential Rotation**:
  - `ApiCredentialRotator` with high-availability round-robin failover across multiple keys per provider.
  - Automatic fallback on HTTP 429, 402, 401, and quota exhaustion without interrupting streaming sessions.
  - Dynamic `+API` key management UI across Platform Settings, Setup Wizard, and MCP Tool Connections.
- **Architecture & Persistence**:
  - Room Database Schema v14 with full migrations supporting tool connections, timeline items, agent run persistence, agent tool bindings, and configurable platform tool-call limits (`max_tool_calls`).
  - Target Android 16 (API 36), Java 21 bytecode, and modern 64-bit ABIs (`arm64-v8a`, `x86_64`).
  - Local LiteRT-LM runtime integration with hardware acceleration selection (NPU, GPU, CPU) and background model downloading via WorkManager.

## [0.8.2] - 2026-09-05

### Added & Improved
- **Separate Application ID (`dev.chungjungsoo.gptmobile.improved`)**:
  - Allows direct, side-by-side installation alongside the original repository's app without signature conflicts or needing to uninstall.
  - Custom deep-linking scheme updated to `dev.chungjungsoo.gptmobile.improved://oauth/mcp/` for MCP OAuth callbacks.
- **Deterministic & Persistent Release Signing**:
  - Replaced ephemeral per-build keystores with a deterministic, consistent release signing pipeline.
  - All future releases and updates will install in-place over previous builds without prompting for uninstallation.
- **Uncapped Autonomous Agent Execution Limits**:
  - Uncapped execution loop: `maxRounds = Int.MAX_VALUE`, `maxToolCalls = Int.MAX_VALUE`.
  - Uncapped execution timeouts: `runTimeoutMillis = Long.MAX_VALUE`, `toolTimeoutMillis = Long.MAX_VALUE`.
  - Concurrency expanded to 32 parallel tool executions with uncapped buffer output (`Int.MAX_VALUE`).
- **Web Search & URL Tool Enhancements**:
  - `ReadUrlTool`: Body limit expanded to 100 MB, output limit to 50 MB, and redirect hops to 50.
  - `WebSearchTool`: Result ceiling expanded to 100 search results across Firecrawl, Perplexity, and Exa.
- **Model Context Protocol (MCP) Scalability**:
  - Uncapped discovery limits (`MAX_DISCOVERED_TOOLS` and `MAX_TOOL_PAGES` to `Int.MAX_VALUE`).
  - Expanded endpoint length limits to 32 KB and Authorization headers to 128 KB.

## [0.8.1] - 2026-09-04

### Added
- Configuration Backup & Restore in Settings:
  - Export and restore platform configurations, customized model parameters, URLs, prompts, and theme preferences via formatted JSON.
  - Integrated with `SecretVault` for secure credential persistence and re-encryption.
  - Interactive UI dialogs for backup export (with one-tap clipboard copy) and validated configuration restore.

## [0.8.0] - 2025-02-17

### Added
- Support for favorites in chat rooms (`is_favorite` flag and Room migration 11 to 12).
- Agent tools per provider profile (native tool calling with OpenAI, Groq, Anthropic, Gemini).
- Web search tool support via Firecrawl, Perplexity, or Exa.
- MCP (Model Context Protocol) Streamable HTTP server integration with bearer token and OAuth authentication.
- Zero-allocation SSE streaming parser (`SseUtils`).
- Edge-to-edge layout support with `WindowInsetsCompat`.
- Automated CI/CD release build pipeline for APK and AAB artifacts.
