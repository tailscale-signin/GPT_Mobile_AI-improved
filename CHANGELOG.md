# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
