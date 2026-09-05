# GPT Mobile AI (Improved)

An enhanced, high-performance, open-source Android client for interacting with Large Language Models (LLMs) and local AI execution.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4.svg)](https://developer.android.com/jetpack/compose)

---

## 🌟 Key Features

- **Multi-Provider Support**: Seamlessly connect with OpenAI, Anthropic Claude, Google Gemini, Groq, Ollama, OpenRouter, Custom OpenAI-compatible endpoints, and on-device execution via LiteRT (MediaPipe/TFLite LLM).
- **Agentic Workflows & Tool Calling**: Built-in autonomous agent runner supporting Model Context Protocol (MCP), local system tools (`current_date`, `device_location`), safe mathematical evaluation (`calculate_expression`), and web navigation (`read_url`, `web_search`).
- **Deep Mobile Optimization**: Built from the ground up for minimal battery usage, low memory footprint, and smooth 120Hz scrolling on mobile devices.
- **Privacy First**: Direct device-to-provider connections with zero intermediary tracking servers. Android Keystore AES-GCM credential encryption and on-device local inference capabilities with LiteRT.

---

## 🚀 Performance & Architecture Improvements

This repository incorporates comprehensive optimizations covering Android packaging, Jetpack Compose rendering, networking, and memory management:

### 1. Build & Packaging Optimization
- **Targeted ABI Splits**: APK splits for `arm64-v8a` and `x86_64` to dramatically reduce APK download and install sizes on user devices.
- **Aggressive R8 Shrinking & Optimization**: Code and resource shrinking enabled on release builds with fine-tuned ProGuard configuration.
- **Stripped Production Logging**: Automatically strips `android.util.Log` calls in release builds to eliminate log string overhead and prevent sensitive data leakage.

### 2. High-Performance Jetpack Compose UI
- **Inbound Streaming Throttling**: Chat streaming uses `StreamingMessageBuffer` with a 33ms flush throttle (30fps) to eliminate sub-frame recomposition thrashing during rapid SSE token streaming.
- **List Virtualization & Key Stabilization**: `LazyColumn` message lists implement unique, stable item keys and explicit `contentType` mapping for efficient item pool recycling and minimal recomposition overhead.
- **Compose Stability Contracts**: UI state models (`ChatAttachment`, `AttachmentProviderRef`, etc.) annotated with `@Immutable` and `@Stable` to enable Jetpack Compose smart recomposition skipping.
- **Derived State & Expression Memoization**: Expensive list slicing, filtering, and scroll calculations are memoized with `remember { derivedStateOf { ... } }`.

### 3. Resilient Networking & Streaming
- **Shared Connection Engine**: Network requests leverage a singleton Ktor CIO engine configured with optimized connection pooling, keep-alive timeouts, and HTTP pipelining.
- **Exponential Backoff & Jitter**: Transient network failures and rate limits automatically retry with exponential backoff and jitter.
- **Robust SSE Line Buffering**: Chunk-safe Server-Sent Events parsing with `SseUtils` handles arbitrary chunk fragmentation and carriage return line endings (`\r\n` / `\n`) across all LLM providers.

### 4. Context, Agent & Memory Safeguards
- **Autonomous Agent Tool Runtime**: Default built-in safe mathematical expression parsing (`calculate_expression`), local time/date resolution (`current_date`), and location context (`device_location`).
- **Sliding-Window Token & Character Compactor**: `ContextBuilder` dynamically enforces a sliding-window character budget tailored to each client tier (e.g. 64k for Cloud LLMs, 24k for Groq, 20k for Ollama) while preserving the active turn.
- **Bounded Streaming Memory**: Tool execution and agent network operations (such as `ReadUrlTool`) enforce bounded byte streaming (1 MB read cap, 64 KB text output cap) and restricted redirect hops to prevent OutOfMemory (OOM) errors and Android Low Memory Killer (LMK) terminations.

---

## 🛠️ Tech Stack

- **UI**: Jetpack Compose, Material 3
- **Language**: Kotlin 2.x with Coroutines & StateFlow
- **Networking**: Ktor Client with CIO Engine, Server-Sent Events (SSE)
- **Local Database & Cache**: Room Database, DataStore Preferences
- **Dependency Injection**: Hilt / Dagger
- **On-Device Inference**: Google LiteRT (MediaPipe GenAI LLM)

---

## 🏗️ Getting Started

### Prerequisites
- Android Studio Ladybug (2024.2.1) or newer
- JDK 17 or higher
- Android SDK 35 / Min SDK 26

### Building from Source
```bash
# Clone the repository
git clone https://github.com/tailscale-signin/GPT_Mobile_AI-improved.git
cd GPT_Mobile_AI-improved

# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew testDebugUnitTest
```

---

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
