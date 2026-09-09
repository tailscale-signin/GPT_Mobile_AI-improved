# Release Notes - v0.8.9.1 (Pre-release)

Welcome to pre-release **v0.8.9.1** of **GPT Mobile AI (Improved)**!

This maintenance and feature update builds upon v0.8.9, bringing OpenRouter advanced provider routing and reasoning configuration, Room database schema version 15 with migration 14->15, instant bottom-anchored chat scrolling, and collapsible details with continuous streaming pulses.

---

### What's New in v0.8.9.1

#### 1. OpenRouter Advanced Routing & Reasoning
- **Fine-Grained Provider Routing**: Added configuration options for OpenRouter provider order, fallback providers, sorting strategy (`price`, `throughput`, `latency`), data collection policy (`allow`, `deny`), and precision/quantizations (`fp16`, `int8`, `int4`, `bf16`).
- **Reasoning Tokens Control**: Custom max reasoning tokens support across OpenRouter endpoints.
- **Provider Adapters & Settings UI**: Integrated advanced routing controls into `OpenAICompatibleAdapter` and `PlatformSettingScreen` via `OpenRouterAdvancedSettingsDialog`.
- **Database Schema 15**: Added `open_router_routing` column to `platform_v2` with `MIGRATION_14_15` and automated test verification.

#### 2. Chat UI & Scrolling Enhancements
- **Instant Bottom Anchoring**: Implemented `rememberChatListState` keyed on message counts to guarantee instantaneous bottom anchoring without visual layout jumps.
- **Collapsible Details**: Introduced collapsible details button with smooth spring animations (`Motion.kt`) for cleaner inspection of tool reasoning and execution steps.
- **Continuous Streaming Pulse**: Visual heartbeat indicator (`●`) during generation and tool runs to clearly signify background processing.

---

### Artifacts & Downloads
- **Universal APK**: `app-universal-release.apk` (runs on all supported 64-bit architectures)
- **Architecture APKs**: `app-arm64-v8a-release.apk` and `app-x86_64-release.apk`
- **Release Bundle (AAB)**: `app-release.aab`
