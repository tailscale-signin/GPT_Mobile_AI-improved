# Release Notes - v0.9.4.3

Welcome to **GPT Mobile AI (Improved)** v0.9.4.3!

This release introduces native integration of the real-time **FancyOpenRouterCreditsCard** directly in the Platform Settings dashboard, cleans up and refines local accelerator runtime components, and provides overall stability and performance enhancements.

---

### Key Highlights & Improvements

#### 1. 💳 Fancy OpenRouter Credits Card Integration
- Integrated `FancyOpenRouterCreditsCard` into `PlatformSettingScreen`, presenting users with real-time balance tracking, credit limits, usage percentages, and responsive reload controls.
- Asynchronous fetching and caching in `PlatformSettingViewModel` with graceful error recovery when network connectivity or token validity fluctuates.

#### 2. ⚡ Local Runtime & Acceleration Polish
- Restored `LocalAccelerators` and `LocalModelValidator` definitions.
- Unified acceleration detection supporting Qualcomm QNN NPU, OpenCL GPU, and multi-threaded CPU acceleration.
- Validated on-device LiteRT-LM runtime execution pipelines.

#### 3. 🎨 UI & Navigation Cleanups
- Refined `HomeTopBar` spacing, layout consistency, and touch targets across modern Android devices.
- Upgraded and validated documentation across RAG architecture, sandboxed visual artifacts, and full-duplex voice sessions.

#### 4. 📦 Build & Packaging Details
- Version code incremented to `46`, version name set to `0.9.4.3`.
- Target SDK 36 (Android 16), Min SDK 31 (Android 12).
- Supported 64-bit ABIs: `arm64-v8a`, `x86_64`.
- Optimized with R8 minification, resource shrinking, and deterministic keystore signing.

---

### Artifacts & Downloads
- **Universal APK**: `app-universal-release.apk` (universal binary for all modern 64-bit devices)
- **Targeted ABI APKs**: `app-arm64-v8a-release.apk` (optimized footprint for modern Android phones) and `app-x86_64-release.apk` (for emulators and Chromebooks)
- **Release Bundle (AAB)**: `app-release.aab`
