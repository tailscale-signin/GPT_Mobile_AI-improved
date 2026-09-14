# Release Notes - v0.9.3.2

Welcome to **GPT Mobile AI (Improved)** (v0.9.3.2)!

This release delivers cutting-edge Qualcomm Snapdragon NPU acceleration, real-time in-chat hardware diagnostics HUD, safe reasoning UI handling, and end-to-end streaming & tool execution hardening.

---

### Key Highlights & New Features

#### 1. ⚡ Qualcomm AI Engine Direct (QNN) & Hexagon NPU Acceleration
- **Native Qualcomm QNN Integration**: Bundles `qnn.runtime` and `qnn.litert.delegate` to run on-device models directly on Qualcomm Hexagon NPUs.
- **FastRPC cDSP Native Library Setup**: Configured `QnnEnvironment` to dynamically register `ADSP_LIBRARY_PATH` and verify native DSP skeleton libraries (`libQnnHtpV79Skel.so`). Enabled uncompressed legacy native library packaging (`useLegacyPackaging = true` and `android:extractNativeLibs="true"`) to satisfy Android cDSP loading requirements.
- **Hardware-Aware Backend Routing**: Directs on-device inference to the Hexagon NPU by default when running on supported Snapdragon platforms, yielding superior tokens-per-second and substantial battery savings.
- **Enriched Model Catalog**: Updated bundled `model_catalog.json` with Snapdragon Hexagon NPU model profiles.

#### 2. 📊 In-Chat Diagnostics & Hardware Telemetry HUD
- **Real-Time Telemetry Badge**: When **Debug Mode** is enabled, an interactive hardware diagnostics chip is rendered inside assistant chat bubbles (`ChatDebugDiagnosticsCard`).
- **Comprehensive Device Metrics**: Displays processor/SoC identifier, exact RAM statistics (total GB and available MB via `ActivityManager.getMemoryInfo`), battery percentage, charging status, thermal throttling levels, and Qualcomm HTP NPU readiness.
- **Instant Diagnostics Inspection**: Tap the diagnostics chip to pop open a detailed hardware inspection dialog right inside the active chat thread.

#### 3. 🛡️ Resilient UI & Nullable Reasoning Safety
- **Safe `<think>` Reasoning Blocks**: Fixed thinking block parser integration to safely handle nullable thought strings (`ThinkingBlock`), preventing chat bubble crashes on deep-thinking or non-thinking streaming responses.
- **Refined Opponent Chat Bubble Layout**: Polished bubble container background styling with adaptive tool container alpha (0.14), centered circular avatar (`GPTMobileIcon`), and clean collapsible details integration.

#### 4. 🌐 Streaming Resilience & Watchdog Protections
- **Infinite Streaming Request Watchdog**: Replaced rigid request timeouts with `HttpTimeoutConfig.INFINITE_TIMEOUT_MS` backed by adaptive socket inactivity detection.
- **Tool Timeout Watchdog**: 45-second execution timeout guard prevents stalled local or MCP tool executions from hanging conversation runs.
- **Ktor Keep-Alive Hardening**: 60-second connection keep-alives and hardened connection pooling prevent silent TCP drops across cellular networks.

#### 5. 📦 Version Bump to v0.9.3.2
- Version code incremented to `42`, version name updated to `0.9.3.2`.

---

### Artifacts & Downloads
- **Universal APK**: `app-universal-release.apk` (universal binary supporting all modern 64-bit devices)
- **Targeted ABI APKs**: `app-arm64-v8a-release.apk` (optimized footprint for modern Android phones) and `app-x86_64-release.apk` (for emulators and Chromebooks)
- **Release Bundle (AAB)**: `app-release.aab`
