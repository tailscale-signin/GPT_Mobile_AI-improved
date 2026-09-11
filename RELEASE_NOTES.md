# Release Notes - v0.9.1.1

Welcome to **v0.9.1.1** of **GPT Mobile AI (Improved)**!

This maintenance and stabilization release resolves Kotlin compiler and resource conflicts across platform settings, provides clean dialog bindings, fixes OpenRouter dialog resolution, and ensures reproducible signed release artifacts.

---

### What's New in v0.9.1.1

#### 1. Platform Settings Dialogs & Compiler Fixes
- **Timeout Dialog Delegation**: Corrected parameter resolution in `TimeoutDialog` composable to prevent overload ambiguity during Kotlin compilation.
- **De-duplicated Dialog Declarations**: Stripped redundant legacy dialog implementations from `PlatformSettingDialogs.kt`.
- **OpenRouter Model Picker Resolution**: Fixed package import in `PlatformSettingScreen.kt` for `OpenRouterModelPickerDialog`.

#### 2. Resource Resolution & Build Resilience
- **AAPT2 Default Value Synchronization**: Added and reconciled missing default resource strings (`gemini_safety_*`, `sample_item_*`, `openrouter_*`, `search_backend`, `none`).
- **Resource Integrity**: Resolved duplicate and conflicting keys across `missing_build_resources.xml` and `strings.xml`.

#### 3. Core Features from v0.9.1 Series
- **Full-Screen MCP Tools Selection**: Dedicated screen destination (`McpToolsSelectionScreen`) at `platform_setting/{platformId}/mcp_tools`.
- **Immediate Platform State Synchronization**: Synchronous DB updates when toggling platform states.
- **Visual Improvements**: Thinking and tool execution bubbles isolated to prevent UI overlaps; details indicator repositioned cleanly above user question bubbles.
- **Round-Robin Auto-Retry**: Automated 3-second delay and credential failover when encountering high-demand spikes.
- **Interactive Sorting**: Dynamic filter chips in `HomeScreen` platform selection dialog.

---

### Artifacts & Downloads
- **Universal APK**: `app-universal-release.apk` (runs on all supported 64-bit architectures)
- **Architecture APKs**: `app-arm64-v8a-release.apk` and `app-x86_64-release.apk`
- **Release Bundle (AAB)**: `app-release.aab`
