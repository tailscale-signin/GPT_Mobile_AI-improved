<div align="center">

<img width="200" height="200" style="display: block;" src="./images/logo.png">

# GPT Mobile Improved

## Multi-provider AI chat and high-performance on-device agent platform for Android

<p>
  <img alt="Android" src="https://img.shields.io/badge/Platform-Android-green.svg"/>
  <img alt="Package" src="https://img.shields.io/badge/Application%20ID-dev.chungjungsoo.gptmobile.improved-blue.svg"/>
  <a href="https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/workflows/release-build.yml"><img alt="GitHub Actions Workflow Status" src="https://img.shields.io/github/actions/workflow/status/tailscale-signin/GPT_Mobile_AI-improved/release-build.yml?branch=main&label=Release%20Build"/></a>
  <a href="https://github.com/tailscale-signin/GPT_Mobile_AI-improved/releases/"><img alt="GitHub Releases Total Downloads" src="https://img.shields.io/github/downloads/tailscale-signin/GPT_Mobile_AI-improved/total?label=Downloads&logo=github"/></a>
  <a href="https://github.com/tailscale-signin/GPT_Mobile_AI-improved/releases/latest/"><img alt="GitHub Releases (latest by date)" src="https://img.shields.io/github/v/release/tailscale-signin/GPT_Mobile_AI-improved?color=black&label=Latest%20Release&logo=github"/></a>
  <a href="./PROGRESS.md"><img alt="Progress" src="https://img.shields.io/badge/Status-Progress%20Page-brightgreen.svg"/></a>
</p>

</div>

---

## 🌟 What Makes This Fork Improved?

This repository (**`GPT_Mobile_AI-improved`**) is an advanced, high-performance evolution of GPT Mobile, engineered for power users, deep agentic workflows, and seamless installations:

1. **Side-by-Side Coexistence (`dev.chungjungsoo.gptmobile.improved`)**:
   - Re-namespaced Application ID allows you to install and use this version **directly alongside the original app** with zero conflicts, no uninstallation needed, and separate data isolation.
2. **Persistent Deterministic Signing**:
   - Replaced ephemeral per-build CI keystores with persistent, deterministic signing. Once installed, **all future releases install seamlessly in-place over this app** without prompting for uninstall or wiping chat history.
3. **Uncapped Autonomous Agent Engine**:
   - Loops, tool calling limits, and run timeouts are uncapped (`Int.MAX_VALUE` / `Long.MAX_VALUE`), enabling fully autonomous deep-reasoning multi-round agent workflows without artificial round terminations.
   - Up to 32 concurrent parallel tool executions with uncapped buffer output limits.
4. **Enhanced Web & MCP Tool Scalability**:
   - **ReadUrl**: Expanded to 100 MB body limit, 50 MB output buffer, and 50 redirect hops.
   - **WebSearch**: Uncapped result ceiling up to 100 results per call (via Firecrawl, Perplexity, or Exa).
   - **MCP Client**: Uncapped discovered tool limits and pagination for extensive Model Context Protocol server registries.
5. **Full Configuration Backup & Restore**:
   - Export and restore all provider configurations, custom endpoints, model parameters, and preferences as JSON with integrated Android Keystore credential re-encryption.
6. **Favorites & Searchable Starred AI Responses**:
   - Star any response across any provider and quickly search through your favorites library on the dedicated Star tab.

---

## 📊 Build & Progress Tracking

Track build milestones, recent updates, and download artifacts:
👉 **[View the Progress & Build Log (PROGRESS.md)](./PROGRESS.md)**

---

## Screenshots

<div align="center">

<img style="display: block;" src="./images/screenshots.webp">

</div>

## Features

- **Chat with Multiple Models Simultaneously**
  - Query multiple models simultaneously in side-by-side or tabbed multi-turn conversations
  - Supported platforms:
    - OpenAI GPT (including custom endpoints)
    - Anthropic Claude
    - Google Gemini
    - Groq
    - Ollama (local or remote instances)
    - OpenAI-compatible third-party APIs
  - Customizable temperature, top_p (nucleus sampling), and system prompts
  - Fully supports custom API URLs and arbitrary model identifiers
- **Uncapped Agent Tools per Provider Profile**
  - Native tool calling with OpenAI, Groq, Anthropic, and Gemini
  - Web search via Firecrawl, Perplexity, or Exa with hardened high-capacity URL fetching
  - MCP (Model Context Protocol) Streamable HTTP servers with public, bearer token, or OAuth authentication
  - Local network discovery and validation for local MCP servers / Ollama instances
  - Parallel runs, persistent trace viewer, cancellation, and foreground notification progress
- **Configuration Backup & Restore**
  - Full export and restore of platform profiles, custom URLs, model configurations, and theme settings via formatted JSON
  - Seamlessly re-encrypts and manages credentials through Android Keystore (`SecretVault`)
  - Integrated one-tap clipboard copy and in-app JSON validation with error reporting
- **AI Response Favorites & Searchable Star Tab**
  - One-tap star/favorite on any AI response bubble across all platforms
  - Dedicated **Star / Favorites tab** on the main home screen
  - Real-time instant search across all favorited AI responses
  - Tap any favorited card to jump directly to its chat room and context
- **Local & Private Data Storage**
  - Chat history and messages are **only saved locally** on your device
  - API credentials are encrypted with Android Keystore
  - During chats, requests go only to selected model providers and assigned tools
- **Modern Android & Jetpack Compose Architecture**
  - [Material You](https://m3.material.io/) dynamic theming, dark mode, and seamless theme switching without Activity restarts
  - Per-app language preferences for Android 13+
  - 100% Kotlin, Jetpack Compose, Kotlin Coroutines & Flow, Hilt dependency injection, and Room database

---

## Downloads & Releases

Pre-built signed release APKs and Android App Bundles (AAB) are automatically generated on every release build:

[<img height="80" alt='Get it on GitHub' src='https://raw.githubusercontent.com/Kunzisoft/Github-badge/main/get-it-on-github.png'/>](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/releases)

- **Latest Release**: [v0.8.1 Release Assets](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/releases/tag/v0.8.1)
- **Targeting Version**: `0.8.2` (Version Code `25`)

> **Install Note:** Because this app uses `dev.chungjungsoo.gptmobile.improved` and deterministic release signing, you can install it without removing the upstream app, and all future updates from this repository will update in-place smoothly!

---

## Building From Source

To build a release APK locally:

```bash
git clone https://github.com/tailscale-signin/GPT_Mobile_AI-improved.git
cd GPT_Mobile_AI-improved
./gradlew assembleRelease
```

The release APK will be generated under `app/build/outputs/apk/release/`.

---

## License

See [LICENSE](./LICENSE) for details.
