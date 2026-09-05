# GPT Mobile AI (Improved)

An enhanced, high-performance, and feature-rich Android client for Large Language Models (LLMs) and local AI execution.

> **Fork Highlights**: This fork fundamentally modernizes the upstream [GPT_Mobile_AI](https://github.com/chungjungsoo/GPT_Mobile) project with an upgraded database architecture, agentic tool workflows, deep Jetpack Compose rendering optimizations, resilient network streaming, bounded memory safeguards, and an enterprise-grade CI/CD build matrix.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-36-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin%202.x-purple.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-4285F4.svg)](https://developer.android.com/jetpack/compose)

---

## 🌟 Highlights of this Fork vs. Original

| Area | Original Upstream | This Improved Fork |
| :--- | :--- | :--- |
| **Local Persistence** | Legacy monolithic `AppDatabase` with tight coupling and legacy schema | **Unified `ChatDatabaseV2` Room Architecture**: decoupled platform profiles, conversation rooms, partitioned messages, agent execution logs, and encrypted tool credentials. |
| **Tool Execution & MCP** | Basic static prompt generation | **Autonomous Agentic Tool Calling & MCP**: Full Model Context Protocol support, autonomous multi-step agent runtime, local device tools (`device_location`, `current_date`), safe math evaluator (`calculate_expression`), and web fetching. |
| **UI Streaming Performance** | Recomposition on every single SSE token arrival, leading to UI stutter and frame drops | **`StreamingMessageBuffer` with 33ms Flush Throttle**: Smooth 30fps/60fps/120fps display without sub-frame recomposition thrashing during hyper-fast token bursts. |
| **Compose Virtualization** | Unkeyed/basic `LazyColumn` item mapping | **Full Compose Stability Contracts**: `@Immutable` and `@Stable` data models, explicit `contentType` mapping, stable item keys, and memoized derived state calculations for lag-free scrolling. |
| **Networking & SSE** | Basic HTTP client with fragile line parsing | **Ktor CIO Singleton with Chunk-Safe SSE Parsing**: Handles split-buffer tokens and arbitrary line breakings (`\r\n` / `\n`) seamlessly, plus automated exponential backoff with jitter on network drops. |
| **Context & Memory Limits** | Unbounded conversation history leading to context overflow or OOM | **Dynamic Sliding-Window Compactor**: Tier-based character/token budgets per provider (Cloud, Groq, Ollama), preserving system prompt and active turn. |
| **Memory Safeguards** | Uncapped byte reading on external resources | **Bounded I/O & Streaming**: Tool web requests enforce a strict 1 MB read cap, 64 KB text output cap, and maximum redirect limits to safeguard device memory. |
| **Binary Optimization & APK Size** | Monolithic fat APK containing all native ABIs | **Targeted ABI Splits**: Separate lightweight APKs for `arm64-v8a` and `x86_64` alongside universal releases, reducing installation footprint by up to 60%. |
| **Release Hardening** | Basic ProGuard with lingering debug logs | **Aggressive R8 Full Mode + Stripped Production Logging**: ProGuard optimization with `android.util.Log` call removal in release builds for maximum runtime speed and privacy. |
| **CI / CD Pipeline** | Standard build steps vulnerable to runner OOM | **Hardened In-Process KSP & Swapfile Engine**: Configured `ksp.run.in.process=true` and automated 4GB swapfile initialization (`/swapfile_extra`) to prevent container memory kills. |

---

## ⚡ Supported Providers & Local Execution

- **Cloud APIs**: OpenAI (`gpt-4o`, `o1`, `o3-mini`), Anthropic Claude (`claude-3-7-sonnet`, `claude-3-5-haiku`), Google Gemini (`gemini-2.0-flash`, `gemini-1.5-pro`), Groq (ultra-low latency Llama-3/Mixtral), OpenRouter, and any custom OpenAI-compatible endpoint.
- **Self-Hosted & Local Inference**: Ollama integration (custom host/port) and on-device execution via **LiteRT** (MediaPipe / TFLite GenAI LLM) for offline, privacy-first conversations.
- **Autonomous Agentic Tools**: Multi-turn agent runner capable of reasoning, invoking registered tools, evaluating expressions, inspecting local context, and formatting results.

---

## 🛠️ Tech Stack & Architecture

- **UI**: Jetpack Compose, Material Design 3
- **Language**: Kotlin 2.x with Coroutines & StateFlow
- **Networking**: Ktor Client with CIO Engine, Server-Sent Events (SSE)
- **Local Database & Cache**: Room Database (`ChatDatabaseV2`), DataStore Preferences
- **Dependency Injection**: Hilt / Dagger with KSP
- **Security**: Android Keystore AES-GCM credential encryption
- **On-Device Inference**: Google LiteRT (MediaPipe GenAI LLM)

---

## 🏗️ Getting Started

### Prerequisites
- Android Studio Ladybug (2024.2.1) or newer
- JDK 21 (JDK 17 minimum)
- Android SDK 36 (target/compile) / Min SDK 26

### Building from Source
```bash
# Clone the repository
git clone https://github.com/tailscale-signin/GPT_Mobile_AI-improved.git
cd GPT_Mobile_AI-improved

# Build debug APK
./gradlew assembleDebug

# Build release APK (ABI splits & universal)
./gradlew assembleRelease

# Run unit tests
./gradlew testDebugUnitTest
```

---

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
