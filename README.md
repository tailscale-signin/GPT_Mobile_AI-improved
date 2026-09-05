<div align="center">

<img width="200" height="200" style="display: block;" src="./images/logo.png">

# GPT Mobile

## Multi-provider AI chat and on-device agent tools for Android

<p>
  <a href="https://mailchi.mp/kotlinweekly/kotlin-weekly-431"><img alt="Kotlin Weekly" src="https://img.shields.io/badge/Kotlin%20Weekly-%23431-blue"/></a>
  <img alt="Android" src="https://img.shields.io/badge/Platform-Android-green.svg"/>
  <a href="https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/workflows/release-build.yml"><img alt="GitHub Actions Workflow Status" src="https://img.shields.io/github/actions/workflow/status/tailscale-signin/GPT_Mobile_AI-improved/release-build.yml?branch=main&label=Release%20Build"/></a>
  <a href="https://hosted.weblate.org/engage/gptmobile/"><img src="https://hosted.weblate.org/widget/gptmobile/gptmobile/svg-badge.svg" alt="Translation status" /></a>
  <a href="https://github.com/tailscale-signin/GPT_Mobile_AI-improved/releases/"><img alt="GitHub Releases Total Downloads" src="https://img.shields.io/github/downloads/tailscale-signin/GPT_Mobile_AI-improved/total?label=Downloads&logo=github"/></a>
  <a href="https://github.com/tailscale-signin/GPT_Mobile_AI-improved/releases/latest/"><img alt="GitHub Releases (latest by date)" src="https://img.shields.io/github/v/release/tailscale-signin/GPT_Mobile_AI-improved?color=black&label=Latest%20Release&logo=github"/></a>
</p>

</div>


## Screenshots

<div align="center">

<img style="display: block;" src="./images/screenshots.webp">

</div>

## Demos


| <video src="https://github.com/Taewan-P/gpt_mobile/assets/27392567/96229e6d-6795-48b4-a915-aca915bd2527"/> | <video src="https://github.com/Taewan-P/gpt_mobile/assets/27392567/1cc13413-7320-4f6f-ace9-de76de58adcc"/> | <video src="https://github.com/Taewan-P/gpt_mobile/assets/27392567/546e2694-953d-4d67-937f-a29fba81046f"/> |
|------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------|


## Features

- **Chat with multiple models at once**
  - Uses official APIs for each platform
  - Supported platforms:
    - OpenAI GPT (including custom endpoints)
    - Anthropic Claude
    - Google Gemini
    - Groq
    - Ollama (local/remote host)
    - OpenAI-compatible third-party APIs
  - Customizable temperature, top_p (nucleus sampling), and system prompts
  - Custom API URLs and custom model names are fully supported
- **Agent Tools per Provider Profile**
  - Native tool calling with OpenAI, OpenAI-compatible/Groq, Anthropic, and Gemini
  - Web search via Firecrawl, Perplexity, or Exa with hardened URL reading
  - MCP (Model Context Protocol) Streamable HTTP servers with public, bearer token, or OAuth authentication
  - Local network discovery and validation for local MCP servers / Ollama instances
  - Parallel runs, persistent trace viewer, cancellation, and foreground notification progress
  - Existing and newly migrated profiles remain chat-only until tools are explicitly assigned
- **Local & Private Data Storage**
  - Chat history is **only saved locally** on your device
  - API credentials are encrypted with Android Keystore and excluded from backup/export
  - During chats, requests go only to selected model providers and assigned tools
- **Modern Android & Jetpack Compose Architecture**
  - [Material You](https://m3.material.io/) dynamic theming, dark mode, and seamless theme switching without Activity restarts
  - Per-app language preferences for Android 13+
  - 100% Kotlin, Jetpack Compose, Kotlin Coroutines & Flow, Hilt dependency injection, and Room database


## Agent documentation

See [Agent tools, privacy, and security](docs/agent-tools.md) and the [0.8.0 release notes](docs/release-notes-v0.8.0.md).

If you have any questions or feature requests, feel free to open an issue or discussion!


## Downloads & Releases

Pre-built signed release APKs and Android App Bundles (AAB) are automatically generated on every release build:

[<img height="80" alt='Get it on GitHub' src='https://raw.githubusercontent.com/Kunzisoft/Github-badge/main/get-it-on-github.png'/>](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/releases)
[<img height="80" alt="Get it on F-Droid" src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"/>](https://f-droid.org/packages/dev.chungjungsoo.gptmobile)
[<img height="80" alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png'/>](https://play.google.com/store/apps/details?id=dev.chungjungsoo.gptmobile&utm_source=github&utm_campaign=gh-readme)

> **Note:** GitHub Releases provide the latest signed APKs and AABs directly from the automated CI/CD pipeline.


## Building From Source

To build a release APK locally:

```bash
git clone https://github.com/tailscale-signin/GPT_Mobile_AI-improved.git
cd GPT_Mobile_AI-improved
./gradlew assembleRelease
```

The unsigned release APK will be generated at `app/build/outputs/apk/release/app-release-unsigned.apk`.


## Contributions

Contributions are welcome! Please check out the [Contribution Guidelines](CONTRIBUTING.md) to get started with building, testing, and submitting pull requests.

For translations, we are using [Hosted Weblate](https://hosted.weblate.org/engage/gptmobile/). If you want your language supported, help us translate the app!

<a href="https://hosted.weblate.org/engage/gptmobile/">
  <img src="https://hosted.weblate.org/widget/gptmobile/gptmobile/multi-auto.svg" alt="Translation status" />
</a>


## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=tailscale-signin/GPT_Mobile_AI-improved&type=Timeline)](https://star-history.com/#tailscale-signin/GPT_Mobile_AI-improved&Timeline)


## License

See [LICENSE](./LICENSE) for details.

[F-Droid Icon License](https://gitlab.com/fdroid/artwork/-/blob/master/fdroid-logo-2015/README.md)
