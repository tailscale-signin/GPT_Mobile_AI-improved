# Release Notes - v0.9.3.0

Welcome to **GPT Mobile AI (Improved)** (v0.9.3.0)!

This release delivers real-time Diagnostics HUD telemetry, debug mode configuration, thinking block visual refinement, and compilation stabilization.

---

### Key Highlights & Features

#### 1. Diagnostics HUD & Debug Mode
- **Debug Mode Setting**: Added setting switch to toggle Debug Mode across the app, persisted seamlessly via AndroidX DataStore (`SettingDataSource` and `SettingRepository`).
- **Real-Time Telemetry**: Chat bubbles now optionally display a Diagnostics HUD for assistant responses, tracking generation latency, Time To First Token (TTFT), token counts, speed (tokens/second), and thermal state.
- **Strict Interface Parity**: Fully implemented and tested across all production repositories and test fakes (`FakeSettingDataSource`, `BackupFakeSettingDataSource`, `PlatformSettingViewModelTest`).

#### 2. Thinking Block UI Polish
- **Optimized Contrast**: Reduced `ThinkingBlock` container background alpha from `0.5f` to `0.25f` to improve legibility and provide an elegant blend with Material 3 dynamic color palettes.

#### 3. Core Capabilities Retained from v0.9.2.4
- **Voice Session Coordinator**: Full-duplex hands-free conversation state machine with instant interruption handling.
- **On-Device Document RAG Engine**: Zero-cloud document indexing, chunking, and retrieval (BM25 keyword and cosine similarity vector search).
- **Multi-Step Agent Workflow Visualization**: Interactive `AgentPlanCard` with sub-task progress tracking.
- **Resilient Streaming Client**: Exponential backoff retry with jitter for high network stability.
- **Incremental Streaming Diff Parser**: Prevents recomposition overhead during high-speed token generation.
- **Sandboxed Artifact Previewing**: Secure interactive HTML/SVG artifact previewing in isolated WebViews.

---

### Artifacts & Downloads
- **Universal APK**: `app-universal-release.apk` (runs on all supported 64-bit architectures)
- **Architecture APKs**: `app-arm64-v8a-release.apk` and `app-x86_64-release.apk`
- **Release Bundle (AAB)**: `app-release.aab`