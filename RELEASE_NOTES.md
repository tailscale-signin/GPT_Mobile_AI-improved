# Release Notes - v0.9.11.0

Welcome to **GPT Mobile AI (Improved)** v0.9.11.0!

This full release brings a major architecture, UX, runtime, and diagnostics overhaul—integrating live tool context tracking, enhanced provider adapter resilience, fine-grained application feature controls, native device location freshness, and complete backup improvements alongside the Creativity slider and custom colored profile labels.

---

### Key Highlights & Improvements

#### 1. App-Wide Architecture & Diagnostics Redesign
- **Live Tool Context**: Integrated `LiveToolContext` for precise tracking of active tool execution states and session telemetry across local and remote runs.
- **Provider Adapters Hardening**: Hardened provider adapters with normalized routing strategies for OpenRouter, OpenAI-compatible gateways, Anthropic, and Google Gemini.
- **Application Feature Settings**: Added granular feature flags (`AppFeatureSettings`) allowing customized controls for foreground AI generation and smart suggestion actions directly in Advanced Settings.
- **Diagnostics HUD**: Enhanced execution inspection and metrics telemetry for real-time memory, thermal, and generation throughput monitoring.

#### 2. Native Device Location Freshness & Dynamic Capabilities
- **Direct Native Geolocation**: Replaced stale catalog dependencies with live, device-level Android location queries via `DeviceLocationProvider` and `LocationFreshness`.
- **Haversine Distance & Geocoding**: High-precision distance calculations and reliable geocoding tool capabilities directly in the agent tool registry.

#### 3. Complete Backup & Granular Archive Management
- **Structured Database & File Archives**: Implemented `CompleteBackupOptions`, `CompleteBackupSelection`, and `CompleteBackupDatabase` enabling users to select specific entities (conversations, credentials, models, attachments) to include or exclude.
- **Encrypted Protection**: AES-256-GCM authenticated archives secured by the Android Keystore without requiring manual password management.

#### 4. Creativity Slider & Reusable Colored Profile Labels
- **Single Intuitive Slider**: Replaced separate raw Temperature and Top-P numeric inputs with an intuitive Creativity slider ranging from "Direct & logical" to "Creative & exploratory".
- **Visual Profile Badges**: Configurable colored profile labels that persist across profile cards, platform settings, and model selection filters.

#### 5. Local Runtime Router & Hugging Face Model Discovery
- **Hugging Face Search Integration**: Directly discover and explore compatible models from Hugging Face through `HuggingFaceModelSearchClient`.
- **Local Runtime Routing**: Dynamic routing across LiteRT-LM, Qualcomm QNN NPU, OpenCL GPU, and multi-threaded CPU fallbacks.

#### 6. Build & Packaging Details
- **Version Code**: `66`
- **Version Name**: `0.9.11.0`
- **Target SDK**: 36 (Android 16) | **Min SDK**: 31 (Android 12)
- **Architectures**: `arm64-v8a`, `x86_64`, Universal APK
- **Release Artifacts**: Signed APK variants (arm64-v8a, x86_64, Universal) and release Android App Bundle (AAB) signed with deterministic release keys.
