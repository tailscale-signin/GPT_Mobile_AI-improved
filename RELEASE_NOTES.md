# Release Notes - v0.9.1 (Pre-release)

Welcome to pre-release **v0.9.1** of **GPT Mobile AI (Improved)**!

This release introduces dedicated full-screen MCP tools configuration, comprehensive platform enable/disable syncing across the application, bounded reasoning and tool-calling execution blocks to prevent overlap, high-demand 3-second round-robin auto-retries, interactive home screen platform sorting, redesigned tool connection settings, and Room Database Schema 16.

---

### What's New in v0.9.1

#### 1. Full-Screen MCP Tools Selection & Configuration
- **Dedicated Screen Destination**: Replaced modal popup dialogs with a full-screen view (`McpToolsSelectionScreen`) accessible at `platform_setting/{platformId}/mcp_tools`.
- **Server Grouping & Categorization**: Tools are neatly grouped by server with tool category icons, search filter bar, parameter definitions, and status badges.
- **Master "Disable All Tools" Toggle**: Per-platform switch to completely disable tool execution (both remote MCP/search and local offline tools) for platforms where pure text/reasoning output is preferred.

#### 2. Platform Sync & Settings Screen Restructuring
- **Immediate State Synchronization**: Toggling off an AI platform immediately disables the model app-wide, persisting synchronously to the database.
- **Dedicated "AI Platforms" Card**: Reorganized `SettingScreen` so that "AI Platforms" is clearly organized into its own top-level feature card situated cleanly above "Local Models".

#### 3. Chat Layout & Details Positioning
- **Details Section Placement**: In `ChatScreen`, the Details toggle button and expandable container are positioned cleanly *above* the user question bubble for clearer visual context.
- **Reasoning & Tool Execution Bubble Isolation**: In `ChatBubble`, isolated `ThinkingBlock` and `ToolTraceBlock` into explicit vertically bounded containers with dedicated padding, preventing visual overlaps during active reasoning or multi-turn tool calling.

#### 4. High Demand 3-Second Round-Robin Auto-Retry
- **Demand Spike Detection**: Added automatic detection for `"Error: This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later."` in `ApiCredentialRotator`.
- **Graceful Failover**: Pauses for 3 seconds before rotating to the next configured API credential or candidate provider.

#### 5. Interactive Home Screen Platform Sorting
- **Sorting Filter Chips**: In `HomeScreen` (`SelectPlatformDialog`), added interactive sorting filter chips (`Default`, `Name`, `Provider`, `Enabled`) with animated transitions.

#### 6. Database Migration (Schema 16)
- **Room Migration 15 -> 16**: Added `disable_all_tools: Boolean = false` to entity `platform_v2` with automated migration testing.
- **Settings Export/Import**: Preserved `disable_all_tools` across configuration backups and restores.

---

### Artifacts & Downloads
- **Universal APK**: `app-universal-release.apk` (runs on all supported 64-bit architectures)
- **Architecture APKs**: `app-arm64-v8a-release.apk` and `app-x86_64-release.apk`
- **Release Bundle (AAB)**: `app-release.aab`
