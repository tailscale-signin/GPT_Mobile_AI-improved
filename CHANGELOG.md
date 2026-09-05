# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
