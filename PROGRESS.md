# Project Build & Progress Dashboard

Welcome to the progress and build tracking dashboard for **GPT Mobile Improved** (`dev.chungjungsoo.gptmobile.improved`).

---

## 🚀 Release & Build Status

| Item | Details |
|------|---------|
| **Target Version** | `v0.8.2` (Version Code `25`) |
| **Current Stable Release** | [v0.8.1](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/releases/tag/v0.8.1) |
| **Package ID** | `dev.chungjungsoo.gptmobile.improved` (Side-by-side installable) |
| **Build Status** | [![Release Build](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/workflows/release-build.yml/badge.svg?branch=main)](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/workflows/release-build.yml) |
| **Total Downloads** | [![Total Downloads](https://img.shields.io/github/downloads/tailscale-signin/GPT_Mobile_AI-improved/total?label=Downloads&logo=github)](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/releases/) |

---

## 📋 Shipped Improvements & Upstream Parity

### 1. Full Parity with Recent `gpt_mobile` Features
- **Reasoning & Thinking Blocks (`ThinkingParser` & `ThinkingAccordion`)**:
  - Implemented real-time `<think>...</think>` block extraction with `ThinkingParser`.
  - Added collapsible Jetpack Compose `ThinkingAccordion` with in-progress pulse indicators and full copy support.
  - Comprehensive unit test suite in `ThinkingParserTest.kt`.
- **MCP Marketplace**:
  - In-app marketplace dialog (`McpMarketplaceDialog`) with dynamic search and category filtering (`SEARCH`, `DEVELOPMENT`, `DATABASE`, `PRODUCTIVITY`, `SYSTEM`, `BROWSER`).
  - One-tap installation of presets from `McpPresetCatalog` (Brave Search, GitHub, Puppeteer, PostgreSQL, Filesystem, Memory, Fetch).
- **Per-Chat Tool Configuration**:
  - Per-chat MCP and built-in tool toggling via `ChatToolSelectionBottomSheet`.
  - Persisted activation states in `ChatMcpToolConfig`.
- **Device Location Tool**:
  - Built-in `DeviceLocationTool` and `DeviceLocationProvider` for contextual device-aware queries with runtime permission handling.

### 2. Side-by-Side Coexistence & Deterministic Keystore
- Isolated Application ID (`dev.chungjungsoo.gptmobile.improved`) allows direct side-by-side usage with upstream GPT Mobile without uninstalls.
- Persistent signing configuration ensures all subsequent updates install directly in-place without keystore mismatch errors.

### 3. Autonomous Agent Architecture
- Removed loops, tool call counts, and execution timeout ceilings (`Int.MAX_VALUE` / `Long.MAX_VALUE`).
- Supported up to 32 parallel tool executions with expanded output buffers for deep reasoning agent sessions.
- Foreground execution support via `AgentRunForegroundService` and `AgentRunCoordinator`.

### 4. High-Capacity Tooling
- **ReadUrl**: Expanded to 100 MB max body size, 50 MB output buffer, and 50 redirects.
- **WebSearch**: Scaled up to 100 results per query across Firecrawl, Perplexity, and Exa.
- **MCP Client**: Uncapped discovery and tool registry pagination.

### 5. Configuration Backup & Restore
- Full JSON export and import for provider profiles, custom endpoints, model parameters, and preferences.
- Android Keystore (`SecretVault`) integration with re-encryption upon restore.

### 6. Favorited Messages & Searchable Star Tab
- Star any AI response across providers.
- Instant search across saved responses with one-tap deep navigation to the original chat.

---

## 🛠️ Releases & Artifacts

- **GitHub Releases**: [Browse All Releases](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/releases)
- **Latest Release Assets**: [v0.8.1 Assets](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/releases/tag/v0.8.1)
