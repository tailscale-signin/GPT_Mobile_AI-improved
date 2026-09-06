# Release Notes - v0.8.5

Welcome to the release of **GPT_Mobile_AI-improved** (v0.8.5)!

This release delivers refined readability for the interactive tool trace visualizer, polished tool and MCP display names, Google Fonts compiler resolution for Android 16 (SDK 36), and full configuration backup & restore capabilities.

---

### Highlights & Key Improvements

#### 1. Tool Trace Visualizer Contrast & Readability
- **Refined Element Opacity**: Updated `ToolTraceBlock` opacity from `0.5f` to `0.75f` across sub-elements, status headers, parameters, and results, improving contrast across both light and dark Compose themes while maintaining subtle visual hierarchy.

#### 2. Polished Tool & Action Display Names
- **Clean Suffix Stripping**: Cleaned MCP and tool title presentation by stripping redundant trailing `Tool` or `tool` suffixes in the UI, ensuring cleaner display names in chat bubbles and agent execution logs.
- **Unit Test Coverage**: Added comprehensive test coverage in `AgentTraceVisualizerTest` verifying name formatting across single-word, multi-word, and custom tool types.

#### 3. Font Compilation & SDK 36 Stability
- **Compose Typography Reliability**: Replaced remote Google Fonts font-provider lookup with reliable `FontFamily.SansSerif` fallbacks in `Type.kt`, eliminating missing provider exceptions and offline build breakages.
- **Modern Build Support**: Clean compilation against Android SDK 36 with Java 21 and Kotlin 2.3.21.

#### 4. Configuration Backup & Restore
- **Export & Import**: Full backup and restore support for user settings, platform API credentials, model configurations, and custom preferences in Settings.
- **Safe JSON Storage**: Secure export/import with validation dialogs to avoid state corruption.

---

### Download & Installation
- **APK**: Download split (`arm64-v8a`, `x86_64`) or universal `app-release.apk` from the GitHub Release assets.
- **AAB**: `app-release.aab` is available for deployment or internal testing distributions.
