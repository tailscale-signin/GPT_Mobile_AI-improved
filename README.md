# GPT Mobile AI (Improved)

An enhanced, high-performance, and feature-rich Android client for Large Language Models (LLMs), autonomous tool calling (MCP), and local AI execution.

> **Fork Overview**: This version is a modernized, high-performance fork of [GPT_Mobile_AI](https://github.com/chungjungsoo/GPT_Mobile). It delivers a significantly smoother user experience, autonomous agent tools (MCP), on-device privacy AI with adaptive hardware acceleration, background execution persistence, multi-key credential failover, encrypted backup vaults, and major battery and performance improvements.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-36-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin%202.x-purple.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-4285F4.svg)](https://developer.android.com/jetpack/compose)

---

## 🌟 What's New & Different in this Fork? (Sorted by User Impact)

Here is a comprehensive breakdown of new features and enhancements in this fork, ordered from the most impactful everyday user experiences down to under-the-hood engine improvements:

### 1. ⚡ Ultra-Smooth Streaming & Instant Bottom Anchoring (Most Noticeable)
- **Zero-Lag High-Refresh Display**: Even with ultra-fast models delivering hundreds of tokens per second (Cerebras, Groq, Claude 3.7), scrolling and typing remain buttery smooth at 60fps/120fps. Adaptive token batching eliminates UI stutter and thread lockups.
- **Instant Bottom Anchoring (`rememberChatListState`)**: Entering a conversation immediately lands at the newest message with zero jump or scroll delay. Navigating via search or a favorited message smoothly preserves focus on the target message.
- **Collapsible Reasoning & Tool Details**: Clean, modern assistant bubbles featuring spring-animated collapsible details (`DetailsButton`), full-bubble cyan focus highlights, and continuous streaming activity indicators.

### 2. 🧠 Autonomous Agent Tools & Model Context Protocol (MCP) + Marketplace
- **Built-in Agent Runtime**: AI models can dynamically run multi-step reasoning, safely invoke tools, handle external errors, and synthesize complete answers.
- **In-App MCP Marketplace**: Discover, install, and toggle tools from a full-screen marketplace in one tap.
- **Zero-Config Web Search**: Preinstalled `droid-mcp-web` online search (`web_search` and `fetch_webpage`) with automatic DuckDuckGo fallback—no API keys or search configurations required.
- **Safety Ceiling & Loop Guard**: Configurable tool call ceiling (default: 10 calls per query) prevents infinite loops, unexpected API token burn, and runaway costs.
- **Gemini MCP Schema Sanitization**: Automatically strips unsupported keywords (`x-mcp-*`, `$schema`, `propertyNames`, `additionalProperties`) ensuring seamless compatibility with Google Gemini function calling.

### 3. 🌙 Resilient Background & Screen-Off Execution
- **Run Tasks with Screen Off**: Long research queries, multi-step tool workflows, web scraping, and on-device model generation continue without interruption when the screen turns off or the phone is locked.
- **Engineered for Android 14+**: Uses `AgentRunForegroundService` with `dataSync` compliance and temporary partial CPU `WAKE_LOCK` management to prevent battery-optimization process termination.

### 4. 🔑 Multi-Key Round-Robin Rotation & Auto-Failover
- **Never Hit Rate Limits**: Add multiple API keys per provider in the dynamic `+API` credential manager.
- **Smart Failover**: The `ApiCredentialRotator` automatically distributes load across your keys and instantly switches to backup keys when receiving rate-limit (HTTP 429), quota-exhausted, or payment-required (HTTP 402) errors.

### 5. 🔀 OpenRouter Advanced Routing & Reasoning Options
- **Provider Routing Controls**: Select preferred providers, fallback orders, ignore specific hosts, and control data privacy / logging preferences directly from platform settings.
- **Custom Reasoning Tokens**: Fine-tune reasoning effort, max thinking tokens, and temperature parameters for models like Claude 3.7 Sonnet Thinking, DeepSeek R1, and OpenAI o-series.

### 6. 🔒 High-Performance Local AI (LiteRT-LM & Ollama)
- **Private On-Device Chat**: Run local models (`.bin`, `.tflite`) entirely offline on your phone with zero data sent to the cloud, or connect to a local Ollama server on your home network.
- **Hardware Governor & Acceleration**: Automatic NPU / GPU / CPU hardware acceleration via Google LiteRT with `DeviceHardwareGovernor` dynamically managing thermal load, battery level, and RAM limits.
- **Warm Engine Retention & Phase Scheduling**: Keeps model weights warm across turns to eliminate reload latency, paired with separate `PREFILL` and `GENERATING` phase management.
- **Resilient Background Model Downloader**: WorkManager-backed downloads with SHA-256 integrity verification, pause/resume support, and notification progress updates.

### 7. ⭐ Rich Favorites Management & Custom Categorization
- **Personal Knowledge Hub**: Bookmark important messages, code snippets, and explanations.
- **Custom Group Filters**: Organize favorites with custom group filter chips ("All", user-defined categories, "+ Add Group").
- **Full-Screen Reader**: Rich Markdown, LaTeX math equations, syntax-highlighted code blocks, and one-tap deep linking straight back to the original message in the chat thread.

### 8. 🛡️ Rolling Context Window Compactor (No Overflow Crashes)
- **Infinite Conversations**: `RollingContextWindowCompactor` dynamically compacts older chat turns while strictly preserving your initial prompt anchor (Turn 0) and system instructions.
- **Zero Token Overflow Crashes**: Automatically stays within the model's exact context limit without dropping system rules.

### 9. 🔐 Keystore Encryption & Encrypted Vault Backups
- **Device Credential Security**: All API keys and secrets are protected using Android Keystore-backed AES-256-GCM encryption (`SecretVault`) stored in secure `noBackupFilesDir`.
- **Passphrase Vault Backups**: Export and restore your complete database with PBKDF2-HMAC-SHA256 key derivation and authenticated AES-256-GCM encryption.

### 10. 🗄️ Instant Search & Robust Database (`ChatDatabaseV2`)
- **Fast Full-Text Search**: Instant search indexing across all conversation histories and tool executions.
- **Safe Room Migrations**: Powered by `ChatDatabaseV2` (Schema v15) with verified automated migrations ensuring no data is ever lost across app updates.

### 11. 📉 Up to 60% Smaller App Download Size
- **Native ABI Splits**: Published as targeted `arm64-v8a` and `x86_64` release APK packages alongside universal APKs, saving storage space and cellular download data.

---

## ⚡ Quick Comparison

| Feature / Capability | Upstream Original | This Improved Fork |
| :--- | :--- | :--- |
| **High-Speed Streaming** | Stutters on fast token bursts | 🧈 Smooth 60/120 fps adaptive buffer |
| **Initial Chat Scroll** | Delayed jump or layout lag | ⚡ Instant bottom anchoring (`rememberChatListState`) |
| **Agent Tools & MCP** | ❌ Not supported | ✅ Built-in Agent Engine + Model Context Protocol |
| **MCP Marketplace** | ❌ Not supported | ✅ Full-screen in-app marketplace with 1-click install |
| **Tool Execution Limits** | ❌ None | ✅ Configurable ceiling (default: 10 calls, user-customizable) |
| **Background / Screen-Off Run** | ❌ Killed on screen lock | ✅ Resilient `AgentRunForegroundService` + WakeLock |
| **Multi-Key API Failover** | ❌ Single key only | ✅ `ApiCredentialRotator` round-robin & rate-limit fallback |
| **OpenRouter Advanced Routing** | ❌ Basic completions only | ✅ Provider preferences, fallbacks, and reasoning controls |
| **Local Inference Engine** | Basic / Limited | ✅ LiteRT-LM (NPU/GPU/CPU), thermal governor, warm engine retention |
| **Long Context Conversations** | Vulnerable to context overflows | ✅ Rolling context compaction with Turn 0 anchor preservation |
| **Credential Security** | Plaintext / basic storage | ✅ Android Keystore AES-256-GCM (`SecretVault`) |
| **Encrypted Backups** | ❌ Not supported | ✅ Passphrase-protected PBKDF2 + AES-GCM export/import |
| **Favorites Management** | ❌ Basic or none | ✅ Custom group chips, rich Markdown dialog, chat jump |
| **Web Search** | ❌ Manual setup / none | ✅ Zero-config `droid-mcp-web` + DuckDuckGo fallback |
| **Search & Database** | Monolithic legacy database | ✅ Modern `ChatDatabaseV2` (Schema v15) with instant search |
| **APK Footprint** | Large universal APK | ✅ Up to 60% lighter native ABI split APKs |

---

## 🌐 Supported Providers

- **Cloud**: OpenAI (`gpt-4o`, `o1`, `o3-mini`), Anthropic Claude (`claude-3-7-sonnet`, `claude-3-5-haiku` with reasoning blocks), Google Gemini (`gemini-2.0-flash`, `gemini-1.5-pro` with MCP schema sanitization), Groq (ultra-fast LPU inference with reasoning extraction), OpenRouter (complete catalog, provider routing, fallback, and reasoning parameters), and any OpenAI-compatible API.
- **Local / Self-Hosted**: Local LiteRT on-device LLMs (NPU/GPU/CPU accelerated), Ollama (custom IP and port).

---

## 🛠️ Architecture & Tech Stack

- **UI**: Jetpack Compose, Material Design 3 (fully optimized with stability contracts)
- **Language**: Kotlin 2.x, Coroutines, StateFlow
- **Networking**: Ktor Client with CIO and OkHttp engines, Server-Sent Events (SSE)
- **Persistence**: Room Database (`ChatDatabaseV2`, Schema v15), DataStore Preferences
- **Dependency Injection**: Hilt / Dagger with KSP
- **Security**: Android Keystore AES-256-GCM credential encryption (`SecretVault`)
- **Inference**: Google LiteRT-LM with dynamic hardware governor
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
