# GPT Mobile AI (Improved)

An enhanced, high-performance, and feature-rich Android client for Large Language Models (LLMs) and local AI execution.

> **Fork Overview**: This version is a modernized, high-performance fork of [GPT_Mobile_AI](https://github.com/chungjungsoo/GPT_Mobile). It delivers a significantly smoother user experience, autonomous agent tools (MCP), on-device privacy AI, and major battery and performance improvements.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-36-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin%202.x-purple.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-4285F4.svg)](https://developer.android.com/jetpack/compose)

---

## 🌟 What's Different in this Fork? (Sorted by User Impact)

Here is a straightforward breakdown of what's new and improved, ranked from the most noticeable daily user benefits down to under-the-hood technical upgrades:

### 1. ⚡ Ultra-Smooth, Lag-Free Text Streaming (Most Noticeable)
- **What you will notice**: When models reply at high speeds (like Groq or Claude 3.5/3.7), the app no longer freezes, drops frames, or stutters while scrolling.
- **How it works**: Uses an adaptive 33ms streaming buffer to batch incoming tokens into smooth 60fps/120fps display updates instead of re-rendering on every single character.

### 2. 🧠 Autonomous Tools & Model Context Protocol (MCP)
- **What you will notice**: AI models can now solve math accurately, check the current date/time, fetch web pages, and interact with external systems using MCP tools.
- **How it works**: Integrated agent runtime capable of multi-step reasoning, safely invoking tools, handling errors, and synthesizing answers.

### 3. 🔒 Offline & Local AI (LiteRT / MediaPipe + Ollama)
- **What you will notice**: Chat with private, on-device models completely offline without sending any data to the cloud, or connect to your home Ollama server.
- **How it works**: Built-in Google LiteRT (MediaPipe GenAI LLM) runtime for local `.bin`/`.tflite` weights, alongside full Ollama API support.

### 4. 🗄️ Instant Search & Robust History (`ChatDatabaseV2`)
- **What you will notice**: Lightning-fast message search across all conversations, reliable chat restoration, and no lost messages or crashes on database updates.
- **How it works**: Upgraded to a clean, modern Room V2 database architecture with partitioned message indexing, agent logs, and encrypted credential storage.

### 5. 📉 Up to 60% Smaller App Download Size
- **What you will notice**: Smaller APK downloads and less storage space consumed on your phone.
- **How it works**: Configured targeted ABI split builds (`arm64-v8a`, `x86_64`) so modern phones don't have to carry unused native libraries.

### 6. 🛡️ Context Window Protection (No More Token Limit Crashes)
- **What you will notice**: Long conversations no longer trigger provider context overflow errors or sudden out-of-memory crashes.
- **How it works**: Intelligent sliding-window context compactor dynamically manages token budgets per provider while always retaining your system instructions.

### 7. 🌐 Network Resilience & Automatic Reconnect
- **What you will notice**: Unstable Wi-Fi or cellular connections will automatically retry with exponential backoff rather than failing mid-sentence.
- **How it works**: Re-architected Ktor CIO engine with chunk-safe SSE stream recovery.

### 8. 🛡️ Enterprise-Grade CI & Build Reliability (Under the Hood)
- **What it does**: Ensures rock-solid builds and automated releases without memory exhaustion or compilation errors.
- **How it works**: Automated swapfile allocation (4GB extra RAM), in-process KSP compilation, R8 Full Mode optimization, and stripped production logs for maximum privacy.

---

## ⚡ Quick Comparison

| Feature / Capability | Upstream Original | This Improved Fork |
| :--- | :--- | :--- |
| **High-Speed Streaming** | Stutters on fast token bursts | 🧈 Smooth 30/60/120 fps throttle buffer |
| **Agent Tools & MCP** | ❌ Not supported | ✅ Built-in Agent Engine + Model Context Protocol |
| **On-Device Models** | Basic / Limited | ✅ Google LiteRT & Full Ollama host integration |
| **Search & Database** | Monolithic legacy database | ✅ Modern `ChatDatabaseV2` with instant search |
| **APK Footprint** | Large universal APK | ✅ 60% lighter native ABI split APKs |
| **Long Chats** | Vulnerable to context overflows | ✅ Dynamic sliding-window context compactor |
| **Network Drops** | Stream breaks immediately | ✅ Safe chunk parsing + auto-retry backoff |
| **Release Privacy** | Debug logs present in builds | ✅ Aggressive R8 Full Mode + zero log leakage |

---

## 🌐 Supported Providers

- **Cloud**: OpenAI (`gpt-4o`, `o1`, `o3-mini`), Anthropic Claude (`claude-3-7-sonnet`, `claude-3-5-haiku`), Google Gemini (`gemini-2.0-flash`, `gemini-1.5-pro`), Groq, OpenRouter, and any OpenAI-compatible API.
- **Local / Self-Hosted**: Local LiteRT on-device LLMs, Ollama (custom IP and port).

---

## 🛠️ Architecture & Tech Stack

- **UI**: Jetpack Compose, Material Design 3 (fully optimized with stability contracts)
- **Language**: Kotlin 2.x, Coroutines, StateFlow
- **Networking**: Ktor Client with CIO engine, Server-Sent Events (SSE)
- **Persistence**: Room Database (`ChatDatabaseV2`), DataStore Preferences
- **Dependency Injection**: Hilt / Dagger with KSP
- **Security**: Android Keystore AES-GCM credential encryption
- **Inference**: Google LiteRT (MediaPipe GenAI LLM)

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
