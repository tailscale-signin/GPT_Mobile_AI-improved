# Release Notes - v0.9.4.6

Welcome to **GPT Mobile AI (Improved)** v0.9.4.6!

This official release consolidates core performance and responsiveness optimizations, fixes Qualcomm QNN NPU runtime loading and probe verification, integrates complete conversation swipe-to-dismiss and pinning workflows, and produces signed release distribution artifacts.

---

### Key Highlights & Improvements

#### 1. ⚡ Performance & Core Architectural Optimizations
- **Application & ViewModel Optimization**:
  - Streamlined main activity startup and viewmodel state updates.
  - Optimized database query caching and state dispatcher coordination.
  - Reduced GC pressure during heavy streaming sessions.

#### 2. 🧠 Qualcomm QNN HTP & NPU Runtime Fixes
- **QNN Operator Precedence Fix**:
  - Corrected boolean operator precedence in `QnnEnvironment.probeEnvironment` to accurately verify Hexagon NPU skeleton (`skelFoundPath`) and FastRPC libraries.
- **Redundant Engine Load & Error Propagation**:
  - Eliminated redundant model re-loading in `LocalRuntimeQnnImpl` when falling back to LiteRT, preventing duplicate memory allocations and Out-Of-Memory events on device.
  - Proper propagation of `CancellationException` to avoid blocking coroutine scopes.

#### 3. 📌 Conversation Pinning & Swipe Gestures
- **Swipeable Conversation Rows**:
  - Swipe right to archive conversations.
  - Swipe left to delete conversations with safety confirmation dialog.
  - 1-second long-press interaction to pin/unpin conversations with animated spot elevation glow.
  - Haptic feedback when reaching trigger boundaries.
  - Three-tier sorting order (`active` > `pinned` > `recency`).

#### 4. 🛡️ Circuit Breaker & Error Propagation
- Automatic failure threshold tracking and circuit transitions (`CLOSED` / `OPEN` / `HALF_OPEN`).
- Classified user error propagation across `ChatRepository` and `AgentRunner` preventing repeated doomed retry loops.

#### 5. 📦 Build & Packaging Details
- Version code incremented to `51`, version name set to `0.9.4.6`.
- Target SDK 36 (Android 16), Min SDK 31 (Android 12).
- Supported 64-bit ABIs: `arm64-v8a`, `x86_64`.
- Signed release APKs (Universal, arm64-v8a, x86_64) and Android App Bundle (AAB).

---

### Artifacts & Downloads
- **Universal APK**: `app-universal-release.apk` (universal binary for all modern 64-bit devices)
- **Targeted ABI APKs**: `app-arm64-v8a-release.apk` (optimized footprint for modern Android phones) and `app-x86_64-release.apk` (for emulators and Chromebooks)
- **Release Bundle (AAB)**: `app-release.aab`
