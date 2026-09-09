# GPT Mobile AI (Improved)

An enhanced, high-performance, and feature-rich Android client for Large Language Models (LLMs), autonomous tool calling (MCP), and local AI execution.

> **Fork Overview**: This version is a modernized, high-performance fork of [GPT_Mobile_AI](https://github.com/chungjungsoo/GPT_Mobile). It delivers a significantly smoother user experience, autonomous agent tools (MCP), on-device privacy AI, background and screen-off execution persistence, multi-key credential failover, encrypted backup vaults, and major battery and performance improvements.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-36-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin%202.x-purple.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-4285F4.svg)](https://developer.android.com/jetpack/compose)

---

## 🌟 What's Different in this Fork? (Sorted by User Impact)

Here is a straightforward breakdown of what's new and improved, ranked from the most noticeable daily user benefits down to under-the-hood technical upgrades:

### 1. ⚡ Ultra-Smooth, Lag-Free Text Streaming (Most Noticeable)
- **What you will notice**: When models reply at high speeds (such as Groq, Cerebras, or Claude 3.5/3.7), the app no longer freezes, drops frames, or stutters while scrolling.
- **How it works**: Uses an adaptive 33ms streaming buffer (dynamically optimized based on device RAM) to batch incoming tokens into smooth 60fps/120fps display updates instead of re-rendering on every single character.

### 2. 🧠 Autonomous Tools & Model Context Protocol (MCP) + Marketplace
- **What you will notice**: AI models can solve math, check real-time date/time, fetch web pages, run terminal commands, and integrate external services. Discover and enable new tools directly through the built-in, full-screen **MCP Tool Marketplace**.
- **How it works**: Integrated agent runtime capable of multi-step reasoning, safely invoking tools, handling errors, and synthesizing answers.
- **Safety Ceiling & Custom Overrides**: Enforces a configurable ceiling (default: 10 tool calls per prompt) to protect against runaway loops and unexpected API billing, with full support for user overrides in model and tool options.
- **Zero-Config Web Search**: Preinstalled `droid-mcp-web` Online Search (`web_search` and `fetch_webpage`) with automatic DuckDuckGo fallback for instant web connectivity without manual API key setup.
- **Visual Tool Tracing**: Real-time tool trace cards with collapsible inputs/outputs and execution status indicators directly within the chat timeline.

### 3. 🌙 Background, Screen-Off & Locked Device Execution
- **What you will notice**: Multistep agent tasks, long web queries, and lengthy local model generation continue uninterrupted even if your phone screen turns off, the device locks, or you switch to another app.
- **How it works**: Managed via `AgentRunForegroundService` with Android 14+ `dataSync` compliance, partial CPU `WAKE_LOCK` management during active operations, and battery optimization exception handling.

### 4. 🔑 Multi-Key Round-Robin Rotation & Auto-Failover
- **What you will notice**: Rate limits (HTTP 429), quota exhaustion, and payment required (HTTP 402) errors no longer interrupt your chats.
- **How it works**: Add multiple API keys per provider in the dynamic API dialog (`+API`). The `ApiCredentialRotator` automatically distributes requests and seamlessly rotates to backup keys when a rate limit or quota error occurs.

### 5. 🔒 Offline & Local AI (LiteRT / MediaPipe + Ollama)
- **What you will notice**: Chat with private, on-device models completely offline without sending any data to the cloud, or connect to your local home Ollama server.
- **How it works**: Built-in Google LiteRT (MediaPipe GenAI LLM) runtime with hardware acceleration (NPU/GPU/CPU auto-selection) for local `.bin`/`.tflite` weights, alongside full Ollama API support. Memory-trimmed lifecycle management protects active inference sessions under low memory.
- **Resilient Background Downloads**: WorkManager-backed model downloader with progress notifications, SHA-256 integrity checks, and resumption.

### 6. ⭐ Rich Favorites Management & Custom Categorization
- **What you will notice**: Save important messages, code snippets, and answers into a dedicated Favorites workspace.
- **How it works**: Categorize saved items with custom group filter chips ("All", user-defined groups, "+ Add Group"). View favorites in an expansive full-screen dialog rendered with rich Markdown, LaTeX equations, and syntax-highlighted code, with one-tap navigation back to the original chat thread.

### 7. 🔐 Keystore Encryption & Encrypted Vault Backups
- **What you will notice**: Your API keys and credentials are safe even if your device backup is inspected.
- **How it works**: All credentials are stored with Android Keystore-backed AES-256-GCM encryption (`SecretVault`) in `noBackupFilesDir`. Full app backups are protected using typed PBKDF2-HMAC-SHA256 key derivation and AES-256-GCM authenticated encryption.

### 8. 🗄️ Instant Search & Robust History (`ChatDatabaseV2`)
- **What you will notice**: Lightning-fast message search across all conversations, reliable chat restoration, and zero lost messages on database updates.
- **How it works**: Upgraded to a clean, modern Room V2 database architecture (Schema v14) with partitioned message indexing, agent logs, tool event recording, and verified migrations.

### 9. 🛡️ Context Window Protection (No More Token Limit Crashes)
- **What you will notice**: Long conversations no longer trigger provider context overflow errors or sudden out-of-memory crashes.
- **How it works**: Intelligent sliding-window context compactor dynamically manages token budgets per provider while always retaining your system instructions.

### 10. 🌐 Network Resilience & Automatic Reconnect
- **What you will notice**: Unstable Wi-Fi or cellular connections automatically retry with exponential backoff rather than failing mid-sentence.
- **How it works**: Re-architected Ktor CIO engine with chunk-safe SSE stream recovery and jittered backoff.

### 11. 📉 Up to 60% Smaller App Download Size
- **What you will notice**: Smaller APK downloads and less storage space consumed on your phone.
- **How it works**: Configured targeted ABI split builds (`arm64-v8a`, `x86_64`) so modern phones don't have to carry unused native libraries.

### 12. 🛡️ Enterprise-Grade CI & Build Reliability (Under the Hood)
- **What it does**: Ensures rock-solid builds and automated releases without memory exhaustion, compilation errors, or linting failures.
- **How it works**: Automated swapfile allocation (4GB extra RAM), in-process KSP compilation, R8 Full Mode optimization with stripped production logs for maximum privacy, and automated PR format lint checks.

---

## ⚡ Quick Comparison

| Feature / Capability | Upstream Original | This Improved Fork |
| :--- | :--- | :--- |
| **High-Speed Streaming** | Stutters on fast token bursts | 🧈 Smooth 30/60/120 fps adaptive buffer |
| **Agent Tools & MCP** | ❌ Not supported | ✅ Built-in Agent Engine + Model Context Protocol |
| **MCP Marketplace** | ❌ Not supported | ✅ Full-screen in-app marketplace with 1-click install |
| **Tool Execution Limits** | ❌ None | ✅ Configurable ceiling (default: 10 calls, user-customizable) |
| **Background / Screen-Off Run** | ❌ Killed on screen lock | ✅ Resilient `AgentRunForegroundService` + WakeLock |
| **Multi-Key API Failover** | ❌ Single key only | ✅ `ApiCredentialRotator` round-robin & rate-limit fallback |
| **Credential Security** | Plaintext / basic storage | ✅ Android Keystore AES-256-GCM (`SecretVault`) |
| **Encrypted Backups** | ❌ Not supported | ✅ Passphrase-protected PBKDF2 + AES-GCM export/import |
| **Favorites Management** | ❌ Basic or none | ✅ Custom group chips, rich Markdown dialog, chat jump |
| **Web Search** | ❌ Manual setup / none | ✅ Zero-config `droid-mcp-web` + DuckDuckGo fallback |
| **On-Device Models** | Basic / Limited | ✅ Google LiteRT (NPU/GPU/CPU) & Full Ollama host integration |
| **Search & Database** | Monolithic legacy database | ✅ Modern `ChatDatabaseV2` (Schema v14) with instant search |
| **APK Footprint** | Large universal APK | ✅ 60% lighter native ABI split APKs |
| **Long Chats** | Vulnerable to context overflows | ✅ Dynamic sliding-window context compactor |
| **Network Drops** | Stream breaks immediately | ✅ Safe chunk parsing + auto-retry backoff |
| **Release Privacy** | Debug logs present in builds | ✅ Aggressive R8 Full Mode + zero log leakage |

---

## 🌐 Supported Providers

- **Cloud**: OpenAI (`gpt-4o`, `o1`, `o3-mini`), Anthropic Claude (`claude-3-7-sonnet`, `claude-3-5-haiku` with reasoning blocks), Google Gemini (`gemini-2.0-flash`, `gemini-1.5-pro`), Groq (ultra-fast LPU inference with reasoning extraction), OpenRouter (complete catalog, provider routing, fallback, and plugins), and any OpenAI-compatible API.
- **Local / Self-Hosted**: Local LiteRT on-device LLMs (NPU/GPU/CPU accelerated), Ollama (custom IP and port).

---

## 🛠️ Architecture & Tech Stack

- **UI**: Jetpack Compose, Material Design 3 (fully optimized with stability contracts)
- **Language**: Kotlin 2.x, Coroutines, StateFlow
- **Networking**: Ktor Client with CIO and OkHttp engines, Server-Sent Events (SSE)
- **Persistence**: Room Database (`ChatDatabaseV2`), DataStore Preferences
- **Dependency Injection**: Hilt / Dagger with KSP
- **Security**: Android Keystore AES-256-GCM credential encryption (`SecretVault`)
- **Inference**: Google LiteRT (MediaPipe GenAI LLM)
- **Background Execution**: Android Foreground Service (`dataSync`), CPU Partial WakeLock, WorkManager

---

## 🏗️ Getting Started

### Prerequisites
- Android Studio Ladybug (2024.2.1) or newer
- JDK 21 (JDK 17 minimum)
- Android SDK 36 (target/compile) / Min SDK 31

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
