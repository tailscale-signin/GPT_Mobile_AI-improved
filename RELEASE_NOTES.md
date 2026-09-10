# Release Notes - v0.9.0

Welcome to release **v0.9.0** of **GPT Mobile AI (Improved)**!

This landmark release brings full local on-device hardware acceleration via LiteRT-LM, phase-split scheduling, cooperative thread yielding, dynamic thermal & battery throttling, live telemetry, rolling context compaction, OpenRouter advanced provider routing, Room Schema 15, instant bottom chat anchoring, and Google Gemini MCP tool schema sanitization.

---

### What's New in v0.9.0

#### 1. LiteRT-LM Hardware Acceleration & Dynamic Inference Engine
- **Phase-Split Scheduling**: Separates execution into prompt evaluation (`LocalInferencePhase.PREFILL`) and token generation (`GENERATING`), providing clear foreground notification progress updates in `AgentRunForegroundService`.
- **Cooperative Thread Yielding**: Added cooperative `yield()` checkpoints during native conversation creation and message evaluation, eliminating UI thread hitches.
- **Dynamic Hardware Governor (`DeviceHardwareGovernor`)**: Monitors thermal status and battery level to adaptively throttle context window token clamps, sampling top-k, and UI stream intervals.
- **120Hz/144Hz Smooth Streaming**: 8ms frame budget dispatching on high-refresh devices for liquid-smooth token rendering.
- **Inactivity Auto-Unload (`unloadIfIdle`)**: Automatically frees native model weights from RAM after inactivity, preventing background memory pressure.
- **Rolling Context Compaction (`RollingContextWindowCompactor`)**: Preserves Turn 0 anchor prefix while rolling recent conversation history to avoid exceeding physical hardware context ceilings.
- **Live Inference Telemetry (`TelemetryBadge`)**: Real-time generation statistics (tok/s, TTFT ms, token estimates, thermal throttling badges) cleanly displayed alongside assistant actions.
- **Hardware-Aware Context Scaling**: Dynamic context window expansion up to 8,192 tokens on >=12GB RAM devices, with strict SoC variant clamping for NPU targets.

#### 2. OpenRouter Advanced Routing & Reasoning
- **Fine-Grained Provider Routing**: Added configuration options for OpenRouter provider order, fallback providers, sorting strategy (`price`, `throughput`, `latency`), data collection policy (`allow`, `deny`), and precision/quantizations (`fp16`, `int8`, `int4`, `bf16`).
- **Reasoning Tokens Control**: Custom max reasoning tokens support across OpenRouter endpoints.
- **Provider Adapters & Settings UI**: Integrated advanced routing controls into `OpenAICompatibleAdapter` and `PlatformSettingScreen` via `OpenRouterAdvancedSettingsDialog`.
- **Database Schema 15**: Added `open_router_routing` column to `platform_v2` with `MIGRATION_14_15` and automated test verification.

#### 3. Chat Presentation & Favorites Navigation
- **Instant Bottom Anchoring**: Implemented `rememberChatListState` keyed on message counts to guarantee instantaneous bottom anchoring without visual layout jumps, safely yielding focus when deep-linking to target/favorite messages.
- **Collapsible Details**: Introduced collapsible details button (`DetailsButton`) with smooth spring animations (`fastEffectsSpec`) for cleaner inspection of tool reasoning and execution steps.
- **Continuous Streaming Pulse**: Visual heartbeat indicator (`●`) during generation and tool runs to clearly signify background processing.
- **Taller Cyan Highlight Bubble**: `OpponentResponseContainer` enclosing avatar, loading indicators, platform selection pills, and chat bubble with generous padding and 32.dp rounded corners.

#### 4. Tool Calling & API Resilience
- **Google Gemini Tool Calling Reliability**: Recursive parameter schema sanitization (`geminiToolParameters`) stripping transport metadata (`x-mcp-header`, `x-mcp-param`) and unsupported schema keywords (`$schema`, `propertyNames`, `additionalProperties`).
- **Immediate Schema Rejection Handling**: Detection of tool schema validation errors (`throwIfToolDefinitionsRejected`) in `ProviderRequestConfig` to trigger prompt fallback immediately without cycling through multiple API keys and timing out.

---

### Artifacts & Downloads
- **Universal APK**: `app-universal-release.apk` (runs on all supported 64-bit architectures)
- **Architecture APKs**: `app-arm64-v8a-release.apk` and `app-x86_64-release.apk`
- **Release Bundle (AAB)**: `app-release.aab`
