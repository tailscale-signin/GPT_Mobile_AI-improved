# Project Build & Progress Dashboard

Welcome to the progress and build tracking dashboard for **GPT Mobile Improved** (`dev.melo.gptmobile.improved`).

---

## 🚀 Release & Build Status

| Item | Details |
|------|---------|
| **Current Target Version** | `v0.9.5.3` (Version Code `55`) |
| **Latest Production Release** | [v0.9.5.3](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/releases/tag/v0.9.5.3) |
| **Package ID** | `dev.melo.gptmobile.improved` (Side-by-side installable) |
| **Build Status** | [![Release Build](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/workflows/release-build.yml/badge.svg?branch=main)](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/workflows/release-build.yml) |
| **Total Downloads** | [![Total Downloads](https://img.shields.io/github/downloads/tailscale-signin/GPT_Mobile_AI-improved/total?label=Downloads&logo=github)](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/releases/) |

---

## 📋 Shipped Improvements & Upstream Parity

### 1. Message Queuing, Shared Labels & Llama Router Mode (v0.9.5.3 / Code 55)
- **Generation Message Queuing**: FIFO queue (`GenerationQueueManager`) managing up to 50 queued messages with overflow protection, live queue badges, and stop confirmation dialogs.
- **Shared Platform Labels**: Multi-platform categorization with `PlatformLabel` and `PlatformLabelManager`, 12-color hex palette, and usage counting.
- **Llama Router Selection**: `LlamaModelInfo` and `LlamaModelMapper` parsing parameter sizes, quantization, and context windows.
- **MCP Tool Architecture**: Verified manifest, triple-verification mechanism, and standardized tool directory structure (`mcp/tools/`, `mcp/resources/`, `assets/mcp-downloads/`).

### 2. UI Gestures, Pinning Restoration & Granular Backups (v0.9.5.2 / Code 54)
- **Pinning & Input During Generation**: 1-second long press restores chat pinning, input composer remains active for typing while AI generates, and archive icon is hidden until swiped right.
- **FancySwipeChatCard**: Enhanced conversational swipe card with color transitions, pulsing actions, and threshold haptics.
- **Backup Enhancements**: Granular options for exporting/importing encrypted favorites, UI preferences, and platform configurations.

### 3. Build Stabilization & Dependency Cleanups (v0.9.5.1 / Code 53)
- Fixed Kotlin 2.x generic `TypeToken` inference in `AdvancedSettingsViewModel`.
- Resolved Material 3 button style resolution and removed deprecated `extractNativeLibs`.

### 4. Llama Platform Integration (v0.9.5.0 / Code 52)
- Added dedicated Llama client type, settings, and router endpoints.

### 5. Core Performance Optimizations & Pinning (v0.9.4.6 / Code 51)
- Swipe-to-dismiss, archive gestures, long-press pin interaction, and Circuit Breaker user error classification.
