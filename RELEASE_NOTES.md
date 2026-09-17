# Release Notes - v0.9.4.6

Welcome to **GPT Mobile AI (Improved)** v0.9.4.6!

This release brings core performance optimizations across application startup, SQLite database indexing, background service notification batching, and reliable CI release publishing.

---

### Key Highlights & Improvements

#### 1. 🚀 Core Application & Database Performance
- **Startup Latency Profiling**:
  - Benchmarked initialization pathways in `GPTMobileApp.kt` and `StartupRecoveryGate`.
  - Parallelized initial platform loading via coroutine `async` in `MainViewModel.kt`, reducing cold-start splash latency.
- **Room Database Query Acceleration**:
  - Added composite indices `index_chats_v2_archived_favorite_updated` and `index_chats_v2_archived_updated` to `ChatRoomV2` entity, significantly improving chat list sorting performance.
- **Service & Notification Throttling**:
  - Batched foreground notification updates in `AgentRunForegroundService` to eliminate UI thread jitter.
  - Added debounce protection to `GptTileService` and OAuth authorization launchers in `MainActivity`.

#### 2. 🛠️ Build & CI/CD Stability
- Fixed GitHub CLI release publishing (`release-build.yml`) to support existing releases without invalid argument errors.
- Added default resource definitions in `missing_build_resources.xml` across all localized strings to prevent AAPT2 stripping warnings.

#### 3. 📦 Build & Packaging Details
- Version code incremented to `50`, version name set to `0.9.4.6`.
- Target SDK 36 (Android 16), Min SDK 31 (Android 12).
- Supported 64-bit ABIs: `arm64-v8a`, `x86_64`.
- Signed release APKs (Universal, arm64-v8a, x86_64) and Android App Bundle (AAB).
