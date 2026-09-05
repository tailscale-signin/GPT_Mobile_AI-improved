# Release Notes - v0.8.2

Welcome to the release of **GPT_Mobile_AI-improved** (v0.8.2)!

This release achieves 100% feature parity with recent additions from `gpt_mobile` while preserving all superior enhancements of `GPT_Mobile_AI-improved`.

---

### Highlights & Key Improvements

#### 1. DeepSeek / Reasoning Thinking Accordion
- **Collapsible UI**: Integrated `ThinkingAccordion` Jetpack Compose component to display `<think>...</think>` reasoning tokens cleanly.
- **Dynamic State**: Supports in-progress thinking animation and completed collapsibility with copy-to-clipboard functionality.
- **Robust Parser**: Zero-leak regex-based `ThinkingParser` with full unit test coverage (`ThinkingParserTest.kt`).

#### 2. MCP Marketplace
- **In-App Discovery**: Added `McpMarketplaceDialog` to browse, search, and install MCP server configurations with a single tap.
- **Category Filter Chips**: Filter by Search, Development, Database, Productivity, System, or Browser tools.
- **Preset Catalog**: Out-of-the-box presets for Brave Search, GitHub, Puppeteer, PostgreSQL, Filesystem, Memory, and Fetch.

#### 3. Per-Chat Tool Customization
- **Contextual Tool Activation**: Toggle which built-in tools and MCP servers are enabled on a per-chat basis using `ChatToolSelectionBottomSheet`.
- **Granular Permissions**: Safe, isolated tool scoping per conversation.

#### 4. Built-in Device Location Tool
- **Contextual Awareness**: Enables models to query coordinates, city, and country with proper Android runtime permissions (`DeviceLocationTool`).

#### 5. Prior Enhancements Retained
- **Deterministic Keystore & Side-by-Side Coexistence**: Isolated package ID `dev.chungjungsoo.gptmobile.improved`.
- **Autonomous Agent**: Up to 32 parallel tool executions, foreground execution service (`AgentRunForegroundService`).
- **High-Capacity Web Tools**: 100 MB URL reading, up to 100 web search results.
- **Full Configuration Backup & Restore**: JSON import/export encrypted via Android Keystore.
- **Favorited Responses & Search Tab**: Room Migration 11 -> 12 with Star tab.

---

### Download & Installation
- **APK**: Download `app-release.apk` from the GitHub Release assets.
- **AAB**: `app-release.aab` is available for deployment or internal testing distributions.
