# GPT Mobile AI (Improved)

A high-performance Android client for interacting with cloud and local large language models, featuring autonomous agent capabilities, Model Context Protocol (MCP) integration, and secure credential management.

## Features

### Core Capabilities
- **Multi-Provider Support**: OpenAI, Anthropic Claude, Google Gemini, Groq, OpenRouter, Ollama, and any OpenAI-compatible API
- **Local Inference**: Run models directly on-device via LiteRT-LM (NPU/GPU/CPU accelerated) or connect to local Ollama servers
- **Autonomous Agent Runtime**: Models can dynamically execute multi-step reasoning and invoke tools
- **Model Context Protocol (MCP)**: Built-in MCP support with marketplace for discovering and installing tools

### Performance & Reliability
- **Adaptive Streaming**: High-refresh display with zero-lag token streaming at 60/120fps
- **Multi-Key API Rotation**: Round-robin key distribution with automatic failover on rate limits (HTTP 429) or quota exhaustion
- **Background Execution**: Foreground service with wake locks for uninterrupted long-running tasks
- **Context Management**: Rolling context window compaction preserving system instructions and turn anchors

### Security & Data
- **Encrypted Storage**: Android Keystore-backed AES-256-GCM encryption for API keys and secrets
- **Vault Backups**: Passphrase-protected encrypted database export/import with PBKDF2 key derivation
- **Privacy-First**: Local models run entirely offline with zero data transmission

### User Experience
- **Favorites & Organization**: Bookmark messages with custom group categorization
- **Deep Search**: Full-text search across conversation history and tool executions
- **Platform Management**: Dedicated hub for managing AI platforms with interactive sorting and filtering
- **OpenRouter Routing**: Advanced provider selection, fallback ordering, and reasoning parameter controls

## Tech Stack

- **UI**: Jetpack Compose, Material Design 3
- **Language**: Kotlin 2.x, Coroutines, Flow/StateFlow
- **Architecture**: Clean MVVM with Hilt/Dagger dependency injection
- **Networking**: Ktor Client (OkHttp/CIO) with SSE streaming support
- **Persistence**: Room Database (Schema v17), DataStore Preferences
- **Security**: Android Keystore, AES-256-GCM encryption
- **Build**: Gradle Kotlin DSL, R8 shrinking, ABI splits

## Getting Started

### Prerequisites
- Android Studio Ladybug (2024.2.1) or newer
- JDK 21 (JDK 17 minimum)
- Android SDK 36 (target/compile) / Min SDK 31

### Building
```bash
git clone https://github.com/tailscale-signin/GPT_Mobile_AI-improved.git
cd GPT_Mobile_AI-improved
./gradlew assembleDebug    # Debug APK
./gradlew assembleRelease  # Release APKs (ABI splits + universal)
./gradlew testDebugUnitTest  # Unit tests
```

## License

Apache License 2.0 - See [LICENSE](LICENSE) for details.

## Acknowledgments

Based on [GPT_Mobile_AI](https://github.com/chungjungsoo/GPT_Mobile_AI) by jungjungsoo. This fork adds autonomous agent capabilities, MCP integration, enhanced security, and performance improvements.
