# GPT Mobile AI (Improved) v0.9.13.0

## Highlights

- **Custom Theme Color Studio**: Interactive custom theme color wheel, dynamic palette previews, and custom hex color support alongside expanded curated presets.
- **Provider Multi-Key Management**: Configure multiple API keys per AI provider with automatic round-robin rotation, quota failover, and key status monitoring.
- **Animated Tool Execution Traces**: Live animated progress indicators, real-time duration counters, and distinct status badges (running, succeeded, failed) for transparent tool activity tracking.
- **Interactive OpenStreetMap (OSM) & MapLibre Mapping**: Rich map cards featuring nearby place search markers, interactive zoom/pan controls, and walking/driving path overlays for location-aware tool results.
- **Fact Vault Recall Controls**: Configurable local learning retention thresholds, granular fact inspection, editing, deletion, and visible recall chips in conversation prompts.
- **Independent Reasoning Model Controls**: Dedicated visibility toggles and display options for reasoning model output traces.
- **Local Runtime Performance Optimization**: Improved resource allocation and thread management for LiteRT and on-device model inference.

## Fixes

- Fixed Llama tool handoff and response parsing to prevent tool-loop stalls and guarantee clean turn completions.
- Fixed duplicate selection and key exhaustion handling in multi-key round-robin rotation.
- Resolved map marker lifecycle issues and prevented leaks during device orientation changes.
- Improved custom theme color contrast, dark mode luminance matching, and persistence across app restarts.
- Optimized tool result serialization, token count estimation, and gateway payload measurements.

## Installation and artifacts

- Version: **0.9.13.0** (version code **68**).
- Android 12 or newer; target Android 16.
- Package: `dev.melo.gptmobile.improved`.
- Signed APKs: **arm64-v8a**, **x86_64**, and **universal**. Most modern Android phones use arm64-v8a.
- Signed Android App Bundle (AAB) and SHA-256 checksums are produced by the release workflow.
- Continuous signing compatibility preserved across updates.

## Memory and telemetry notes

Fact Vault learning is opt-in and off by default. Extraction uses lightweight, on-device parsing patterns without external embedding dependencies. Relevant active facts are passed only to the active model provider for the current prompt. Tool token counts are local estimates for transparency.

## Validation

All core features, map integrations, and Llama handoff fixes have been verified with regression test suites and clean debug APK compilation. Signed release packaging is handled via the automated GitHub Actions pipeline.
