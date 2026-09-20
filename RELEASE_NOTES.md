# Release Notes - v0.9.5.6

Welcome to **GPT Mobile AI (Improved)** v0.9.5.6!

This release introduces UI contrast and opacity refinements across chat components, sets up location-based Model Context Protocol (MCP) tools, enhances Llama model selection in the chat models dialog, updates LiteRT-LM to v0.16.1, and delivers optimized signed Android binaries.

---

### Key Highlights & Improvements

#### 1. 🎨 Chat Bubble & UI Opacity Tuning
- **AI Response Bubble Opacity**: Increased AI response bubble container opacity by 50% (`alpha = 0.06f` from previous `0.04f`) for improved readability against the background across theme variants.
- **Timestamps Transparency**: Made message timestamps 2x more transparent (`alpha = 0.30f` from `0.60f`) in both User and Assistant bubbles, reducing visual distraction while keeping them easily readable.
- **Details Toggle Transparency**: Details collapse/expand text and arrow icon are now 2x more transparent (`alpha = 0.50f` from `1.0f`), yielding a cleaner, more minimal chat layout.
- **Updated `ChatAlphaTokens`**: Centralized alpha tokens in `ChatAlphaTokens.kt` reflecting the new transparency design standards.

#### 2. 📍 Location MCP Tools Built-in Support
- **`geolocate_ip`**: Look up geolocation data for an IP address with automatic fallback to detecting host public IP via `api.ipify.org`.
- **`reverse_geocode`**: Convert latitude and longitude coordinates to human-readable address info using OpenStreetMap Nominatim with strict boundary validation.
- **`get_current_location`**: Guidance placeholder tool bridging to device native location services.
- Version bumped to `0.9.5.6` across `mcp/server.js`, `mcp/tools/manifest.json`, and `mcp/package.json`.

#### 3. 🦙 Llama Router Model Picker in Chat Models Dialog
- Enabled direct router model picker dropdown for `ClientType.LLAMA` platforms in `ChatModelDialog` and wired dynamic `platformApiUrls` across `ChatScreen`.

#### 4. 📦 OpenRouter Batch Background Worker & Room Cache
- Persistent background execution via `OpenRouterBatchWorker` (WorkManager) with Room database cache eviction for high-volume batched completions.

#### 5. ⚡ LiteRT-LM v0.16.1 & On-Device Native Serving
- Upgraded Google LiteRT-LM to `v0.16.1` with Qualcomm Snapdragon Hexagon NPU (QNN) HTP acceleration.

#### 6. ⚙️ Build & Packaging Details
- **Version Code**: `58`
- **Version Name**: `0.9.5.6`
- **Target SDK**: 36 (Android 16), **Min SDK**: 31 (Android 12)
- **Architectures**: `arm64-v8a`, `x86_64`, Universal APK
- **Optimization**: R8 minification, resource shrinking, and native packaging (`useLegacyPackaging = true`).

---

### Artifacts & Downloads
- **Universal APK**: `app-universal-release.apk`
- **Targeted ABI APKs**: `app-arm64-v8a-release.apk` and `app-x86_64-release.apk`
- **Release Bundle (AAB)**: `app-release.aab`
